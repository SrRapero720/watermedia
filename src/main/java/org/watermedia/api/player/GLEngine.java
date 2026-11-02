package org.watermedia.api.player;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.IOTool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * OpenGL implementation of {@link GFXEngine} with asynchronous frame submission and YUV shader
 * conversion.
 * <p>
 * All GL work happens on the render thread (the OpenGL context is thread-bound). Frames submitted
 * from other threads go through a latest-wins slot: at most one upload task is queued per engine,
 * and submitting a newer frame before the previous one was consumed replaces it. This bounds
 * render-thread work to one upload per engine per render frame regardless of how fast the
 * producer decodes.
 * <p>
 * <b>The engine is self-contained: no host integration is required.</b> Every piece of GL context
 * state it touches (texture bindings, active unit, sampler bindings, pixel-store parameters,
 * buffer/VAO/framebuffer bindings, program, viewport and draw toggles) is captured before a batch
 * of work and restored to its exact prior value afterwards, so any state cache the host keeps
 * (Minecraft's {@code GlStateManager}, Sodium/Iris trackers, or none at all) stays truthful.
 * Texture names exposed through {@link #texture()} are deleted safely: storage is freed
 * immediately on release, but the name itself is only handed back to the driver once no texture
 * unit binds it anymore (which proves no host binding cache still references it), so a cached
 * binding can never point at a driver-reused name. All engines sharing a render thread drain
 * through one batched task per frame, so the capture/restore cost is constant per frame, not per
 * player, at any scale.
 * <p>
 * When {@code ARB_buffer_storage} (OpenGL 4.4) is available, pixel data is written by the
 * <em>producer</em> thread directly into a persistently-mapped PBO ring, so the render thread only
 * issues {@code glTexSubImage2D} from buffer offsets and a fence. Without it, and always for an
 * engine built without a render thread, frames go through a double-buffered PBO upload performed
 * on the render thread.
 * <p>
 * Supports direct upload (BGRA, RGBA, RGB), packed YUV (YUYV/UYVY), semi-planar (NV12/NV21/P010),
 * planar YUV (420P/422P/444P), planar YUVA (with alpha), and grayscale (GRAY).
 * All YUV/GRAY formats are converted to RGBA on the GPU via FBO + shader pass; each conversion
 * shader compiles once per OpenGL context and is shared by every engine on it until the last one
 * is released. High bit depths (10/12/16/32-bit) are supported via GL_R16/GL_RG16/GL_R32F textures.
 * The developer always sees a single RGBA texture from {@link #texture()}.
 */
public final class GLEngine extends GFXEngine {
    private static final Marker IT = MarkerManager.getMarker(GLEngine.class.getSimpleName());

    // HOT-PATH glGetError CALLS FORCE DRIVER SYNCHRONIZATION (NOTABLY WITH NVIDIA THREADED
    // OPTIMIZATION). THEY ARE DISABLED UNLESS THE DEBUG FLAG IS SET; COLD PATHS ALWAYS CHECK.
    private static final boolean GL_CHECKS = Boolean.getBoolean("watermedia.glchecks");

    // PBO CONFIGURATION
    private static final int NUM_PBOS = 2;   // LEGACY DOUBLE BUFFER PER PLANE
    private static final int MAX_PLANES = 4;
    private static final int RING_SLOTS = 4; // PERSISTENT RING DEPTH (1 WRITING + 1 PENDING + GPU IN-FLIGHT)
    private static final Submission DROPPED = new Submission(null, null, null, new int[0], -2L, 0); // RING FULL: FRAME DROPPED
    private static final int[] EMPTY_TEXTURES = new int[0];

    // THE CONVERSION PASS, BUNDLED UNDER assets/watermedia/shaders: ONE FULLSCREEN VERTEX SHADER AND ONE FRAGMENT PER
    // CONV KIND, ALL SHARING colorimetry.glsl WITH VKEngine
    private static final String VERTEX_SHADER = "opengl/convert.vert";
    private static final String[] FRAGMENTS = { "opengl/gray.frag", "opengl/nv.frag", "opengl/yuv3.frag", "opengl/yuva.frag", "opengl/yuyv.frag" };

    // CONVERSION KINDS: INDEX INTO FRAGMENTS/programs/uniforms; CONV_NONE = DIRECT UPLOAD
    private static final int CONV_NONE = -1;
    private static final int CONV_GRAY = 0;
    private static final int CONV_NV = 1;
    private static final int CONV_YUV3 = 2;
    private static final int CONV_YUVA = 3;
    private static final int CONV_YUYV = 4;
    // ONE UNIFORM SCHEME FOR EVERY PROGRAM, SO ONE BIND PATH SERVES ALL: A NAME A FRAGMENT DOES NOT DECLARE RESOLVES TO
    // -1, WHICH glUniform* IGNORES. SLOTS: [plane0..plane3, bitScale, uvSwap, outputWidth, colorimetry]
    private static final String[] UNIFORMS = { "plane0", "plane1", "plane2", "plane3", "bitScale", "uvSwap", "outputWidth", "colorimetry" };

    // TEXTURE UNITS SCANNED BY THE ORPHAN SWEEP: MINECRAFT'S 12-UNIT BINDING CACHE WITH MARGIN; HOSTS BINDING BEYOND THIS
    // USE DSA OR UNCACHED PATHS
    private static final int ORPHAN_SCAN_UNITS = 32;

    // ONE DRAIN HUB PER RENDER THREAD (ONE BATCHED TASK/FRAME IN ONE ENVELOPE); ORPHANS, KEYED
    // BY GLCapabilities IDENTITY, HOLDS EXPOSED TEXTURE NAMES AWAITING PROVABLY-SAFE DELETION.
    private static final Map<Thread, Hub> HUBS = new ConcurrentHashMap<>();
    private static final Map<GLCapabilities, ArrayDeque<Integer>> ORPHANS =
            Collections.synchronizedMap(new WeakHashMap<>());
    // ONE CONVERSION KIT PER CONTEXT BY GLCapabilities IDENTITY, GUARDED BY ITS OWN MONITOR; THE LAST ENGINE OUT REMOVES ITS
    // ENTRY, SO THE MAP HOLDS ONLY CONTEXTS WITH A LIVE CONVERTING ENGINE (SEE Kit)
    private static final Map<GLCapabilities, Kit> KITS = new HashMap<>();

    // THREAD CONTEXT (OPENGL-SPECIFIC)
    private final Thread renderThread;
    private final Executor renderThreadEx;
    private final Hub hub;
    private boolean hubQueued;           // GUARDED BY hub MONITOR

    // MANAGED TEXTURE (WHAT THE DEV BINDS)
    private int managedTexture = 0;
    private int managedTextureW = 0;
    private int managedTextureH = 0;

    // PRELOADED FRAME TEXTURES (ANIMATED IMAGE FAST PATH), UPLOADED ACROSS RENDER TICKS:
    // frameTexReady PUBLISHES THE USABLE PREFIX FOR frame(); frameTexGen INVALIDATES STALE TASKS.
    private volatile int[] frameTextures = EMPTY_TEXTURES;
    private volatile int activeFrameTexture = -1;
    private volatile int frameTexReady = 0;
    private int frameTexGen = 0;

    // LATEST-WINS SUBMISSION SLOT (PRODUCER WRITES, RENDER THREAD DRAINS). THE HUB QUEUES AT
    // MOST ONE DRAIN PER ENGINE PER WAVE; A NEWER SUBMISSION REPLACES AN UNDRAINED OLDER ONE.
    private volatile Submission pending;

    // PERSISTENT-MAPPED PBO RING (ARB_buffer_storage): THE PRODUCER MEMCPYS PIXELS INTO A SLOT WHILE produced - retired <
    // RING_SLOTS, SO THE RENDER THREAD NEVER TOUCHES CLIENT MEMORY; IT CONSUMES THE LATEST (SKIPPING OLDER), FENCES IT AND
    // RETIRES IN ORDER AS FENCES SIGNAL. ringLock GUARDS ARM/DESTROY VS MEMCPY
    private final Object ringLock = new Object();
    private volatile long ringAddr;       // 0 = NOT ARMED. PUBLISHED LAST ON ARM
    private volatile long ringRetired;    // RENDER THREAD WRITER, PRODUCER READER
    private volatile int ringEra;         // BUMPED ON ARM/DESTROY TO INVALIDATE STALE SLOTS
    private long ringSlotBytes;           // GUARDED BY ringLock
    private long ringProduced;            // GUARDED BY ringLock
    private long ringConsumed;            // RENDER THREAD ONLY
    private int ringBufferId;             // RENDER THREAD ONLY
    private final long[] ringFences = new long[RING_SLOTS];
    private Boolean ringCapable;          // LAZY ARB_buffer_storage PROBE (RENDER THREAD)

    // FBO FOR YUV TO RGBA CONVERSION
    private int fbo = 0;

    // FULLSCREEN QUAD (CORE PROFILE, NO IMMEDIATE MODE)
    private int quadVAO = 0;
    private int quadVBO = 0;

    // PLANE LAYOUT FOR THE CURRENT FORMAT (BUILT IN format() ON THE RENDER THREAD)
    private Plane[] planes = new Plane[0];

    // LEGACY PER-PLANE PBO STATE: pbos[PLANE * NUM_PBOS + BUFFER_INDEX]
    private final int[] pbos = new int[MAX_PLANES * NUM_PBOS];
    private int pboWriteIdx = 0;
    private boolean pboInitialized = false;
    private boolean pboReady = false;
    private int activePlanes = 0;
    private final long[] planeBytes = new long[MAX_PLANES]; // CURRENTLY ALLOCATED BYTES PER PLANE
    private boolean firstFrame = true;

    // ANOMALY LATCHES: EACH REPEATABLE UPLOAD ANOMALY IS LOGGED ONCE PER ENGINE
    private volatile boolean failWarned; // PRODUCER AND RENDER THREADS
    private boolean shortWarned;         // RENDER THREAD ONLY
    private boolean strideWarned;        // RENDER THREAD ONLY
    private boolean glErrorWarned;       // RENDER THREAD ONLY

    // BIT DEPTH AND COLOR DERIVED STATE (SET IN format())
    private float bitScale = 1.0f;
    private float[] colorimetry;
    private int glInternalR = GL30.GL_R8;    // R8 / R16 / R32F
    private int glInternalRG = GL30.GL_RG8;  // RG8 / RG16
    private int glType = GL11.GL_UNSIGNED_BYTE; // UNSIGNED_BYTE / UNSIGNED_SHORT / FLOAT

    // THE CONTEXT'S CONVERSION KIT, TAKEN ON THE FIRST CONVERSION LAYOUT (RENDER THREAD) AND READ BY supports()
    private volatile Kit kit;
    private int conv = CONV_NONE; // ACTIVE CONVERSION KIND FOR THE CURRENT FORMAT

    // LAST REQUESTED FORMAT (PRODUCER-SIDE SNAPSHOT): format() DEFERS FIELD UPDATES TO THE RENDER
    // THREAD, SO ASYNC preload() VALIDATES AGAINST THIS TO REJECT SYNCHRONOUSLY INSTEAD OF WARN-ONLY
    private volatile Req req;

    // renderThreadEx MUST RUN ITS TASKS ON renderThread (THE GL CONTEXT OWNER) AND BE PUMPED EVERY RENDER FRAME; BOTH NULL
    // BUILDS A NO-THREAD-CONTRACT ENGINE WHOSE GL CALLS RUN ON THE CALLING THREAD, WHICH THEN MUST OWN THE CONTEXT
    GLEngine(final Thread renderThread, final Executor renderThreadEx) {
        if (renderThread != null && renderThreadEx == null) throw new IllegalArgumentException("renderThreadEx is required when renderThread is set");
        this.renderThread = renderThread;
        this.renderThreadEx = renderThreadEx;

        // ENGINES SHARING A RENDER THREAD SHARE ITS HUB (FIRST EXECUTOR WINS), EVICTED WITH ITS LAST
        // ENGINE SO A HOT-SWAP NEVER LEAKS; THE HUBS MONITOR KEEPS ACQUIRE AND EVICT FROM RACING.
        if (renderThread != null) {
            synchronized (HUBS) {
                this.hub = HUBS.computeIfAbsent(renderThread, t -> new Hub(renderThreadEx));
                this.hub.engines++;
            }
            // VALIDATE THE THREAD PAIR ASYNCHRONOUSLY. A MISMATCH IS LOGGED, NOT THROWN: THROWING RUNS ON
            // THE EXECUTOR AND WOULD CRASH THE HOST RENDER LOOP INSTEAD OF THE MISCONFIGURED CALLER.
            renderThreadEx.execute(() -> {
                if (Thread.currentThread() != renderThread)
                    LOGGER.error(IT, "renderThreadEx does not dispatch to renderThread; uploads will corrupt the GL context");
            });
        } else {
            this.hub = null;
        }
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Returns the GL name of the resolved RGBA texture of the active frame, or 0 before the first one; read it fresh on every draw. */
    @Override
    public long texture() {
        // DIRECT FORMATS (BGRA, RGBA, RGB) ALIAS PLANE 0; PLANAR AND PACKED ONES RESOLVE TO IT THROUGH THE FBO SHADER PASS, SO
        // THE HANDLE IS NEVER A RAW PLANE. IT HOLDS THE CURRENT FRAME ONLY AFTER THE RENDER CYCLE AND MAY BE REALLOCATED
        if (this.released) return 0L;
        final int[] textures = this.frameTextures;
        final int frame = this.activeFrameTexture;
        if (frame >= 0 && frame < textures.length) return textures[frame];
        return this.managedTexture;
    }

    @Override
    public boolean preload() {
        return true;
    }

    @Override
    public boolean supports(final PixelFormat format, final int bits) {
        // THE BASE DECLINES BCn: ITS SAMPLING IS NOT WIRED YET
        if (!super.supports(format, bits)) return false;
        return switch (format) {
            // DIRECT LAYOUTS UPLOAD AS GL TYPES: PACKED 8_8_8_8_REV FOR BGRA/YUYV, BYTE OR SHORT FOR RGB/RGBA
            case BGRA, YUYV, UYVY -> bits == 8;
            case RGB, RGBA -> bits == 8 || bits == 16;
            // A KIND WHOSE CONVERSION SHADER FAILED TO COMPILE ON THIS CONTEXT IS DECLINED SO THE PRODUCER RE-ROUTES IT
            // THROUGH THE SCALER TO BGRA INSTEAD OF RENDERING BLACK THROUGH A MISSING PROGRAM.
            default -> {
                final Kit kit = this.kit;
                yield kit == null || (kit.failKinds & (1 << kindOf(format))) == 0;
            }
        };
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Override
    boolean preload(final ByteBuffer[] frames) {
        if (frames == null || frames.length == 0 || this.released) return false;
        for (final ByteBuffer frame: frames) {
            if (frame == null || !frame.isDirect()) return false;
        }
        if (this.renderThread == null || this.renderThread == Thread.currentThread()) {
            // SYNCHRONOUS PATH (ALREADY ON RENDER THREAD): UPLOAD EVERYTHING NOW.
            if (this.width <= 0 || this.height <= 0) return false;
            if (!directUpload(this.format)) return false;
            final Env env = Env.save(false);
            try {
                this.beginFrameTextures();
                final int[] textures = new int[frames.length];
                for (int i = 0; i < frames.length; i++) {
                    textures[i] = this.newTexture();
                    if (!this.uploadDirectTexture(textures[i], frames[i])) {
                        this.orphanTextures(textures);
                        return false;
                    }
                }
                this.frameTextures = textures;
                this.frameTexReady = textures.length;
                this.activeFrameTexture = 0;
                return true;
            } finally {
                env.restore();
            }
        }

        // REJECT SYNCHRONOUSLY WHAT THE RENDER-THREAD TASK COULD ONLY WARN ABOUT: THE CALLER TREATS
        // true AS "MODE ARMED", SO AN ASYNC-ONLY REJECTION WOULD BLANK THE MEDIA SILENTLY
        final Req req = this.req;
        if (req == null || req.width <= 0 || req.height <= 0 || !directUpload(req.format)) return false;

        // ASYNC PATH: ONE RENDER TASK PER FRAME SO A LONG ANIMATION NEVER STALLS ONE RENDER TICK;
        // frame() CLAMPS TO THE UPLOADED PREFIX AND genBox INVALIDATES THE BATCH ON RELEASE/REFORMAT.
        final int[] genBox = new int[1];
        final int[][] texBox = new int[1][];
        this.renderThreadEx.execute(() -> {
            if (this.released || this.width <= 0 || this.height <= 0 || !directUpload(this.format)) {
                genBox[0] = -1;
                if (!this.released) LOGGER.warn(IT, "Frame textures rejected: format {} not direct-uploadable", this.format);
                return;
            }
            final Env env = Env.save(false);
            try {
                this.beginFrameTextures();
                genBox[0] = this.frameTexGen;
                final int[] textures = new int[frames.length];
                textures[0] = this.newTexture();
                if (!this.uploadDirectTexture(textures[0], frames[0])) {
                    this.orphanTextures(textures);
                    genBox[0] = -1;
                    return;
                }
                texBox[0] = textures;
                this.frameTextures = textures;
                this.frameTexReady = 1;
                this.activeFrameTexture = 0;
            } finally {
                env.restore();
            }
        });
        for (int i = 1; i < frames.length; i++) {
            final int index = i;
            this.renderThreadEx.execute(() -> {
                if (this.released || genBox[0] != this.frameTexGen || texBox[0] == null) return; // STALE BATCH
                final Env env = Env.save(false);
                try {
                    final int[] textures = texBox[0];
                    textures[index] = this.newTexture();
                    // A FAILED FRAME ABORTS THE TAIL: ADVANCING ready PAST IT WOULD EXPOSE AN EMPTY TEXTURE
                    if (this.uploadDirectTexture(textures[index], frames[index])) this.frameTexReady = index + 1;
                    else genBox[0] = -1;
                } finally {
                    env.restore();
                }
            });
        }
        return true;
    }

    @Override
    void frame(final int index) {
        final int ready = this.frameTexReady;
        if (ready <= 0) return;
        // CLAMP TO THE UPLOADED PREFIX WHILE THE TAIL OF THE ANIMATION IS STILL UPLOADING
        this.activeFrameTexture = MathUtil.clamp(index, 0, ready - 1);
    }

    // RESETS THE PIPELINE FOR A NEW FORMAT (PLANE TEXTURES, PBOs, RING; SHADERS CACHED PER KIND, MANAGED TEXTURE REALLOCATED
    // ON THE NEXT UPLOAD). DEFERRED TO THE RENDER THREAD, SO width()/height() LAG: PRODUCERS TRACK THEIR OWN SIZE
    @Override
    boolean apply(final PixelFormat format, final int width, final int height, final int bits, final Colorimetry color) {
        this.req = new Req(format, width, height);
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(() -> this.applyNow(format, width, height, bits, color));
            return true;
        }
        this.applyNow(format, width, height, bits, color);
        return true;
    }

    // WRITES THE FRAME INTO THE PERSISTENT RING (PRODUCER-SIDE MEMCPY) OR PARKS THE CLIENT BUFFERS
    // IN THE LATEST-WINS SLOT; THE SHARED HUB QUEUES AT MOST ONE BATCHED DRAIN PER RENDER THREAD.
    @Override
    void push(final ByteBuffer[] bufs, final int[] strides) {
        try {
            if (this.renderThread == null || this.renderThread == Thread.currentThread()) {
                // SYNCHRONOUS PATH: ALREADY ON THE RENDER THREAD (OR NO THREAD CONTRACT AT ALL)
                final Env env = Env.save(this.conv != CONV_NONE);
                try {
                    this.renderSubmission(client(bufs, strides));
                } finally {
                    env.restore();
                }
                return;
            }

            // RING CONGESTION (DROPPED) SKIPS THE FRAME BUT STILL SCHEDULES A DRAIN SO FENCES
            // RETIRE AND THE RING RECOVERS.
            final Submission ring = this.ringWrite(bufs, strides);
            if (ring != DROPPED) this.pending = ring != null ? ring : client(bufs, strides);
            this.hub.schedule(this);
        } catch (final RuntimeException e) {
            // A GL FAILURE OR A REJECTING EXECUTOR DROPS THE FRAME; UPLOADS NEVER THROW AT THE DECODER
            if (!this.failWarned) LOGGER.error(IT, "Upload failed; frame dropped", e);
            this.failWarned = true;
        }
    }

    // released IS ALREADY SET: PRODUCERS STOP AT ONCE AND STALE RENDER TASKS BECOME NO-OPS; THE GPU
    // TEARDOWN RUNS ONCE, DEFERRED TO THE RENDER THREAD WHEN THERE IS ONE
    @Override
    void destroy() {
        // DROP OUR HUB REFERENCE; THE LAST ENGINE LEAVING EVICTS THE HUB, SO A DEAD THREAD LEAKS NOTHING
        if (this.hub != null) {
            synchronized (HUBS) {
                if (--this.hub.engines <= 0) HUBS.remove(this.renderThread, this.hub);
            }
        }
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(this::destroyGL);
            return;
        }
        this.destroyGL();
    }

    // ==========================================================================
    // PIPELINE RESETS (RENDER THREAD)
    // ==========================================================================
    // PREPARES THE ENGINE FOR A FRESH FRAME-TEXTURE SET: DROPS STREAMING RESOURCES (RING, PBOS)
    // AND ANY PREVIOUS SET, THEN CLAIMS A NEW GENERATION.
    private void beginFrameTextures() {
        this.releaseFrameTextures();
        this.releasePBOs();
        this.destroyRing();
        this.firstFrame = true;
        this.frameTexGen++;
    }

    private void applyNow(final PixelFormat format, final int width, final int height, final int bits, final Colorimetry color) {
        if (this.released) return; // A RELEASE OVERTOOK THE DEFERRED TASK

        // RELEASE FORMAT-SPECIFIC RESOURCES
        final Env env = Env.save(false);
        try {
            this.releaseFrameTextures();
            this.releasePlaneTextures();
            this.releasePBOs();
            this.destroyRing();
        } finally {
            env.restore();
        }

        // RESET UPLOAD STATE
        this.pboWriteIdx = 0;
        this.pboReady = false;
        this.firstFrame = true;

        // DERIVE BIT DEPTH STATE: 10 AND 12-BIT SAMPLES LIVE IN 16-BIT TEXELS, STRETCHED BY bitScale IN THE SHADER
        this.bitScale = bitScale(bits);
        this.colorimetry = color.constants(format, width, height, bits);
        switch (bits) {
            case 10, 12, 16 -> {
                this.glInternalR = GL30.GL_R16;
                this.glInternalRG = GL30.GL_RG16;
                this.glType = GL11.GL_UNSIGNED_SHORT;
            }
            case 32 -> {
                this.glInternalR = GL30.GL_R32F;
                this.glInternalRG = GL30.GL_RG32F;
                this.glType = GL11.GL_FLOAT;
            }
            default -> { // 8-BIT
                this.glInternalR = GL30.GL_R8;
                this.glInternalRG = GL30.GL_RG8;
                this.glType = GL11.GL_UNSIGNED_BYTE;
            }
        }

        // PUBLISH THE LAYOUT THEN BUILD THE PLANE LAYOUT FOR THE NEW FORMAT
        this.publish(format, width, height, bits, color);
        this.buildPlanes();
        this.activePlanes = this.planes.length;

        // THE CONTEXT'S KIT COMPILES EACH CONVERSION KIND ONCE FOR EVERY ENGINE ON IT
        if (this.conv != CONV_NONE) {
            if (this.kit == null) this.kit = Kit.acquire();
            this.kit.compile(this.conv, format);
        }

        LOGGER.debug(IT, "Format set: {} {}x{} ({} planes, {}bpc)", format, width, height, this.planes.length, bits);
    }

    // ==========================================================================
    // SUBMISSION: PRODUCER SIDE
    // ==========================================================================
    // COPIES ALL PLANES INTO THE NEXT FREE SLOT; RETURNS null WHEN THE RING IS UNAVAILABLE/TOO
    // SMALL (CALLER FALLS BACK TO CLIENT BUFFERS) OR DROPPED WHEN EVERY SLOT IS IN FLIGHT.
    private Submission ringWrite(final ByteBuffer[] bufs, final int[] strides) {
        final int[] sizes = new int[bufs.length];
        long total = 0L;
        for (int i = 0; i < bufs.length; i++) {
            sizes[i] = bufs[i].remaining();
            total += sizes[i];
        }
        synchronized (this.ringLock) {
            if (this.ringAddr == 0L || total > this.ringSlotBytes) return null;
            if (this.ringProduced - this.ringRetired >= RING_SLOTS) return DROPPED;
            final long slot = this.ringProduced;
            long dst = this.ringAddr + (slot % RING_SLOTS) * this.ringSlotBytes;
            for (int i = 0; i < bufs.length; i++) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(bufs[i]), dst, sizes[i]);
                dst += sizes[i];
            }
            this.ringProduced = slot + 1;
            return new Submission(null, null, strides, sizes, slot, this.ringEra);
        }
    }

    // ==========================================================================
    // SUBMISSION: RENDER THREAD SIDE
    // ==========================================================================
    // ONE ENGINE'S SHARE OF A HUB WAVE: CONSUME THE LATEST-WINS SLOT AND RETIRE RING FENCES.
    // THE CALLER OWNS THE STATE ENVELOPE.
    private void drainOne() {
        final Submission s = this.pending;
        this.pending = null;
        if (s != null) this.renderSubmission(s);
        this.retireRing(this.ringConsumed);
    }

    private void renderSubmission(final Submission s) {
        if (this.released || this.width <= 0 || this.height <= 0 || this.planes.length == 0) return;
        if (s.sizes.length != this.planes.length) return;                          // STALE FORMAT
        if (s.slot >= 0L && (s.era != this.ringEra || this.ringAddr == 0L)) return; // STALE RING

        if (this.frameTextures.length > 0) this.releaseFrameTextures();
        this.ensureTargets();

        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);

        final boolean uploaded;
        if (s.slot >= 0L) {
            // FREE SLOTS THE LATEST-WINS POLICY SKIPPED, THEN CONSUME THIS ONE (A REFUSED ONE CARRIES NO FENCE)
            this.retireRing(s.slot);
            uploaded = this.uploadRing(s);
            this.ringConsumed = s.slot + 1;
        } else {
            uploaded = this.uploadClient(s);
        }
        if (!uploaded) return; // A REFUSED FRAME IS NEITHER CONVERTED NOR PUBLISHED: THE LAST ONE STAYS EXPOSED

        this.firstFrame = false;
        if (this.conv != CONV_NONE) this.convertToRGBA();
    }

    // UPLOAD FROM CLIENT MEMORY: RESPEC ON FIRST FRAME/SIZE CHANGE, LEGACY DOUBLE-BUFFERED PBOS IN STEADY STATE; AN ENGINE
    // WITH A RENDER THREAD THEN ARMS (OR REGROWS) THE RING SO LATER OFF-THREAD FRAMES SKIP THIS PATH
    private boolean uploadClient(final Submission s) {
        long total = 0L;
        boolean respec = this.firstFrame || !this.pboReady;
        final long[] needs = new long[this.planes.length];
        for (int i = 0; i < this.planes.length; i++) {
            final long needed = this.required(this.planes[i], i, s.strides[i], s.sizes[i]);
            if (needed < 0L) return false;
            needs[i] = needed;
            respec |= this.planeBytes[i] != needed;
            // RING SLOTS ARE SIZED FOR WHAT PRODUCERS ACTUALLY COPY (remaining()), WHICH MAY
            // EXCEED THE TIGHT PLANE BYTES WHEN BUFFERS CARRY ALIGNMENT PADDING
            total += s.sizes[i];
        }

        if (!this.pboInitialized) {
            // LAZY LEGACY PBO PAIRS: ONE DOUBLE BUFFER PER PLANE
            final int count = this.planes.length * NUM_PBOS;
            final int[] ids = new int[count];
            GL15.glGenBuffers(ids);
            System.arraycopy(ids, 0, this.pbos, 0, count);
            this.checkGLError("initPBOs");
            this.pboInitialized = true;
            this.pboWriteIdx = 0;
            this.pboReady = false;
        }

        if (respec) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < this.planes.length; i++) {
                final Plane p = this.planes[i];
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, p.tex);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, p.align(s.strides[i]));
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, p.rowLen(s.strides[i]));
                GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, p.internal, p.w, p.h, 0, p.format, p.type, s.addrs[i]);
                this.checkGLError("plane texImage");
                this.planeBytes[i] = needs[i];
            }
            this.pboWriteIdx = 0;
            this.pboReady = true;
        } else {
            // THE FRAME GOES INTO THE PBO THE PREVIOUS UPLOAD DID NOT USE AND THE TEXTURE READS IT FROM THERE, SO IT SHOWS THIS
            // FRAME WHILE THE GPU MAY STILL BE COPYING OUT OF THE OTHER ONE
            final int writeIdx = (this.pboWriteIdx + 1) % NUM_PBOS;
            for (int i = 0; i < this.planes.length; i++) {
                final Plane p = this.planes[i];
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[i * NUM_PBOS + writeIdx]);
                GL15.nglBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, this.planeBytes[i], s.addrs[i], GL15.GL_STREAM_DRAW);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, p.tex);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, p.align(s.strides[i]));
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, p.rowLen(s.strides[i]));
                GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, p.w, p.h, p.format, p.type, 0L);
                if (GL_CHECKS) this.checkGLError("plane PBO texSub");
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            this.pboWriteIdx = writeIdx;
        }

        // ONLY AN OFF-THREAD PRODUCER WRITES THE RING, SO AN ENGINE WITHOUT A RENDER THREAD NEVER MAPS ONE
        if (this.renderThread != null && (this.ringAddr == 0L || total > this.ringSlotBytes)) this.armRing(total);
        return true;
    }

    // UPLOAD FROM A RING SLOT: PIXELS ARE ALREADY GPU-VISIBLE, SO ONLY texSub FROM OFFSETS
    // PLUS A FENCE; PLANE SIZE CHANGES RE-SPEC VIA texImage FROM THE SAME OFFSETS.
    private boolean uploadRing(final Submission s) {
        // EVERY PLANE IS CHECKED BEFORE THE FIRST ONE UPLOADS, SO A REFUSED FRAME NEVER MIXES NEW AND OLD PLANES
        final long[] needs = new long[this.planes.length];
        for (int i = 0; i < this.planes.length; i++) {
            needs[i] = this.required(this.planes[i], i, s.strides[i], s.sizes[i]);
            if (needs[i] < 0L) return false;
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.ringBufferId);
        long offset = (s.slot % RING_SLOTS) * this.ringSlotBytes;
        for (int i = 0; i < this.planes.length; i++) {
            final Plane p = this.planes[i];
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, p.tex);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, p.align(s.strides[i]));
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, p.rowLen(s.strides[i]));
            if (this.planeBytes[i] != needs[i]) {
                GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, p.internal, p.w, p.h, 0, p.format, p.type, offset);
                this.checkGLError("ring plane texImage");
                this.planeBytes[i] = needs[i];
            } else {
                GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, p.w, p.h, p.format, p.type, offset);
                if (GL_CHECKS) this.checkGLError("ring plane texSub");
            }
            offset += s.sizes[i];
        }
        this.ringFences[(int) (s.slot % RING_SLOTS)] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        return true;
    }

    // RETIRES RING SLOTS STRICTLY IN ORDER UP TO cap. SKIPPED SLOTS (NO FENCE) RETIRE
    // IMMEDIATELY; CONSUMED SLOTS RETIRE ONCE THE GPU SIGNALED THEIR FENCE.
    private void retireRing(final long cap) {
        long retired = this.ringRetired;
        while (retired < cap) {
            final int idx = (int) (retired % RING_SLOTS);
            final long fence = this.ringFences[idx];
            if (fence != 0L) {
                final int state = GL32.glClientWaitSync(fence, 0, 0L);
                if (state != GL32.GL_ALREADY_SIGNALED && state != GL32.GL_CONDITION_SATISFIED) break;
                GL32.glDeleteSync(fence);
                this.ringFences[idx] = 0L;
            }
            retired++;
        }
        this.ringRetired = retired;
    }

    // ==========================================================================
    // PERSISTENT RING LIFECYCLE (RENDER THREAD)
    // ==========================================================================
    private void armRing(final long slotBytes) {
        if (this.ringCapable == null) {
            final GLCapabilities caps = GL.getCapabilities();
            this.ringCapable = caps.GL_ARB_buffer_storage || caps.OpenGL44;
            if (!this.ringCapable) LOGGER.debug(IT, "ARB_buffer_storage unavailable; using legacy PBO uploads");
        }
        if (!this.ringCapable || slotBytes <= 0L) return;
        this.destroyRing();

        final int flags = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
        final int id = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, id);
        GL44.glBufferStorage(GL21.GL_PIXEL_UNPACK_BUFFER, slotBytes * RING_SLOTS, flags);
        final ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_UNPACK_BUFFER, 0, slotBytes * RING_SLOTS, flags);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.checkGLError("armRing");
        if (mapped == null) {
            GL15.glDeleteBuffers(id);
            this.ringCapable = false;
            return;
        }
        synchronized (this.ringLock) {
            this.ringBufferId = id;
            this.ringSlotBytes = slotBytes;
            this.ringProduced = 0L;
            this.ringRetired = 0L;
            this.ringConsumed = 0L;
            this.ringEra++;
            this.ringAddr = MemoryUtil.memAddress(mapped); // PUBLISH LAST
        }
        LOGGER.debug(IT, "Persistent PBO ring armed: {} slots x {} bytes", RING_SLOTS, slotBytes);
    }

    private void destroyRing() {
        synchronized (this.ringLock) {
            this.ringAddr = 0L; // UNPUBLISH FIRST SO PRODUCERS STOP WRITING
            if (this.ringBufferId != 0) {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.ringBufferId);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                GL15.glDeleteBuffers(this.ringBufferId);
                this.ringBufferId = 0;
            }
            for (int i = 0; i < RING_SLOTS; i++) {
                if (this.ringFences[i] != 0L) {
                    GL32.glDeleteSync(this.ringFences[i]);
                    this.ringFences[i] = 0L;
                }
            }
            this.ringSlotBytes = 0L;
            this.ringProduced = 0L;
            this.ringRetired = 0L;
            this.ringConsumed = 0L;
            this.ringEra++;
        }
    }

    // ==========================================================================
    // RELEASE
    // ==========================================================================
    private void destroyGL() {
        final Env env = Env.save(false);
        try {
            this.destroyRing();
            if (this.fbo != 0) { GL30.glDeleteFramebuffers(this.fbo); this.fbo = 0; }
            if (this.quadVAO != 0) { GL30.glDeleteVertexArrays(this.quadVAO); this.quadVAO = 0; }
            if (this.quadVBO != 0) { GL15.glDeleteBuffers(this.quadVBO); this.quadVBO = 0; }
            this.releaseFrameTextures();
            this.releasePlaneTextures();
            if (this.managedTexture != 0) { this.orphanTexture(this.managedTexture); this.managedTexture = 0; }
            this.managedTextureW = 0;
            this.managedTextureH = 0;
            this.releasePBOs();
            this.firstFrame = true;
        } finally {
            // A FAILED DELETE ABOVE STILL LEAVES THE KIT, SO THE CONTEXT'S PROGRAMS NEVER OUTLIVE THEIR LAST ENGINE
            if (this.kit != null) { this.kit.release(); this.kit = null; }
            env.restore();
        }
    }

    // ==========================================================================
    // FBO CONVERSION: RENDERS PLANE TEXTURES THROUGH SHADER INTO managedTexture
    // ==========================================================================
    // THE HUB/SUBMIT ENVELOPE ALREADY SAVED THE HOST'S PROGRAM/VIEWPORT/FBO/VAO/DRAW TOGGLES
    // AND FORCED A CLEAN DRAW STATE, SO THIS PASS ONLY SETS WHAT IT USES.
    private void convertToRGBA() {
        // SHADER COMPILE FAILED: SKIP RATHER THAN BIND PROGRAM 0 (BLACK OUTPUT); supports() NOW DECLINES IT
        final Kit kit = this.kit;
        final int program = kit.programs[this.conv];
        if (program == 0) return;
        if (this.managedTexture == 0) this.managedTexture = this.newTexture();
        if (this.fbo == 0) this.fbo = GL30.glGenFramebuffers();

        // LAZY FULLSCREEN QUAD (CORE PROFILE, NO IMMEDIATE MODE); A VAO NEVER CROSSES CONTEXTS, SO IT STAYS PER ENGINE
        if (this.quadVAO == 0) {
            final float[] verts = {
                    -1, -1,   0, 0,
                     1, -1,   1, 0,
                     1,  1,   1, 1,
                    -1, -1,   0, 0,
                     1,  1,   1, 1,
                    -1,  1,   0, 1,
            };
            this.quadVAO = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(this.quadVAO);
            this.quadVBO = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVBO);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, verts, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);
            GL30.glBindVertexArray(0);
            this.checkGLError("init quad");
        }

        if (this.managedTextureW != this.width || this.managedTextureH != this.height) {
            // A BOUND UNPACK PBO WOULD TURN THE NULL POINTER INTO OFFSET 0: UNBIND EXPLICITLY
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.managedTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, this.width, this.height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            this.checkGLError("managed texture alloc");
            this.managedTextureW = this.width;
            this.managedTextureH = this.height;
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.managedTexture, 0);
        GL11.glViewport(0, 0, this.width, this.height);

        // ONE BIND PATH FOR EVERY CONVERSION KIND: BIND EACH PLANE TO ITS UNIT AND SET THE SHARED
        // UNIFORMS; LOCATIONS A PROGRAM DIDN'T DECLARE ARE -1, WHICH glUniform* SILENTLY IGNORES.
        GL20.glUseProgram(program);
        final int[] locs = kit.uniforms[this.conv];
        for (int i = 0; i < this.planes.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.planes[i].tex);
            GL20.glUniform1i(locs[i], i);
        }
        GL20.glUniform1f(locs[4], this.bitScale);
        GL20.glUniform1f(locs[5], this.format == PixelFormat.NV21 || this.format == PixelFormat.UYVY ? 1.0f : 0.0f);
        GL20.glUniform1f(locs[6], (float) this.width);
        GL20.glUniform4fv(locs[7], this.colorimetry);

        GL30.glBindVertexArray(this.quadVAO);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);
        if (GL_CHECKS) this.checkGLError("FBO convert quad");
    }

    // ==========================================================================
    // PLANE LAYOUT
    // ==========================================================================
    // BUILDS THE PER-PLANE LAYOUT: DIRECT FORMATS (BGRA/RGBA/RGB) TARGET managedTexture WITHOUT
    // A SHADER PASS; EVERYTHING ELSE RENDERS PLANE TEXTURES THROUGH THE FBO CONVERT.
    private void buildPlanes() {
        final PixelFormat f = this.format;
        this.conv = kindOf(f);
        final Plane[] planes = new Plane[f.planes()];
        for (int i = 0; i < planes.length; i++) {
            // SIZES AND TEXEL BYTES COME FROM PixelFormat; ONLY THE GL TEXEL TYPE IS DECIDED HERE
            final int internal;
            final int glFormat;
            final int type;
            switch (f) {
                case BGRA -> { internal = GL11.GL_RGBA; glFormat = GL12.GL_BGRA; type = GL12.GL_UNSIGNED_INT_8_8_8_8_REV; }
                case RGB -> { internal = GL11.GL_RGBA; glFormat = GL11.GL_RGB; type = this.bits > 8 ? GL11.GL_UNSIGNED_SHORT : GL11.GL_UNSIGNED_BYTE; }
                case RGBA -> { internal = GL11.GL_RGBA; glFormat = GL11.GL_RGBA; type = this.bits > 8 ? GL11.GL_UNSIGNED_SHORT : GL12.GL_UNSIGNED_INT_8_8_8_8_REV; }
                case YUYV, UYVY -> { internal = GL11.GL_RGBA; glFormat = GL11.GL_RGBA; type = GL11.GL_UNSIGNED_BYTE; }
                default -> { // GRAY, NV AND PLANAR YUV: ONE SAMPLE PER TEXEL, TWO ON THE INTERLEAVED NV CHROMA
                    final boolean pair = i == 1 && (f == PixelFormat.NV12 || f == PixelFormat.NV21);
                    internal = pair ? this.glInternalRG : this.glInternalR;
                    glFormat = pair ? GL30.GL_RG : GL11.GL_RED;
                    type = this.glType;
                }
            }
            planes[i] = new Plane(f.planeWidth(i, this.width), f.planeHeight(i, this.height), internal, glFormat, type, f.texelBytes(i, this.bits));
        }
        this.planes = planes;
    }

    // CREATES TARGET TEXTURES LAZILY. DIRECT FORMATS ALIAS PLANE 0 TO managedTexture; PACKED
    // YUYV NEEDS NEAREST FILTERING SO PIXEL PAIRS ARE NOT INTERPOLATED.
    private void ensureTargets() {
        if (this.conv == CONV_NONE) {
            if (this.managedTexture == 0) this.managedTexture = this.newTexture();
            this.planes[0].tex = this.managedTexture;
            return;
        }
        final boolean packed = this.conv == CONV_YUYV;
        for (final Plane p: this.planes) {
            if (p.tex != 0) continue;
            p.tex = this.newTexture();
            if (packed) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, p.tex);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            }
        }
    }

    // ==========================================================================
    // UPLOAD HELPERS
    // ==========================================================================
    // A DIRECT FORMAT HAS ONE PLANE, ALREADY LAID OUT BY buildPlanes(), SO A PRELOADED FRAME UPLOADS THROUGH IT; PRELOADED
    // FRAMES ARE TIGHTLY PACKED, SO THE PLANE DESCRIBES THEM WITH STRIDE 0
    private boolean uploadDirectTexture(final int texture, final ByteBuffer buffer) {
        final Plane p = this.planes[0];
        if (this.required(p, 0, 0, buffer.remaining()) < 0L) return false;
        final long addr = MemoryUtil.memAddress(buffer);
        if (addr == 0L) return false;

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, p.align(0));
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, p.rowLen(0));
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

        GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, p.internal, p.w, p.h, 0, p.format, p.type, addr);
        this.checkGLError("preloaded frame glTexImage2D");
        return true;
    }

    // BYTES PLANE i READS FROM size BYTES WHOSE ROWS START stride APART, OR -1 AFTER A LATCHED WARNING WHEN GL CANNOT
    // EXPRESS THE STRIDE OR THE BUFFER IS SHORT
    private long required(final Plane p, final int i, final int stride, final long size) {
        final long needed = minBytes(stride, (long) p.w * p.texelBytes, p.h);
        if (needed < 0L || p.align(stride) == 0) {
            if (!this.strideWarned) LOGGER.warn(IT, "Plane {} stride {} cannot hold rows of {} {}-byte texels; frame dropped", i, stride, p.w, p.texelBytes);
            this.strideWarned = true;
            return -1L;
        }
        if (size < needed) {
            if (!this.shortWarned) LOGGER.warn(IT, "Plane {} buffer too small: {} < {}", i, size, needed);
            this.shortWarned = true;
            return -1L;
        }
        return needed;
    }

    private int newTexture() {
        final int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        return tex;
    }

    private void checkGLError(final String op) {
        final int err = GL11.glGetError();
        if (err == GL11.GL_NO_ERROR || this.glErrorWarned) return;
        this.glErrorWarned = true;
        LOGGER.warn(IT, "GL error after '{}': {}", op, glErrorName(err));
    }

    // ==========================================================================
    // TEXTURE AND BUFFER DELETION
    // ==========================================================================
    // FREE STORAGE NOW (ZERO-SIZE RESPEC), DEFER THE NAME: A HOST CACHE MAY STILL POINT AT IT AND A REUSED NAME SKIPS A
    // FUTURE REBIND; "UNBOUND ON EVERY UNIT" (sweepOrphans) PROVES IT DELETABLE.
    private void orphanTexture(final int texture) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 0, 0, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        ORPHANS.computeIfAbsent(GL.getCapabilities(), c -> new ArrayDeque<>()).addFirst(texture);
    }

    private void orphanTextures(final int[] textures) {
        for (final int texture: textures) {
            if (texture != 0) this.orphanTexture(texture);
        }
    }

    private void releaseFrameTextures() {
        final int[] textures = this.frameTextures;
        this.frameTexGen++;
        if (textures.length == 0) return;
        this.orphanTextures(textures);
        this.frameTextures = EMPTY_TEXTURES;
        this.frameTexReady = 0;
        this.activeFrameTexture = -1;
    }

    private void releasePlaneTextures() {
        for (final Plane p: this.planes) {
            // PLANE 0 ALIASES managedTexture FOR DIRECT FORMATS, RELEASED SEPARATELY.
            // PLANE TEXTURES ARE NEVER EXPOSED TO THE HOST, SO IMMEDIATE DELETION IS SAFE.
            if (p.tex != 0 && p.tex != this.managedTexture) GL11.glDeleteTextures(p.tex);
            p.tex = 0;
        }
    }

    private void releasePBOs() {
        if (!this.pboInitialized) return;
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        final int count = this.activePlanes * NUM_PBOS;
        if (count > 0) {
            final int[] toDelete = new int[count];
            System.arraycopy(this.pbos, 0, toDelete, 0, count);
            GL15.glDeleteBuffers(toDelete);
        }
        Arrays.fill(this.pbos, 0);
        Arrays.fill(this.planeBytes, 0);
        this.pboInitialized = false;
        this.pboReady = false;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // SNAPSHOTS SIZES AND BASE ADDRESSES NOW: THE DRAIN RUNS LATER ON THE RENDER THREAD AND MUST NOT
    // DEPEND ON WHERE position SITS BY THEN (THE CONTRACT ONLY PROMISES THE BYTES STAY VALID)
    private static Submission client(final ByteBuffer[] bufs, final int[] strides) {
        final int[] sizes = new int[bufs.length];
        final long[] addrs = new long[bufs.length];
        for (int i = 0; i < bufs.length; i++) {
            sizes[i] = bufs[i].remaining();
            addrs[i] = MemoryUtil.memAddress(bufs[i]);
        }
        return new Submission(bufs, addrs, strides, sizes, -1L, 0);
    }

    // CONVERSION KIND OF A LAYOUT: DIRECT FORMATS (BGRA, RGBA, RGB) NEED NO SHADER PASS
    private static int kindOf(final PixelFormat format) {
        return switch (format) {
            case GRAY -> CONV_GRAY;
            case NV12, NV21 -> CONV_NV;
            case YUV420P, YUV422P, YUV444P -> CONV_YUV3;
            case YUVA420P, YUVA422P, YUVA444P -> CONV_YUVA;
            case YUYV, UYVY -> CONV_YUYV;
            default -> CONV_NONE;
        };
    }

    private static boolean directUpload(final PixelFormat format) {
        return format == PixelFormat.BGRA || format == PixelFormat.RGBA || format == PixelFormat.RGB;
    }

    // RUNS RIGHT AFTER AN ENVELOPE RESTORE (ACTUAL STATE == HOST STATE): SCANS THE TEXTURE
    // UNITS AND DELETES EVERY ORPHAN NO UNIT STILL BINDS. LEAVES THE ACTIVE UNIT UNTOUCHED.
    private static void sweepOrphans(final int activeUnit) {
        final ArrayDeque<Integer> orphans = ORPHANS.get(GL.getCapabilities());
        if (orphans == null || orphans.isEmpty()) return;
        final int units = Math.min(GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS), ORPHAN_SCAN_UNITS);
        final int[] bound = new int[units];
        for (int u = 0; u < units; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            bound[u] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        GL13.glActiveTexture(activeUnit);
        orphans.removeIf(id -> {
            for (final int b: bound) {
                if (b == id) return false;
            }
            GL11.glDeleteTextures(id);
            return true;
        });
    }

    // LINKS THE CONVERSION PROGRAM OF ONE FRAGMENT; 0 WHEN A FILE OR A STAGE FAILS, SO THE KIT DECLINES THAT KIND
    private static int compileShader(final String fragment) {
        final String vertSrc, fragSrc;
        try {
            vertSrc = IOTool.shader(VERTEX_SHADER);
            fragSrc = IOTool.shader(fragment);
        } catch (final IOException e) {
            LOGGER.error(IT, "Cannot read the conversion shader {}", fragment, e);
            return 0;
        }
        final int vert = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vert, vertSrc);
        GL20.glCompileShader(vert);
        if (GL20.glGetShaderi(vert, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error(IT, "Failed to compile vertex shader: {}", GL20.glGetShaderInfoLog(vert, 1_024));
            GL20.glDeleteShader(vert);
            return 0;
        }

        final int frag = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(frag, fragSrc);
        GL20.glCompileShader(frag);
        if (GL20.glGetShaderi(frag, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error(IT, "Failed to compile fragment shader: {}", GL20.glGetShaderInfoLog(frag, 1_024));
            GL20.glDeleteShader(vert);
            GL20.glDeleteShader(frag);
            return 0;
        }

        final int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vert);
        GL20.glAttachShader(prog, frag);
        GL20.glBindAttribLocation(prog, 0, "position");
        GL20.glBindAttribLocation(prog, 1, "uv");
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            LOGGER.error(IT, "Failed to link shader program: {}", GL20.glGetProgramInfoLog(prog, 1_024));
            GL20.glDeleteProgram(prog);
            GL20.glDeleteShader(vert);
            GL20.glDeleteShader(frag);
            return 0;
        }

        GL20.glDetachShader(prog, vert);
        GL20.glDetachShader(prog, frag);
        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);
        return prog;
    }

    private static String glErrorName(final int err) {
        return switch (err) {
            case GL11.GL_INVALID_ENUM -> "INVALID_ENUM";
            case GL11.GL_INVALID_VALUE -> "INVALID_VALUE";
            case GL11.GL_INVALID_OPERATION -> "INVALID_OPERATION";
            case GL11.GL_STACK_OVERFLOW -> "STACK_OVERFLOW";
            case GL11.GL_STACK_UNDERFLOW -> "STACK_UNDERFLOW";
            case GL11.GL_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
            default -> "0x" + Integer.toHexString(err);
        };
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // ONE SUBMITTED FRAME. slot >= 0 MEANS PIXELS LIVE IN THE PERSISTENT RING (bufs IS NULL); OTHERWISE bufs KEEPS THE
    // CALLER'S DIRECT BUFFERS REACHABLE AND addrs THEIR BASE ADDRESSES UNTIL THE DRAIN CONSUMES OR REPLACES IT.
    private record Submission(ByteBuffer[] bufs, long[] addrs, int[] strides, int[] sizes, long slot, int era) {}

    // PRODUCER-VISIBLE SNAPSHOT OF THE LAST format() REQUEST (SEE req FIELD)
    private record Req(PixelFormat format, int width, int height) {}

    // PER-PLANE TEXTURE LAYOUT FOR THE CURRENT FORMAT
    private static final class Plane {
        final int w;
        final int h;
        final int internal;
        final int format;
        final int type;
        final int texelBytes;
        final int elementBytes; // ONE GL ELEMENT: A COMPONENT, OR THE WHOLE PACKED 8_8_8_8 PIXEL
        int tex;

        Plane(final int w, final int h, final int internal, final int format, final int type, final int texelBytes) {
            this.w = w;
            this.h = h;
            this.internal = internal;
            this.format = format;
            this.type = type;
            this.texelBytes = texelBytes;
            this.elementBytes = type == GL11.GL_UNSIGNED_BYTE ? 1 : type == GL11.GL_UNSIGNED_SHORT ? 2 : 4;
        }

        int rowLen(final int stride) {
            return stride == 0 ? 0 : stride / this.texelBytes;
        }

        // GL_UNPACK_ALIGNMENT THAT LANDS ROWS OF rowLen(stride) TEXELS ON stride: GL PADS A ROW UP TO AN ALIGNMENT LARGER
        // THAN ONE ELEMENT, SO A STRIDE OF NO WHOLE TEXEL COUNT NEEDS ONE DIVIDING IT. 0 WHEN NONE FITS
        int align(final int stride) {
            final int rest = stride == 0 ? 0 : stride % this.texelBytes;
            if (rest == 0) return 1;
            for (int a = 8; a > this.elementBytes; a >>= 1)
                if (stride % a == 0 && rest < a) return a;
            return 0;
        }
    }

    // BATCHES EVERY ENGINE'S PENDING DRAIN ON ONE RENDER THREAD INTO A SINGLE TASK PER FRAME,
    // WRAPPED IN ONE STATE ENVELOPE: THE glGet CAPTURE COST IS PER FRAME, NOT PER PLAYER.
    private static final class Hub {
        private final Executor ex;
        private final ArrayList<GLEngine> waiting = new ArrayList<>(); // GUARDED BY this
        private boolean queued;                                        // GUARDED BY this
        int engines;                                                   // LIVE ENGINE REFCOUNT, GUARDED BY THE HUBS MONITOR

        Hub(final Executor ex) {
            this.ex = ex;
        }

        void schedule(final GLEngine engine) {
            final boolean fire;
            synchronized (this) {
                if (!engine.hubQueued) {
                    engine.hubQueued = true;
                    this.waiting.add(engine);
                }
                fire = !this.queued;
                this.queued = true;
            }
            if (fire) {
                try {
                    this.ex.execute(this::drainAll);
                } catch (final RuntimeException e) {
                    synchronized (this) { this.queued = false; } // KEEP THE PIPELINE RECOVERABLE
                    throw e;
                }
            }
        }

        private void drainAll() {
            final GLEngine[] batch;
            synchronized (this) {
                this.queued = false;
                batch = this.waiting.toArray(new GLEngine[0]);
                this.waiting.clear();
                for (final GLEngine e: batch) e.hubQueued = false;
            }
            if (batch.length == 0) return;
            boolean convert = false;
            for (final GLEngine e: batch) convert |= e.conv != CONV_NONE;
            final Env env = Env.save(convert);
            try {
                for (final GLEngine e: batch) {
                    try {
                        e.drainOne();
                    } catch (final RuntimeException ex) {
                        // ISOLATE FAILURES: ONE BROKEN ENGINE MUST NOT STARVE THE REST OF THE WAVE
                        if (!e.failWarned) LOGGER.error(IT, "Engine drain failed", ex);
                        e.failWarned = true;
                    }
                }
            } finally {
                env.restore();
            }
        }
    }

    // ONE CONTEXT'S CONVERSION PROGRAMS AND THEIR UNIFORM LOCATIONS, SHARED BY EVERY ENGINE ON IT. ENGINES TAKE IT AND
    // LEAVE IT ON THEIR RENDER THREAD, SO EVERY GL CALL HERE RUNS WITH THE CONTEXT CURRENT
    private static final class Kit {
        private final GLCapabilities caps;
        // PROGRAMS AND LOCATIONS BY CONV KIND; LOCATIONS A PROGRAM DOESN'T DECLARE ARE -1, WHICH glUniform* IGNORES
        final int[] programs = new int[FRAGMENTS.length];
        final int[][] uniforms = new int[FRAGMENTS.length][];
        // KINDS WHOSE SHADER FAILED (ONE BIT PER CONV_*): supports() DECLINES THEM SO THE PRODUCER SENDS BGRA, NEVER RETRIED
        volatile int failKinds;
        private int engines; // GUARDED BY KITS

        private Kit(final GLCapabilities caps) {
            this.caps = caps;
        }

        static Kit acquire() {
            synchronized (KITS) {
                final Kit kit = KITS.computeIfAbsent(GL.getCapabilities(), Kit::new);
                kit.engines++;
                return kit;
            }
        }

        // THE LAST ENGINE OUT DELETES EVERYTHING; A LATER ENGINE ON THE SAME CONTEXT BUILDS A FRESH KIT
        void release() {
            synchronized (KITS) {
                if (--this.engines > 0) return;
                KITS.remove(this.caps, this);
            }
            for (final int program: this.programs) {
                if (program != 0) GL20.glDeleteProgram(program);
            }
        }

        // COMPILES A KIND ONCE; ALL PROGRAMS SHARE ONE UNIFORM SCHEME, SO ONE RESOLVE LOOP COVERS EVERY KIND
        void compile(final int kind, final PixelFormat format) {
            if (this.programs[kind] != 0 || (this.failKinds & (1 << kind)) != 0) return;
            final int prog = compileShader(FRAGMENTS[kind]);
            if (prog == 0) {
                this.failKinds |= 1 << kind;
                return;
            }
            final int[] locs = new int[UNIFORMS.length];
            for (int i = 0; i < UNIFORMS.length; i++) locs[i] = GL20.glGetUniformLocation(prog, UNIFORMS[i]);
            this.uniforms[kind] = locs;
            this.programs[kind] = prog;
            LOGGER.debug(IT, "Compiled the conversion shader for {} (program={})", format, prog);
        }
    }

    // EXACT CAPTURE/RESTORE OF ALL GL STATE THE ENGINE TOUCHES, KEEPING ANY HOST SKIP-IF-EQUAL
    // CACHE (GlStateManager, SODIUM) TRUTHFUL; THE CONVERT VARIANT ALSO FORCES A CLEAN DRAW STATE.
    private static final class Env {
        private static final int UNITS = 4; // TEXTURE0..3, THE UNITS THE CONVERT PASS BINDS

        private final boolean convert;
        private final boolean samplers;
        private final int active;
        private final int activeTex;
        private final int pixelUnpack;
        private final int unpackAlign;
        private final int unpackRow;
        private final int unpackSkipPx;
        private final int unpackSkipRows;
        private final int[] unitTex = new int[UNITS];
        private final int[] unitSampler = new int[UNITS];
        private int program;
        private int readFbo;
        private int drawFbo;
        private int vao;
        private int arrayBuffer;
        private final int[] polyMode = new int[2]; // GL RETURNS {FRONT, BACK}
        private final int[] viewport = new int[4];
        private final int[] colorMask = new int[4];
        private boolean blend;
        private boolean scissor;
        private boolean logicOp;
        private boolean cull;
        private boolean rasterDiscard;

        private Env(final boolean convert) {
            final GLCapabilities caps = convert ? GL.getCapabilities() : null;
            this.convert = convert;
            this.samplers = convert && (caps.OpenGL33 || caps.GL_ARB_sampler_objects);
            this.active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            this.activeTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            this.pixelUnpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            this.unpackAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            this.unpackRow = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
            this.unpackSkipPx = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
            this.unpackSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
            if (!convert) return;

            for (int i = 0; i < UNITS; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                this.unitTex[i] = GL13.GL_TEXTURE0 + i == this.active ? this.activeTex : GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                if (this.samplers) {
                    // SAMPLER OBJECTS OVERRIDE TEXTURE PARAMETERS: NEUTRALIZE THEM FOR THE PASS
                    this.unitSampler[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
                    if (this.unitSampler[i] != 0) GL33.glBindSampler(i, 0);
                }
            }
            GL13.glActiveTexture(this.active);
            this.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            this.readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            this.drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            this.vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            this.arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, this.polyMode);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
            GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, this.colorMask);
            this.blend = GL11.glIsEnabled(GL11.GL_BLEND);
            this.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            this.logicOp = GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP);
            this.cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            this.rasterDiscard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);

            // FORCE A CLEAN DRAW STATE FOR THE FULLSCREEN CONVERT PASS
            if (this.blend) GL11.glDisable(GL11.GL_BLEND);
            if (this.scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (this.logicOp) GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            if (this.cull) GL11.glDisable(GL11.GL_CULL_FACE);
            if (this.rasterDiscard) GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            GL11.glColorMask(true, true, true, true);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        }

        static Env save(final boolean convert) {
            return new Env(convert);
        }

        void restore() {
            if (this.convert) {
                for (int i = 0; i < UNITS; i++) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.unitTex[i]);
                    if (this.samplers && this.unitSampler[i] != 0) GL33.glBindSampler(i, this.unitSampler[i]);
                }
                GL20.glUseProgram(this.program);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.readFbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.drawFbo);
                GL30.glBindVertexArray(this.vao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.arrayBuffer);
                GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, this.polyMode[0]);
                GL11.glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
                GL11.glColorMask(this.colorMask[0] != 0, this.colorMask[1] != 0, this.colorMask[2] != 0, this.colorMask[3] != 0);
                if (this.blend) GL11.glEnable(GL11.GL_BLEND);
                if (this.scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
                if (this.logicOp) GL11.glEnable(GL11.GL_COLOR_LOGIC_OP);
                if (this.cull) GL11.glEnable(GL11.GL_CULL_FACE);
                if (this.rasterDiscard) GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            }
            GL13.glActiveTexture(this.active);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.activeTex);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pixelUnpack);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, this.unpackAlign);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, this.unpackRow);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, this.unpackSkipPx);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, this.unpackSkipRows);

            // HOST STATE IS EXACT AGAIN: DELETE ANY ORPHANED NAME NO UNIT STILL BINDS
            sweepOrphans(this.active);
        }
    }
}
