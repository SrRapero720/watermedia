package org.watermedia.api.player;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVHWDeviceContext;
import org.bytedeco.ffmpeg.avutil.AVHWFramesContext;
import org.bytedeco.ffmpeg.avutil.AVVkFrame;
import org.bytedeco.ffmpeg.avutil.AVVulkanDeviceContext;
import org.bytedeco.ffmpeg.avutil.AVVulkanFramesContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.system.linux.UNISTD;
import org.lwjgl.system.windows.Kernel32;
import org.lwjgl.vulkan.*;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.FFTool;
import org.watermedia.tools.IOTool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC;
import static org.bytedeco.ffmpeg.global.avfilter.avfilter_get_by_name;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memCopy;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.*;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.vkGetMemoryFdKHR;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.vkImportSemaphoreFdKHR;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR;
import static org.lwjgl.vulkan.KHRVideoDecodeAV1.VK_KHR_VIDEO_DECODE_AV1_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeAV1.VK_VIDEO_CODEC_OPERATION_DECODE_AV1_BIT_KHR;
import static org.lwjgl.vulkan.KHRVideoDecodeH264.VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeH264.VK_VIDEO_CODEC_OPERATION_DECODE_H264_BIT_KHR;
import static org.lwjgl.vulkan.KHRVideoDecodeH265.VK_KHR_VIDEO_DECODE_H265_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeH265.VK_VIDEO_CODEC_OPERATION_DECODE_H265_BIT_KHR;
import static org.lwjgl.vulkan.KHRVideoDecodeQueue.VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeQueue.VK_QUEUE_VIDEO_DECODE_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.VK_SEMAPHORE_TYPE_TIMELINE;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_FEATURES;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_4_FEATURES;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
import static org.watermedia.WaterMedia.LOGGER;

/**
 * Vulkan implementation of {@link GFXEngine}: it uploads decoded video/image planes into an
 * engine-owned RGBA texture and exposes a ready-to-sample {@code VkImageView}.
 * <p>
 * The engine borrows the consumer's {@link VKContext} (instance, physical device, logical device, a
 * queue and its serialization lock) and never owns that device; the only device it creates is the
 * decoding twin described below. Every object the engine produces is sampleable by the consumer's own
 * pipeline, and the engine destroys only what it created.
 * <p>
 * <b>Zero-copy ingestion.</b> When the device exposes {@code VK_EXT_external_memory_host} and the
 * decoder's plane buffer is aligned to {@code minImportedHostPointerAlignment}, the engine imports
 * that host pointer straight into a {@code VkBuffer} (no CPU copy of the pixels) and issues a
 * {@code vkCmdCopyBufferToImage} from it. Imported {@code {VkDeviceMemory, VkBuffer}} pairs are
 * cached by host pointer because the producer rotates over a small fixed buffer pool. When import is
 * unavailable or a buffer is unaligned, the engine falls back to a persistent host-visible staging
 * buffer per plane per slot, copies the bytes in, and copies that to the image.
 * <p>
 * <b>Conversion.</b> Passthrough formats (BGRA/RGBA) are copied directly into the managed image.
 * Every other format converts with the colorimetry of its layout: YCbCr matrix, sample range, chroma
 * siting and transfer curve. When the device enables {@code samplerYcbcrConversion}, an even-sized
 * 8-bit SDR frame without BT.2020 primaries in NV12 or planar YUV420/422/444 whose chroma siting the
 * format advertises uploads its planes into one multi-planar image, and an immutable
 * {@code VkSamplerYcbcrConversion} sampler built from that colorimetry converts in sampler hardware;
 * a trivial compute pass then samples it once into the managed {@code R8G8B8A8_UNORM} storage image,
 * so {@link #texture()} still returns a ready-to-sample RGBA view. Every other format (GRAY, NV21,
 * planar YUVA, packed YUYV/UYVY, deeper samples, PQ/HLG or BT.2020 primaries) runs one arithmetic
 * compute pass that samples the plane images and writes the managed image with the color math and
 * bit-depth scaling of {@code GLEngine}, HDR mapped to SDR included. The samplers, layouts and
 * pipelines of both paths are built once per device and shared by every engine on it until the last
 * one is released.
 * <p>
 * <b>Triple buffering &amp; synchronization.</b> Three slots are used round-robin; each owns its plane
 * images, managed image+view, command buffer and fence. Before reusing a slot the engine waits the
 * fence of the frame submitted two calls earlier, which honors the {@code upload} buffer-retention
 * contract for the zero-copy path regardless of producer pool size. Submission and conversion run on
 * the producer thread; the engine and the consumer submit to the same queue serialized by
 * {@link VKContext#queueLock()}, so a single same-queue image barrier (to {@code FRAGMENT_SHADER} /
 * {@code SHADER_READ}) makes each frame visible to the consumer, with no cross-thread semaphores.
 * <p>
 * <b>Decoded frames.</b> When the context describes its device ({@link VKContext#deviceInfo()}) and the
 * FFmpeg build decodes with Vulkan Video, the player hands the engine {@code AV_PIX_FMT_VULKAN} frames and
 * nothing crosses host memory. A device created with queues for FFmpeg decodes itself, and the conversion
 * pass samples the decoder's image in place, ordered by the frame's timeline semaphore as
 * {@code hwcontext_vulkan.h} requires. A device that only enables the video and external memory
 * extensions gets a twin: a second device on the same GPU with its feature chain and extensions and
 * queues of its own, which FFmpeg decodes on. The engine creates each pool image again on the consumer's
 * device, bound to the twin's exported memory, with its timeline semaphore imported as well, and moves
 * the image between the two devices through the external queue family. When that device runs libplacebo
 * ({@link VKContext#deviceInfo()}), the player hands over libplacebo's RGBA frames instead, which the
 * engine only scales, sampled or imported the same way.
 * <p>
 * <b>Threading.</b> {@code upload} and {@code format(PixelFormat, int, int, int)} are called from the producer/decode
 * thread; {@link #texture()} from the render thread. {@code texture()} is cheap: it returns the view
 * of the most-recently-submitted slot whose fence has signaled (or the last one it returned), reading
 * published {@code volatile} state and polling fences under the shared queue lock.
 */
public final class VKEngine extends GFXEngine {
    private static final Marker IT = MarkerManager.getMarker(VKEngine.class.getSimpleName());

    // TRIPLE BUFFERING: 1 RECORDING + UP TO 2 IN FLIGHT. ALSO LETS texture() ALWAYS FIND A
    // COMPLETED FRAME WHILE NEWER ONES ARE STILL ON THE GPU.
    private static final int SLOTS = 3;
    private static final int MAX_PLANES = 4;
    private static final int PUSH_BYTES = 96; // { int mode; float bitScale; int width; int height; vec4 colorimetry[4]; vec2 crop; vec2 origin; } (std430)
    private static final int YCBCR_PUSH_BYTES = 24; // { int width; int height; vec2 crop; vec2 origin; }
    private static final long WAIT_TIMEOUT_NS = 5_000_000_000L; // 5s, GUARD AGAINST A WEDGED GPU

    // COMPUTE CONVERSION MODES (MUST MATCH THE MODE_* CONSTANTS OF vulkan/convert.comp)
    private static final int MODE_GRAY = 0;
    private static final int MODE_NV12 = 1;
    private static final int MODE_NV21 = 2;
    private static final int MODE_YUV3 = 3;
    private static final int MODE_YUVA = 4;
    private static final int MODE_YUYV = 5; // PACKED [Y0,U,Y1,V]
    private static final int MODE_UYVY = 6; // PACKED [U,Y0,V,Y1]
    private static final int MODE_RGBA = 7; // DECODED RGBA/BGRA FRAMES, ONLY CROPPED AND SCALED

    // DEVICE FEATURES FFMPEG USES (SEE Kit#features): DECODING NEEDS TIMELINE SEMAPHORES AND synchronization2; LIBPLACEBO REQUIRES hostQueryReset
    // AND FFMPEG'S FILTER SHADERS DECLARE RUNTIME DESCRIPTOR ARRAYS, NON-UNIFORM STORAGE IMAGE INDEXING AND LocalSizeId (maintenance4)
    private static final int FEATURE_TIMELINE = 1;
    private static final int FEATURE_SYNC2 = 1 << 1;
    private static final int FEATURE_HOST_QUERY_RESET = 1 << 2;
    private static final int FEATURE_RUNTIME_ARRAY = 1 << 3;
    private static final int FEATURE_STORAGE_NON_UNIFORM = 1 << 4;
    private static final int FEATURE_MAINTENANCE_4 = 1 << 5;
    private static final int DECODE_FEATURES = FEATURE_TIMELINE | FEATURE_SYNC2;
    private static final int FILTER_FEATURES = FEATURE_HOST_QUERY_RESET | FEATURE_RUNTIME_ARRAY | FEATURE_STORAGE_NON_UNIFORM | FEATURE_MAINTENANCE_4;

    // THE COMPUTE SHADERS BUNDLED UNDER assets/watermedia/shaders: ONE CONVERSION FOR EVERY NON-PASSTHROUGH FORMAT, MIRRORING
    // GLEngine'S FRAGMENTS THROUGH colorimetry.glsl, AND THE SINGLE-SAMPLE PASS BEHIND THE HARDWARE YCBCR SAMPLER
    private static final String CONVERT_SHADER = "vulkan/convert.comp";
    private static final String YCBCR_SHADER = "vulkan/ycbcr.comp";

    // PROCESS-WIDE CACHES OF THE COMPILED SHADERS (SPIR-V IS DEVICE-INDEPENDENT), FILLED LAZILY
    // UNDER THE CLASS MONITOR ON THE FIRST CONVERSION FRAME. SEE compileSpirv().
    private static volatile byte[] convertSpirv;
    private static volatile byte[] ycbcrSpirv;
    // SHADER COMPILATION FAILED IN THIS PROCESS (NO shaderc, BAD GLSL): EVERY CONVERSION LAYOUT IS DECLINED FROM THEN ON
    private static volatile boolean convertBroken;
    // ONE CONVERSION KIT PER DEVICE, GUARDED BY ITS OWN MONITOR (SEE Kit). KEYED BY VkDevice IDENTITY, NOT ITS HANDLE: A
    // RECREATED DEVICE MAY REUSE THE ADDRESS OF ONE WHOSE KIT A WEDGED ENGINE STILL HOLDS
    private static final Map<VkDevice, Kit> KITS = new IdentityHashMap<>();
    // THE EXTENSION NAME ARRAYS AND TWIN FREE CALLBACKS HANDED TO FFMPEG'S DEVICE CONTEXTS, KEPT REACHABLE SO JAVACPP NEVER FREES THEM
    private static final List<Pointer> HW_NATIVES = Collections.synchronizedList(new ArrayList<>());

    // CONTEXT (BORROWED, NEVER DESTROYED)
    private final VKContext ctx;
    private final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties memProps;
    private final int minAlign; // 0 WHEN HOST IMPORT IS UNAVAILABLE
    // THE DEVICE'S SHARED SAMPLER, LAYOUTS AND PIPELINES; LEFT ONLY AFTER EVERY OBJECT OF THIS ENGINE IS GONE
    private final Kit kit;

    // ENGINE-OWNED OBJECTS (LIVE FOR THE WHOLE ENGINE)
    private long cmdPool;
    private long descPool;

    private final Slot[] slots = new Slot[SLOTS];

    // IMPORT CACHE: HOST POINTER -> IMPORTED {VkDeviceMemory, VkBuffer}. GUARDED BY THE ENGINE MONITOR.
    private final Map<Long, Imported> importCache = new HashMap<>();

    // FORMAT-DERIVED STATE (BUILT IN apply())
    private boolean convert;     // TRUE WHEN A COMPUTE PASS PRODUCES THE MANAGED IMAGE
    private int mode;            // COMPUTE MODE WHEN convert
    private int outFormat;       // MANAGED IMAGE FORMAT
    private int outUsage;        // MANAGED IMAGE USAGE
    private float bitScale = 1.0f;
    private float[] colorimetry; // Colorimetry.constants OF THE LAYOUT, PUSHED AFTER THE FOUR SCALARS
    private int planeCount;      // SOURCE PLANES PER FRAME; PASSTHROUGH HAS ONE, COPIED STRAIGHT INTO THE MANAGED IMAGE
    private final PlaneSpec[] planeSpecs = new PlaneSpec[MAX_PLANES];
    private int formatGen;       // BUMPED ON EVERY FORMAT CHANGE; SLOTS REBUILD WHEN STALE

    // SAMPLER-YCBCR FAST PATH (FORMAT-SPECIFIC: TAKEN FROM THE KIT ON FIRST USE AND
    // LEFT ON EVERY FORMAT CHANGE AND IN destroyResources)
    private int ycbcrFmt;        // MULTIPLANAR VkFormat, 0 = COMPUTE/PASSTHROUGH PATH
    private int ycbcrX;          // CHOSEN HORIZONTAL VkChromaLocation
    private int ycbcrY;          // CHOSEN VERTICAL VkChromaLocation
    private int ycbcrFilter;     // CHOSEN VkFilter FOR min/mag/chroma (ALWAYS THE SAME THREE)
    private Ycbcr ycbcr;         // THE KIT'S CONVERSION, SAMPLER AND PIPELINE FOR THIS LAYOUT
    private long ycbcrPool;      // SLOTS SETS FOR THE YCBCR LAYOUT

    // DECODED FRAMES: ONE PICTURE PER DECODER IMAGE BY HANDLE, THE POOL THAT OWNS THEM (REFERENCED WHILE THEY LIVE), THE TWIN IT LIVES ON
    // (NULL ON THIS DEVICE), A POOL REFUSED FOR GOOD AND THE COLORIMETRY CONSTANTS OF ITS IMAGE EXTENT
    private final Map<Long, Picture> pictures = new HashMap<>();
    private AVBufferRef framesRef;
    private long framesCtx;
    private Twin framesTwin;
    private long refusedPool;
    private float[] frameColorimetry;
    private int frameW, frameH;
    private volatile long decodedFrames; // DIAGNOSTICS, READ FROM OTHER THREADS
    private volatile long importedFrames; // DIAGNOSTICS: THE DECODED FRAMES THAT CAME FROM THE TWIN
    private volatile boolean frameWarned;
    private volatile boolean importWarned;
    // THE ENGINE'S OBJECTS ON THE KIT'S TWIN, BUILT ON THE FIRST IMPORT (SEE twinObjects), AND WHETHER A FAILED STEP ENDED ITS USE
    private VkDevice twinDevice;
    private long twinPool;
    private boolean twinBroken;

    // SUBMISSION BOOKKEEPING (PRODUCER-THREAD ONLY)
    private long head;           // NUMBER OF FRAMES SUBMITTED
    private long submitSeq;      // MONOTONIC SEQUENCE STAMPED ONTO EACH SLOT ON SUBMIT

    // PUBLISHED TO texture() (RENDER THREAD). retiring HOLDS THE IMAGES OF A PREVIOUS LAYOUT WHOSE VIEW STAYS
    // EXPOSED UNTIL THE FIRST FRAME OF THE NEW ONE LANDS, GUARDED BY THE QUEUE LOCK
    private volatile long lastView;
    private volatile long lastSeq;
    private final List<Runnable> retiring = new ArrayList<>();
    // A DRIVER FAILURE PARKS THE ENGINE: UPLOADS ARE DROPPED UNTIL THE NEXT LAYOUT
    private volatile boolean failed;
    // ANOMALY LATCHES: EACH REPEATABLE UPLOAD ANOMALY IS LOGGED ONCE PER ENGINE
    private volatile boolean failWarned;
    private volatile boolean shortWarned;
    private volatile boolean strideWarned;

    // THE CONTEXT AND EVERY VULKAN OBJECT IT EXPOSES MUST OUTLIVE THIS ENGINE, WHICH ONLY CREATES ITS OWN COMMAND POOL,
    // DESCRIPTORS AND PER-SLOT IMAGES ON IT AND SHARES THE DEVICE'S KIT WITH EVERY OTHER ENGINE THERE
    VKEngine(final VKContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("VKContext must be non-null");
        this.ctx = ctx;
        this.device = ctx.vkDevice();
        this.memProps = ctx.memoryProperties();
        this.minAlign = ctx.hostImportSupported() ? (int) ctx.minImportedHostPointerAlignment() : 0;
        this.kit = Kit.acquire(ctx);

        // BUILD THE ENGINE OBJECTS (LIVE FOR THE WHOLE ENGINE LIFETIME).
        for (int i = 0; i < SLOTS; i++) this.slots[i] = new Slot();
        try (final MemoryStack stack = stackPush()) {
            // COMMAND POOL + PRIMARY COMMAND BUFFERS (RESETTABLE INDIVIDUALLY).
            final VkCommandPoolCreateInfo cpci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(ctx.queueFamily());
            final LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(this.device, cpci, null, pPool), "create command pool");
            this.cmdPool = pPool.get(0);

            final VkCommandBufferAllocateInfo cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(this.cmdPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(SLOTS);
            final PointerBuffer pCmd = stack.mallocPointer(SLOTS);
            check(vkAllocateCommandBuffers(this.device, cbai, pCmd), "alloc command buffers");

            final VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack).sType$Default(); // UNSIGNALED
            for (int i = 0; i < SLOTS; i++) {
                this.slots[i].cmd = new VkCommandBuffer(pCmd.get(i), this.device);
                final LongBuffer pf = stack.mallocLong(1);
                check(vkCreateFence(this.device, fci, null, pf), "create fence");
                this.slots[i].fence = pf.get(0);
            }

            // DESCRIPTOR POOL + ONE SET PER SLOT ON THE KIT'S LAYOUT.
            final VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_PLANES * SLOTS);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(SLOTS);
            final VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(SLOTS).pPoolSizes(sizes);
            final LongBuffer pdp = stack.mallocLong(1);
            check(vkCreateDescriptorPool(this.device, dpci, null, pdp), "create descriptor pool");
            this.descPool = pdp.get(0);

            final LongBuffer layouts = stack.mallocLong(SLOTS);
            for (int i = 0; i < SLOTS; i++) layouts.put(i, this.kit.descLayout);
            final VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(this.descPool).pSetLayouts(layouts);
            final LongBuffer pSets = stack.mallocLong(SLOTS);
            check(vkAllocateDescriptorSets(this.device, dsai, pSets), "alloc descriptor sets");
            for (int i = 0; i < SLOTS; i++) this.slots[i].descSet = pSets.get(i);
        } catch (final RuntimeException | Error e) {
            this.destroyResources(); // NOTHING CREATED SO FAR OUTLIVES A FAILED CONSTRUCTION
            throw e;
        }
        // THE KIT BUILDS THE COMPUTE PIPELINE ON THE DEVICE'S FIRST CONVERSION UPLOAD: PASSTHROUGH-ONLY ENGINES
        // (E.G. BGRA/RGBA IMAGE THUMBNAILS) NEVER COMPILE, AND CREATING MANY ENGINES NEVER REBUILDS IT
        LOGGER.debug(IT, "VKEngine initialized (hostImport={}, minAlign={})", ctx.hostImportSupported(), this.minAlign);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Returns the RGBA {@code VkImageView} of the newest frame whose fence signaled, or 0 before the first one; read it fresh on every draw. */
    @Override
    public long texture() {
        // ALWAYS A READY-TO-SAMPLE RGBA VIEW, NEVER A RAW PLANE. IT ROTATES PER FRAME, AND A LAYOUT CHANGE KEEPS THE PREVIOUS VIEW
        // UNTIL THE NEW LAYOUT'S FIRST FRAME COMPLETES, SO A CACHED HANDLE MAY POINT AT A STALE OR DESTROYED IMAGE
        if (this.released) return 0L;
        // THE QUEUE LOCK SERIALIZES THIS FENCE POLL WITH vkResetFences/vkQueueSubmit AND KEEPS THE lastSeq/lastView
        // READ-MODIFY-WRITE ATOMIC WITH apply(), WHICH ZEROES THE SLOT VIEWS UNDER THE SAME LOCK
        synchronized (this.ctx.queueLock()) {
            long bestSeq = this.lastSeq;
            long bestView = this.lastView;
            for (final Slot s: this.slots) {
                final long seq = s.seq;
                if (seq > bestSeq && s.submitted && vkGetFenceStatus(this.device, s.fence) == VK_SUCCESS) {
                    bestSeq = seq;
                    bestView = s.view;
                }
            }
            // THE FIRST FRAME OF A NEW LAYOUT REPLACES THE VIEW KEPT ALIVE ACROSS apply(): ITS IMAGES RETIRE
            // NOW, ONCE THE CONSUMER'S IN-FLIGHT FRAMES (WHICH MAY STILL SAMPLE THEM) COMPLETE
            if (bestView != this.lastView && !this.retiring.isEmpty()) {
                for (final Runnable old: this.retiring) this.ctx.retire(old);
                this.retiring.clear();
            }
            this.lastSeq = bestSeq;
            this.lastView = bestView;
            return bestView;
        }
    }

    @Override
    public boolean supports(final PixelFormat format, final int bits) {
        if (!super.supports(format, bits)) return false; // THE BASE DECLINES BCn: ITS SAMPLING IS NOT WIRED YET
        // WITHOUT A WORKING SHADER COMPILER ONLY THE PASSTHROUGH LAYOUTS REMAIN; THE PRODUCER CONVERTS THE REST TO BGRA
        if (convertBroken && format != PixelFormat.BGRA && format != PixelFormat.RGBA) return false;
        return switch (format) {
            // RGB (3-BYTE, NO ALPHA) IS DECLINED: 3-CHANNEL OPTIMAL-TILING SUPPORT IS OPTIONAL IN VULKAN,
            // SO THE PRODUCER PRE-CONVERTS IT TO BGRA UPSTREAM
            case RGB -> false;
            // PASSTHROUGH COPIES FOUR BYTES PER PIXEL AND PACKED 4:2:2 IS FETCHED THROUGH AN RGBA8 PLANE
            case BGRA, RGBA, YUYV, UYVY -> bits == 8;
            // PLANAR AND GRAY SAMPLE R (AND RG FOR NV) PLANES THROUGH THE LINEAR SAMPLER: 16-BIT AND FLOAT
            // SAMPLING ARE OPTIONAL IN THE SPEC, SO THE DEVICE MUST ADVERTISE THEM FOR THIS DEPTH
            default -> this.sampleable(redFormat(bits))
                    && (format != PixelFormat.NV12 && format != PixelFormat.NV21 || this.sampleable(redGreenFormat(bits)));
        };
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Override
    int alignment() {
        return this.minAlign;
    }

    // RESETS PER-FORMAT GPU STATE (SLOT IMAGES, IMPORT CACHE; POOL, PIPELINE, SAMPLER AND FENCES STAY) AFTER DRAINING OUR
    // WORK; THE LAST PUBLISHED VIEW STAYS EXPOSED UNTIL THE FIRST FRAME OF THE NEW LAYOUT COMPLETES (SEE texture)
    @Override
    synchronized boolean apply(final PixelFormat format, final int width, final int height, final int bits, final Colorimetry color) {
        // DRAIN ENGINE WORK SO PER-FORMAT RESOURCES CAN BE DESTROYED SAFELY; A WEDGED GPU REFUSES THE LAYOUT INSTEAD
        if (!this.waitAllIdle()) return false;
        for (final Slot s: this.slots) {
            // THE DECODED FRAME A SLOT HELD IS FULLY READ, SO IT RETURNS TO THE DECODER'S POOL
            if (s.held != null) av_frame_unref(s.held);
            this.retireOutput(s);
            this.destroySlotFormatRes(s);
            s.gen = -1L;
        }
        this.destroyImportCache();
        // THE YCBCR SETS ARE FORMAT-SPECIFIC AND NEVER REFERENCED BY CONSUMER FRAMES (THEY ONLY SAMPLE THE RGBA VIEW), SO
        // AFTER THE DRAIN ABOVE THEY DIE NOW AND THE KIT'S CONVERSION IS LEFT; THE NEW FORMAT TAKES ITS OWN ON FIRST USE.
        // THE VIEWS OVER DECODED FRAMES FOLLOW THE PLANE FORMATS, SO THEY DIE WITH THE LAYOUT TOO; THE PICTURES AND THEIR IMPORTS STAY
        this.destroyYcbcrRes();
        this.destroyViews();

        this.publish(format, width, height, bits, color);

        // BUILD THE FORMAT TABLE IN ONE PASS: PER-PLANE SPECS, OUTPUT FORMAT/USAGE, COMPUTE MODE
        // AND THE OPTIONAL SAMPLER-YCBCR ROUTE, ALL DERIVED FROM THE NEW FORMAT.
        this.bitScale = bitScale(bits);
        this.colorimetry = color.constants(format, width, height, bits);
        final int rF = redFormat(bits);

        this.convert = true;
        this.planeCount = format.planes();
        this.ycbcrFmt = 0;
        // UPLOADED BGRA/RGBA IS COPIED INTO THE MANAGED IMAGE; DECODED ONES (SEE upload(AVFrame)) PASS THROUGH THE COMPUTE PASS IN MODE_RGBA
        switch (format) {
            case BGRA -> { this.convert = false; this.mode = MODE_RGBA; this.outFormat = VK_FORMAT_B8G8R8A8_UNORM; this.plane(0, this.outFormat); }
            case RGBA -> { this.convert = false; this.mode = MODE_RGBA; this.outFormat = VK_FORMAT_R8G8B8A8_UNORM; this.plane(0, this.outFormat); }
            case GRAY -> {
                // GRAY IS NOT A YCBCR FORMAT AT ALL: THE SINGLE-PLANE COMPUTE BROADCAST STAYS.
                this.mode = MODE_GRAY;
                this.plane(0, rF);
            }
            case NV12, NV21 -> {
                this.mode = format == PixelFormat.NV21 ? MODE_NV21 : MODE_NV12;
                this.plane(0, rF);
                this.plane(1, redGreenFormat(bits));
                // ONLY 8-BIT EVEN-SIZED NV12 TAKES THE YCBCR PATH: VULKAN HAS NO VU-ORDER PLANE (NV21),
                // 10/12-BIT PLANAR IS LSB-ALIGNED VS VULKAN'S MSB-ALIGNED G10X6/G12X4, AND 420
                // MULTIPLANAR NEEDS EVEN WxH (VUID-VkImageCreateInfo-format-04712/04713).
                if (format == PixelFormat.NV12 && bits == 8 && (width & 1) == 0 && (height & 1) == 0)
                    this.tryYcbcr(VK_FORMAT_G8_B8R8_2PLANE_420_UNORM);
            }
            case YUV420P, YUV422P, YUV444P -> {
                this.mode = MODE_YUV3;
                this.plane(0, rF);
                this.plane(1, rF);
                this.plane(2, rF);
                // 8-BIT ONLY (SEE THE NV12 NOTE ON 10/12-BIT ALIGNMENT). SUBSAMPLED MULTIPLANAR
                // IMAGES REQUIRE EVEN EXTENTS ALONG THE SUBSAMPLED AXES: 420 NEEDS EVEN WxH,
                // 422 NEEDS EVEN W, 444 HAS NO RESTRICTION.
                if (bits == 8) {
                    switch (format) {
                        case YUV420P -> { if ((width & 1) == 0 && (height & 1) == 0) this.tryYcbcr(VK_FORMAT_G8_B8_R8_3PLANE_420_UNORM); }
                        case YUV422P -> { if ((width & 1) == 0) this.tryYcbcr(VK_FORMAT_G8_B8_R8_3PLANE_422_UNORM); }
                        default -> this.tryYcbcr(VK_FORMAT_G8_B8_R8_3PLANE_444_UNORM);
                    }
                }
            }
            case YUVA420P, YUVA422P, YUVA444P -> {
                // YUVA STAYS ON COMPUTE: NO VULKAN MULTIPLANAR FORMAT CARRIES AN ALPHA PLANE.
                this.mode = MODE_YUVA;
                this.plane(0, rF);
                this.plane(1, rF);
                this.plane(2, rF);
                this.plane(3, rF);
            }
            // PACKED YUYV/UYVY STAYS ON COMPUTE: THE EXACT PIXEL-PAIR texelFetch DECODE IS ILLEGAL
            // THROUGH A YCBCR SAMPLER (NO OpImageFetch), AND SINGLE-PLANE 422 PACKED FORMATS
            // (G8B8G8R8_422) HAVE SPOTTY OPTIMAL-TILING SUPPORT ACROSS DRIVERS.
            case YUYV, UYVY -> {
                this.mode = format == PixelFormat.UYVY ? MODE_UYVY : MODE_YUYV;
                this.plane(0, VK_FORMAT_R8G8B8A8_UNORM); // ONE RGBA8 TEXEL PER PIXEL PAIR
            }
            // RGB AND BCn NEVER PASS supports(), SO THEY CANNOT REACH THIS TABLE
            default -> throw new IllegalStateException("VKEngine: " + format + " has no upload path");
        }
        if (this.convert) {
            this.outFormat = VK_FORMAT_R8G8B8A8_UNORM;
            this.outUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        } else {
            this.outUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        }

        this.formatGen++;
        this.head = 0L;
        this.failed = false; // A NEW LAYOUT IS A FRESH ATTEMPT AFTER A DRIVER FAILURE
        if (this.ycbcrFmt != 0)
            LOGGER.debug(IT, "Format set: {} {}x{} ({} planes, {}bpc) -> YCbCr sampler path ({} {} range, chroma x {} y {}, {}; vkFormat={}, filter={})",
                    format, width, height, this.planeCount, bits, color.matrix(), color.full() ? "full" : "limited",
                    this.ycbcrX == VK_CHROMA_LOCATION_MIDPOINT ? "midpoint" : "cosited", this.ycbcrY == VK_CHROMA_LOCATION_MIDPOINT ? "midpoint" : "cosited",
                    color.transfer(), this.ycbcrFmt, this.ycbcrFilter == VK_FILTER_LINEAR ? "linear" : "nearest");
        else if (this.convert)
            LOGGER.debug(IT, "Format set: {} {}x{} ({} planes, {}bpc) -> compute path ({} {} range, chroma x {} y {}, {}{})",
                    format, width, height, this.planeCount, bits, color.matrix(), color.full() ? "full" : "limited",
                    color.cositedX() ? "cosited" : "midpoint", color.cositedY() ? "cosited" : "midpoint",
                    color.transfer(), color.wide() ? ", BT.2020 primaries" : "");
        else
            LOGGER.debug(IT, "Format set: {} {}x{} ({} planes, {}bpc) -> passthrough path", format, width, height, this.planeCount, bits);
        return true;
    }

    @Override
    synchronized void release(final ByteBuffer buffer) {
        if (this.released || buffer == null || !buffer.isDirect()) return;
        // THE CACHE IS KEYED BY THE BUFFER'S BASE ADDRESS (POSITION-0 IMPORTS ONLY).
        final Imported imp = this.importCache.remove(memAddress(buffer) - buffer.position());
        if (imp == null) return;
        // AN IN-FLIGHT UPLOAD COMMAND BUFFER MAY STILL READ THE IMPORT: DRAIN THE ENGINE'S OWN WORK FIRST, THEN
        // DESTROY IT WHILE THE HOST MEMORY IS STILL VALID; A WEDGED GPU LEAKS THE IMPORT RATHER THAN FREEING IT IN USE
        if (!this.waitAllIdle()) return;
        vkDestroyBuffer(this.device, imp.buffer(), null);
        vkFreeMemory(this.device, imp.memory(), null);
    }

    // A NEW REFERENCE TO THE FFMPEG DEVICE CONTEXT PLAYERS DECODE ON (SEE Kit#hwDevice), OR NULL WHEN THE DEVICE CANNOT DECODE codecId
    AVBufferRef hwDevice(final int codecId) {
        return this.released ? null : this.kit.hwDevice(this.ctx, codecId);
    }

    // WHETHER THE DEVICE CONTEXT hwDevice HANDS OUT RUNS LIBPLACEBO, SO PLAYERS TURN THEIR DECODED FRAMES INTO RGBA THERE (FALSE UNTIL IT IS BUILT)
    boolean placebo() {
        return !this.released && this.kit.placebo;
    }

    // HOST IMPORTS DIE NOW: THE PRODUCER FREES THEIR MEMORY RIGHT AFTER release() AND AN IMPORT OUTLIVING ITS HOST POINTER
    // CRASHES THE DRIVER; THE REST IS RETIRED BY THE BACKEND ONCE ITS IN-FLIGHT FRAMES (WHICH MAY SAMPLE THE VIEW) COMPLETE
    @Override
    synchronized void destroy() {
        if (!this.waitAllIdle()) {
            // A WEDGED GPU MAY STILL READ EVERY OBJECT: LEAKING THEM BEATS DESTROYING MEMORY IN USE
            LOGGER.error(IT, "VKEngine released while the GPU is wedged; its Vulkan objects are leaked");
            return;
        }
        // THE DECODED FRAMES ARE FULLY READ: THEY RETURN TO THEIR POOL AND THE PICTURES OF ITS IMAGES DIE WITH THE REFERENCE
        for (final Slot s: this.slots) {
            if (s.held != null) av_frame_free(s.held);
            s.held = null;
        }
        this.destroyPictures();
        this.destroyImportCache();
        final List<Runnable> old;
        synchronized (this.ctx.queueLock()) {
            old = List.copyOf(this.retiring);
            this.retiring.clear();
        }
        this.ctx.retire(() -> {
            for (final Runnable r: old) r.run();
            this.destroyResources();
            LOGGER.debug(IT, "VKEngine released");
        });
    }

    // NEVER THROWS: ANY DRIVER FAILURE PARKS THE ENGINE (SEE fail) AND THE FRAME IS DROPPED
    @Override
    synchronized void push(final ByteBuffer[] bufs, final int[] strides) {
        if (this.failed || this.width <= 0 || this.height <= 0) return;
        final int sources = this.planeCount;
        if (bufs.length != sources) return; // STALE FORMAT / WRONG PLANE COUNT

        // VALIDATE BEFORE TOUCHING A FENCE. THE LAST ROW CARRIES NO PADDING, SO THE COPY READS EXACTLY needed BYTES;
        // bufferRowLength COUNTS TEXELS, SO A STRIDE SHORTER THAN A ROW OR NOT A WHOLE NUMBER OF TEXELS CANNOT BE EXPRESSED
        final long[] needed = new long[sources];
        final int[] rowLen = new int[sources];
        for (int i = 0; i < sources; i++) {
            final PlaneSpec spec = this.planeSpecs[i];
            needed[i] = minBytes(strides[i], (long) spec.w * spec.texelBytes, spec.h);
            if (needed[i] < 0L || strides[i] % spec.texelBytes != 0) {
                if (!this.strideWarned) LOGGER.warn(IT, "Plane {} stride {} is shorter than its {}-texel row or not a multiple of its {}-byte texels; frame dropped",
                        i, strides[i], spec.w, spec.texelBytes);
                this.strideWarned = true;
            } else if (bufs[i].remaining() < needed[i]) {
                if (!this.shortWarned) LOGGER.warn(IT, "Plane {} buffer too small: {} < {}", i, bufs[i].remaining(), needed[i]);
                this.shortWarned = true;
            } else {
                rowLen[i] = strides[i] / spec.texelBytes; // A TIGHT STRIDE (0) IS ALSO VULKAN'S TIGHT ROW LENGTH
                continue;
            }
            // A DROPPED UPLOAD STILL COUNTS FOR RETENTION: THE LAST SUBMITTED FRAME FINISHES READING THE PRODUCER'S
            // BUFFERS BEFORE THIS CALL RETURNS, SO THE NEXT CALL'S head-2 WAIT CANNOT LEAVE IT IN FLIGHT
            final Slot last = this.slots[(int) ((this.head + SLOTS - 1) % SLOTS)];
            if (this.head >= 1 && last.submitted) this.await(this.device, last.fence, "Retention wait");
            return;
        }

        final int cur = (int) (this.head % SLOTS);
        final Slot slot = this.slots[cur];

        try (final MemoryStack stack = stackPush()) {
            // RETENTION: BLOCK UNTIL FRAME head-2 (SLOT (head + 1) % SLOTS) FINISHED READING THE PRODUCER'S BUFFERS, SO FRAME N IS
            // COMPLETE BY upload(N+2): THE GFXEngine "FINISH READING BEFORE THE SECOND SUBSEQUENT UPLOAD" CONTRACT, WHICH MATTERS
            // FOR THE ZERO-COPY PATH WHERE THE GPU READS THE DECODER'S MEMORY DIRECTLY
            final Slot twoAgo = this.slots[(int) ((this.head + 1) % SLOTS)];
            if (this.head >= 2 && twoAgo.submitted && !this.await(this.device, twoAgo.fence, "Retention wait")) return;
            // THE SLOT WE REUSE LAST HELD FRAME head-SLOTS; ITS WORK MUST BE DONE BEFORE WE RESET
            // ITS COMMAND BUFFER AND OVERWRITE ITS IMAGES.
            if (slot.submitted && !this.await(this.device, slot.fence, "Slot wait")) return;
            synchronized (this.ctx.queueLock()) {
                check(vkResetFences(this.device, slot.fence), "reset fence");
            }
            slot.submitted = false; // texture() SKIPS THIS SLOT WHILE WE RE-RECORD IT

            // LAZILY (RE)CREATE PLANE/OUTPUT IMAGES FOR THE CURRENT FORMAT (NO-OP IN STEADY STATE).
            this.ensureSlot(slot, false);

            // RESOLVE EACH SOURCE TO A VkBuffer BEFORE RECORDING. THE STAGING FALLBACK COPIES HOST
            // BYTES NOW (SYNCHRONOUSLY), SO THE PRODUCER BUFFER IS FULLY READ ON RETURN; THE IMPORT
            // PATH DEFERS THE READ TO THE GPU (COVERED BY THE FENCE WAITS ABOVE).
            final long[] src = new long[sources];
            for (int i = 0; i < sources; i++) {
                // IMPORT ONLY POSITION-0 BUFFERS WHOSE CAPACITY IS A MULTIPLE OF minAlign:
                // memAddress() INCLUDES position AND THE IMPORTED SIZE IS ROUNDED UP TO minAlign,
                // SO ANYTHING ELSE COULD IMPORT PAST THE END OF THE UNDERLYING ALLOCATION.
                final long ptr = memAddress(bufs[i]);
                long buffer = 0L;
                if (this.minAlign > 0 && bufs[i].position() == 0 && (ptr % this.minAlign) == 0L
                        && (bufs[i].capacity() % this.minAlign) == 0)
                    buffer = this.importBuffer(ptr, bufs[i].capacity());
                if (buffer == 0L) buffer = this.stageInto(slot, i, bufs[i], needed[i]);
                src[i] = buffer;
            }
            if (this.failed) return; // A WEDGED DRAIN WHILE REPLACING AN IMPORT PARKED THE ENGINE MID-LOOP

            // RECORD
            check(vkResetCommandBuffer(slot.cmd, 0), "reset command buffer");
            final VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(slot.cmd, begin), "begin command buffer");

            if (this.ycbcrFmt != 0) {
                // SAMPLER-YCBCR PATH: EVERY PLANE LIVES IN ONE MULTIPLANAR IMAGE. WHOLE-IMAGE LAYOUT
                // BARRIERS MUST USE THE COLOR ASPECT: IT IS THE ONLY VALID ASPECT (AND COVERS ALL
                // PLANES) FOR A NON-DISJOINT MULTIPLANAR IMAGE.
                this.barrier(stack, slot.cmd, slot.planeImg[0],
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                for (int i = 0; i < this.planeCount; i++) {
                    // EACH SOURCE PLANE TARGETS ITS OWN PLANE ASPECT; EXTENT AND ROW LENGTH ARE IN
                    // PLANE TEXELS (NV12 CHROMA TEXEL = 2 BYTES, SO rowLen = stride / 2).
                    final PlaneSpec spec = this.planeSpecs[i];
                    this.copyToImage(stack, slot.cmd, src[i], slot.planeImg[0], spec.w, spec.h,
                            rowLen[i], VK_IMAGE_ASPECT_PLANE_0_BIT << i);
                }
                this.barrier(stack, slot.cmd, slot.planeImg[0],
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            } else if (this.convert) {
                for (int i = 0; i < this.planeCount; i++) {
                    final PlaneSpec spec = this.planeSpecs[i];
                    this.barrier(stack, slot.cmd, slot.planeImg[i],
                            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                    this.copyToImage(stack, slot.cmd, src[i], slot.planeImg[i], spec.w, spec.h,
                            rowLen[i], VK_IMAGE_ASPECT_COLOR_BIT);
                    this.barrier(stack, slot.cmd, slot.planeImg[i],
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
                }
            } else {
                // srcStage FRAGMENT_SHADER FOR THE SAME WRITE-AFTER-READ REASON AS THE CONVERSION (SEE dispatch)
                this.barrier(stack, slot.cmd, slot.outImg,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                this.copyToImage(stack, slot.cmd, src[0], slot.outImg, this.width, this.height,
                        rowLen[0], VK_IMAGE_ASPECT_COLOR_BIT);
                this.barrier(stack, slot.cmd, slot.outImg,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            }
            // THE PLANES COVER THEIR IMAGES EXACTLY, SO NOTHING IS CROPPED AND THE DEPTH SCALING IS THE LAYOUT'S
            if (this.convert) this.dispatch(stack, slot, 0.0f, 0.0f, 1.0f, 1.0f, this.bitScale, this.colorimetry);
            check(vkEndCommandBuffer(slot.cmd), "end command buffer");

            // SUBMIT UNDER THE SHARED QUEUE LOCK SIGNALING THIS SLOT'S FENCE.
            final VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(slot.cmd));
            synchronized (this.ctx.queueLock()) {
                final int r = vkQueueSubmit(this.ctx.queue(), si, slot.fence);
                if (r != VK_SUCCESS) {
                    this.fail("vkQueueSubmit", r);
                    return;
                }
            }
            // PUBLISH: seq FIRST, THEN submitted, SO texture() READING submitted=true SEES A VALID seq.
            slot.seq = ++this.submitSeq;
            slot.submitted = true;
            this.head++;
        } catch (final RuntimeException | LinkageError e) {
            this.fail(e);
        }
    }

    // WHETHER THE ENGINE SAMPLES A FRAME DECODED FOR IT (SEE hwDevice): ALWAYS ON ITS OWN DEVICE, AND ON THE KIT'S TWIN ONCE THE FRAME'S
    // IMAGE IMPORTS HERE, BUT NEVER WITHOUT ITS SHADERS. WHEN FALSE THE PLAYER BRINGS THE FRAME DOWN TO RAM, AND A REFUSED POOL STAYS REFUSED
    synchronized boolean samples(final AVFrame frame) {
        if (this.released || convertBroken || frame == null || frame.format() != AV_PIX_FMT_VULKAN || FFTool.isNull(frame.hw_frames_ctx())) return false;
        final AVVkFrame f = new AVVkFrame(frame.data(0));
        try (final MemoryStack stack = stackPush()) {
            return this.picture(stack, frame, new AVHWFramesContext(frame.hw_frames_ctx().data()), f, images(f)) != null;
        } catch (final RuntimeException | LinkageError e) {
            this.fail(e);
            return false;
        }
    }

    // SAMPLED UNDER hwcontext_vulkan.h'S PROTOCOL (WAIT sem_value, SIGNAL + 1, NEW LAYOUT UNDER lock_frame); A TWIN'S IMAGE CROSSES THE
    // EXTERNAL FAMILY: THE TWIN RELEASES IT (+1), THIS DEVICE SAMPLES AND RELEASES IT (+2), THE TWIN ACQUIRES IT BACK (+3)
    synchronized void upload(final AVFrame frame) {
        if (this.released || this.failed || this.width <= 0 || this.height <= 0) return;
        if (frame == null || frame.format() != AV_PIX_FMT_VULKAN || FFTool.isNull(frame.hw_frames_ctx())) return;
        final AVHWFramesContext fc = new AVHWFramesContext(frame.hw_frames_ctx().data());
        final AVVulkanFramesContext vfc = new AVVulkanFramesContext(fc.hwctx());
        final AVVkFrame f = new AVVkFrame(frame.data(0));
        final int images = images(f);
        final FFTool.Pixels pixels = FFTool.pixels(fc.sw_format());
        if (pixels == null || pixels.format() != this.format || pixels.bits() != this.bits
                || images == 0 || images != 1 && images != this.planeCount) {
            if (!this.frameWarned) LOGGER.warn(IT, "Decoded frames of layout {} ({} images) do not match the engine's {} {}-bit layout; frames are dropped",
                    FFTool.string(av_get_pix_fmt_name(fc.sw_format()), fc.sw_format()), images, this.format, this.bits);
            this.frameWarned = true;
            return;
        }

        final Slot slot = this.slots[(int) (this.head % SLOTS)];
        boolean locked = false;
        long crossed = 0L; // THE TIMELINE VALUE A TWIN'S IMAGE REACHED OUTSIDE THE TWIN WHILE ITS STEPS ARE UNFINISHED
        try (final MemoryStack stack = stackPush()) {
            // THE FRAME THIS SLOT HELD IS FULLY READ ONCE ITS FENCES SIGNAL; ONLY THEN DOES IT RETURN TO THE DECODER'S POOL
            if (slot.submitted && !this.await(this.device, slot.fence, "Slot wait")) return;
            if (slot.twinSubmitted) {
                if (!this.await(this.twinDevice, slot.twinFence, "Twin wait")) return;
                check(vkResetFences(this.twinDevice, slot.twinFence), "reset twin fence");
                slot.twinSubmitted = false;
            }
            synchronized (this.ctx.queueLock()) {
                check(vkResetFences(this.device, slot.fence), "reset fence");
            }
            slot.submitted = false;
            if (slot.held != null) av_frame_unref(slot.held);

            final Picture picture = this.picture(stack, frame, fc, f, images);
            if (picture == null) return; // A POOL THIS DEVICE CANNOT IMPORT: THE PLAYER ASKED samples() AND DOWNLOADS ITS FRAMES
            final Twin twin = this.framesTwin;
            // THE SAMPLER ROUTE NEEDS THE DECODER'S OWN MULTIPLANAR IMAGE IN THE CONVERSION'S FORMAT; ANYTHING ELSE RUNS ON COMPUTE
            if (this.ycbcrFmt != 0 && (images != 1 || vfc.format(0) != this.ycbcrFmt)) {
                LOGGER.debug(IT, "Decoded frames arrive as {} image(s) of vkFormat {}: the YCbCr sampler route gives way to compute", images, vfc.format(0));
                if (!this.waitAllIdle()) return;
                this.destroyViews();
                this.destroyYcbcrRes();
                this.ycbcrFmt = 0;
                this.formatGen++;
            }
            // THE CONSTANTS SITE THE CHROMA ON THE DECODER'S IMAGE EXTENT (THE CODED SIZE); THE CROP MAPS THE OUTPUT ONTO ITS VISIBLE PART
            if (fc.width() != this.frameW || fc.height() != this.frameH) {
                this.frameW = fc.width();
                this.frameH = fc.height();
                this.frameColorimetry = this.color.constants(this.format, this.frameW, this.frameH, this.bits);
                LOGGER.debug(IT, "Decoded frames: {} {}x{} in {} image(s) of vkFormat {} {} on the {} route into {}x{}", this.format, fc.width(), fc.height(),
                        images, vfc.format(0), twin != null ? "imported from the twin" : "sampled in place", this.ycbcrFmt != 0 ? "YCbCr sampler" : "compute",
                        this.width, this.height);
            }
            this.ensureSlot(slot, true);

            // BIND THIS FRAME'S VIEWS; THE SLOT IS IDLE, SO ITS SET MAY CHANGE
            final long[] views = this.views(stack, picture, f, images);
            if (this.ycbcrFmt != 0) {
                final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
                write(writes.get(0), stack, slot.ycbcrSet, 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, this.ycbcr.sampler, views[MAX_PLANES], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                vkUpdateDescriptorSets(this.device, writes, null);
            } else {
                final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(MAX_PLANES, stack);
                for (int j = 0; j < MAX_PLANES; j++)
                    write(writes.get(j), stack, slot.descSet, j, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, this.kit.sampler, views[j < this.planeCount ? j : 0], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                vkUpdateDescriptorSets(this.device, writes, null);
            }

            // THE FRAME'S SYNCHRONIZATION STATE IS READ AND UPDATED UNDER ITS LOCK, LIKE THE DECODER DOES
            vfc.lock_frame().call(fc, f);
            locked = true;
            // A TWIN'S IMAGE (ALWAYS ONE) IS WAITED HERE THROUGH ITS IMPORTED SEMAPHORE, ONE STEP AFTER THE TWIN'S RELEASE
            final int step = twin != null ? 1 : 0;
            final LongBuffer sems = stack.mallocLong(images);
            final LongBuffer waits = stack.mallocLong(images);
            final LongBuffer signals = stack.mallocLong(images);
            final IntBuffer stages = stack.mallocInt(images);
            for (int i = 0; i < images; i++) {
                sems.put(i, twin != null ? picture.semaphore() : f.sem(i));
                waits.put(i, f.sem_value(i) + step);
                signals.put(i, f.sem_value(i) + step + 1);
                stages.put(i, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            }

            check(vkResetCommandBuffer(slot.cmd, 0), "reset command buffer");
            final VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(slot.cmd, begin), "begin command buffer");
            // THE SEMAPHORE WAIT MADE THE DECODER'S WRITES VISIBLE; THE BARRIERS ONLY MOVE EACH IMAGE INTO A SAMPLEABLE LAYOUT, AND A TWIN'S
            // IMAGE ACQUIRES THE LAYOUT ITS RELEASE ALREADY NAMED FROM THE EXTERNAL FAMILY (BOTH SIDES OF A TRANSFER NAME THE SAME LAYOUTS)
            for (int i = 0; i < images; i++)
                this.barrier(stack, slot.cmd, twin != null ? picture.image() : f.img(i), f.layout(i), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                        twin != null ? VK_QUEUE_FAMILY_EXTERNAL : VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
            // THE PICTURE STARTS AT THE LEFT AND TOP CROPS (av_frame_apply_cropping LEAVES THEM ON HARDWARE FRAMES) AND SPANS THE FRAME
            // SIZE; THE DECODER'S PLANES ARE NORMALIZED BY THE SAMPLER, SO THE DEPTH SCALING STAYS 1 WHATEVER THE LAYOUT'S BITS
            final float left = (float) frame.crop_left() / this.frameW, top = (float) frame.crop_top() / this.frameH;
            this.dispatch(stack, slot, left, top, (float) frame.width() / this.frameW - left, (float) frame.height() / this.frameH - top,
                    1.0f, this.frameColorimetry);
            // THE READ IS DONE: THE IMAGE GOES BACK TO THE EXTERNAL FAMILY, KEEPING ITS LAYOUT, FOR THE TWIN TO ACQUIRE
            if (twin != null)
                this.barrier(stack, slot.cmd, picture.image(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_EXTERNAL);
            check(vkEndCommandBuffer(slot.cmd), "end command buffer");

            // THE REFERENCE IS TAKEN BEFORE THE SUBMISSION, SO A FENCE IN FLIGHT ALWAYS HAS ITS FRAME
            if (slot.held == null) slot.held = av_frame_alloc();
            if (av_frame_ref(slot.held, frame) < 0) throw new IllegalStateException("VKEngine: cannot reference the decoded frame");
            if (twin != null) {
                this.twinStep(stack, slot.twinRelease, twin, f, f.layout(0), VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_EXTERNAL, f.sem_value(0), 0L);
                crossed = f.sem_value(0) + 1;
            }
            // LWJGL DERIVES THE SIGNAL AND COMMAND BUFFER COUNTS FROM THEIR BUFFERS BUT NOT THE WAIT COUNT, SHARED WITH THE STAGE MASKS
            final VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default()
                    .pNext(VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default().pWaitSemaphoreValues(waits).pSignalSemaphoreValues(signals))
                    .waitSemaphoreCount(images).pWaitSemaphores(sems).pWaitDstStageMask(stages)
                    .pCommandBuffers(stack.pointers(slot.cmd)).pSignalSemaphores(sems);
            synchronized (this.ctx.queueLock()) {
                final int r = vkQueueSubmit(this.ctx.queue(), si, slot.fence);
                if (r != VK_SUCCESS) {
                    av_frame_unref(slot.held);
                    this.fail("vkQueueSubmit", r);
                    return;
                }
            }
            slot.seq = ++this.submitSeq;
            slot.submitted = true;
            this.head++;
            if (twin != null) {
                crossed = f.sem_value(0) + 2;
                this.twinStep(stack, slot.twinAcquire, twin, f, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_QUEUE_FAMILY_EXTERNAL, VK_QUEUE_FAMILY_IGNORED,
                        f.sem_value(0) + 2, slot.twinFence);
                slot.twinSubmitted = true;
                this.importedFrames++;
            }
            // access IS A VkAccessFlags2 MASK: FFMPEG FEEDS IT AS srcAccessMask TO ITS NEXT synchronization2 BARRIER ON THE IMAGE. AFTER THE TWIN'S
            // ACQUIRE NO ACCESS OF ITS OWN IS PENDING, AND THE SEMAPHORE ORDERS THE DECODER'S NEXT WRITE AFTER THIS DEVICE'S READ
            for (int i = 0; i < images; i++)
                f.sem_value(i, signals.get(i) + step).layout(i, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).access(i, twin != null ? 0L : VK_ACCESS_2_SHADER_READ_BIT);
            crossed = 0L;
            this.decodedFrames++;
        } catch (final RuntimeException | LinkageError e) {
            this.fail(e);
        } finally {
            // A FAILED STEP LEAVES A TWIN'S IMAGE WITH THE EXTERNAL FAMILY AT THE LAST VALUE SUBMITTED, FOR THE DECODER'S NEXT BARRIER TO
            // ACQUIRE, AND THE ENGINE OFF THE TWIN: AN UNFENCED RELEASE MAY STILL BE PENDING
            if (crossed != 0L) {
                f.sem_value(0, crossed).layout(0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).queue_family(0, VK_QUEUE_FAMILY_EXTERNAL).access(0, 0L);
                this.twinBroken = true;
            }
            if (locked) vfc.unlock_frame().call(fc, f);
        }
    }

    // ==========================================================================
    // CONVERSION PASS AND DECODED FRAMES (PRODUCER THREAD)
    // ==========================================================================
    // RECORDS THE CONVERSION INTO THE SLOT'S MANAGED IMAGE FROM THE PLANES BOUND TO ITS SET: THE STORAGE BARRIER, THE YCBCR OR COMPUTE
    // PASS OVER THE SOURCE FROM origin ACROSS crop (FRACTIONS) AND THE HAND-OVER TO THE CONSUMER'S FRAGMENT SAMPLING (SAME-QUEUE BARRIER)
    private void dispatch(final MemoryStack stack, final Slot slot, final float originX, final float originY, final float cropX, final float cropY,
                          final float bitScale, final float[] colorimetry) {
        // THE STORAGE IMAGE MUST BE IN GENERAL WHILE THE PASS WRITES IT; srcStage FRAGMENT_SHADER ORDERS IT AFTER A CONSUMER FRAME THAT
        // MAY STILL SAMPLE THIS SLOT'S VIEW (texture() FALLBACK) ON THE SHARED QUEUE, A WRITE-AFTER-READ HAZARD THE FENCE DOES NOT COVER
        this.barrier(stack, slot.cmd, slot.outImg,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
        if (this.ycbcrFmt != 0) {
            vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.ycbcr.pipeline);
            vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.ycbcr.pipeLayout, 0, stack.longs(slot.ycbcrSet), null);
            final ByteBuffer push = stack.malloc(YCBCR_PUSH_BYTES);
            push.putInt(0, this.width).putInt(4, this.height).putFloat(8, cropX).putFloat(12, cropY).putFloat(16, originX).putFloat(20, originY);
            vkCmdPushConstants(slot.cmd, this.ycbcr.pipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        } else {
            // THE DEVICE'S FIRST CONVERSION FRAME BUILDS THE KIT'S PIPELINE; EVERY LATER ENGINE REUSES IT
            vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.kit.pipeline());
            vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.kit.pipeLayout, 0, stack.longs(slot.descSet), null);
            final ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0, this.mode).putFloat(4, bitScale).putInt(8, this.width).putInt(12, this.height);
            for (int i = 0; i < colorimetry.length; i++) push.putFloat(16 + i * 4, colorimetry[i]);
            push.putFloat(80, cropX).putFloat(84, cropY).putFloat(88, originX).putFloat(92, originY);
            vkCmdPushConstants(slot.cmd, this.kit.pipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        }
        vkCmdDispatch(slot.cmd, (this.width + 7) / 8, (this.height + 7) / 8, 1);
        this.barrier(stack, slot.cmd, slot.outImg,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    // THE PICTURE OF A DECODER IMAGE, KEPT UNTIL ITS POOL GOES. A NEW POOL DRAINS THE ENGINE AND REPLACES THE PICTURES AND THE REFERENCE
    // THAT KEEPS THEIR IMAGES ALIVE; NULL FOR A POOL THIS DEVICE CANNOT SAMPLE
    private Picture picture(final MemoryStack stack, final AVFrame frame, final AVHWFramesContext fc, final AVVkFrame f, final int images) {
        if (fc.address() == this.refusedPool) return null;
        if (fc.address() != this.framesCtx) {
            if (!this.waitAllIdle()) return null;
            this.destroyPictures();
            this.framesRef = av_buffer_ref(frame.hw_frames_ctx());
            if (FFTool.isNull(this.framesRef)) throw new IllegalStateException("VKEngine: cannot reference the decoder's frame pool");
            this.framesCtx = fc.address();
            // THE POOL LIVES ON THIS DEVICE OR ON THE KIT'S TWIN; THE FRAMES OF ANY OTHER DEVICE CANNOT BE REACHED FROM HERE
            final long owner = new AVVulkanDeviceContext(fc.device_ctx().hwctx()).act_dev();
            final Twin twin = this.kit.twin;
            if (owner != this.device.address()) {
                if (twin == null || owner != twin.device().address()) return this.refuse(fc, "lives on a foreign Vulkan device", null);
                // A STEP THAT FAILED MID-FRAME MAY LEAVE A TWIN COMMAND BUFFER PENDING, SO THE ENGINE NEVER RECORDS ON THE TWIN AGAIN
                if (this.twinBroken) return this.refuse(fc, "lives on the twin this engine stopped using after a failed submission", null);
                this.framesTwin = twin;
            }
        }
        final Picture cached = this.pictures.get(f.img(0));
        if (cached != null) return cached;
        final Picture fresh = this.framesTwin != null ? this.importPicture(stack, fc, f, images) : new Picture(f.img(0), 0L, 0L, new long[MAX_PLANES + 1]);
        if (fresh != null) this.pictures.put(f.img(0), fresh);
        return fresh;
    }

    // IMPORTS A TWIN'S DECODER IMAGE: AN IMAGE MADE HERE EXACTLY AS FFMPEG MADE IT (vulkan_pool_alloc AND create_frame OF hwcontext_vulkan.c),
    // BOUND TO ITS EXPORTED MEMORY, WITH ITS TIMELINE SEMAPHORE IMPORTED FOR GOOD, SO BOTH DEVICES SHARE ONE PAYLOAD; NULL REFUSES THE POOL
    private Picture importPicture(final MemoryStack stack, final AVHWFramesContext fc, final AVVkFrame f, final int images) {
        final Twin twin = this.framesTwin;
        final AVVulkanFramesContext vfc = new AVVulkanFramesContext(fc.hwctx());
        // DECODER POOLS HOLD ONE OPTIMAL MULTIPLANAR IMAGE PER FRAME IN AN ALLOCATION OF ITS OWN; NOTHING ELSE IS REPEATED HERE
        if (images != 1 || vfc.tiling() != VK_IMAGE_TILING_OPTIMAL || f.offset(0) != 0L) return this.refuse(fc, "is not one optimal image per frame", null);
        final long chain = FFTool.isNull(vfc.create_pnext()) ? 0L : vfc.create_pnext().address(); // THE VIDEO PROFILE LIST OF A DECODER POOL
        final int memoryType = twin.win32() ? VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT : VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
        final int semaphoreType = twin.win32() ? VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT : VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;
        // FFMPEG CHAINS THE HANDLE TYPES ITS EXPORT QUERY CALLS COMPATIBLE INTO THE IMAGE (try_export_flags, WITHOUT THE PROFILE LIST)
        final int compatible = this.exportable(stack, vfc, memoryType, vfc.img_flags() != 0 ? vfc.img_flags() : VK_IMAGE_CREATE_ALIAS_BIT);
        if (compatible == 0) return this.refuse(fc, "cannot be imported into this device", null);
        // BINARIES' FFMPEG ASKS WITH THE POOL'S FLAGS (63d0237033), OLDER ONES WITH THE ALIAS FLAG: OTHER NATIVES ARE TRUSTED ONLY WHERE BOTH
        // ANSWERS AGREE, AS ASKING FOR THE HANDLE OF MEMORY NEVER EXPORTED IS UNDEFINED
        if (!FFTool.bundled() && this.exportable(stack, vfc, memoryType, VK_IMAGE_CREATE_ALIAS_BIT) != compatible)
            return this.refuse(fc, "may not be exported by natives other than the bundled ones", null);

        final LongBuffer p = stack.mallocLong(1);
        long image = 0L, memory = 0L, semaphore = 0L;
        boolean kept = false;
        try {
            if (this.twinPool == 0L) this.twinObjects(stack, twin);
            // THE RAW pNext ADDRESSES KEEP THE PROFILE LIST BEHIND THE EXTERNAL INFO: LWJGL'S TYPED pNext PREPENDS AND WOULD CUT IT
            final VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .pNext(VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default().pNext(chain).handleTypes(compatible).address())
                    .flags(vfc.img_flags()).imageType(VK_IMAGE_TYPE_2D).format(vfc.format(0))
                    .mipLevels(1).arrayLayers(vfc.nb_layers()).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(vfc.tiling()).usage(vfc.usage()).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .sharingMode(VK_SHARING_MODE_CONCURRENT).pQueueFamilyIndices(stack.ints(twin.families()));
            ici.extent().width(fc.width()).height(fc.height()).depth(1);
            if (vkCreateImage(this.device, ici, null, p) != VK_SUCCESS) return this.refuse(fc, "cannot be created on this device", null);
            image = p.get(0);
            // FFMPEG ALLOCATED THE REQUIRED SIZE FROM THE FIRST DEVICE-LOCAL TYPE, DEDICATED WHEN THE DRIVER PREFERS IT: THE IMPORT REPEATS ALL THREE
            final VkMemoryDedicatedRequirements dedicated = VkMemoryDedicatedRequirements.calloc(stack).sType$Default();
            final VkMemoryRequirements2 req = VkMemoryRequirements2.calloc(stack).sType$Default().pNext(dedicated);
            vkGetImageMemoryRequirements2(this.device, VkImageMemoryRequirementsInfo2.calloc(stack).sType$Default().image(image), req);
            final int type = this.memType(req.memoryRequirements().memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (type < 0 || req.memoryRequirements().size() != f.size(0)) return this.refuse(fc, "needs another allocation on this device", null);
            final long dedicate = dedicated.prefersDedicatedAllocation() || dedicated.requiresDedicatedAllocation()
                    ? VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image).address() : 0L;
            final VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(f.size(0)).memoryTypeIndex(type);
            final VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack).sType$Default()
                    .pNext(VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE));
            check(vkCreateSemaphore(this.device, sci, null, p), "create imported semaphore");
            semaphore = p.get(0);

            // AN NT HANDLE STAYS THE EXPORTER'S TO CLOSE (LWJGL BINDS NO CloseHandle, SO KERNEL32'S IS CALLED BY ADDRESS); A FILE DESCRIPTOR
            // BELONGS TO THE IMPORT ONCE IT SUCCEEDS AND IS CLOSED HERE ONLY WHEN IT FAILS
            if (twin.win32()) {
                final long close = Kernel32.getLibrary().getFunctionAddress("CloseHandle");
                final PointerBuffer handle = stack.callocPointer(1);
                if (vkGetMemoryWin32HandleKHR(twin.device(), VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                        .memory(f.mem(0)).handleType(memoryType), handle) != VK_SUCCESS || handle.get(0) == 0L)
                    return this.refuse(fc, "exports no memory", null);
                mai.pNext(VkImportMemoryWin32HandleInfoKHR.calloc(stack).sType$Default().pNext(dedicate).handleType(memoryType).handle(handle.get(0)).address());
                final int allocated = vkAllocateMemory(this.device, mai, null, p);
                JNI.callPI(handle.get(0), close);
                if (allocated != VK_SUCCESS) return this.refuse(fc, "cannot import its memory (VkResult " + allocated + ")", null);
                memory = p.get(0);
                handle.put(0, 0L);
                if (vkGetSemaphoreWin32HandleKHR(twin.device(), VkSemaphoreGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                        .semaphore(f.sem(0)).handleType(semaphoreType), handle) != VK_SUCCESS || handle.get(0) == 0L)
                    return this.refuse(fc, "exports no semaphore", null);
                // LWJGL 3.3.6 REJECTS A NULL name, WHICH THE SPEC REQUIRES NEXT TO A handle, SO THE IMPORT IS CALLED BY ITS ADDRESS
                final int imported = JNI.callPPI(this.device.address(), VkImportSemaphoreWin32HandleInfoKHR.calloc(stack).sType$Default()
                        .semaphore(semaphore).handleType(semaphoreType).handle(handle.get(0)).address(), this.device.getCapabilities().vkImportSemaphoreWin32HandleKHR);
                JNI.callPI(handle.get(0), close);
                if (imported != VK_SUCCESS) return this.refuse(fc, "cannot import its semaphore (VkResult " + imported + ")", null);
            } else {
                final IntBuffer fd = stack.mallocInt(1);
                if (vkGetMemoryFdKHR(twin.device(), VkMemoryGetFdInfoKHR.calloc(stack).sType$Default().memory(f.mem(0)).handleType(memoryType), fd) != VK_SUCCESS)
                    return this.refuse(fc, "exports no memory", null);
                mai.pNext(VkImportMemoryFdInfoKHR.calloc(stack).sType$Default().pNext(dedicate).handleType(memoryType).fd(fd.get(0)).address());
                final int allocated = vkAllocateMemory(this.device, mai, null, p);
                if (allocated != VK_SUCCESS) {
                    UNISTD.close(null, fd.get(0));
                    return this.refuse(fc, "cannot import its memory (VkResult " + allocated + ")", null);
                }
                memory = p.get(0);
                if (vkGetSemaphoreFdKHR(twin.device(), VkSemaphoreGetFdInfoKHR.calloc(stack).sType$Default().semaphore(f.sem(0)).handleType(semaphoreType), fd) != VK_SUCCESS)
                    return this.refuse(fc, "exports no semaphore", null);
                final int imported = vkImportSemaphoreFdKHR(this.device, VkImportSemaphoreFdInfoKHR.calloc(stack).sType$Default()
                        .semaphore(semaphore).handleType(semaphoreType).fd(fd.get(0)));
                if (imported != VK_SUCCESS) {
                    UNISTD.close(null, fd.get(0));
                    return this.refuse(fc, "cannot import its semaphore (VkResult " + imported + ")", null);
                }
            }
            if (vkBindImageMemory(this.device, image, memory, 0L) != VK_SUCCESS) return this.refuse(fc, "cannot bind its imported memory", null);
            kept = true;
            return new Picture(image, memory, semaphore, new long[MAX_PLANES + 1]);
        } catch (final RuntimeException | LinkageError e) {
            return this.refuse(fc, "failed to import", e); // A DRIVER OR BINDING FAILURE STILL LEAVES THE RAM PATH, NEVER A PARKED ENGINE
        } finally {
            if (!kept) {
                if (semaphore != 0L) vkDestroySemaphore(this.device, semaphore, null);
                if (image != 0L) vkDestroyImage(this.device, image, null);
                if (memory != 0L) vkFreeMemory(this.device, memory, null);
            }
        }
    }

    // THE HANDLE TYPES A POOL'S IMAGE EXPORTS AS COMPATIBLE WITH type WHEN QUERIED WITH flags, OR 0 WHEN THIS DEVICE CANNOT IMPORT IT
    private int exportable(final MemoryStack stack, final AVVulkanFramesContext vfc, final int type, final int flags) {
        final VkExternalImageFormatProperties external = VkExternalImageFormatProperties.calloc(stack).sType$Default();
        final int queried = vkGetPhysicalDeviceImageFormatProperties2(this.ctx.physicalDevice(), VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default()
                        .pNext(VkPhysicalDeviceExternalImageFormatInfo.calloc(stack).sType$Default().handleType(type))
                        .format(vfc.format(0)).type(VK_IMAGE_TYPE_2D).tiling(vfc.tiling()).usage(vfc.usage()).flags(flags),
                VkImageFormatProperties2.calloc(stack).sType$Default().pNext(external));
        final VkExternalMemoryProperties props = external.externalMemoryProperties();
        return queried == VK_SUCCESS && (props.externalMemoryFeatures() & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) != 0 ? props.compatibleHandleTypes() : 0;
    }

    // REFUSES THE CURRENT POOL FOR GOOD, LOGGED ONCE PER ENGINE: samples() TURNS ITS FRAMES DOWN, SO THE PLAYER BRINGS THEM TO RAM
    private Picture refuse(final AVHWFramesContext fc, final String reason, final Throwable cause) {
        this.refusedPool = fc.address();
        if (!this.importWarned) LOGGER.warn(IT, "The decoder's frame pool {}; its frames come down to RAM instead", reason, cause);
        this.importWarned = true;
        return null;
    }

    // THE VIEWS OF A PICTURE, BUILT ON FIRST USE FOR THE CURRENT LAYOUT: ONE PER PLANE (ITS PLANE ASPECT OF THE MULTIPLANAR IMAGE, OR ITS
    // OWN IMAGE) FOR THE COMPUTE PASS, OR THE YCBCR CONVERSION'S COLOR VIEW AT INDEX MAX_PLANES
    private long[] views(final MemoryStack stack, final Picture picture, final AVVkFrame f, final int images) {
        final long[] views = picture.views();
        if (views[0] != 0L || views[MAX_PLANES] != 0L) return views;
        if (this.ycbcrFmt != 0) {
            views[MAX_PLANES] = this.view(stack, picture.image(), this.ycbcrFmt, VK_IMAGE_ASPECT_COLOR_BIT, this.ycbcr.conv, VK_IMAGE_USAGE_SAMPLED_BIT);
        } else {
            // ONE IMAGE (THE ONLY KIND A TWIN IMPORTS) IS VIEWED THROUGH ITS PICTURE: A PLANE OF A MULTIPLANAR IMAGE, OR THE COLOR OF A FILTER'S RGBA
            for (int i = 0; i < this.planeCount; i++)
                views[i] = images > 1
                        ? this.view(stack, f.img(i), this.planeSpecs[i].format, VK_IMAGE_ASPECT_COLOR_BIT, 0L, VK_IMAGE_USAGE_SAMPLED_BIT)
                        : this.view(stack, picture.image(), this.planeSpecs[i].format, this.planeCount > 1 ? VK_IMAGE_ASPECT_PLANE_0_BIT << i : VK_IMAGE_ASPECT_COLOR_BIT,
                                0L, VK_IMAGE_USAGE_SAMPLED_BIT);
        }
        return views;
    }

    // THE ENGINE'S OBJECTS ON THE TWIN, BUILT ON THE FIRST IMPORT: A COMMAND POOL ON THE SHARED QUEUE'S FAMILY WITH A RELEASE AND AN ACQUIRE
    // BUFFER PER SLOT, AND A FENCE PER SLOT FOR THE ACQUIRE, THE TWIN'S LAST STEP. ALL OR NOTHING, SO A HALF SET NEVER PASSES samples()
    private void twinObjects(final MemoryStack stack, final Twin twin) {
        final LongBuffer p = stack.mallocLong(1);
        check(vkCreateCommandPool(twin.device(), VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(twin.family()), null, p), "create twin command pool");
        this.twinDevice = twin.device();
        this.twinPool = p.get(0);
        try {
            final PointerBuffer cmds = stack.mallocPointer(2 * SLOTS);
            check(vkAllocateCommandBuffers(twin.device(), VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(this.twinPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(2 * SLOTS), cmds), "alloc twin command buffers");
            for (int i = 0; i < SLOTS; i++) {
                this.slots[i].twinRelease = new VkCommandBuffer(cmds.get(2 * i), twin.device());
                this.slots[i].twinAcquire = new VkCommandBuffer(cmds.get(2 * i + 1), twin.device());
                check(vkCreateFence(twin.device(), VkFenceCreateInfo.calloc(stack).sType$Default(), null, p), "create twin fence");
                this.slots[i].twinFence = p.get(0);
            }
        } catch (final RuntimeException e) {
            for (final Slot s: this.slots) {
                if (s.twinFence != 0L) vkDestroyFence(twin.device(), s.twinFence, null);
                s.twinFence = 0L;
                s.twinRelease = null;
                s.twinAcquire = null;
            }
            vkDestroyCommandPool(twin.device(), this.twinPool, null); // FREES CMD BUFFERS
            this.twinPool = 0L;
            throw e;
        }
    }

    // ONE OWNERSHIP STEP OF A TWIN'S IMAGE ON THE TWIN'S SHARED QUEUE: A LONE BARRIER FROM oldLayout TO THE SAMPLED LAYOUT BETWEEN THE
    // FAMILIES, WAITING wait AND SIGNALING wait + 1 ON THE IMAGE'S TIMELINE
    @SuppressWarnings("deprecation") // lock_queue LEAVES WITH avutil 62 FOR VK_KHR_internally_synchronized_queues, WHICH CONSUMERS DO NOT ENABLE
    private void twinStep(final MemoryStack stack, final VkCommandBuffer cmd, final Twin twin, final AVVkFrame f, final int oldLayout,
                          final int srcFamily, final int dstFamily, final long wait, final long fence) {
        check(vkResetCommandBuffer(cmd, 0), "reset twin command buffer");
        check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin twin command buffer");
        this.barrier(stack, cmd, f.img(0), oldLayout, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, srcFamily, dstFamily);
        check(vkEndCommandBuffer(cmd), "end twin command buffer");
        final VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default()
                .pNext(VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default().pWaitSemaphoreValues(stack.longs(wait)).pSignalSemaphoreValues(stack.longs(wait + 1)))
                .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(f.sem(0))).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                .pCommandBuffers(stack.pointers(cmd)).pSignalSemaphores(stack.longs(f.sem(0)));
        // FFMPEG MAY SUBMIT TO THE SAME QUEUE FROM ITS OWN THREADS: ITS lock_queue SERIALIZES BOTH (A NO-OP ON INTERNALLY SYNCHRONIZED QUEUES)
        twin.vk().lock_queue().call(twin.context(), twin.family(), 0);
        try {
            check(vkQueueSubmit(twin.queue(), si, fence), "submit to the twin");
        } finally {
            twin.vk().unlock_queue().call(twin.context(), twin.family(), 0);
        }
    }

    // CALLERS GUARANTEE THE ENGINE'S OWN WORK IS DRAINED: THE VIEWS AND THE COLORIMETRY OF THE IMAGE EXTENT FOLLOW THE LAYOUT
    private void destroyViews() {
        for (final Picture picture: this.pictures.values()) {
            final long[] views = picture.views();
            for (int i = 0; i < views.length; i++) {
                if (views[i] != 0L) vkDestroyImageView(this.device, views[i], null);
                views[i] = 0L;
            }
        }
        this.frameW = 0;
        this.frameH = 0;
    }

    // CALLERS GUARANTEE THE ENGINE'S OWN WORK IS DRAINED: THE PICTURES DIE, IMPORTS INCLUDED, AND THE POOL REFERENCE THAT KEPT THEIR IMAGES
    // ALIVE GOES
    private void destroyPictures() {
        this.destroyViews();
        for (final Picture picture: this.pictures.values()) {
            if (picture.memory() == 0L) continue;
            vkDestroyImage(this.device, picture.image(), null);
            vkFreeMemory(this.device, picture.memory(), null);
            vkDestroySemaphore(this.device, picture.semaphore(), null);
        }
        this.pictures.clear();
        if (this.framesRef != null) av_buffer_unref(this.framesRef);
        this.framesRef = null;
        this.framesCtx = 0L;
        this.framesTwin = null;
        this.refusedPool = 0L;
    }

    // ==========================================================================
    // HOST-POINTER IMPORT (ZERO-COPY), CACHED BY HOST POINTER
    // ==========================================================================
    // IMPORTS THE DECODER'S HOST MEMORY AS A TRANSFER-SOURCE VkBuffer SO THE GPU READS IT IN PLACE.
    // RETURNS THE VkBuffer, OR 0 IF THE IMPORT IS REJECTED (CALLER FALLS BACK TO STAGING).
    private long importBuffer(final long ptr, final long size) {
        final Imported cached = this.importCache.get(ptr);
        if (cached != null && cached.size() >= size) return cached.buffer();
        if (cached != null) { // A LARGER REGION IS NEEDED: DRAIN THE READS OF THE STALE IMPORT, THEN DROP IT
            if (!this.waitAllIdle()) return 0L;
            this.importCache.remove(ptr);
            vkDestroyBuffer(this.device, cached.buffer(), null);
            vkFreeMemory(this.device, cached.memory(), null);
        }
        try (final MemoryStack stack = stackPush()) {
            // WHICH MEMORY TYPES MAY THIS HOST POINTER BE IMPORTED AS.
            final VkMemoryHostPointerPropertiesEXT props = VkMemoryHostPointerPropertiesEXT.calloc(stack).sType$Default();
            if (vkGetMemoryHostPointerPropertiesEXT(this.device,
                    VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT, ptr, props) != VK_SUCCESS)
                return 0L;
            int type = this.memType(props.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            if (type < 0) type = this.memType(props.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
            if (type < 0) type = this.memType(props.memoryTypeBits(), 0);
            if (type < 0) return 0L;

            // THE IMPORTED SIZE MUST BE A MULTIPLE OF minAlign; THE PRODUCER ALREADY ROUNDS ITS
            // ALLOCATIONS UP, SO capacity IS A MULTIPLE OF minAlign AND THE REGION IS FULLY VALID.
            final long importSize = ((size + this.minAlign - 1) / this.minAlign) * this.minAlign;

            final VkExternalMemoryBufferCreateInfo ext = VkExternalMemoryBufferCreateInfo.calloc(stack)
                    .sType$Default().handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT);
            final VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .pNext(ext).size(importSize)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            final LongBuffer pBuf = stack.mallocLong(1);
            if (vkCreateBuffer(this.device, bci, null, pBuf) != VK_SUCCESS) return 0L;
            final long buffer = pBuf.get(0);

            final VkImportMemoryHostPointerInfoEXT imp = VkImportMemoryHostPointerInfoEXT.calloc(stack)
                    .sType$Default().handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT)
                    .pHostPointer(ptr);
            final VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .pNext(imp).allocationSize(importSize).memoryTypeIndex(type);
            final LongBuffer pMem = stack.mallocLong(1);
            if (vkAllocateMemory(this.device, mai, null, pMem) != VK_SUCCESS) {
                vkDestroyBuffer(this.device, buffer, null);
                return 0L;
            }
            final long memory = pMem.get(0);
            if (vkBindBufferMemory(this.device, buffer, memory, 0) != VK_SUCCESS) {
                vkFreeMemory(this.device, memory, null);
                vkDestroyBuffer(this.device, buffer, null);
                return 0L;
            }
            this.importCache.put(ptr, new Imported(memory, buffer, importSize));
            return buffer;
        }
    }

    // ==========================================================================
    // HOST-VISIBLE STAGING FALLBACK, PERSISTENT PER PLANE PER SLOT
    // ==========================================================================
    // COPIES THE PLANE BYTES INTO THIS SLOT'S MAPPED STAGING BUFFER AND RETURNS IT AS THE COPY
    // SOURCE. THE BUFFER GROWS ON DEMAND AND IS MAPPED ONCE (HOST_COHERENT, NO EXPLICIT FLUSH).
    private long stageInto(final Slot slot, final int i, final ByteBuffer src, final long needed) {
        Stage st = slot.stage[i];
        if (st == null || st.size() < needed) {
            if (st != null) {
                vkUnmapMemory(this.device, st.memory());
                vkDestroyBuffer(this.device, st.buffer(), null);
                vkFreeMemory(this.device, st.memory(), null);
            }
            try (final MemoryStack stack = stackPush()) {
                final VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                        .size(needed).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                final LongBuffer pBuf = stack.mallocLong(1);
                check(vkCreateBuffer(this.device, bci, null, pBuf), "create staging buffer");
                final long buffer = pBuf.get(0);
                long memory = 0L;
                try {
                    final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
                    vkGetBufferMemoryRequirements(this.device, buffer, req);
                    int type = this.memType(req.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                    if (type < 0) type = this.memType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
                    if (type < 0) throw new IllegalStateException("VKEngine: no host-visible memory type for staging");

                    final VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                            .allocationSize(req.size()).memoryTypeIndex(type);
                    final LongBuffer pMem = stack.mallocLong(1);
                    check(vkAllocateMemory(this.device, mai, null, pMem), "alloc staging memory");
                    memory = pMem.get(0);
                    check(vkBindBufferMemory(this.device, buffer, memory, 0), "bind staging memory");

                    final PointerBuffer pp = stack.mallocPointer(1);
                    check(vkMapMemory(this.device, memory, 0, req.size(), 0, pp), "map staging memory");
                    st = new Stage(buffer, memory, pp.get(0), needed);
                } catch (final RuntimeException e) {
                    // A PARTIAL FAILURE MUST NOT LEAK THE BUFFER OR ITS MEMORY
                    if (memory != 0L) vkFreeMemory(this.device, memory, null);
                    vkDestroyBuffer(this.device, buffer, null);
                    throw e;
                }
                slot.stage[i] = st;
            }
        }
        memCopy(memAddress(src), st.mapped(), needed);
        return st.buffer();
    }

    // ==========================================================================
    // PER-SLOT FORMAT RESOURCES
    // ==========================================================================
    // HANDS A SLOT'S MANAGED IMAGE TO THE CONSUMER'S RETIREMENT: IT MAY STILL SAMPLE THE VIEW texture() RETURNED (THE ENGINE'S
    // FENCES COVER ONLY ITS OWN WORK), SO THE EXPOSED VIEW DIES AFTER THE NEXT PUBLISHED FRAME AND THE OTHERS WITH THE
    // IN-FLIGHT FRAMES; PLANE AND STAGING RESOURCES ARE PRIVATE AND STAY FOR destroySlotFormatRes. CALLERS DRAINED THE SLOT
    private void retireOutput(final Slot s) {
        synchronized (this.ctx.queueLock()) {
            final long view = s.view, img = s.outImg, mem = s.outMem;
            if (view != 0L || img != 0L || mem != 0L) {
                s.view = 0L;
                s.outImg = 0L;
                s.outMem = 0L;
                final Runnable destroy = () -> {
                    if (view != 0L) vkDestroyImageView(this.device, view, null);
                    if (img != 0L) vkDestroyImage(this.device, img, null);
                    if (mem != 0L) vkFreeMemory(this.device, mem, null);
                };
                if (view == this.lastView) this.retiring.add(destroy);
                else this.ctx.retire(destroy);
            }
            s.seq = 0L;
            s.submitted = false;
        }
    }

    // (RE)BUILDS THIS IDLE SLOT'S MANAGED IMAGE, PLANE IMAGES AND DESCRIPTOR SET FOR THE CURRENT FORMAT; A SLOT FED DECODED FRAMES
    // (external) SAMPLES THE DECODER'S OWN IMAGES, SO IT OWNS NO PLANE IMAGES AND upload(AVFrame) BINDS THE PLANES PER FRAME
    private void ensureSlot(final Slot slot, final boolean external) {
        if (slot.gen == this.formatGen && slot.external == external) return;
        this.retireOutput(slot);
        this.destroySlotFormatRes(slot);
        // DECODED FRAMES ALWAYS GO THROUGH THE COMPUTE PASS, PASSTHROUGH LAYOUTS INCLUDED, SO THE OUTPUT IS ITS RGBA STORAGE IMAGE
        final boolean compute = this.convert || external;
        try (final MemoryStack stack = stackPush()) {
            final Img out = compute
                    ? this.image(stack, VK_FORMAT_R8G8B8A8_UNORM, this.width, this.height, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, 0L)
                    : this.image(stack, this.outFormat, this.width, this.height, this.outUsage, 0L);
            slot.outImg = out.image();
            slot.outMem = out.memory();
            slot.view = out.view();
            if (this.ycbcrFmt != 0) {
                this.ensureYcbcr(); // CONVERSION/SAMPLER/PIPELINE/SETS MUST EXIST BEFORE THE VIEW CHAINS THEM
                // ONE MULTIPLANAR IMAGE HOLDS EVERY PLANE AND ITS VIEW CHAINS THE IMMUTABLE SAMPLER'S CONVERSION (REQUIRED TO MATCH);
                // BINDING 0 = THAT VIEW (THE SAMPLER FIELD IS IGNORED), BINDING 1 = THIS SLOT'S RGBA STORAGE VIEW
                final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(external ? 1 : 2, stack);
                if (!external) {
                    final Img mp = this.image(stack, this.ycbcrFmt, this.width, this.height,
                            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, this.ycbcr.conv);
                    slot.planeImg[0] = mp.image();
                    slot.planeMem[0] = mp.memory();
                    slot.planeView[0] = mp.view();
                    write(writes.get(1), stack, slot.ycbcrSet, 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, this.ycbcr.sampler, mp.view(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
                write(writes.get(0), stack, slot.ycbcrSet, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0L, out.view(), VK_IMAGE_LAYOUT_GENERAL);
                vkUpdateDescriptorSets(this.device, writes, null);
            } else if (compute) {
                // REWRITE THE SLOT'S DESCRIPTOR SET: BINDINGS 0..3 = PLANE VIEWS, BINDING 4 = OUTPUT
                final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(external ? 1 : 1 + MAX_PLANES, stack);
                if (!external) {
                    for (int i = 0; i < this.planeCount; i++) {
                        final PlaneSpec spec = this.planeSpecs[i];
                        final Img p = this.image(stack, spec.format, spec.w, spec.h,
                                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, 0L);
                        slot.planeImg[i] = p.image();
                        slot.planeMem[i] = p.memory();
                        slot.planeView[i] = p.view();
                    }
                    // UNUSED PLANE SLOTS BIND PLANE 0 (ALWAYS PRESENT FOR CONVERT): A VALID VIEW THE SHADER NEVER READS
                    for (int j = 0; j < MAX_PLANES; j++)
                        write(writes.get(1 + j), stack, slot.descSet, j, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, this.kit.sampler,
                                slot.planeView[j < this.planeCount ? j : 0], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
                write(writes.get(0), stack, slot.descSet, MAX_PLANES, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0L, slot.view, VK_IMAGE_LAYOUT_GENERAL);
                vkUpdateDescriptorSets(this.device, writes, null);
            }
        }
        slot.gen = this.formatGen;
        slot.external = external;
    }

    private void destroySlotFormatRes(final Slot s) {
        for (int j = 0; j < MAX_PLANES; j++) {
            if (s.planeView[j] != 0L) { vkDestroyImageView(this.device, s.planeView[j], null); s.planeView[j] = 0L; }
            if (s.planeImg[j] != 0L) { vkDestroyImage(this.device, s.planeImg[j], null); s.planeImg[j] = 0L; }
            if (s.planeMem[j] != 0L) { vkFreeMemory(this.device, s.planeMem[j], null); s.planeMem[j] = 0L; }
            if (s.stage[j] != null) {
                vkUnmapMemory(this.device, s.stage[j].memory());
                vkDestroyBuffer(this.device, s.stage[j].buffer(), null);
                vkFreeMemory(this.device, s.stage[j].memory(), null);
                s.stage[j] = null;
            }
        }
        if (s.view != 0L) { vkDestroyImageView(this.device, s.view, null); s.view = 0L; }
        if (s.outImg != 0L) { vkDestroyImage(this.device, s.outImg, null); s.outImg = 0L; }
        if (s.outMem != 0L) { vkFreeMemory(this.device, s.outMem, null); s.outMem = 0L; }
    }

    private void destroyImportCache() {
        for (final Imported imp: this.importCache.values()) {
            vkDestroyBuffer(this.device, imp.buffer(), null);
            vkFreeMemory(this.device, imp.memory(), null);
        }
        this.importCache.clear();
    }

    // RUNS ON THE CONSUMER'S RENDER THREAD AFTER ITS IN-FLIGHT FRAMES RETIRE (SEE VKContext#retire). THE KIT GOES LAST: THESE SETS
    // WERE ALLOCATED ON ITS LAYOUTS, AND ITS REFERENCE KEEPS ALIVE THE TWIN THAT OWNS THE TWIN POOL AND FENCES
    private void destroyResources() {
        for (final Slot s: this.slots) {
            this.destroySlotFormatRes(s);
            if (s.fence != 0L) { vkDestroyFence(this.device, s.fence, null); s.fence = 0L; }
            if (s.twinFence != 0L) { vkDestroyFence(this.twinDevice, s.twinFence, null); s.twinFence = 0L; }
        }
        if (this.twinPool != 0L) { vkDestroyCommandPool(this.twinDevice, this.twinPool, null); this.twinPool = 0L; } // FREES CMD BUFFERS
        this.destroyYcbcrRes();
        if (this.descPool != 0L) { vkDestroyDescriptorPool(this.device, this.descPool, null); this.descPool = 0L; } // FREES SETS
        if (this.cmdPool != 0L) { vkDestroyCommandPool(this.device, this.cmdPool, null); this.cmdPool = 0L; }       // FREES CMD BUFFERS
        this.kit.release();
    }

    // DRAINS THE ENGINE'S OWN FENCES, THE TWIN'S INCLUDED; FALSE PARKS THE ENGINE BECAUSE A WEDGED GPU MAY STILL READ EVERY OBJECT
    private boolean waitAllIdle() {
        for (final Slot s: this.slots) {
            if (s.fence != 0L && s.submitted && !this.await(this.device, s.fence, "Idle wait")) return false;
            if (s.twinFence != 0L && s.twinSubmitted && !this.await(this.twinDevice, s.twinFence, "Twin idle wait")) return false;
        }
        return true;
    }

    // FENCE WAIT ON THIS DEVICE OR THE TWIN THAT PARKS THE ENGINE ON A TIMEOUT OR DEVICE LOSS INSTEAD OF RESETTING A FENCE STILL IN FLIGHT
    private boolean await(final VkDevice device, final long fence, final String what) {
        final int result = vkWaitForFences(device, fence, true, WAIT_TIMEOUT_NS);
        if (result == VK_SUCCESS) return true;
        this.fail(what, result);
        return false;
    }

    // PARKS THE ENGINE AFTER A DRIVER FAILURE: FRAMES ARE DROPPED UNTIL THE NEXT LAYOUT AND THE LAST VIEW STAYS EXPOSED
    private void fail(final String op, final int result) {
        this.failed = true;
        if (!this.failWarned) LOGGER.error(IT, "{} failed (VkResult {}); frames are dropped until the next format", op, result);
        this.failWarned = true;
    }

    private void fail(final Throwable cause) {
        this.failed = true;
        if (!this.failWarned) LOGGER.error(IT, "Upload failed; frames are dropped until the next format", cause);
        this.failWarned = true;
    }

    // ==========================================================================
    // RECORDING HELPERS
    // ==========================================================================
    // aspect IS COLOR FOR SINGLE-PLANE IMAGES AND PLANE_0/1/2 WHEN COPYING INTO A MULTIPLANAR IMAGE.
    private void copyToImage(final MemoryStack stack, final VkCommandBuffer cmd, final long srcBuffer,
                             final long image, final int w, final int h, final int rowLenTexels, final int aspect) {
        final VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).bufferOffset(0).bufferRowLength(rowLenTexels).bufferImageHeight(0);
        region.get(0).imageSubresource(s -> s.aspectMask(aspect).mipLevel(0).baseArrayLayer(0).layerCount(1));
        region.get(0).imageOffset(o -> o.x(0).y(0).z(0));
        region.get(0).imageExtent(e -> e.width(w).height(h).depth(1));
        vkCmdCopyBufferToImage(cmd, srcBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    private void barrier(final MemoryStack stack, final VkCommandBuffer cmd, final long image,
                         final int oldLayout, final int newLayout,
                         final int srcStage, final int srcAccess, final int dstStage, final int dstAccess) {
        this.barrier(stack, cmd, image, oldLayout, newLayout, srcStage, srcAccess, dstStage, dstAccess, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
    }

    // srcFamily AND dstFamily NAME AN OWNERSHIP TRANSFER; A CONCURRENT IMAGE CROSSES DEVICES BETWEEN IGNORED AND THE EXTERNAL FAMILY
    private void barrier(final MemoryStack stack, final VkCommandBuffer cmd, final long image, final int oldLayout, final int newLayout,
                         final int srcStage, final int srcAccess, final int dstStage, final int dstAccess, final int srcFamily, final int dstFamily) {
        final VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
        b.get(0).sType$Default()
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(srcFamily).dstQueueFamilyIndex(dstFamily)
                .image(image);
        b.get(0).subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, b);
    }

    // CREATES AN OPTIMAL-TILING 2D IMAGE WITH A DEVICE-LOCAL ALLOCATION AND A COLOR VIEW. WHEN conv
    // IS NON-ZERO THE VIEW CHAINS VkSamplerYcbcrConversionInfo, MANDATORY TO SAMPLE A MULTIPLANAR
    // FORMAT THROUGH THE COLOR ASPECT, AND IT MUST BE THE SAME CONVERSION AS THE IMMUTABLE SAMPLER.
    private Img image(final MemoryStack stack, final int format, final int w, final int h, final int usage, final long conv) {
        final VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                .imageType(VK_IMAGE_TYPE_2D).format(format)
                .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        ici.extent().width(w).height(h).depth(1);
        final LongBuffer p = stack.mallocLong(1);
        check(vkCreateImage(this.device, ici, null, p), "create image");
        final long img = p.get(0);
        long mem = 0L;
        try {
            final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(this.device, img, req);
            final int type = this.memType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (type < 0) throw new IllegalStateException("VKEngine: no device-local memory type for image");
            final VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(req.size()).memoryTypeIndex(type);
            final LongBuffer pm = stack.mallocLong(1);
            check(vkAllocateMemory(this.device, mai, null, pm), "alloc image memory");
            mem = pm.get(0);
            check(vkBindImageMemory(this.device, img, mem, 0), "bind image memory");
            return new Img(img, mem, this.view(stack, img, format, VK_IMAGE_ASPECT_COLOR_BIT, conv, 0));
        } catch (final RuntimeException e) {
            // A PARTIAL FAILURE MUST NOT LEAK THE IMAGE OR ITS MEMORY
            if (mem != 0L) vkFreeMemory(this.device, mem, null);
            vkDestroyImage(this.device, img, null);
            throw e;
        }
    }

    // A 2D VIEW OF ONE ASPECT OF AN IMAGE WITH IDENTITY COMPONENTS (REQUIRED WITH A CONVERSION); usage NARROWS IT TO WHAT ITS FORMAT
    // SUPPORTS (0 INHERITS THE IMAGE'S), AS A DECODER IMAGE CARRIES EXTENDED USAGES SUCH AS STORAGE THAT ITS MULTIPLANAR FORMAT LACKS
    private long view(final MemoryStack stack, final long image, final int format, final int aspect, final long conv, final int usage) {
        final VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
        if (conv != 0L) vci.pNext(VkSamplerYcbcrConversionInfo.calloc(stack).sType$Default().conversion(conv));
        if (usage != 0) vci.pNext(VkImageViewUsageCreateInfo.calloc(stack).sType$Default().usage(usage));
        vci.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        final LongBuffer pv = stack.mallocLong(1);
        check(vkCreateImageView(this.device, vci, null, pv), "create image view");
        return pv.get(0);
    }

    // FINDS A MEMORY TYPE INDEX IN typeBits WITH ALL props SET, OR -1. props == 0 MATCHES ANY TYPE.
    private int memType(final int typeBits, final int props) {
        final int count = this.memProps.memoryTypeCount();
        for (int i = 0; i < count; i++) {
            if ((typeBits & (1 << i)) != 0 && (this.memProps.memoryTypes(i).propertyFlags() & props) == props) return i;
        }
        return -1;
    }

    // ==========================================================================
    // FORMAT TABLE HELPERS
    // ==========================================================================
    // SIZES PLANE i OF THE PUBLISHED LAYOUT. SUBSAMPLED PLANES ROUND UP LIKE THE PRODUCER'S, SO ON ODD SIZES THE
    // LAST CHROMA COLUMN/ROW (AND THE PACKED gid.x >> 1 FETCH) STAYS INSIDE THE IMAGE
    private void plane(final int i, final int vkFormat) {
        PlaneSpec spec = this.planeSpecs[i];
        if (spec == null) { spec = new PlaneSpec(); this.planeSpecs[i] = spec; }
        spec.format = vkFormat;
        spec.texelBytes = this.format.texelBytes(i, this.bits);
        spec.w = Math.max(1, this.format.planeWidth(i, this.width));
        spec.h = Math.max(1, this.format.planeHeight(i, this.height));
    }

    // WHETHER A PLANE FORMAT CAN BE COPIED INTO AND SAMPLED THROUGH THE LINEAR SAMPLER ON THIS DEVICE
    private boolean sampleable(final int vkFormat) {
        final int need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        return (this.kit.features(vkFormat) & need) == need;
    }

    // ROUTES THE CURRENT FORMAT THROUGH THE SAMPLER-YCBCR PATH WHEN THE DEVICE FEATURE IS ON, THE FRAME NEEDS NO DISPLAY
    // MAPPING AND THE MULTIPLANAR FORMAT CARRIES THE REQUIRED BITS; OTHERWISE THE CALLER'S COMPUTE PATH STAYS (ycbcrFmt 0)
    private void tryYcbcr(final int fmt) {
        if (!this.ctx.ycbcrSampler()) {
            LOGGER.debug(IT, "YCbCr sampler path declined for {}: samplerYcbcrConversion feature off", this.format);
            return;
        }
        // THE SAMPLER ONLY EXPANDS THE RANGE AND APPLIES THE MATRIX: PQ, HLG AND BT.2020 PRIMARIES NEED THE COMPUTE display()
        final Colorimetry color = this.color;
        if (color.transfer() != Colorimetry.Transfer.SDR || color.wide()) {
            LOGGER.debug(IT, "YCbCr sampler path declined for {}: {} transfer{} needs the compute display mapping",
                    this.format, color.transfer(), color.wide() ? " with BT.2020 primaries" : "");
            return;
        }
        // THE IMAGE IS SAMPLED BY THE TRIVIAL PASS AND FILLED VIA BUFFER->IMAGE COPIES
        final int feats = this.kit.features(fmt);
        final int need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        if ((feats & need) != need) {
            LOGGER.debug(IT, "YCbCr sampler path declined for {}: format {} unsupported (optimalTilingFeatures=0x{})",
                    this.format, fmt, Integer.toHexString(feats));
            return;
        }
        // A SUBSAMPLED AXIS NEEDS THE FRAME'S OWN SITING ADVERTISED; A FULL-RESOLUTION AXIS IGNORES IT BUT STILL TAKES AN
        // ADVERTISED VALUE, SO A FORMAT WITHOUT EITHER BIT HAS NO VALID OFFSET AT ALL
        final boolean mid = (feats & VK_FORMAT_FEATURE_MIDPOINT_CHROMA_SAMPLES_BIT) != 0;
        final boolean cos = (feats & VK_FORMAT_FEATURE_COSITED_CHROMA_SAMPLES_BIT) != 0;
        final PlaneSpec chroma = this.planeSpecs[1];
        final boolean cosX = chroma.w < this.width ? color.cositedX() : !mid;
        final boolean cosY = chroma.h < this.height ? color.cositedY() : !mid;
        if ((cosX || cosY) && !cos || (!cosX || !cosY) && !mid) {
            LOGGER.debug(IT, "YCbCr sampler path declined for {}: format {} lacks chroma siting x {} y {} (optimalTilingFeatures=0x{})",
                    this.format, fmt, cosX ? "cosited" : "midpoint", cosY ? "cosited" : "midpoint", Integer.toHexString(feats));
            return;
        }
        this.ycbcrFmt = fmt;
        this.ycbcrX = cosX ? VK_CHROMA_LOCATION_COSITED_EVEN : VK_CHROMA_LOCATION_MIDPOINT;
        this.ycbcrY = cosY ? VK_CHROMA_LOCATION_COSITED_EVEN : VK_CHROMA_LOCATION_MIDPOINT;
        // LINEAR ONLY WHEN THE FORMAT ADVERTISES YCBCR LINEAR CHROMA RECONSTRUCTION (VUID-VkSamplerYcbcrConversionCreateInfo-chromaFilter-01657)
        // AND PLAIN LINEAR SAMPLING (VUID-vkCmdDispatch-magFilter-04553); min/mag/chroma SHARE ONE FILTER, AS WITHOUT SEPARATE
        // RECONSTRUCTION SUPPORT THEY MUST BE EQUAL (VUID-VkSamplerCreateInfo-minFilter-01645)
        final int linNeed = VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                | VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT;
        this.ycbcrFilter = (feats & linNeed) == linNeed ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    }

    // ==========================================================================
    // SAMPLER-YCBCR OBJECTS (FORMAT-SPECIFIC, LAZY)
    // ==========================================================================
    // TAKES THE KIT'S CONVERSION FOR THE CURRENT LAYOUT ON FIRST USE AND ALLOCATES THIS ENGINE'S POOL + ONE SET PER SLOT ON
    // ITS LAYOUT. ONLY THE PRODUCER THREAD CALLS THIS, AND A FORMAT CHANGE DRAINED EVERYTHING FIRST
    private void ensureYcbcr() {
        if (this.ycbcr != null) return;
        final int model = switch (this.color.matrix()) {
            case BT601 -> VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601;
            case BT709 -> VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709;
            case BT2020 -> VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_2020;
        };
        final int range = this.color.full() ? VK_SAMPLER_YCBCR_RANGE_ITU_FULL : VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
        final Ycbcr y = this.kit.ycbcr(new YcbcrKey(this.ycbcrFmt, model, range, this.ycbcrX, this.ycbcrY, this.ycbcrFilter));
        this.ycbcr = y;
        try (final MemoryStack stack = stackPush()) {
            // A YCBCR SAMPLER MAY COST SEVERAL COMBINED-SAMPLER DESCRIPTORS (RADV, MALI): THE POOL RESERVES WHAT THE DRIVER REPORTS
            final VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SLOTS * y.perSet);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(SLOTS);
            final VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(SLOTS).pPoolSizes(sizes);
            final LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorPool(this.device, dpci, null, p), "create ycbcr descriptor pool");
            this.ycbcrPool = p.get(0);

            final LongBuffer layouts = stack.mallocLong(SLOTS);
            for (int i = 0; i < SLOTS; i++) layouts.put(i, y.layout);
            final VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(this.ycbcrPool).pSetLayouts(layouts);
            final LongBuffer pSets = stack.mallocLong(SLOTS);
            check(vkAllocateDescriptorSets(this.device, dsai, pSets), "alloc ycbcr descriptor sets");
            for (int i = 0; i < SLOTS; i++) this.slots[i].ycbcrSet = pSets.get(i);
        }
    }

    // CALLERS GUARANTEE THE ENGINE'S OWN WORK IS DRAINED. THE POOL DIES BEFORE THE KIT MAY DESTROY THE LAYOUT AND THE
    // IMMUTABLE SAMPLER ITS SETS REFERENCE
    private void destroyYcbcrRes() {
        for (final Slot s: this.slots) s.ycbcrSet = 0L;
        if (this.ycbcrPool != 0L) { vkDestroyDescriptorPool(this.device, this.ycbcrPool, null); this.ycbcrPool = 0L; } // FREES SETS
        if (this.ycbcr != null) { this.kit.release(this.ycbcr); this.ycbcr = null; }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // THE PLANES SHARE ONE MULTIPLANAR IMAGE OR EACH HAS ITS OWN (ff_vk_count_images)
    private static int images(final AVVkFrame f) {
        int images = 0;
        while (images < MAX_PLANES && f.img(images) != 0L) images++;
        return images;
    }

    // ONE IMAGE DESCRIPTOR WRITE OF A SET'S BINDING (A COMBINED SAMPLER OR A STORAGE IMAGE)
    private static void write(final VkWriteDescriptorSet write, final MemoryStack stack, final long set, final int binding,
                              final int type, final long sampler, final long view, final int layout) {
        final VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).sampler(sampler).imageView(view).imageLayout(layout);
        write.sType$Default().dstSet(set).dstBinding(binding).dstArrayElement(0).descriptorCount(1).descriptorType(type).pImageInfo(info);
    }

    // PLANE FORMATS PER SAMPLE DEPTH: 8-BIT UNORM, 16-BIT UNORM FOR 10/12/16 (bitScale RESCALES), 32-BIT FLOAT
    private static int redFormat(final int bits) {
        return bits == 32 ? VK_FORMAT_R32_SFLOAT : (bits > 8 ? VK_FORMAT_R16_UNORM : VK_FORMAT_R8_UNORM);
    }

    private static int redGreenFormat(final int bits) {
        return bits == 32 ? VK_FORMAT_R32G32_SFLOAT : (bits > 8 ? VK_FORMAT_R16G16_UNORM : VK_FORMAT_R8G8_UNORM);
    }

    // COMPILES A BUNDLED SHADER TO SPIR-V VIA shaderc. RUNS ONCE PER PROCESS PER SHADER: CALLERS CACHE THE
    // (DEVICE-INDEPENDENT) BYTECODE IN convertSpirv/ycbcrSpirv UNDER THE CLASS MONITOR, SO CREATING MANY
    // ENGINES NEVER RECOMPILES; shaderc COMPILATION IS THE EXPENSIVE STEP.
    private static byte[] compileSpirv(final String name) {
        long compiler = 0L;
        long result = 0L;
        try {
            final String glsl = IOTool.shader(name);
            compiler = shaderc_compiler_initialize();
            if (compiler == 0L) throw new IllegalStateException("VKEngine: shaderc_compiler_initialize failed");
            result = shaderc_compile_into_spv(compiler, glsl, shaderc_compute_shader, name, "main", 0L);
            if (result == 0L) throw new IllegalStateException("VKEngine: shaderc compile returned null");
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                throw new IllegalStateException("VKEngine: shader compile failed: " + shaderc_result_get_error_message(result));
            final ByteBuffer spv = shaderc_result_get_bytes(result);
            if (spv == null) throw new IllegalStateException("VKEngine: shaderc produced no SPIR-V");
            final byte[] bytes = new byte[spv.remaining()];
            spv.duplicate().get(bytes);
            return bytes;
        } catch (final IOException e) {
            // A MISSING FILE, A MISSING shaderc OR A BROKEN SHADER NEVER RECOVERS: DECLINE CONVERSION LAYOUTS FOR THE REST OF THE PROCESS
            convertBroken = true;
            throw new IllegalStateException("VKEngine: cannot read the shader " + name, e);
        } catch (final RuntimeException | LinkageError e) {
            convertBroken = true;
            throw e;
        } finally {
            if (result != 0L) shaderc_result_release(result);
            if (compiler != 0L) shaderc_compiler_release(compiler);
        }
    }

    private static void check(final int result, final String op) {
        if (result != VK_SUCCESS) throw new IllegalStateException("VKEngine: " + op + " failed (VkResult " + result + ")");
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // WHAT EVERY ENGINE ON ONE DEVICE SHARES: THE PLANE SAMPLER, THE COMPUTE SET AND PIPELINE LAYOUTS AND PIPELINE, ONE
    // YCBCR CONVERSION + PIPELINE PER KEY AND THE FORMAT-FEATURE QUERIES. ENGINES LEAVE IT ONLY AFTER THEIR WORK DRAINED
    private static final class Kit {
        private final VkDevice device;
        private final VkPhysicalDevice physical;
        long sampler;
        long descLayout;
        long pipeLayout;
        private volatile long pipeline; // BUILT ON THE DEVICE'S FIRST CONVERSION FRAME
        private final Map<YcbcrKey, Ycbcr> ycbcrs = new HashMap<>(); // GUARDED BY this
        private final Map<Integer, Integer> formatFeatures = new ConcurrentHashMap<>(); // vkFormat -> optimalTilingFeatures
        private int engines; // GUARDED BY KITS
        // THE FFMPEG DEVICE CONTEXT OVER THIS VkDevice OR ITS TWIN, BUILT ON THE FIRST DESCRIBED REQUEST AND SHARED BY EVERY PLAYER, WITH THE
        // DECODE OPERATIONS IT COVERS; ITS OWN LOCK, SO BUILDING A TWIN NEVER STALLS THE RENDER THREAD ON THE KIT MONITOR
        private final Object hwLock = new Object();
        private AVBufferRef hwDevice;
        private boolean hwDeviceTried;
        private int decodeOps;
        volatile boolean placebo; // THE DEVICE CONTEXT RUNS LIBPLACEBO (SEE context)
        // THE TWIN FFMPEG DECODES ON WHEN THIS DEVICE GIVES IT NO QUEUES (SEE buildHwDevice), NULL OTHERWISE; READ BY THE ENGINES
        volatile Twin twin;

        private Kit(final VkDevice device, final VkPhysicalDevice physical) {
            this.device = device;
            this.physical = physical;
            try (final MemoryStack stack = stackPush()) {
                // SAMPLER (LINEAR, CLAMP): PLANAR/NV CHROMA UPSAMPLING; PACKED YUV USES texelFetch.
                final VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK)
                        .unnormalizedCoordinates(false);
                final LongBuffer p = stack.mallocLong(1);
                check(vkCreateSampler(device, sci, null, p), "create sampler");
                this.sampler = p.get(0);

                // DESCRIPTOR SET LAYOUT: 4 COMBINED IMAGE SAMPLERS (PLANES) + 1 STORAGE IMAGE (OUTPUT).
                final VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(5, stack);
                for (int j = 0; j < MAX_PLANES; j++) {
                    binds.get(j).binding(j).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                }
                binds.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                final VkDescriptorSetLayoutCreateInfo dlci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
                check(vkCreateDescriptorSetLayout(device, dlci, null, p), "create descriptor set layout");
                this.descLayout = p.get(0);

                // PIPELINE LAYOUT (DESCRIPTOR SET + PUSH CONSTANTS).
                final VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
                pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
                final VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                        .pSetLayouts(stack.longs(this.descLayout)).pPushConstantRanges(pcr);
                check(vkCreatePipelineLayout(device, plci, null, p), "create pipeline layout");
                this.pipeLayout = p.get(0);
            } catch (final RuntimeException | Error e) {
                this.destroy();
                throw e;
            }
        }

        // ======================================================================
        // INTERNAL API
        // ======================================================================
        static Kit acquire(final VKContext ctx) {
            synchronized (KITS) {
                final Kit kit = KITS.computeIfAbsent(ctx.vkDevice(), device -> new Kit(device, ctx.physicalDevice()));
                kit.engines++;
                return kit;
            }
        }

        // THE LAST ENGINE OUT ALREADY LEFT ITS YCBCR CONVERSIONS, SO ONLY THE DEVICE-WIDE OBJECTS REMAIN
        void release() {
            synchronized (KITS) {
                if (--this.engines > 0) return;
                KITS.remove(this.device, this);
            }
            this.destroy();
        }

        // A NEW REFERENCE TO THE FFMPEG DEVICE CONTEXT WHEN THE DEVICE DECODES codecId, ELSE NULL; THE DEVICE IS JUDGED ONCE DESCRIBED
        AVBufferRef hwDevice(final VKContext ctx, final int codecId) {
            final int operation = switch (codecId) {
                case AV_CODEC_ID_H264 -> VK_VIDEO_CODEC_OPERATION_DECODE_H264_BIT_KHR;
                case AV_CODEC_ID_HEVC -> VK_VIDEO_CODEC_OPERATION_DECODE_H265_BIT_KHR;
                case AV_CODEC_ID_AV1 -> VK_VIDEO_CODEC_OPERATION_DECODE_AV1_BIT_KHR;
                default -> 0;
            };
            if (operation == 0) return null;
            synchronized (this.hwLock) {
                if (!this.hwDeviceTried && ctx.deviceInfo() != null) {
                    this.hwDeviceTried = true;
                    try {
                        this.hwDevice = this.buildHwDevice(ctx);
                    } catch (final RuntimeException | LinkageError e) {
                        LOGGER.warn(IT, "FFmpeg cannot decode on this Vulkan device", e);
                    }
                }
                return this.hwDevice == null || (this.decodeOps & operation) == 0 ? null : av_buffer_ref(this.hwDevice);
            }
        }

        // PIPELINES ARE BUILT OUTSIDE THE KIT MONITOR, SO A RELEASE ON THE RENDER THREAD NEVER WAITS FOR A DRIVER COMPILE; A
        // RACING BUILD OF THE SAME PIPELINE LOSES AND DESTROYS ITS COPY
        long pipeline() {
            final long built = this.pipeline;
            if (built != 0L) return built;
            if (convertSpirv == null) {
                synchronized (VKEngine.class) {
                    if (convertSpirv == null) convertSpirv = compileSpirv(CONVERT_SHADER);
                }
            }
            final long fresh = this.compute(convertSpirv, this.pipeLayout);
            final long kept;
            synchronized (this) {
                if (this.pipeline == 0L) this.pipeline = fresh;
                kept = this.pipeline;
            }
            if (kept != fresh) vkDestroyPipeline(this.device, fresh, null);
            else LOGGER.debug(IT, "Built the conversion pipeline for device 0x{}", Long.toHexString(this.device.address()));
            return kept;
        }

        Ycbcr ycbcr(final YcbcrKey key) {
            synchronized (this) {
                final Ycbcr cached = this.ycbcrs.get(key);
                if (cached != null) {
                    cached.refs++;
                    return cached;
                }
            }
            final Ycbcr fresh = this.build(key);
            final Ycbcr kept;
            synchronized (this) {
                final Ycbcr raced = this.ycbcrs.putIfAbsent(key, fresh);
                kept = raced != null ? raced : fresh;
                kept.refs++;
            }
            if (kept != fresh) destroy(this.device, fresh);
            else LOGGER.debug(IT, "Built the YCbCr pipeline for vkFormat {} on device 0x{}", key.format(), Long.toHexString(this.device.address()));
            return kept;
        }

        void release(final Ycbcr y) {
            synchronized (this) {
                if (--y.refs > 0) return;
                this.ycbcrs.remove(y.key, y);
            }
            destroy(this.device, y);
        }

        // OPTIMAL-TILING FEATURES OF A FORMAT, QUERIED ONCE PER DEVICE AND FORMAT
        int features(final int vkFormat) {
            return this.formatFeatures.computeIfAbsent(vkFormat, fmt -> {
                try (final MemoryStack stack = stackPush()) {
                    final VkFormatProperties props = VkFormatProperties.malloc(stack);
                    vkGetPhysicalDeviceFormatProperties(this.physical, fmt, props);
                    return props.optimalTilingFeatures();
                }
            });
        }

        // ======================================================================
        // HELPERS
        // ======================================================================
        private void destroy() {
            if (this.pipeline != 0L) vkDestroyPipeline(this.device, this.pipeline, null);
            if (this.pipeLayout != 0L) vkDestroyPipelineLayout(this.device, this.pipeLayout, null);
            if (this.descLayout != 0L) vkDestroyDescriptorSetLayout(this.device, this.descLayout, null);
            if (this.sampler != 0L) vkDestroySampler(this.device, this.sampler, null);
            // PLAYERS KEEP THEIR OWN REFERENCES, SO THE CONTEXT (AND A TWIN) ONLY GOES WHEN THE LAST OF THEM IS GONE TOO
            synchronized (this.hwLock) {
                if (this.hwDevice != null) av_buffer_unref(this.hwDevice);
                this.hwDevice = null;
            }
        }

        // THE DEVICE FFMPEG DECODES ON: THE CONSUMER'S OWN WHEN IT CREATED QUEUES FOR FFMPEG, ELSE A TWIN WHOSE FRAMES THE ENGINES IMPORT;
        // BOTH NEED THE VIDEO EXTENSIONS, TIMELINE SEMAPHORES, synchronization2 AND VULKAN 1.3
        private AVBufferRef buildHwDevice(final VKContext ctx) {
            final VKContext.DeviceInfo info = ctx.deviceInfo();
            if (info == null || !CodecsAPI.vulkanDecode()) return null;
            if (!info.deviceExtensions().contains(VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME)) {
                LOGGER.debug(IT, "Vulkan decoding off: the device lacks {}", VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME);
                return null;
            }
            // FFMPEG WAITS ON TIMELINE SEMAPHORES AND SUBMITS WITH synchronization2 WITHOUT READING THE CHAIN, SO BOTH MUST BE ENABLED
            final int enabledFeatures = features(info.features());
            if ((enabledFeatures & DECODE_FEATURES) != DECODE_FEATURES) {
                LOGGER.debug(IT, "Vulkan decoding off: the device was created without timeline semaphores or synchronization2 (timeline={}, synchronization2={})",
                        (enabledFeatures & FEATURE_TIMELINE) != 0, (enabledFeatures & FEATURE_SYNC2) != 0);
                return null;
            }
            // THE CODEC EXTENSIONS ENABLED ON THE DEVICE BOUND WHAT ITS DECODE FAMILIES ADVERTISE
            int enabled = 0;
            if (info.deviceExtensions().contains(VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME)) enabled |= VK_VIDEO_CODEC_OPERATION_DECODE_H264_BIT_KHR;
            if (info.deviceExtensions().contains(VK_KHR_VIDEO_DECODE_H265_EXTENSION_NAME)) enabled |= VK_VIDEO_CODEC_OPERATION_DECODE_H265_BIT_KHR;
            if (info.deviceExtensions().contains(VK_KHR_VIDEO_DECODE_AV1_EXTENSION_NAME)) enabled |= VK_VIDEO_CODEC_OPERATION_DECODE_AV1_BIT_KHR;
            try (final MemoryStack stack = stackPush()) {
                final VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(this.physical, props);
                final int api = Math.min(ctx.vkInstance().getCapabilities().apiVersion, props.apiVersion());
                if (api < VK_API_VERSION_1_3) {
                    LOGGER.debug(IT, "Vulkan decoding off: FFmpeg needs a Vulkan 1.3 instance and device");
                    return null;
                }
                final IntBuffer count = stack.mallocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties2(this.physical, count, null);
                final int families = count.get(0);
                final VkQueueFamilyVideoPropertiesKHR.Buffer video = VkQueueFamilyVideoPropertiesKHR.calloc(families, stack);
                final VkQueueFamilyProperties2.Buffer properties = VkQueueFamilyProperties2.calloc(families, stack);
                for (int i = 0; i < families; i++) properties.get(i).sType$Default().pNext(video.get(i).sType$Default());
                vkGetPhysicalDeviceQueueFamilyProperties2(this.physical, count, properties);

                // THE CONSUMER CREATED QUEUES FOR FFMPEG: IT DECODES ON THE CONSUMER'S OWN DEVICE
                final Queues lent = queues(Arrays.copyOf(info.queueCounts(), families), ctx.queueFamily(), properties, video, enabled);
                if (lent.decodes()) return this.context(ctx, info, this.device, lent, props, null);

                // OTHERWISE ON A TWIN: THE CONSUMER'S FEATURE CHAIN AND EXTENSIONS ON A DEVICE OF ITS OWN, SO FFMPEG'S IMAGES TAKE ONLY CREATE
                // PARAMETERS THE CONSUMER'S DEVICE CAN REPEAT; THEY CROSS AS OPAQUE HANDLES AND MUST BE CONCURRENT (TWO FAMILIES OR MORE)
                final boolean win32 = Platform.get() == Platform.WINDOWS;
                final String memory = win32 ? VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME : VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
                final String semaphore = win32 ? VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME : VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
                final VKCapabilitiesDevice caps = this.device.getCapabilities();
                final boolean bound = win32 ? caps.VK_KHR_external_memory_win32 && caps.VK_KHR_external_semaphore_win32
                        : caps.VK_KHR_external_memory_fd && caps.VK_KHR_external_semaphore_fd;
                // FFMPEG EXPORTS ITS TIMELINE SEMAPHORES ONLY WHEN THIS QUERY CALLS THEM EXPORTABLE, AND THIS DEVICE MUST IMPORT THEM
                final VkExternalSemaphoreProperties semaphores = VkExternalSemaphoreProperties.calloc(stack).sType$Default();
                vkGetPhysicalDeviceExternalSemaphoreProperties(this.physical, VkPhysicalDeviceExternalSemaphoreInfo.calloc(stack).sType$Default()
                        .handleType(win32 ? VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT : VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT), semaphores);
                final int crossing = VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT | VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT;
                // ONE QUEUE PER FAMILY DECODING USES (GRAPHICS, COMPUTE, DECODE): EACH LISTED FAMILY SHARES FFMPEG'S CONCURRENT IMAGES, SO ENCODE,
                // OPTICAL FLOW AND TRANSFER-ONLY FAMILIES STAY OUT. NONE IS KEPT BACK: THE ENGINES TAKE THE CONSUMER'S FAMILY WITH FFMPEG'S LOCK
                final int[] counts = new int[families];
                for (int i = 0; i < families; i++)
                    if ((properties.get(i).queueFamilyProperties().queueFlags() & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT | VK_QUEUE_VIDEO_DECODE_BIT_KHR)) != 0)
                        counts[i] = 1;
                final Queues spare = queues(counts, -1, properties, video, enabled);
                if (!info.deviceExtensions().contains(memory) || !info.deviceExtensions().contains(semaphore) || !bound
                        || (semaphores.externalSemaphoreFeatures() & crossing) != crossing || !spare.decodes() || spare.families().length < 2
                        || counts[ctx.queueFamily()] == 0) {
                    LOGGER.debug(IT, "Vulkan decoding off: the device leaves FFmpeg no compute, transfer and video decode queues (compute={}, transfer={}, "
                            + "decode operations=0x{}), and a twin needs {} and {} enabled, shareable semaphores and a GPU that decodes (operations=0x{})",
                            lent.compute(), lent.transfer(), Integer.toHexString(lent.operations()), memory, semaphore, Integer.toHexString(spare.operations()));
                    return null;
                }
                final VkDeviceQueueCreateInfo.Buffer created = VkDeviceQueueCreateInfo.calloc(spare.families().length, stack);
                for (int i = 0; i < spare.families().length; i++) {
                    final int family = spare.families()[i];
                    final FloatBuffer priorities = stack.mallocFloat(counts[family]);
                    for (int q = 0; q < counts[family]; q++) priorities.put(q, 1.0f);
                    created.get(i).sType$Default().queueFamilyIndex(family).pQueuePriorities(priorities);
                }
                final PointerBuffer names = stack.mallocPointer(info.deviceExtensions().size());
                for (final String name: info.deviceExtensions()) names.put(stack.UTF8(name));
                // THE CHAIN STARTS WITH A VkPhysicalDeviceFeatures2, SO IT GOES IN pNext AND pEnabledFeatures STAYS NULL
                final VKContext.DeviceInfo twinInfo = new VKContext.DeviceInfo(this.twinFeatures(info.features(), enabledFeatures),
                        info.instanceExtensions(), info.deviceExtensions(), info.queueCounts());
                final VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(twinInfo.features().address())
                        .pQueueCreateInfos(created).ppEnabledExtensionNames(names.flip());
                final PointerBuffer handle = stack.mallocPointer(1);
                final int result = vkCreateDevice(this.physical, dci, null, handle);
                if (result != VK_SUCCESS) {
                    LOGGER.warn(IT, "Vulkan decoding off: the twin device cannot be created (VkResult {})", result);
                    return null;
                }
                final VkDevice twin = new VkDevice(handle.get(0), this.physical, dci, api);
                final TwinFree free = new TwinFree(twin);
                HW_NATIVES.add(free);
                final AVBufferRef ref;
                try {
                    ref = this.context(ctx, twinInfo, twin, spare, props, free);
                } catch (final RuntimeException | LinkageError e) {
                    free.call(null);
                    throw e;
                }
                if (ref == null) {
                    free.call(null);
                    return null;
                }
                // THE ENGINES MOVE THE IMAGES ON THE QUEUE OF THE CONSUMER'S FAMILY, WHICH FFMPEG MAY ALSO SUBMIT TO UNDER ITS lock_queue
                vkGetDeviceQueue(twin, ctx.queueFamily(), 0, handle);
                final AVHWDeviceContext context = new AVHWDeviceContext(ref.data());
                this.twin = new Twin(twin, context, new AVVulkanDeviceContext(context.hwctx()), new VkQueue(handle.get(0), twin), ctx.queueFamily(),
                        spare.families(), win32);
                return ref;
            }
        }

        // HANDS FFMPEG A DEVICE AS hwcontext_vulkan.h DESCRIBES IT: THE HANDLES, THE LOADER ENTRY POINT, THE CONSUMER'S FEATURE CHAIN AND
        // EXTENSIONS (A TWIN WAS CREATED WITH THEM TOO) AND THE QUEUE FAMILIES; A TWIN'S CONTEXT DESTROYS THE TWIN WHEN FFMPEG FREES IT
        private AVBufferRef context(final VKContext ctx, final VKContext.DeviceInfo info, final VkDevice device, final Queues queues,
                                    final VkPhysicalDeviceProperties props, final TwinFree free) {
            final AVBufferRef ref = av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_VULKAN);
            if (FFTool.isNull(ref)) return null;
            final AVHWDeviceContext hw = new AVHWDeviceContext(ref.data());
            if (free != null) hw._free(free);
            final AVVulkanDeviceContext vk = new AVVulkanDeviceContext(hw.hwctx());
            vk.get_proc_addr(VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"));
            vk.inst(ctx.vkInstance().address()).phys_dev(this.physical.address()).act_dev(device.address());
            // THE EMBEDDED VkPhysicalDeviceFeatures2 TAKES THE CONSUMER'S COPY; ITS pNext CHAIN STAYS THE CONSUMER'S, KEPT ALIVE BY IT
            memCopy(info.features().address(), vk.device_features().address(), VkPhysicalDeviceFeatures2.SIZEOF);
            vk.enabled_inst_extensions(strings(info.instanceExtensions())).nb_enabled_inst_extensions(info.instanceExtensions().size());
            vk.enabled_dev_extensions(strings(info.deviceExtensions())).nb_enabled_dev_extensions(info.deviceExtensions().size());
            for (int i = 0; i < queues.entries().size(); i++) {
                final int[] entry = queues.entries().get(i);
                vk.qf(i).idx(entry[0]).num(entry[1]).flags(entry[2]).video_caps(entry[3]);
            }
            vk.nb_qf(queues.entries().size());
            final int result = av_hwdevice_ctx_init(ref);
            if (result < 0) {
                LOGGER.warn(IT, "FFmpeg refused the Vulkan device: {}", FFTool.error(result));
                av_buffer_unref(ref);
                return null;
            }
            this.decodeOps = queues.operations();
            // LIBPLACEBO REQUIRES hostQueryReset, FFMPEG'S FILTER SHADERS THE REST OF FILTER_FEATURES, AND LIBPLACEBO SUBMITS TO A GRAPHICS QUEUE
            // THAT MUST NOT BE THE CONSUMER'S RENDER QUEUE
            this.placebo = (features(info.features()) & FILTER_FEATURES) == FILTER_FEATURES && queues.graphics() && !FFTool.isNull(avfilter_get_by_name("libplacebo"));
            final StringBuilder families = new StringBuilder();
            for (final int[] entry: queues.entries())
                families.append(families.isEmpty() ? "" : ", ").append(entry[0]).append('x').append(entry[1]).append(" flags=0x").append(Integer.toHexString(entry[2]));
            LOGGER.info(IT, "FFmpeg decodes on {} {} (decode operations 0x{}, queue families {}, libplacebo {})", free == null ? "the Vulkan device" : "a twin of the Vulkan device",
                    props.deviceNameString(), Integer.toHexString(queues.operations()), families, this.placebo ? "on" : "off");
            return ref;
        }

        // THE CONSUMER'S CHAIN, OR WHEN IT LACKS FILTER_FEATURES THE GPU HAS, A COPY WITH THEM: FEATURES NEVER CHANGE HOW IMAGES ARE CREATED, SO POOLS STAY
        // IMPORTABLE. THE COPY KEEPS THE CORE AND VULKAN 1.1-1.3 FEATURES, AND FFMPEG READS IT WHILE THE TWIN LIVES, SO IT IS NEVER FREED
        private VkPhysicalDeviceFeatures2 twinFeatures(final VkPhysicalDeviceFeatures2 host, final int enabled) {
            if ((enabled & FILTER_FEATURES) == FILTER_FEATURES) return host;
            try (final MemoryStack stack = stackPush()) {
                final VkPhysicalDeviceVulkan13Features offered13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
                final VkPhysicalDeviceVulkan12Features offered12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().pNext(offered13.address());
                vkGetPhysicalDeviceFeatures2(this.physical, VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(offered12.address()));
                if (!offered12.hostQueryReset() || !offered12.runtimeDescriptorArray() || !offered12.shaderStorageImageArrayNonUniformIndexing() || !offered13.maintenance4())
                    return host;
            }
            final VkPhysicalDeviceVulkan13Features v13 = VkPhysicalDeviceVulkan13Features.calloc().sType$Default();
            final VkPhysicalDeviceVulkan12Features v12 = VkPhysicalDeviceVulkan12Features.calloc().sType$Default().pNext(v13.address());
            final VkPhysicalDeviceVulkan11Features v11 = VkPhysicalDeviceVulkan11Features.calloc().sType$Default().pNext(v12.address());
            final VkPhysicalDeviceFeatures2 copy = VkPhysicalDeviceFeatures2.calloc().sType$Default().pNext(v11.address());
            memCopy(host.features().address(), copy.features().address(), VkPhysicalDeviceFeatures.SIZEOF);
            // THE 1.1-1.3 STRUCTURES FOLD IN AS THEY ARE AND THE PROMOTED ONES DECODING NEEDS FIELD BY FIELD (THOSE MAY NOT SIT NEXT TO THEM);
            // A STRUCTURE OF ANY OTHER TYPE HAS NO SIZE KNOWN HERE AND CANNOT BE COPIED
            final List<Integer> dropped = new ArrayList<>();
            for (VkBaseInStructure next = VkBaseInStructure.createSafe(host.pNext()); next != null; next = next.pNext()) {
                final long at = next.address();
                switch (next.sType()) {
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES -> merge(at, v11.address(), VkPhysicalDeviceVulkan11Features.SIZEOF);
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES -> merge(at, v12.address(), VkPhysicalDeviceVulkan12Features.SIZEOF);
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES -> merge(at, v13.address(), VkPhysicalDeviceVulkan13Features.SIZEOF);
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES ->
                            v11.samplerYcbcrConversion(v11.samplerYcbcrConversion() || VkPhysicalDeviceSamplerYcbcrConversionFeatures.create(at).samplerYcbcrConversion());
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES ->
                            v12.timelineSemaphore(v12.timelineSemaphore() || VkPhysicalDeviceTimelineSemaphoreFeatures.create(at).timelineSemaphore());
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES ->
                            v13.synchronization2(v13.synchronization2() || VkPhysicalDeviceSynchronization2Features.create(at).synchronization2());
                    default -> dropped.add(next.sType());
                }
            }
            v12.hostQueryReset(true).runtimeDescriptorArray(true).shaderStorageImageArrayNonUniformIndexing(true);
            v13.maintenance4(true);
            LOGGER.debug(IT, "The twin enables hostQueryReset, runtimeDescriptorArray, shaderStorageImageArrayNonUniformIndexing and maintenance4 for libplacebo{}",
                    dropped.isEmpty() ? "" : " and leaves out the consumer's feature structures of sType " + dropped);
            return copy;
        }

        // CONVERSION -> IMMUTABLE SAMPLER -> SET LAYOUT -> PIPELINE LAYOUT -> PIPELINE, PLUS THE DESCRIPTOR COST OF ITS IMAGES
        private Ycbcr build(final YcbcrKey key) {
            final Ycbcr y = new Ycbcr(key);
            try (final MemoryStack stack = stackPush()) {
                // THE LAYOUT'S MATRIX, RANGE AND SITING (CHECKED BY tryYcbcr), IDENTITY COMPONENTS (calloc) AND THE QUERIED
                // FILTER. NO FORCED EXPLICIT RECONSTRUCTION: LET THE IMPLEMENTATION PICK
                final VkSamplerYcbcrConversionCreateInfo cci = VkSamplerYcbcrConversionCreateInfo.calloc(stack).sType$Default()
                        .format(key.format()).ycbcrModel(key.model()).ycbcrRange(key.range())
                        .xChromaOffset(key.x()).yChromaOffset(key.y())
                        .chromaFilter(key.filter())
                        .forceExplicitReconstruction(false);
                final LongBuffer p = stack.mallocLong(1);
                check(vkCreateSamplerYcbcrConversion(this.device, cci, null, p), "create ycbcr conversion");
                y.conv = p.get(0);

                // SAMPLER: min/mag == chromaFilter (LEGAL WITHOUT SEPARATE-RECONSTRUCTION SUPPORT), AND
                // CLAMP/NO-ANISO/NO-COMPARE/NORMALIZED, ALL MANDATORY WITH A YCBCR CONVERSION.
                final VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .pNext(VkSamplerYcbcrConversionInfo.calloc(stack).sType$Default().conversion(y.conv))
                        .magFilter(key.filter()).minFilter(key.filter())
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK)
                        .unnormalizedCoordinates(false);
                check(vkCreateSampler(this.device, sci, null, p), "create ycbcr sampler");
                y.sampler = p.get(0);

                // SET LAYOUT: BINDING 0 = COMBINED IMAGE SAMPLER WITH THE IMMUTABLE YCBCR SAMPLER
                // (IMMUTABILITY IS MANDATORY FOR CONVERSION-ENABLED SAMPLING), BINDING 1 = STORAGE IMAGE.
                final VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
                binds.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .pImmutableSamplers(stack.longs(y.sampler));
                binds.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                final VkDescriptorSetLayoutCreateInfo dlci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
                check(vkCreateDescriptorSetLayout(this.device, dlci, null, p), "create ycbcr set layout");
                y.layout = p.get(0);

                // PIPELINE LAYOUT: THE YCBCR_PUSH_BYTES PUSH CONSTANTS { int width; int height; vec2 crop; vec2 origin; }
                final VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
                pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(YCBCR_PUSH_BYTES);
                final VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                        .pSetLayouts(stack.longs(y.layout)).pPushConstantRanges(pcr);
                check(vkCreatePipelineLayout(this.device, plci, null, p), "create ycbcr pipeline layout");
                y.pipeLayout = p.get(0);

                if (ycbcrSpirv == null) {
                    synchronized (VKEngine.class) {
                        if (ycbcrSpirv == null) ycbcrSpirv = compileSpirv(YCBCR_SHADER);
                    }
                }
                y.pipeline = this.compute(ycbcrSpirv, y.pipeLayout);

                final VkSamplerYcbcrConversionImageFormatProperties cost = VkSamplerYcbcrConversionImageFormatProperties.calloc(stack).sType$Default();
                final VkPhysicalDeviceImageFormatInfo2 image = VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default()
                        .format(key.format()).type(VK_IMAGE_TYPE_2D).tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                final int queried = vkGetPhysicalDeviceImageFormatProperties2(this.physical, image,
                        VkImageFormatProperties2.calloc(stack).sType$Default().pNext(cost.address()));
                y.perSet = queried == VK_SUCCESS ? Math.max(1, cost.combinedImageSamplerDescriptorCount()) : 1;
            } catch (final RuntimeException | LinkageError e) {
                destroy(this.device, y);
                throw e;
            }
            return y;
        }

        // A COMPUTE PIPELINE FROM CACHED SPIR-V; THE MODULE DIES RIGHT AFTER (LEGAL)
        private long compute(final byte[] spirv, final long layout) {
            try (final MemoryStack stack = stackPush()) {
                final ByteBuffer code = stack.malloc(spirv.length).put(spirv).flip();
                final VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
                final LongBuffer p = stack.mallocLong(1);
                check(vkCreateShaderModule(this.device, smci, null, p), "create shader module");
                final long module = p.get(0);
                final VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                        .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                final VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack);
                ci.get(0).sType$Default().stage(stage).layout(layout);
                final int r = vkCreateComputePipelines(this.device, 0, ci, null, p);
                vkDestroyShaderModule(this.device, module, null);
                check(r, "create compute pipeline");
                return p.get(0);
            }
        }

        // ======================================================================
        // UTILITY
        // ======================================================================
        // THE FAMILIES FFMPEG MAY USE ON A DEVICE WITH counts QUEUES PER FAMILY. IT TAKES THE FIRST ENTRY FITTING EACH ROLE, SO DEDICATED
        // TRANSFER AND COMPUTE FAMILIES GO FIRST AND THE CONSUMER'S FAMILY LAST, SHORT OF THE LAST QUEUE OF own (-1 KEEPS NONE BACK)
        private static Queues queues(final int[] counts, final int own, final VkQueueFamilyProperties2.Buffer properties,
                                     final VkQueueFamilyVideoPropertiesKHR.Buffer video, final int enabled) {
            final List<int[]> entries = new ArrayList<>();
            boolean compute = false, transfer = false, graphics = false;
            int operations = 0;
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] <= 0) continue;
                int flags = properties.get(i).queueFamilyProperties().queueFlags();
                if ((flags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) != 0) flags |= VK_QUEUE_TRANSFER_BIT; // IMPLIED BY THE SPEC
                int num = i == own ? counts[i] - 1 : counts[i];
                // THE RENDER QUEUE ALONE IN ITS FAMILY: A GRAPHICS-ONLY ENTRY FFMPEG NEVER SUBMITS TO, LISTED SO THE DECODED
                // IMAGES ARE SHARED WITH THE QUEUE THAT SAMPLES THEM; LIBPLACEBO WOULD SUBMIT TO IT, SO IT IS NO GRAPHICS QUEUE OF FFMPEG'S
                if (num == 0) {
                    num = 1;
                    flags = VK_QUEUE_GRAPHICS_BIT;
                } else graphics |= (flags & VK_QUEUE_GRAPHICS_BIT) != 0;
                final int caps = video.get(i).videoCodecOperations();
                compute |= (flags & VK_QUEUE_COMPUTE_BIT) != 0;
                transfer |= (flags & VK_QUEUE_TRANSFER_BIT) != 0;
                if ((flags & VK_QUEUE_VIDEO_DECODE_BIT_KHR) != 0) operations |= caps & enabled;
                final int rank = (flags & VK_QUEUE_GRAPHICS_BIT) != 0 ? 3 : (flags & VK_QUEUE_COMPUTE_BIT) != 0 ? 1
                        : (flags & VK_QUEUE_VIDEO_DECODE_BIT_KHR) != 0 ? 2 : 0;
                entries.add(new int[] { i, num, flags, caps, rank });
            }
            entries.sort(Comparator.comparingInt(entry -> entry[4]));
            // ONE ENTRY PER FAMILY, SO THE LIST ORDER IS THE ORDER FFMPEG SHARES ITS IMAGES IN (img_qfs)
            return new Queues(entries, compute, transfer, graphics, operations, entries.stream().mapToInt(entry -> entry[0]).toArray());
        }

        // THE DECODE_FEATURES AND FILTER_FEATURES A CREATE-INFO CHAIN ENABLES, IN ITS VULKAN 1.2/1.3 STRUCTURES OR THE ONES THOSE PROMOTED
        private static int features(final VkPhysicalDeviceFeatures2 chain) {
            int found = 0;
            for (VkBaseInStructure next = VkBaseInStructure.createSafe(chain.pNext()); next != null; next = next.pNext()) {
                final long at = next.address();
                found |= switch (next.sType()) {
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES -> {
                        final VkPhysicalDeviceVulkan12Features f = VkPhysicalDeviceVulkan12Features.create(at);
                        yield (f.timelineSemaphore() ? FEATURE_TIMELINE : 0) | (f.hostQueryReset() ? FEATURE_HOST_QUERY_RESET : 0)
                                | (f.runtimeDescriptorArray() ? FEATURE_RUNTIME_ARRAY : 0) | (f.shaderStorageImageArrayNonUniformIndexing() ? FEATURE_STORAGE_NON_UNIFORM : 0);
                    }
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES -> {
                        final VkPhysicalDeviceVulkan13Features f = VkPhysicalDeviceVulkan13Features.create(at);
                        yield (f.synchronization2() ? FEATURE_SYNC2 : 0) | (f.maintenance4() ? FEATURE_MAINTENANCE_4 : 0);
                    }
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_FEATURES -> {
                        final VkPhysicalDeviceDescriptorIndexingFeatures f = VkPhysicalDeviceDescriptorIndexingFeatures.create(at);
                        yield (f.runtimeDescriptorArray() ? FEATURE_RUNTIME_ARRAY : 0) | (f.shaderStorageImageArrayNonUniformIndexing() ? FEATURE_STORAGE_NON_UNIFORM : 0);
                    }
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES -> VkPhysicalDeviceTimelineSemaphoreFeatures.create(at).timelineSemaphore() ? FEATURE_TIMELINE : 0;
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES -> VkPhysicalDeviceSynchronization2Features.create(at).synchronization2() ? FEATURE_SYNC2 : 0;
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES -> VkPhysicalDeviceHostQueryResetFeatures.create(at).hostQueryReset() ? FEATURE_HOST_QUERY_RESET : 0;
                    case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_4_FEATURES -> VkPhysicalDeviceMaintenance4Features.create(at).maintenance4() ? FEATURE_MAINTENANCE_4 : 0;
                    default -> 0;
                };
            }
            return found;
        }

        // ORS THE VkBool32 FIELDS OF A FEATURE STRUCTURE INTO ANOTHER OF THE SAME TYPE, PAST THEIR sType AND pNext
        private static void merge(final long from, final long into, final int size) {
            for (int at = VkBaseInStructure.SIZEOF; at < size; at += Integer.BYTES) memPutInt(into + at, memGetInt(into + at) | memGetInt(from + at));
        }

        // A C STRING ARRAY FOR THE DEVICE CONTEXT, OR NULL FOR NO ENTRIES. FFMPEG RE-READS THE EXTENSION NAMES ON EVERY DECODER INIT AND
        // A PLAYER'S REFERENCE MAY OUTLIVE THE KIT, SO THE ARRAYS LIVE FOR THE PROCESS (A FEW HUNDRED BYTES PER DEVICE)
        private static PointerPointer<BytePointer> strings(final List<String> names) {
            if (names.isEmpty()) return null;
            final PointerPointer<BytePointer> array = new PointerPointer<>(names.size());
            for (int i = 0; i < names.size(); i++) {
                final BytePointer name = new BytePointer(names.get(i));
                HW_NATIVES.add(name);
                array.put(i, name);
            }
            HW_NATIVES.add(array);
            return array;
        }

        // THE SAMPLER AND CONVERSION DIE LAST: THE SET LAYOUT REFERENCES THE IMMUTABLE SAMPLER UNTIL IT IS GONE
        private static void destroy(final VkDevice device, final Ycbcr y) {
            if (y.pipeline != 0L) vkDestroyPipeline(device, y.pipeline, null);
            if (y.pipeLayout != 0L) vkDestroyPipelineLayout(device, y.pipeLayout, null);
            if (y.layout != 0L) vkDestroyDescriptorSetLayout(device, y.layout, null);
            if (y.sampler != 0L) vkDestroySampler(device, y.sampler, null);
            if (y.conv != 0L) vkDestroySamplerYcbcrConversion(device, y.conv, null);
        }
    }

    // DESTROYS A TWIN ONCE FFMPEG FREES ITS DEVICE CONTEXT, AFTER THE KIT AND THE LAST PLAYER LET GO OF IT, OR WHEN THE CONTEXT NEVER
    // CAME TO BE; THE ENGINES' TWIN OBJECTS ARE GONE BY THEN, AS THE KIT OUTLIVES THEM
    private static final class TwinFree extends AVHWDeviceContext.Free_AVHWDeviceContext {
        private final VkDevice device;
        private boolean destroyed;

        TwinFree(final VkDevice device) {
            this.device = device;
        }

        @Override
        public synchronized void call(final AVHWDeviceContext context) {
            if (this.destroyed) return;
            this.destroyed = true;
            vkDestroyDevice(this.device, null);
            LOGGER.debug(IT, "Destroyed the twin Vulkan device 0x{}", Long.toHexString(this.device.address()));
        }
    }

    // ONE KEY'S SHARED YCBCR OBJECTS; perSet IS THE COMBINED-SAMPLER DESCRIPTOR COST OF ONE SET ON layout
    private static final class Ycbcr {
        final YcbcrKey key;
        long conv, sampler, layout, pipeLayout, pipeline;
        int perSet;
        int refs; // GUARDED BY THE KIT

        Ycbcr(final YcbcrKey key) {
            this.key = key;
        }
    }

    // WHAT A YCBCR CONVERSION IS BUILT FROM: MULTIPLANAR FORMAT, MODEL, RANGE, CHROMA OFFSETS AND FILTER
    private record YcbcrKey(int format, int model, int range, int x, int y, int filter) {}

    // ONE TRIPLE-BUFFER SLOT: ITS OWN PLANE IMAGES, MANAGED IMAGE/VIEW, COMMAND BUFFER AND FENCE.
    private static final class Slot {
        VkCommandBuffer cmd;
        long fence;
        volatile long seq;       // MONOTONIC SUBMIT STAMP (0 = NEVER SUBMITTED). PUBLISHED TO texture()
        volatile boolean submitted;
        volatile long view;      // MANAGED IMAGE VIEW (RETURNED BY texture())
        long outImg, outMem;
        final long[] planeImg = new long[MAX_PLANES];
        final long[] planeMem = new long[MAX_PLANES];
        final long[] planeView = new long[MAX_PLANES];
        final Stage[] stage = new Stage[MAX_PLANES];
        long descSet;
        long ycbcrSet;           // SET FOR THE YCBCR LAYOUT (0 UNTIL THE YCBCR OBJECTS EXIST)
        long gen = -1L;          // FORMAT GENERATION THIS SLOT'S RESOURCES MATCH
        boolean external;        // BUILT FOR DECODED FRAMES: NO PLANE IMAGES, THE PLANES ARE BOUND PER FRAME
        AVFrame held;            // THE DECODED FRAME THE LAST SUBMISSION READS, REFERENCED UNTIL ITS FENCES SIGNAL
        VkCommandBuffer twinRelease, twinAcquire; // A TWIN'S IMAGE: ITS RELEASE BEFORE AND ITS ACQUIRE AFTER THIS DEVICE'S READ
        long twinFence;          // SIGNALED BY THE ACQUIRE, THE LAST STEP OF A TWIN'S FRAME
        boolean twinSubmitted;
    }

    // A PERSISTENT MAPPED HOST-VISIBLE STAGING BUFFER (FALLBACK COPY SOURCE).
    private record Stage(long buffer, long memory, long mapped, long size) {}

    // A HOST POINTER IMPORTED AS A VkBuffer (ZERO-COPY COPY SOURCE).
    private record Imported(long memory, long buffer, long size) {}

    // AN IMAGE + ITS BACKING MEMORY + ITS VIEW.
    private record Img(long image, long memory, long view) {}

    // WHAT THE ENGINE SAMPLES OF A DECODER IMAGE: THE IMAGE AND ITS VIEWS (BUILT PER LAYOUT). FOR A TWIN'S IMAGE THE IMAGE IS ITS COPY ON
    // THIS DEVICE, BOUND TO THE IMPORTED memory AND WAITED THROUGH THE IMPORTED semaphore; BOTH ARE 0 FOR AN IMAGE OF THIS DEVICE
    private record Picture(long image, long memory, long semaphore, long[] views) {}

    // A TWIN AS ENGINES IMPORT FROM IT: ITS DEVICE AND FFMPEG CONTEXTS, THE QUEUE 0 OF family THE ENGINES SHARE WITH FFMPEG UNDER ITS QUEUE
    // LOCK, THE FAMILIES FFMPEG SHARES ITS IMAGES WITH IN ITS OWN ORDER, AND WHETHER ITS OPAQUE HANDLES ARE WIN32 HANDLES OR FILE DESCRIPTORS
    private record Twin(VkDevice device, AVHWDeviceContext context, AVVulkanDeviceContext vk, VkQueue queue, int family, int[] families, boolean win32) {}

    // THE QUEUE FAMILIES FFMPEG MAY USE ON A DEVICE ({ idx, num, flags, video_caps, rank } IN ITS ORDER), THE ROLES THEY COVER (graphics ONLY
    // WITH A QUEUE OF ITS OWN), THE DECODE OPERATIONS THEIR VIDEO FAMILIES OFFER AND THE FAMILIES THEY LIST, ONCE EACH
    private record Queues(List<int[]> entries, boolean compute, boolean transfer, boolean graphics, int operations, int[] families) {
        boolean decodes() { return this.compute && this.transfer && this.operations != 0; }
    }

    // PER-PLANE GPU IMAGE DESCRIPTOR FOR THE CURRENT FORMAT.
    private static final class PlaneSpec {
        int format, texelBytes, w, h;
    }
}
