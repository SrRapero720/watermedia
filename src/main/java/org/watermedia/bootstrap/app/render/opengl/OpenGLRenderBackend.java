package org.watermedia.bootstrap.app.render.opengl;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.GLDebugMessageARBCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.api.player.GFXEngine;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.bootstrap.app.render.RenderBackend;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.tools.IOTool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.*;

/** OpenGL 3.2 core render backend of the app UI. */
public final class OpenGLRenderBackend implements RenderBackend {
    private static final Marker IT = MarkerManager.getMarker(OpenGLRenderBackend.class.getSimpleName());

    private static final int FLOATS_PER_VERTEX = 8;
    // MATCHES RenderEngine.MAX_VERTICES, WHICH VALIDATES DRAWS AGAINST THE SAME CAP; KEEP THEM IN SYNC
    private static final int MAX_VERTICES = 8_192;

    private final long window;
    private int program;
    private int projectionUniform;
    private int useTextureUniform;
    private int sdfProgram;
    private int sdfProjectionUniform;
    private int sdfCenterUniform;
    private int sdfHalfUniform;
    private int sdfRadiusUniform;
    private int sdfSoftnessUniform;
    private int sdfColorUniform;
    private final float[] softQuad = new float[6 * FLOATS_PER_VERTEX];
    private int vao;
    private int vbo;
    private FloatBuffer uploadBuffer;
    private FloatBuffer projectionBuffer;
    private boolean initialized;
    private boolean frameActive;            // BETWEEN beginFrame AND present THE PROGRAM, VAO AND VBO STAY BOUND
    private boolean projectionDirty = true; // RE-UPLOAD THE PROJECTION UNIFORM ONLY AFTER IT CHANGED
    private float boundUseTexture = -1f;    // LAST useTexture UNIFORM VALUE, SO EQUAL DRAWS SKIP RE-SETTING IT
    private String deviceName = "Unknown";
    private String deviceVersion = "";
    private int glVerMajor;
    private int glVerMinor;
    private GLDebugMessageARBCallback debugCallback; // RETAINED SO THE NATIVE CLOSURE OUTLIVES THE CALL; FREED IN cleanup()
    // TREE CACHE LAYERS: A FRAMEBUFFER AND COLOR TEXTURE EACH, SIZED TO THE VIEWPORT AND BUILT ON FIRST USE
    private final int[] layerFbo = new int[2];
    private final int[] layerTex = new int[2];
    private int layerW;
    private int layerH;
    private int viewW;
    private int viewH;
    private int layerEpoch;
    private int activeLayer = -1;
    private boolean layerFailed;
    // THE BOUND TEXTURE IS A LAYER, SAMPLED AS PREMULTIPLIED COLOR
    private boolean layerBound;
    private boolean scissorOn;

    public OpenGLRenderBackend(final long window) {
        this.window = window;
    }

    // ==========================================================================
    // PUBLIC API: CONTEXT / CAPABILITIES
    // ==========================================================================
    @Override
    public Supplier<GFXEngine> mediaEngineSupplier(final Thread renderThread, final Executor renderExecutor) {
        return () -> PlayerAPI.glEngine(renderThread, renderExecutor);
    }

    @Override
    public String deviceName() {
        return this.deviceName;
    }

    @Override
    public String deviceVersion() {
        return this.deviceVersion;
    }

    // ==========================================================================
    // PUBLIC API: PER-FRAME FLOW
    // ==========================================================================
    @Override
    public void beginFrame() {
        this.init();
        // THE UI PROGRAM, VAO AND VBO STAY BOUND FOR THE WHOLE FRAME; present() UNBINDS THEM FOR THE MEDIA
        // GLEngine, WHICH SHARES THIS CONTEXT
        GL20.glUseProgram(this.program);
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        this.frameActive = true;
        this.projectionDirty = true;
        this.boundUseTexture = -1f;
    }

    @Override
    public void present() {
        if (this.activeLayer >= 0) this.endLayer();
        if (this.frameActive) {
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
            this.frameActive = false;
        }
        glfwSwapBuffers(this.window);
    }

    // ==========================================================================
    // PUBLIC API: LIFECYCLE (SHADERS / BUFFERS)
    // ==========================================================================
    @Override
    public void init() {
        if (this.initialized) return;
        this.attachContext();

        final int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, "opengl/ui.vert");
        final int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, "opengl/ui.frag");

        this.program = GL20.glCreateProgram();
        GL20.glAttachShader(this.program, vertexShader);
        GL20.glAttachShader(this.program, fragmentShader);
        GL20.glBindAttribLocation(this.program, 0, "position");
        GL20.glBindAttribLocation(this.program, 1, "texCoord");
        GL20.glBindAttribLocation(this.program, 2, "vertColor");
        GL20.glLinkProgram(this.program);
        if (GL20.glGetProgrami(this.program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
            throw new IllegalStateException("OpenGL renderer link failed: " + GL20.glGetProgramInfoLog(this.program, 2_048));

        GL20.glDetachShader(this.program, vertexShader);
        GL20.glDetachShader(this.program, fragmentShader);
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);

        this.projectionUniform = GL20.glGetUniformLocation(this.program, "projection");
        this.useTextureUniform = GL20.glGetUniformLocation(this.program, "useTexture");

        GL20.glUseProgram(this.program);
        GL20.glUniform1i(GL20.glGetUniformLocation(this.program, "tex"), 0);
        GL20.glUseProgram(0);

        // SDF SOFT-RECT PROGRAM: GLOW AND SHADOW IN ONE PASS (SHARES THE VAO; READS ONLY THE position ATTRIB)
        final int sdfVertex = compileShader(GL20.GL_VERTEX_SHADER, "opengl/sdf.vert");
        final int sdfFragment = compileShader(GL20.GL_FRAGMENT_SHADER, "opengl/sdf.frag");
        this.sdfProgram = GL20.glCreateProgram();
        GL20.glAttachShader(this.sdfProgram, sdfVertex);
        GL20.glAttachShader(this.sdfProgram, sdfFragment);
        GL20.glBindAttribLocation(this.sdfProgram, 0, "position");
        GL20.glLinkProgram(this.sdfProgram);
        if (GL20.glGetProgrami(this.sdfProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
            throw new IllegalStateException("OpenGL SDF program link failed: " + GL20.glGetProgramInfoLog(this.sdfProgram, 2_048));
        GL20.glDetachShader(this.sdfProgram, sdfVertex);
        GL20.glDetachShader(this.sdfProgram, sdfFragment);
        GL20.glDeleteShader(sdfVertex);
        GL20.glDeleteShader(sdfFragment);
        this.sdfProjectionUniform = GL20.glGetUniformLocation(this.sdfProgram, "projection");
        this.sdfCenterUniform = GL20.glGetUniformLocation(this.sdfProgram, "uCenter");
        this.sdfHalfUniform = GL20.glGetUniformLocation(this.sdfProgram, "uHalf");
        this.sdfRadiusUniform = GL20.glGetUniformLocation(this.sdfProgram, "uRadius");
        this.sdfSoftnessUniform = GL20.glGetUniformLocation(this.sdfProgram, "uSoftness");
        this.sdfColorUniform = GL20.glGetUniformLocation(this.sdfProgram, "uColor");

        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) MAX_VERTICES * FLOATS_PER_VERTEX * Float.BYTES, GL15.GL_STREAM_DRAW);

        final int stride = FLOATS_PER_VERTEX * Float.BYTES;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2L * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 4L * Float.BYTES);
        GL30.glBindVertexArray(0);

        this.uploadBuffer = MemoryUtil.memAllocFloat(MAX_VERTICES * FLOATS_PER_VERTEX);
        this.projectionBuffer = MemoryUtil.memAllocFloat(16);
        // THE FIRST glViewport IS THE FRAMEBUFFER SIZE THE CONTEXT STARTED WITH, UNTIL viewport() REPLACES IT
        try (final MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer w = stack.mallocInt(1);
            final IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(this.window, w, h);
            this.viewW = w.get(0);
            this.viewH = h.get(0);
        }
        this.initialized = true;
    }

    @Override
    public void cleanup() {
        // THE DEBUG CLOSURE GOES FIRST, EVEN WHEN init() FAILED AFTER ATTACHING IT: A DRIVER CALLING A COLLECTED
        // CLOSURE AFTER THE CONTEXT CAN CRASH THE JVM
        if (this.debugCallback != null) {
            this.debugCallback.free();
            this.debugCallback = null;
        }
        if (!this.initialized) return;
        this.deleteLayers();
        GL20.glDeleteProgram(this.program);
        GL20.glDeleteProgram(this.sdfProgram);
        GL30.glDeleteVertexArrays(this.vao);
        GL15.glDeleteBuffers(this.vbo);
        MemoryUtil.memFree(this.uploadBuffer);
        MemoryUtil.memFree(this.projectionBuffer);
        this.initialized = false;
    }

    @Override
    public void configureFrameState() {
        GL11.glEnable(GL11.GL_BLEND);
        // ALPHA ACCUMULATES AS COVERAGE, SO A TRANSPARENT LAYER ENDS PREMULTIPLIED AND THE OPAQUE ONES STAY AT 1
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    @Override
    public void clear(final float r, final float g, final float b, final float a) {
        GL11.glClearColor(r, g, b, a);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    @Override
    public void viewport(final int width, final int height) {
        GL11.glViewport(0, 0, width, height);
        if (width == this.viewW && height == this.viewH) return;
        this.viewW = width;
        this.viewH = height;
        this.layerEpoch++;
    }

    // ==========================================================================
    // PUBLIC API: TREE CACHE LAYERS
    // ==========================================================================
    @Override
    public boolean beginLayer(final int index, final float r, final float g, final float b, final float a) {
        if (this.layerFailed || this.viewW <= 0 || this.viewH <= 0) return false;
        if (this.layerW != this.viewW || this.layerH != this.viewH) this.deleteLayers();
        if (this.layerFbo[index] == 0 && !this.createLayer(index)) return false;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.layerFbo[index]);
        this.activeLayer = index;
        // glClear HONORS THE SCISSOR, AND A LAYER OPENED INSIDE A CLIP MUST STILL CLEAR WHOLE
        if (this.scissorOn) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        this.clear(r, g, b, a);
        if (this.scissorOn) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        return true;
    }

    @Override
    public void endLayer() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        this.activeLayer = -1;
    }

    @Override
    public boolean bindLayer(final int index) {
        if (this.layerTex[index] == 0) return false;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.layerTex[index]);
        this.layerBound = true;
        return true;
    }

    @Override
    public boolean layerFlipped() {
        return true;
    }

    @Override
    public int layerEpoch() {
        return this.layerEpoch;
    }

    @Override
    public void releaseLayers() {
        if (this.layerFbo[0] == 0 && this.layerFbo[1] == 0) return;
        this.deleteLayers();
        this.layerEpoch++;
    }

    // ==========================================================================
    // PUBLIC API: TEXTURES
    // ==========================================================================
    @Override
    public TextureHandle createTexture(final int width, final int height, final ByteBuffer rgba) {
        this.init();

        final int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, width);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
        // RESTORE THE DEFAULT ROW LENGTH SO THE SHARED GL STATE DOES NOT MIS-FEED A LATER UPLOAD
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        return new TextureHandle(id, width, height);
    }

    @Override
    public void deleteTexture(final TextureHandle texture) {
        if (texture != null && texture.id() > 0) GL11.glDeleteTextures(texture.id());
    }

    @Override
    public void bindTexture(final int textureId) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        this.layerBound = false;
    }

    @Override
    public void useProjection(final Matrix4f projection) {
        this.init();
        this.projectionBuffer.clear();
        projection.get(this.projectionBuffer);
        this.projectionDirty = true;
    }

    // ==========================================================================
    // PUBLIC API: DRAWING
    // ==========================================================================
    @Override
    public void draw(final DrawMode mode, final float[] vertices, final int vertexCount, final boolean textured) {
        this.init();
        if (vertexCount <= 0) return;

        final int floats = vertexCount * FLOATS_PER_VERTEX;
        if (floats > this.uploadBuffer.capacity())
            throw new IllegalArgumentException("Too many UI vertices in one draw call: " + vertexCount);

        // OUTSIDE A FRAME (A FLUSH FROM createTexture DURING LOADING) BIND AND UNBIND LOCALLY;
        // INSIDE ONE beginFrame ALREADY BOUND THE PROGRAM, VAO AND VBO AND present() UNBINDS THEM
        final boolean standalone = !this.frameActive;
        if (standalone) {
            GL20.glUseProgram(this.program);
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
            this.projectionDirty = true;
            this.boundUseTexture = -1f;
        }

        if (this.projectionDirty) {
            GL20.glUniformMatrix4fv(this.projectionUniform, false, this.projectionBuffer);
            this.projectionDirty = false;
        }
        final float useTexture = !textured ? 0f : this.layerBound ? 2f : 1f;
        if (useTexture != this.boundUseTexture) {
            GL20.glUniform1f(this.useTextureUniform, useTexture);
            this.boundUseTexture = useTexture;
        }

        this.uploadBuffer.clear();
        this.uploadBuffer.put(vertices, 0, floats);
        this.uploadBuffer.flip();
        // ORPHAN AND UPLOAD IN ONE CALL: RESPECIFYING THE STORE HANDS BACK FRESH DRIVER MEMORY INSTEAD OF STALLING
        // ON THE PRIOR DRAW STILL READING THE SAME REGION
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, this.uploadBuffer, GL15.GL_STREAM_DRAW);

        final int glMode = switch (mode) {
            case TRIANGLES -> GL11.GL_TRIANGLES;
            case TRIANGLE_FAN -> GL11.GL_TRIANGLE_FAN;
            case LINES -> GL11.GL_LINES;
            case LINE_LOOP -> GL11.GL_LINE_LOOP;
        };
        GL11.glDrawArrays(glMode, 0, vertexCount);

        if (standalone) {
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
        }
    }

    @Override
    public boolean supportsSoftRect() {
        return true;
    }

    @Override
    public void softRect(final float x, final float y, final float w, final float h, final float radius,
                         final float r, final float g, final float b, final float a, final float softness) {
        this.init();
        final float soft = Math.max(0.5f, softness);
        final float x0 = x - soft;
        final float y0 = y - soft;
        final float x1 = x + w + soft;
        final float y1 = y + h + soft;
        // TWO TRIANGLES COVERING THE RECT PLUS THE SOFTNESS MARGIN; THE SDF SHADER READS ONLY position
        this.softVertex(0, x0, y0);
        this.softVertex(1, x1, y0);
        this.softVertex(2, x1, y1);
        this.softVertex(3, x0, y0);
        this.softVertex(4, x1, y1);
        this.softVertex(5, x0, y1);

        GL20.glUseProgram(this.sdfProgram);
        GL20.glUniformMatrix4fv(this.sdfProjectionUniform, false, this.projectionBuffer);
        GL20.glUniform2f(this.sdfCenterUniform, x + w / 2f, y + h / 2f);
        GL20.glUniform2f(this.sdfHalfUniform, w / 2f, h / 2f);
        GL20.glUniform1f(this.sdfRadiusUniform, Math.max(0f, radius));
        GL20.glUniform1f(this.sdfSoftnessUniform, soft);
        GL20.glUniform4f(this.sdfColorUniform, r, g, b, a);

        if (!this.frameActive) {
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        }
        this.uploadBuffer.clear();
        this.uploadBuffer.put(this.softQuad, 0, this.softQuad.length);
        this.uploadBuffer.flip();
        // ORPHAN AND UPLOAD, AS IN draw()
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, this.uploadBuffer, GL15.GL_STREAM_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

        if (this.frameActive) {
            // BACK TO THE UI PROGRAM FOR THE FOLLOWING BATCHED DRAWS, WITH ITS UNIFORMS RE-SET ON THE NEXT DRAW
            GL20.glUseProgram(this.program);
            this.projectionDirty = true;
            this.boundUseTexture = -1f;
        } else {
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
        }
    }

    @Override
    public void lineWidth(final float width) {
        GL11.glLineWidth(width);
    }

    @Override
    public void clip(final int x, final int y, final int width, final int height, final int canvasHeight) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, canvasHeight - y - height, width, height);
        this.scissorOn = true;
    }

    @Override
    public void clearClip() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        this.scissorOn = false;
    }

    // ==========================================================================
    // CONTEXT / CAPABILITIES
    // ==========================================================================
    private void attachContext() {
        // THE REAL CONTEXT IS PINNED TO 3.2 CORE, WHICH SOME DRIVERS (NVIDIA) REPORT VERBATIM RATHER THAN THE GPU MAX,
        // SO A THROWAWAY DEFAULT-HINTS CONTEXT PROBES THE DRIVER'S HIGHEST VERSION FIRST
        long probe = 0L;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            probe = glfwCreateWindow(1, 1, "wm-gl-probe", 0L, 0L);
            if (probe != 0L) {
                glfwMakeContextCurrent(probe);
                GL.createCapabilities();
                this.captureInfo();
            }
        } catch (final Throwable ignored) {
            // A FAILED PROBE ONLY LEAVES THE VERSION OF THE REAL CONTEXT
        } finally {
            // DEFAULT HINTS AGAIN SO LATER WINDOWS RE-HINT CLEANLY
            glfwMakeContextCurrent(0L);
            if (probe != 0L) glfwDestroyWindow(probe);
            glfwDefaultWindowHints();
        }

        glfwMakeContextCurrent(this.window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        // GPU NAME AND FALLBACK VERSION FROM THE REAL CONTEXT (captureInfo ONLY RAISES THE VERSION, NEVER LOWERS IT)
        this.captureInfo();
        // A REQUESTED DEBUG CONTEXT IS SILENCED WITH A RETAINED EMPTY CALLBACK; WITHOUT ONE THERE IS NOTHING TO
        // SILENCE AND ARB_debug_output MAY BE ABSENT
        if (RenderSystem.GL_DEBUG) {
            this.debugCallback = GLDebugMessageARBCallback.create((source, type, id, severity, length, message, userParam) -> {});
            ARBDebugOutput.glDebugMessageCallbackARB(this.debugCallback, 0);
        }
    }

    private void captureInfo() {
        // KEEPS THE HIGHEST CONTEXT VERSION SEEN ACROSS THE PROBE AND THE REAL CONTEXT
        try {
            final String renderer = GL11.glGetString(GL11.GL_RENDERER);
            if (renderer != null && !renderer.isBlank()) this.deviceName = renderer;
        } catch (final Throwable ignored) {}
        try {
            final int major = GL11.glGetInteger(GL30.GL_MAJOR_VERSION);
            final int minor = GL11.glGetInteger(GL30.GL_MINOR_VERSION);
            if (major > 0 && (major > this.glVerMajor || (major == this.glVerMajor && minor > this.glVerMinor))) {
                this.glVerMajor = major;
                this.glVerMinor = minor;
                this.deviceVersion = major + "." + minor;
            }
        } catch (final Throwable ignored) {}
    }

    // ==========================================================================
    // TREE CACHE LAYERS
    // ==========================================================================
    private boolean createLayer(final int index) {
        final int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, this.viewW, this.viewH, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        final int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        final int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        this.layerBound = false;
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(fbo);
            GL11.glDeleteTextures(texture);
            this.layerFailed = true;
            WaterMedia.LOGGER.warn(IT, "The tree cache framebuffer is incomplete (0x{}); the UI repaints whole while the CRT bands move",
                    Integer.toHexString(status));
            return false;
        }
        this.layerFbo[index] = fbo;
        this.layerTex[index] = texture;
        this.layerW = this.viewW;
        this.layerH = this.viewH;
        return true;
    }

    private void deleteLayers() {
        for (int i = 0; i < 2; i++) {
            if (this.layerFbo[i] != 0) GL30.glDeleteFramebuffers(this.layerFbo[i]);
            if (this.layerTex[i] != 0) GL11.glDeleteTextures(this.layerTex[i]);
            this.layerFbo[i] = 0;
            this.layerTex[i] = 0;
        }
    }

    // ==========================================================================
    // DRAWING
    // ==========================================================================
    private void softVertex(final int index, final float x, final float y) {
        final int off = index * FLOATS_PER_VERTEX;
        this.softQuad[off] = x;
        this.softQuad[off + 1] = y;
        this.softQuad[off + 2] = 0f;
        this.softQuad[off + 3] = 0f;
        this.softQuad[off + 4] = 0f;
        this.softQuad[off + 5] = 0f;
        this.softQuad[off + 6] = 0f;
        this.softQuad[off + 7] = 0f;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static int compileShader(final int type, final String name) {
        // THE UI CANNOT DRAW WITHOUT ITS BUNDLED SHADERS, SO ANY FAILURE ABORTS THE BACKEND
        final String source;
        try { source = IOTool.shader(name); }
        catch (final IOException e) { throw new IllegalStateException("OpenGL renderer shader unavailable: " + name, e); }
        final int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            final String log = GL20.glGetShaderInfoLog(shader, 2_048);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("OpenGL renderer shader " + name + " failed: " + log);
        }
        return shader;
    }
}
