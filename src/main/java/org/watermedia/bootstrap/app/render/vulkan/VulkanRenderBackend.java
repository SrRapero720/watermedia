package org.watermedia.bootstrap.app.render.vulkan;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.watermedia.WaterMedia;
import org.watermedia.api.player.GFXEngine;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.api.player.VKContext;
import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.render.RenderBackend;
import org.watermedia.tools.IOTool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.*;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.KHRPortabilitySubset.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.KHRVideoDecodeAV1.VK_KHR_VIDEO_DECODE_AV1_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeH264.VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeH265.VK_KHR_VIDEO_DECODE_H265_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeQueue.VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeQueue.VK_QUEUE_VIDEO_DECODE_BIT_KHR;
import static org.lwjgl.vulkan.KHRVideoQueue.VK_KHR_VIDEO_QUEUE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * Vulkan render backend of the app UI that owns the instance, device and swapchain, and doubles as the {@link VKContext}
 * the media {@code VKEngine} borrows; both submit to one queue, always under {@link #queueLock()}.
 */
public final class VulkanRenderBackend implements RenderBackend, VKContext {
    private static final Marker IT = MarkerManager.getMarker(VulkanRenderBackend.class.getSimpleName());

    private static final int FLOATS_PER_VERTEX = 8;          // pos.xy, uv.xy, color.rgba
    private static final int VERTEX_BYTES = FLOATS_PER_VERTEX * Float.BYTES;
    private static final int MAX_VERTICES = 8_192;           // INITIAL PER-FRAME VERTEX CAPACITY (GROWS ON DEMAND)
    private static final int MAX_FRAMES_IN_FLIGHT = 2;
    private static final int SETS_PER_POOL = 256;            // TEXTURED-DRAW DESCRIPTOR SETS PER PER-FRAME POOL CHUNK
    private static final int PUSH_BYTES = 68;                // mat4 proj (64) + int useTexture (4)
    // SDF PUSH: mat4 proj (64) + vec4 color (@64) + vec2 center (@80) + vec2 halfExt (@88) + float radius (@96)
    // + float softness (@100); vec4 BEFORE vec2 KEEPS THEM std430-ALIGNED
    private static final int PUSH_SDF_BYTES = 104;
    private static final long BLOCK = 0xFFFFFFFFFFFFFFFFL;   // UINT64_MAX: BLOCKING WAIT/ACQUIRE

    // CONTEXT (CREATED IN THE CONSTRUCTOR; SHARED WITH THE VKEngine)
    private final long window;
    private final Object queueLock = new Object();
    private VkInstance instance;
    private long surface;
    private VkPhysicalDevice physicalDevice;
    private String deviceName = "Unknown";
    private String deviceVersion = "";
    private VkDevice device;
    private VkQueue queue;
    private int queueFamily;
    private VkPhysicalDeviceMemoryProperties memProps; // PERSISTENT (NOT STACK)
    private boolean hostImport;
    private long minAlign;
    private boolean wideLines;
    private boolean ycbcrSampler;
    // WHAT THE DEVICE WAS CREATED WITH, LENT THROUGH VKContext#deviceInfo SO FFMPEG DECODES ON IT: THE FEATURE CHAIN
    // LIVES AS LONG AS THE DEVICE, THE EXTENSION LISTS AND QUEUE COUNTS ARE FIXED, AND THE RENDER QUEUE IS THE LAST OF
    // ITS FAMILY
    private VkPhysicalDeviceFeatures2 features;
    private VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeatures;
    private VkPhysicalDeviceVulkan12Features features12;
    private VkPhysicalDeviceVulkan13Features features13;
    private final List<String> instanceExtensions = new ArrayList<>();
    private final List<String> deviceExtensions = new ArrayList<>();
    private int[] queueCounts = new int[0];
    private String decodeCodecs = "off";

    // STATIC CORE RESOURCES (BUILT LAZILY ONCE IN ensureCore())
    private boolean coreReady;
    private int surfaceFormat;
    private int surfaceColorSpace;
    private long renderPass;
    private long sampler;
    private long descLayout;
    private long pipeLayout;
    private long pipeTriList, pipeLineList, pipeLineStrip;
    private long sdfPipeLayout; // NO DESCRIPTOR SET; LARGER PUSH RANGE FOR THE SOFT-RECT PARAMS
    private long sdfPipe;       // SDF SOFT-RECT PIPELINE (TRIANGLE LIST)
    private long cmdPool;       // PER-FRAME COMMAND BUFFERS
    private long uploadPool;    // ONE-TIME TEXTURE UPLOADS (SEPARATE SO MID-FRAME UPLOADS NEVER TOUCH THE FRAME POOL)
    private VkCommandBuffer uploadCmd;
    private long uploadFence;
    private long dummyImg, dummyMem, dummyView; // 1x1 WHITE BOUND FOR NON-TEXTURED DRAWS (SHADER STILL DECLARES THE SAMPLER)
    private long dummyPool, dummySet;
    private final Frame[] frames = new Frame[MAX_FRAMES_IN_FLIGHT];

    // SWAPCHAIN (REBUILT ON RESIZE / OUT-OF-DATE)
    private long swapchain;
    private int extentW, extentH;
    private long[] swapImages = new long[0];
    private long[] swapViews = new long[0];
    private long[] framebuffers = new long[0];
    private long[] renderFinished = new long[0]; // PER SWAPCHAIN IMAGE (AVOIDS PRESENT-WAIT SEMAPHORE REUSE HAZARDS)
    private long[] imagesInFlight = new long[0];  // FENCE OF THE FRAME LAST RENDERING TO EACH IMAGE (0 = NONE)

    // TREE CACHE LAYERS: SWAPCHAIN-SIZED IMAGES OF THE SURFACE FORMAT, SO THE UI PIPELINES DRAW INTO THEM UNCHANGED;
    // BUILT ON FIRST USE AND DROPPED WITH THE SWAPCHAIN
    private long layerPass;
    private boolean layerSupport;
    private final long[] layerImage = new long[2];
    private final long[] layerMemory = new long[2];
    private final long[] layerView = new long[2];
    private final long[] layerFramebuffer = new long[2];
    private int layerEpoch;
    private int activeLayer = -1;
    // THE BOUND VIEW IS A LAYER, SAMPLED AS PREMULTIPLIED COLOR
    private boolean layerBound;

    // TEXTURE REGISTRY (APP TEXTURES KEYED BY INT ID)
    private final Map<Integer, TextureRecord> textures = new HashMap<>();
    private int nextTextureId = 1;

    // FRAME STATE
    private final float[] proj = {1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f};
    private boolean clipEnabled;
    private int clipX, clipY, clipW, clipH;
    private float lineW = 1f;
    private float clearR, clearG, clearB, clearA;
    private long currentView; // VkImageView SAMPLED BY THE NEXT TEXTURED DRAW (APP OR ENGINE-OWNED)
    private int currentFrame;
    private int imageIndex;
    // MONOTONIC FRAME COUNTER AND DEFERRED-DESTRUCTION QUEUE: A RELEASED VKEngine HANDS ITS RESOURCES HERE
    // (VKContext#retire) AND THEY ARE DESTROYED ONLY AFTER THE FRAMES THAT MIGHT SAMPLE THEM HAVE RETIRED
    private volatile long frameCounter;
    private final Queue<Retire> retireQueue = new ConcurrentLinkedQueue<>();
    private boolean frameValid; // FALSE WHEN THE FRAME COULD NOT ACQUIRE AN IMAGE (MINIMIZED / OUT-OF-DATE)
    private boolean renderPassActive;
    // DYNAMIC-STATE BOOKKEEPING: SET ONCE PER CHANGE; A FRESH COMMAND BUFFER MARKS ALL DIRTY
    private boolean vpDirty, scDirty, lwDirty;
    private long boundPipeline, boundVbo, boundSet;
    // LAST TEXTURED-DRAW VIEW AND ITS DESCRIPTOR SET, SO CONSECUTIVE DRAWS SAMPLING THE SAME VIEW (ATLAS FLUSHES,
    // REPEATED BLITS) REUSE THE SET INSTEAD OF WRITING A NEW ONE
    private long boundView, boundTexSet;

    /** Creates the instance, surface and device for a GLFW window built with {@code GLFW_NO_API}; {@link #init()} builds the rest. */
    public VulkanRenderBackend(final long glfwWindow) {
        if (!glfwVulkanSupported()) throw new IllegalStateException("VulkanRenderBackend: GLFW reports Vulkan is unavailable");
        this.window = glfwWindow;
        try {
            this.createInstanceAndSurface();
            this.pickPhysicalDevice();
            this.createDeviceAndQueue();
        } catch (final Throwable t) {
            // cleanup() IS NULL-SAFE, SO A FAILED CONSTRUCTION NEVER LEAKS THE INSTANCE, SURFACE OR DEVICE INTO THE
            // OPENGL FALLBACK
            this.cleanup();
            throw t;
        }
        WaterMedia.LOGGER.info(IT, "VulkanRenderBackend device ready (queueFamily={}, hostImport={}, minAlign={}, wideLines={}, ycbcrSampler={}, videoDecode={})",
                this.queueFamily, this.hostImport, this.minAlign, this.wideLines, this.ycbcrSampler, this.decodeCodecs);
    }

    // ==========================================================================
    // PUBLIC API: LIFECYCLE
    // ==========================================================================
    @Override
    public void init() {
        this.ensureCore();
        if (this.swapchain == 0L) this.buildSwapchain();
    }

    @Override
    public void cleanup() {
        if (this.device != null) vkDeviceWaitIdle(this.device);

        // STILL-PENDING ENGINE DESTRUCTORS RUN NOW: THE DEVICE IS IDLE, SO NO FRAME REFERENCES THEM
        Retire r;
        while ((r = this.retireQueue.poll()) != null) r.run().run();

        // EVERY HANDLE IS CHECKED, SO A CORE THAT FAILED HALFWAY IS ALSO TORN DOWN BEFORE ITS DEVICE
        if (this.device != null) {
            this.destroySwapchainObjects();

            for (final Frame f: this.frames) {
                if (f == null) continue;
                this.freeRetired(f);
                if (f.vbo != 0L) vkDestroyBuffer(this.device, f.vbo, null);
                if (f.vboMem != 0L) vkFreeMemory(this.device, f.vboMem, null);
                if (f.imageAvailable != 0L) vkDestroySemaphore(this.device, f.imageAvailable, null);
                if (f.inFlightFence != 0L) vkDestroyFence(this.device, f.inFlightFence, null);
                for (final long pool: f.descPools) vkDestroyDescriptorPool(this.device, pool, null);
            }

            for (final TextureRecord rec: this.textures.values()) {
                vkDestroyImageView(this.device, rec.view(), null);
                vkDestroyImage(this.device, rec.image(), null);
                vkFreeMemory(this.device, rec.memory(), null);
            }
            this.textures.clear();

            if (this.dummyPool != 0L) vkDestroyDescriptorPool(this.device, this.dummyPool, null); // FREES THE SET
            if (this.dummyView != 0L) vkDestroyImageView(this.device, this.dummyView, null);
            if (this.dummyImg != 0L) vkDestroyImage(this.device, this.dummyImg, null);
            if (this.dummyMem != 0L) vkFreeMemory(this.device, this.dummyMem, null);

            if (this.pipeTriList != 0L) vkDestroyPipeline(this.device, this.pipeTriList, null);
            if (this.pipeLineList != 0L) vkDestroyPipeline(this.device, this.pipeLineList, null);
            if (this.pipeLineStrip != 0L) vkDestroyPipeline(this.device, this.pipeLineStrip, null);
            if (this.sdfPipe != 0L) vkDestroyPipeline(this.device, this.sdfPipe, null);
            if (this.pipeLayout != 0L) vkDestroyPipelineLayout(this.device, this.pipeLayout, null);
            if (this.sdfPipeLayout != 0L) vkDestroyPipelineLayout(this.device, this.sdfPipeLayout, null);
            if (this.descLayout != 0L) vkDestroyDescriptorSetLayout(this.device, this.descLayout, null);
            if (this.renderPass != 0L) vkDestroyRenderPass(this.device, this.renderPass, null);
            if (this.layerPass != 0L) vkDestroyRenderPass(this.device, this.layerPass, null);
            if (this.sampler != 0L) vkDestroySampler(this.device, this.sampler, null);
            if (this.uploadFence != 0L) vkDestroyFence(this.device, this.uploadFence, null);
            if (this.uploadPool != 0L) vkDestroyCommandPool(this.device, this.uploadPool, null); // FREES uploadCmd
            if (this.cmdPool != 0L) vkDestroyCommandPool(this.device, this.cmdPool, null); // FREES THE FRAME COMMAND BUFFERS
            this.coreReady = false;
        }

        if (this.memProps != null) { this.memProps.free(); this.memProps = null; }
        if (this.device != null) { vkDestroyDevice(this.device, null); this.device = null; }
        if (this.features13 != null) { this.features13.free(); this.features13 = null; }
        if (this.features12 != null) { this.features12.free(); this.features12 = null; }
        if (this.ycbcrFeatures != null) { this.ycbcrFeatures.free(); this.ycbcrFeatures = null; }
        if (this.features != null) { this.features.free(); this.features = null; }
        if (this.surface != 0L) { vkDestroySurfaceKHR(this.instance, this.surface, null); this.surface = 0L; }
        if (this.instance != null) { vkDestroyInstance(this.instance, null); this.instance = null; }
        WaterMedia.LOGGER.info(IT, "VulkanRenderBackend cleaned up");
    }

    // ==========================================================================
    // PUBLIC API: PER-FRAME FLOW
    // ==========================================================================
    @Override
    public void beginFrame() {
        this.frameValid = false;
        this.ensureCore();
        if (this.swapchain == 0L && !this.buildSwapchain()) return; // STILL MINIMIZED

        final Frame f = this.frames[this.currentFrame];
        vkWaitForFences(this.device, f.inFlightFence, true, BLOCK);
        // PRIOR GPU WORK OF THIS SLOT IS DONE: SAFE TO RECLAIM VERTEX BUFFERS RETIRED WHEN IT LAST GREW
        this.freeRetired(f);
        // THE FRAME CLOCK ADVANCES ONLY ON REAL FRAMES, PAST THE FENCE WAIT; ENGINE RESOURCES WHOSE FRAMES COMPLETED
        // ARE DESTROYED IN due ORDER, STOPPING AT THE FIRST ONE NOT YET DUE
        this.frameCounter++;
        Retire retired;
        while ((retired = this.retireQueue.peek()) != null && this.frameCounter >= retired.due()) {
            this.retireQueue.poll();
            retired.run().run();
        }

        try (final MemoryStack stack = stackPush()) {
            final IntBuffer pImg = stack.mallocInt(1);
            int acq = vkAcquireNextImageKHR(this.device, this.swapchain, BLOCK, f.imageAvailable, VK_NULL_HANDLE, pImg);
            if (acq == VK_ERROR_OUT_OF_DATE_KHR) {
                this.recreateSwapchain();
                if (this.swapchain == 0L) return;
                acq = vkAcquireNextImageKHR(this.device, this.swapchain, BLOCK, f.imageAvailable, VK_NULL_HANDLE, pImg);
            }
            if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR) return; // GIVE UP THIS FRAME
            this.imageIndex = pImg.get(0);

            // TWO IN-FLIGHT FRAMES MUST NOT TARGET THE SAME IMAGE (THE IMAGE COUNT MAY EXCEED THE FRAME COUNT)
            if (this.imagesInFlight[this.imageIndex] != 0L)
                vkWaitForFences(this.device, this.imagesInFlight[this.imageIndex], true, BLOCK);
            this.imagesInFlight[this.imageIndex] = f.inFlightFence;
            vkResetFences(this.device, f.inFlightFence);

            vkResetCommandBuffer(f.cmd, 0);
            final VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(f.cmd, begin), "begin command buffer");
        }

        // RESET THE PER-FRAME TRANSIENT STATE
        for (int i = 0; i <= f.descPoolIndex && i < f.descPools.size(); i++) vkResetDescriptorPool(this.device, f.descPools.get(i), 0);
        f.descPoolIndex = 0;
        f.descPoolUsed = 0;
        f.viewSetCount = 0;
        f.vertexOffset = 0;
        this.boundPipeline = 0L;
        this.boundVbo = 0L;
        this.boundSet = 0L;
        // THE PER-FRAME DESCRIPTOR POOLS WERE JUST RESET, SO ANY CACHED SET FROM A PRIOR FRAME IS STALE
        this.boundView = 0L;
        this.boundTexSet = 0L;
        this.vpDirty = true;
        this.scDirty = true;
        this.lwDirty = true;
        this.renderPassActive = false;
        this.frameValid = true;
    }

    @Override
    public void present() {
        if (!this.frameValid) return;
        this.endLayer();
        final Frame f = this.frames[this.currentFrame];
        if (!this.renderPassActive) this.beginRenderPass(); // THE IMAGE MUST REACH PRESENT_SRC EVEN WITHOUT DRAWS
        vkCmdEndRenderPass(f.cmd);
        this.renderPassActive = false;
        check(vkEndCommandBuffer(f.cmd), "end command buffer");

        final int pres;
        try (final MemoryStack stack = stackPush()) {
            final VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default()
                    .waitSemaphoreCount(1) // NOT AUTO-DERIVED: SHARED WITH pWaitDstStageMask
                    .pWaitSemaphores(stack.longs(f.imageAvailable))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(f.cmd))
                    .pSignalSemaphores(stack.longs(this.renderFinished[this.imageIndex]));
            final VkPresentInfoKHR pi = VkPresentInfoKHR.calloc(stack).sType$Default()
                    .pWaitSemaphores(stack.longs(this.renderFinished[this.imageIndex]))
                    .swapchainCount(1).pSwapchains(stack.longs(this.swapchain)).pImageIndices(stack.ints(this.imageIndex));
            // THE VKEngine PRODUCER SUBMITS TO THE SAME QUEUE UNDER THE SAME LOCK
            synchronized (this.queueLock) {
                check(vkQueueSubmit(this.queue, si, f.inFlightFence), "queue submit");
                pres = vkQueuePresentKHR(this.queue, pi);
            }
        }
        if (pres == VK_ERROR_OUT_OF_DATE_KHR || pres == VK_SUBOPTIMAL_KHR) this.recreateSwapchain();
        else if (pres != VK_SUCCESS) check(pres, "queue present");

        this.currentFrame = (this.currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
        this.frameValid = false;
    }

    @Override
    public void configureFrameState() {
        // NO-OP: BLEND IS BAKED INTO THE PIPELINE, AND THERE IS NO DEPTH ATTACHMENT
    }

    @Override
    public void clear(final float r, final float g, final float b, final float a) {
        this.clearR = r;
        this.clearG = g;
        this.clearB = b;
        this.clearA = a;
        if (!this.frameValid) return;
        if (this.renderPassActive) {
            // MID-FRAME CLEAR: THE RENDER PASS IS ALREADY OPEN, SO CLEAR INSIDE IT
            try (final MemoryStack stack = stackPush()) {
                final VkClearAttachment.Buffer at = VkClearAttachment.calloc(1, stack);
                at.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0);
                at.get(0).clearValue().color(c -> c.float32(0, r).float32(1, g).float32(2, b).float32(3, a));
                final VkClearRect.Buffer rect = VkClearRect.calloc(1, stack);
                rect.get(0).baseArrayLayer(0).layerCount(1);
                rect.get(0).rect(rc -> rc.offset(o -> o.x(0).y(0)).extent(e -> e.width(this.extentW).height(this.extentH)));
                vkCmdClearAttachments(this.frames[this.currentFrame].cmd, at, rect);
            }
        } else {
            this.beginRenderPass();
        }
    }

    @Override
    public void viewport(final int width, final int height) {
        // THE DRAW-TIME VIEWPORT USES THE SWAPCHAIN EXTENT, NOT THIS WINDOW SIZE
        this.vpDirty = true;
    }

    // ==========================================================================
    // PUBLIC API: TREE CACHE LAYERS
    // ==========================================================================
    @Override
    public boolean beginLayer(final int index, final float r, final float g, final float b, final float a) {
        if (!this.frameValid || !this.layerSupport) return false;
        if (this.layerImage[index] == 0L && !this.createLayer(index)) return false;
        final VkCommandBuffer cmd = this.frames[this.currentFrame].cmd;
        if (this.renderPassActive) vkCmdEndRenderPass(cmd);
        try (final MemoryStack stack = stackPush()) {
            final VkClearValue.Buffer clear = VkClearValue.calloc(1, stack);
            clear.get(0).color(c -> c.float32(0, r).float32(1, g).float32(2, b).float32(3, a));
            final VkRenderPassBeginInfo rp = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                    .renderPass(this.layerPass).framebuffer(this.layerFramebuffer[index]).pClearValues(clear);
            rp.renderArea(area -> area.offset(o -> o.x(0).y(0)).extent(e -> e.width(this.extentW).height(this.extentH)));
            vkCmdBeginRenderPass(cmd, rp, VK_SUBPASS_CONTENTS_INLINE);
        }
        this.renderPassActive = true;
        this.activeLayer = index;
        this.vpDirty = true;
        this.scDirty = true;
        this.lwDirty = true;
        return true;
    }

    @Override
    public void endLayer() {
        if (this.activeLayer < 0) return;
        if (this.renderPassActive) vkCmdEndRenderPass(this.frames[this.currentFrame].cmd);
        this.renderPassActive = false;
        this.activeLayer = -1;
        this.vpDirty = true;
        this.scDirty = true;
        this.lwDirty = true;
    }

    @Override
    public boolean bindLayer(final int index) {
        if (this.layerView[index] == 0L) return false;
        this.currentView = this.layerView[index];
        this.layerBound = true;
        return true;
    }

    @Override
    public int layerEpoch() {
        return this.layerEpoch;
    }

    @Override
    public void releaseLayers() {
        if (this.layerImage[0] == 0L && this.layerImage[1] == 0L) return;
        // BETWEEN FRAMES ONLY THIS BACKEND'S OWN IN-FLIGHT FRAMES CAN STILL READ THE LAYERS; EVERY FENCE STARTS SIGNALED
        try (final MemoryStack stack = stackPush()) {
            final LongBuffer fences = stack.mallocLong(MAX_FRAMES_IN_FLIGHT);
            for (final Frame f: this.frames) fences.put(f.inFlightFence);
            vkWaitForFences(this.device, fences.flip(), true, BLOCK);
        }
        for (int i = 0; i < 2; i++) this.destroyLayer(i);
        this.layerEpoch++;
    }

    // ==========================================================================
    // PUBLIC API: DRAWING
    // ==========================================================================
    @Override
    public void useProjection(final Matrix4f projection) {
        projection.get(this.proj);
    }

    @Override
    public void bindTexture(final int textureId) {
        final TextureRecord rec = this.textures.get(textureId);
        this.currentView = rec != null ? rec.view() : 0L;
        this.layerBound = false;
    }

    /** Samples an engine-owned {@code VkImageView} already in {@code SHADER_READ_ONLY_OPTIMAL}, never transitioning or destroying it. */
    @Override
    public void bindMediaTexture(final long handle) {
        this.currentView = handle;
        this.layerBound = false;
    }

    @Override
    public void clip(final int x, final int y, final int width, final int height, final int canvasHeight) {
        // THE NEGATIVE-HEIGHT VIEWPORT MAPS APP-TOP TO FRAMEBUFFER-TOP AND THE SCISSOR ORIGIN IS TOP-LEFT TOO,
        // SO THE CLIP Y NEEDS NO GL BOTTOM-LEFT FLIP
        this.clipEnabled = true;
        this.clipX = x;
        this.clipY = y;
        this.clipW = width;
        this.clipH = height;
        this.scDirty = true;
    }

    @Override
    public void clearClip() {
        this.clipEnabled = false;
        this.scDirty = true;
    }

    @Override
    public void lineWidth(final float width) {
        this.lineW = width;
        this.lwDirty = true;
    }

    @Override
    public void draw(final DrawMode mode, final float[] vertices, final int vertexCount, final boolean textured) {
        if (!this.frameValid || vertexCount <= 0) return;
        if (!this.renderPassActive) this.beginRenderPass(); // A DRAW BEFORE ANY clear()

        final Frame f = this.frames[this.currentFrame];
        final boolean loop = mode == DrawMode.LINE_LOOP;
        final boolean fan = mode == DrawMode.TRIANGLE_FAN;
        // LINE_LOOP CLOSES BY REPEATING VERTEX 0; TRIANGLE_FAN EXPANDS TO A TRIANGLE LIST (v0, vi, vi+1) BECAUSE FAN
        // TOPOLOGY IS NOT PORTABLE (MoltenVK REJECTS IT)
        final int drawVerts = fan ? (vertexCount - 2) * 3 : (loop ? vertexCount + 1 : vertexCount);
        if (drawVerts <= 0) return;

        // THE OUTGROWN VERTEX BUFFER IS RETIRED, NOT FREED, BECAUSE EARLIER DRAWS OF THIS FRAME READ IT UNTIL
        // THE FENCE SIGNALS
        if (f.vertexOffset + drawVerts > f.vboCapVerts) this.growVbo(f, f.vertexOffset + drawVerts);

        // APPEND AT THE RUNNING OFFSET OF THE PERSISTENTLY MAPPED HOST-VISIBLE BUFFER
        final long base = f.vboMapped + (long) f.vertexOffset * VERTEX_BYTES;
        final FloatBuffer dst = MemoryUtil.memFloatBuffer(base, drawVerts * FLOATS_PER_VERTEX);
        if (fan) {
            for (int i = 1; i < vertexCount - 1; i++) {
                dst.put(vertices, 0, FLOATS_PER_VERTEX);
                dst.put(vertices, i * FLOATS_PER_VERTEX, FLOATS_PER_VERTEX);
                dst.put(vertices, (i + 1) * FLOATS_PER_VERTEX, FLOATS_PER_VERTEX);
            }
        } else {
            dst.put(vertices, 0, vertexCount * FLOATS_PER_VERTEX);
            if (loop) dst.put(vertices, 0, FLOATS_PER_VERTEX);
        }
        final int firstVertex = f.vertexOffset;
        f.vertexOffset += drawVerts;

        final VkCommandBuffer cmd = f.cmd;
        try (final MemoryStack stack = stackPush()) {
            final long pipe = switch (mode) {
                case TRIANGLES, TRIANGLE_FAN -> this.pipeTriList; // FANS ARE EXPANDED TO A LIST ABOVE
                case LINES -> this.pipeLineList;
                case LINE_LOOP -> this.pipeLineStrip; // A CLOSED LINE STRIP: VULKAN HAS NO LINE_LOOP TOPOLOGY
            };
            if (pipe != this.boundPipeline) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
                this.boundPipeline = pipe;
            }
            if (f.vbo != this.boundVbo) {
                vkCmdBindVertexBuffers(cmd, 0, stack.longs(f.vbo), stack.longs(0L));
                this.boundVbo = f.vbo;
            }
            // DYNAMIC STATE: SET ON CHANGE; IT PERSISTS FOR THE NEXT DRAWS OF THIS COMMAND BUFFER
            if (this.vpDirty) {
                // NEGATIVE-HEIGHT VIEWPORT FROM THE SWAPCHAIN EXTENT, NOT viewport(): IT GIVES OPENGL'S CLIP SPACE
                // SO THE ortho2D(0, w, h, 0) MATH RENDERS THE SAME, AND ALWAYS MATCHES THE FRAMEBUFFER AND SCISSOR
                final VkViewport.Buffer v = VkViewport.calloc(1, stack);
                v.get(0).x(0f).y(this.extentH).width(this.extentW).height(-(float) this.extentH).minDepth(0f).maxDepth(1f);
                vkCmdSetViewport(cmd, 0, v);
                this.vpDirty = false;
            }
            if (this.scDirty) {
                vkCmdSetScissor(cmd, 0, this.scissor(stack));
                this.scDirty = false;
            }
            if (this.lwDirty) {
                vkCmdSetLineWidth(cmd, this.wideLines ? this.lineW : 1.0f); // 1.0 WITHOUT wideLines
                this.lwDirty = false;
            }

            final boolean useTex = textured && this.currentView != 0L;
            // TEXTURED DRAWS SAMPLE currentView THROUGH THE SET THIS FRAME ALREADY WROTE FOR IT, SO ALTERNATING BETWEEN
            // ATLASES, ICONS AND THUMBNAILS DOES NOT ALLOCATE A SET PER SWITCH; NON-TEXTURED DRAWS BIND THE DUMMY
            final long set;
            if (useTex) {
                if (this.currentView != this.boundView || this.boundTexSet == 0L) {
                    this.boundTexSet = this.textureSet(stack, f, this.currentView);
                    this.boundView = this.currentView;
                }
                set = this.boundTexSet;
            } else {
                set = this.dummySet;
            }
            if (set != this.boundSet) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeLayout, 0, stack.longs(set), null);
                this.boundSet = set;
            }

            final ByteBuffer push = stack.malloc(PUSH_BYTES);
            for (int i = 0; i < 16; i++) push.putFloat(i * 4, this.proj[i]);
            push.putInt(64, !useTex ? 0 : this.layerBound ? 2 : 1);
            vkCmdPushConstants(cmd, this.pipeLayout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);

            vkCmdDraw(cmd, drawVerts, 1, firstVertex, 0);
        }
    }

    @Override
    public boolean supportsSoftRect() {
        return true;
    }

    @Override
    public void softRect(final float x, final float y, final float w, final float h, final float radius,
                         final float r, final float g, final float b, final float a, final float softness) {
        if (!this.frameValid) return;
        if (!this.renderPassActive) this.beginRenderPass();
        final float soft = Math.max(0.5f, softness);
        final Frame f = this.frames[this.currentFrame];
        final int drawVerts = 6;
        if (f.vertexOffset + drawVerts > f.vboCapVerts) this.growVbo(f, f.vertexOffset + drawVerts);
        final long base = f.vboMapped + (long) f.vertexOffset * VERTEX_BYTES;
        final FloatBuffer dst = MemoryUtil.memFloatBuffer(base, drawVerts * FLOATS_PER_VERTEX);
        final float x0 = x - soft, y0 = y - soft, x1 = x + w + soft, y1 = y + h + soft;
        // 6-VERTEX QUAD: ONLY position IS READ, uv AND color PAD THE 8-FLOAT STRIDE
        putSoftVertex(dst, x0, y0);
        putSoftVertex(dst, x1, y0);
        putSoftVertex(dst, x1, y1);
        putSoftVertex(dst, x0, y0);
        putSoftVertex(dst, x1, y1);
        putSoftVertex(dst, x0, y1);
        final int firstVertex = f.vertexOffset;
        f.vertexOffset += drawVerts;

        final VkCommandBuffer cmd = f.cmd;
        try (final MemoryStack stack = stackPush()) {
            if (this.sdfPipe != this.boundPipeline) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.sdfPipe);
                this.boundPipeline = this.sdfPipe;
            }
            if (f.vbo != this.boundVbo) {
                vkCmdBindVertexBuffers(cmd, 0, stack.longs(f.vbo), stack.longs(0L));
                this.boundVbo = f.vbo;
            }
            if (this.vpDirty) {
                final VkViewport.Buffer v = VkViewport.calloc(1, stack);
                v.get(0).x(0f).y(this.extentH).width(this.extentW).height(-(float) this.extentH).minDepth(0f).maxDepth(1f);
                vkCmdSetViewport(cmd, 0, v);
                this.vpDirty = false;
            }
            if (this.scDirty) {
                vkCmdSetScissor(cmd, 0, this.scissor(stack));
                this.scDirty = false;
            }

            final ByteBuffer push = stack.malloc(PUSH_SDF_BYTES);
            for (int i = 0; i < 16; i++) push.putFloat(i * 4, this.proj[i]);
            push.putFloat(64, r).putFloat(68, g).putFloat(72, b).putFloat(76, a);
            push.putFloat(80, x + w / 2f).putFloat(84, y + h / 2f);
            push.putFloat(88, w / 2f).putFloat(92, h / 2f);
            push.putFloat(96, Math.max(0f, radius));
            push.putFloat(100, soft);
            vkCmdPushConstants(cmd, this.sdfPipeLayout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);

            vkCmdDraw(cmd, drawVerts, 1, firstVertex, 0);
        }
        // THE SDF LAYOUT IS INCOMPATIBLE WITH THE UI ONE (NO SET 0, DIFFERENT PUSH), SO THE NEXT UI DRAW REBINDS ITS
        // PIPELINE AND DESCRIPTOR SET (IT RE-PUSHES ITS CONSTANTS EVERY DRAW ANYWAY)
        this.boundPipeline = VK_NULL_HANDLE;
        this.boundSet = VK_NULL_HANDLE;
    }

    // ==========================================================================
    // PUBLIC API: TEXTURES
    // ==========================================================================
    @Override
    public TextureHandle createTexture(final int width, final int height, final ByteBuffer rgba) {
        this.ensureCore();
        final TextureRecord rec = this.uploadTexture(width, height, rgba);
        final int id = this.nextTextureId++;
        this.textures.put(id, rec);
        return new TextureHandle(id, width, height);
    }

    @Override
    public void deleteTexture(final TextureHandle texture) {
        if (texture == null || texture.id() <= 0) return;
        final TextureRecord rec = this.textures.remove(texture.id());
        if (rec == null) return;
        // RARE (ATLAS OR ICON TEARDOWN): DRAIN THE RENDER QUEUE, THE ONLY ONE THAT SAMPLES THE VIEW, UNDER THE LOCK THE
        // ENGINE'S UPLOADS SHARE; A DEVICE-WIDE WAIT WOULD RACE FFMPEG'S DECODE SUBMISSIONS ON ITS OWN QUEUES
        synchronized (this.queueLock) {
            vkQueueWaitIdle(this.queue);
        }
        vkDestroyImageView(this.device, rec.view(), null);
        vkDestroyImage(this.device, rec.image(), null);
        vkFreeMemory(this.device, rec.memory(), null);
        // THE DRIVER MAY HAND THE SAME HANDLE TO THE NEXT VIEW, SO NO CACHED SET MAY OUTLIVE THIS ONE
        if (this.boundView == rec.view()) this.boundTexSet = 0L;
        for (final Frame f: this.frames) {
            if (f == null) continue;
            for (int i = 0; i < f.viewSetCount; i++) {
                if (f.viewSets[i * 2] != rec.view()) continue;
                f.viewSetCount--;
                f.viewSets[i * 2] = f.viewSets[f.viewSetCount * 2];
                f.viewSets[i * 2 + 1] = f.viewSets[f.viewSetCount * 2 + 1];
                break;
            }
        }
    }

    // ==========================================================================
    // PUBLIC API: VKContext
    // ==========================================================================
    @Override
    public Supplier<GFXEngine> mediaEngineSupplier(final Thread renderThread, final Executor renderExecutor) {
        // VKEngine BORROWS THIS BACKEND'S DEVICE (THIS IS THE VKContext); THE THREAD AND EXECUTOR ARE UNUSED
        return () -> PlayerAPI.vkEngine(this);
    }

    @Override
    public void retire(final Runnable destroy) {
        // STAMPED A FEW FRAMES AHEAD (+1 ABSORBS THE frameCounter READ/WRITE RACE WITH beginFrame); A BACKGROUND
        // RELEASE THREAD MAY CALL THIS, SO THE QUEUE IS CONCURRENT
        this.retireQueue.add(new Retire(this.frameCounter + MAX_FRAMES_IN_FLIGHT + 1, destroy));
    }

    @Override
    public VkInstance vkInstance() {
        return this.instance;
    }

    @Override
    public VkPhysicalDevice physicalDevice() {
        return this.physicalDevice;
    }

    @Override
    public String deviceName() {
        return this.deviceName;
    }

    @Override
    public String deviceVersion() {
        return this.deviceVersion;
    }

    @Override
    public VkDevice vkDevice() {
        return this.device;
    }

    @Override
    public VkQueue queue() {
        return this.queue;
    }

    @Override
    public int queueFamily() {
        return this.queueFamily;
    }

    @Override
    public Object queueLock() {
        return this.queueLock;
    }

    @Override
    public VkPhysicalDeviceMemoryProperties memoryProperties() {
        return this.memProps;
    }

    @Override
    public boolean hostImportSupported() {
        return this.hostImport;
    }

    @Override
    public long minImportedHostPointerAlignment() {
        return this.minAlign;
    }

    @Override
    public boolean ycbcrSampler() {
        return this.ycbcrSampler;
    }

    @Override
    public VKContext.DeviceInfo deviceInfo() {
        return new VKContext.DeviceInfo(this.features, List.copyOf(this.instanceExtensions), List.copyOf(this.deviceExtensions), this.queueCounts.clone());
    }

    // ==========================================================================
    // CONSTRUCTION
    // ==========================================================================
    private void createInstanceAndSurface() {
        try (final MemoryStack stack = stackPush()) {
            // API VERSION IS min(LOADER, 1.4) BUT NEVER BELOW 1.1: A HIGHER apiVersion ONLY WIDENS WHAT MAY BE USED
            // (THE CODE STAYS 1.1-CORE), AND getInstanceVersionSupported IS NULL-SAFE ON 1.0 LOADERS
            final int loaderVer = VK.getInstanceVersionSupported();
            final int apiVersion = MathUtil.clamp(loaderVer, VK_API_VERSION_1_1, VK14.VK_API_VERSION_1_4);
            final VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("WaterMedia"))
                    .applicationVersion(VK_MAKE_API_VERSION(0, 3, 0, 0))
                    .pEngineName(stack.UTF8("WaterMedia"))
                    .engineVersion(VK_MAKE_API_VERSION(0, 3, 0, 0))
                    // 1.1 FLOOR: THE NEGATIVE-HEIGHT VIEWPORT (KHR_maintenance1) OF THE GL Y-FLIP IS CORE THERE
                    .apiVersion(apiVersion);

            final PointerBuffer glfwExts = glfwGetRequiredInstanceExtensions();
            if (glfwExts == null) throw new IllegalStateException("VulkanRenderBackend: glfwGetRequiredInstanceExtensions returned null");

            // MOLTENVK: LOADERS HIDE PORTABILITY DEVICES UNLESS THE INSTANCE ENABLES VK_KHR_portability_enumeration
            // AND ITS FLAG; WITHOUT IT vkEnumeratePhysicalDevices FINDS NOTHING ON MACOS
            boolean portEnum = false;
            final IntBuffer instExtCount = stack.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((ByteBuffer) null, instExtCount, null);
            final VkExtensionProperties.Buffer instExts = VkExtensionProperties.malloc(instExtCount.get(0), stack);
            vkEnumerateInstanceExtensionProperties((ByteBuffer) null, instExtCount, instExts);
            for (int i = 0; i < instExts.capacity(); i++) {
                if (VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME.equals(instExts.get(i).extensionNameString())) {
                    portEnum = true;
                    break;
                }
            }
            PointerBuffer exts = glfwExts;
            if (portEnum) {
                exts = stack.mallocPointer(glfwExts.remaining() + 1);
                exts.put(glfwExts).put(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)).flip();
            }
            for (int i = 0; i < exts.remaining(); i++) this.instanceExtensions.add(MemoryUtil.memUTF8(exts.get(i)));

            final VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType$Default()
                    .flags(portEnum ? VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0)
                    .pApplicationInfo(app).ppEnabledExtensionNames(exts);
            final PointerBuffer pInst = stack.mallocPointer(1);
            check(vkCreateInstance(ici, null, pInst), "create instance");
            this.instance = new VkInstance(pInst.get(0), ici);

            final LongBuffer pSurface = stack.mallocLong(1);
            check(glfwCreateWindowSurface(this.instance, this.window, null, pSurface), "create window surface");
            this.surface = pSurface.get(0);
        }
    }

    private void pickPhysicalDevice() {
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(this.instance, count, null), "count physical devices");
            if (count.get(0) == 0) throw new IllegalStateException("VulkanRenderBackend: no Vulkan physical devices");
            final PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(this.instance, count, devices), "enumerate physical devices");

            VkPhysicalDevice best = null;
            int bestFamily = -1;
            boolean bestHostImport = false;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < devices.capacity(); i++) {
                final VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), this.instance);
                if (!hasDeviceExtension(candidate, stack, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) continue;
                final int family = this.findGraphicsPresentFamily(candidate, stack);
                if (family < 0) continue;

                final VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(candidate, props);
                final boolean extHost = hasDeviceExtension(candidate, stack, VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME);

                // PREFER A DISCRETE GPU, THEN ONE THAT CAN ZERO-COPY-IMPORT THE DECODER'S HOST BUFFERS
                int score = 0;
                if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) score += 1_000;
                if (extHost) score += 100;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                    bestFamily = family;
                    bestHostImport = extHost;
                }
            }
            if (best == null) throw new IllegalStateException("VulkanRenderBackend: no device supports graphics+present on a single queue family");
            this.physicalDevice = best;
            this.queueFamily = bestFamily;
            this.hostImport = bestHostImport;

            final VkPhysicalDeviceFeatures feats = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(best, feats);
            this.wideLines = feats.wideLines();
        }
    }

    private void createDeviceAndQueue() {
        try (final MemoryStack stack = stackPush()) {
            final VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(this.physicalDevice, props);
            // GPU NAME AND MAX API VERSION, CAPTURED ONCE FOR DIAGNOSTICS SO ANY THREAD CAN READ THEM
            final String name = props.deviceNameString();
            if (!name.isBlank()) this.deviceName = name;
            final int deviceApi = props.apiVersion();
            this.deviceVersion = ((deviceApi >>> 22) & 0x7F) + "." + ((deviceApi >>> 12) & 0x3FF);
            // WHAT MAY BE USED IS THE LOWER OF THE INSTANCE'S AND THE DEVICE'S VERSIONS
            final int api = Math.min(deviceApi, this.instance.getCapabilities().apiVersion);

            // ONE QUEUE FAMILY FOR GRAPHICS, PRESENT AND THE ENGINE'S TRANSFER/COMPUTE, SO NO OWNERSHIP TRANSFERS; A DEVICE
            // ADVERTISING VK_KHR_portability_subset (MoltenVK) MUST ENABLE IT, AND A 1.3 DEVICE GETS THE VIDEO DECODE
            // EXTENSIONS IT OFFERS
            final IntBuffer count = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(this.physicalDevice, (ByteBuffer) null, count, null);
            final VkExtensionProperties.Buffer available = VkExtensionProperties.malloc(count.get(0), stack);
            vkEnumerateDeviceExtensionProperties(this.physicalDevice, (ByteBuffer) null, count, available);
            final Set<String> supported = new HashSet<>();
            for (int i = 0; i < available.capacity(); i++) supported.add(available.get(i).extensionNameString());
            this.deviceExtensions.add(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            if (this.hostImport) this.deviceExtensions.add(VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME);
            if (supported.contains(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)) this.deviceExtensions.add(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);
            final List<String> codecs = new ArrayList<>();
            if (api >= VK_API_VERSION_1_3 && supported.contains(VK_KHR_VIDEO_QUEUE_EXTENSION_NAME) && supported.contains(VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME)) {
                if (supported.contains(VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME)) codecs.add(VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME);
                if (supported.contains(VK_KHR_VIDEO_DECODE_H265_EXTENSION_NAME)) codecs.add(VK_KHR_VIDEO_DECODE_H265_EXTENSION_NAME);
                if (supported.contains(VK_KHR_VIDEO_DECODE_AV1_EXTENSION_NAME)) codecs.add(VK_KHR_VIDEO_DECODE_AV1_EXTENSION_NAME);
            }

            // FFMPEG NEEDS QUEUES OF ITS OWN: A VIDEO DECODE FAMILY PLUS COMPUTE AND TRANSFER QUEUES OTHER THAN THE RENDER
            // QUEUE, AND LIBPLACEBO A GRAPHICS ONE: IT TAKES THE FIRST QUEUE OF THE RENDER FAMILY (ALSO ITS COMPUTE ONE
            // WITHOUT A COMPUTE FAMILY) AND THE APP RENDERS ON THE LAST
            vkGetPhysicalDeviceQueueFamilyProperties(this.physicalDevice, count, null);
            final VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(this.physicalDevice, count, families);
            this.queueCounts = new int[families.capacity()];
            this.queueCounts[this.queueFamily] = 1;
            if (!codecs.isEmpty()) {
                int decode = -1, compute = -1, transfer = -1;
                for (int i = 0; i < families.capacity(); i++) {
                    final int flags = families.get(i).queueFlags();
                    if (decode < 0 && (flags & VK_QUEUE_VIDEO_DECODE_BIT_KHR) != 0) decode = i;
                    if (compute < 0 && (flags & VK_QUEUE_COMPUTE_BIT) != 0 && (flags & VK_QUEUE_GRAPHICS_BIT) == 0) compute = i;
                    if (transfer < 0 && (flags & VK_QUEUE_TRANSFER_BIT) != 0 && (flags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT | VK_QUEUE_VIDEO_DECODE_BIT_KHR)) == 0) transfer = i;
                }
                final boolean spare = families.get(this.queueFamily).queueCount() >= 2;
                if (compute < 0 && spare) compute = this.queueFamily;
                if (decode >= 0 && compute >= 0) {
                    this.queueCounts[decode] = 1;
                    if (compute != this.queueFamily) this.queueCounts[compute] = 1;
                    if (spare) this.queueCounts[this.queueFamily] = 2;
                    if (transfer >= 0) this.queueCounts[transfer] = 1;
                    this.deviceExtensions.add(VK_KHR_VIDEO_QUEUE_EXTENSION_NAME);
                    this.deviceExtensions.add(VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME);
                    this.deviceExtensions.addAll(codecs);
                    this.decodeCodecs = String.join(",", codecs).replace("VK_KHR_video_decode_", "");
                }
            }
            int used = 0;
            for (final int queues: this.queueCounts) if (queues > 0) used++;
            final VkDeviceQueueCreateInfo.Buffer q = VkDeviceQueueCreateInfo.calloc(used, stack);
            for (int i = 0, j = 0; i < this.queueCounts.length; i++) {
                if (this.queueCounts[i] <= 0) continue;
                final FloatBuffer priorities = stack.mallocFloat(this.queueCounts[i]);
                for (int k = 0; k < this.queueCounts[i]; k++) priorities.put(k, 1.0f);
                q.get(j++).sType$Default().queueFamilyIndex(i).pQueuePriorities(priorities);
            }
            final PointerBuffer exts = stack.mallocPointer(this.deviceExtensions.size());
            for (final String extension: this.deviceExtensions) exts.put(stack.UTF8(extension));
            exts.flip();

            // THE FEATURES, KEPT AS FFMPEG READS THEM: wideLines FOR lineWidth, THE YCBCR SAMPLER THE VKEngine CONVERTS
            // WITH (OPTIONAL 1.1 FEATURE), THE TIMELINE SEMAPHORES AND synchronization2 DECODING RUNS ON AND WHAT
            // LIBPLACEBO AND FFMPEG'S FILTER SHADERS NEED, EACH WHEN OFFERED
            this.features = VkPhysicalDeviceFeatures2.calloc().sType$Default();
            this.features.features().wideLines(this.wideLines);
            if (api >= VK_API_VERSION_1_1) {
                final VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr = VkPhysicalDeviceSamplerYcbcrConversionFeatures.calloc(stack).sType$Default();
                final VkPhysicalDeviceVulkan12Features vulkan12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
                final VkPhysicalDeviceVulkan13Features vulkan13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
                // THE RAW ADDRESS: LWJGL'S TYPED pNext PREPENDS AND WOULD CUT THE CHAIN BEHIND THE STRUCT
                final VkPhysicalDeviceFeatures2 query = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(ycbcr.address());
                if (api >= VK_API_VERSION_1_2) ycbcr.pNext(vulkan12.address());
                if (api >= VK_API_VERSION_1_3) vulkan12.pNext(vulkan13.address());
                vkGetPhysicalDeviceFeatures2(this.physicalDevice, query);
                this.ycbcrSampler = ycbcr.samplerYcbcrConversion();
                this.ycbcrFeatures = VkPhysicalDeviceSamplerYcbcrConversionFeatures.calloc().sType$Default().samplerYcbcrConversion(this.ycbcrSampler);
                this.features.pNext(this.ycbcrFeatures.address());
                if (api >= VK_API_VERSION_1_2) {
                    this.features12 = VkPhysicalDeviceVulkan12Features.calloc().sType$Default().timelineSemaphore(vulkan12.timelineSemaphore())
                            .hostQueryReset(vulkan12.hostQueryReset()).runtimeDescriptorArray(vulkan12.runtimeDescriptorArray())
                            .shaderStorageImageArrayNonUniformIndexing(vulkan12.shaderStorageImageArrayNonUniformIndexing());
                    this.ycbcrFeatures.pNext(this.features12.address());
                }
                if (api >= VK_API_VERSION_1_3) {
                    this.features13 = VkPhysicalDeviceVulkan13Features.calloc().sType$Default().synchronization2(vulkan13.synchronization2())
                            .maintenance4(vulkan13.maintenance4());
                    this.features12.pNext(this.features13.address());
                }
            }

            // A 1.0 DEVICE TAKES THE BASE FEATURES THE OLD WAY; VkPhysicalDeviceFeatures2 IN THE CHAIN NEEDS 1.1
            final VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default()
                    .pQueueCreateInfos(q).ppEnabledExtensionNames(exts);
            if (api >= VK_API_VERSION_1_1) dci.pNext(this.features.address());
            else dci.pEnabledFeatures(this.features.features());
            final PointerBuffer pDev = stack.mallocPointer(1);
            check(vkCreateDevice(this.physicalDevice, dci, null, pDev), "create device");
            this.device = new VkDevice(pDev.get(0), this.physicalDevice, dci);

            final PointerBuffer pQ = stack.mallocPointer(1);
            vkGetDeviceQueue(this.device, this.queueFamily, this.queueCounts[this.queueFamily] - 1, pQ);
            this.queue = new VkQueue(pQ.get(0), this.device);

            // HEAP-ALLOCATED: THE MEMORY PROPERTIES OUTLIVE EVERY STACK FRAME AND THE VKEngine READS THEM
            this.memProps = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, this.memProps);

            // HOST-IMPORT ALIGNMENT IS A CHAINED PROPERTY QUERY, VALID ONLY WITH THE EXTENSION ENABLED
            if (this.hostImport) {
                final VkPhysicalDeviceExternalMemoryHostPropertiesEXT ext = VkPhysicalDeviceExternalMemoryHostPropertiesEXT.calloc(stack).sType$Default();
                final VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(ext.address());
                vkGetPhysicalDeviceProperties2(this.physicalDevice, props2);
                this.minAlign = ext.minImportedHostPointerAlignment();
            }
        }
    }

    private int findGraphicsPresentFamily(final VkPhysicalDevice pd, final MemoryStack stack) {
        final IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, count, null);
        final VkQueueFamilyProperties.Buffer props = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, count, props);
        final IntBuffer present = stack.mallocInt(1);
        for (int i = 0; i < props.capacity(); i++) {
            if ((props.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            // CHECKED: present IS UNINITIALIZED STACK MEMORY, SO A SILENT FAILURE WOULD READ GARBAGE
            check(vkGetPhysicalDeviceSurfaceSupportKHR(pd, i, this.surface, present), "query surface support");
            if (present.get(0) == VK_TRUE) return i;
        }
        return -1;
    }

    // ==========================================================================
    // CORE / SWAPCHAIN LIFECYCLE
    // ==========================================================================
    private void ensureCore() {
        // THE EXTENT-INDEPENDENT RESOURCES, BUILT ONCE: THE RENDER PASS AND PIPELINES NEED ONLY THE SURFACE FORMAT,
        // NOT THE SWAPCHAIN, SO createTexture() CAN RUN BEFORE ANY FRAME
        if (this.coreReady) return;

        try (final MemoryStack stack = stackPush()) {
            // SURFACE FORMAT: PREFER B8G8R8A8_UNORM (NON-SRGB) FOR PARITY WITH THE GL DEFAULT FRAMEBUFFER
            final IntBuffer fmtCount = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfaceFormatsKHR(this.physicalDevice, this.surface, fmtCount, null);
            final VkSurfaceFormatKHR.Buffer fmts = VkSurfaceFormatKHR.malloc(fmtCount.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(this.physicalDevice, this.surface, fmtCount, fmts);
            this.surfaceFormat = fmts.get(0).format();
            this.surfaceColorSpace = fmts.get(0).colorSpace();
            for (int i = 0; i < fmts.capacity(); i++) {
                if (fmts.get(i).format() == VK_FORMAT_B8G8R8A8_UNORM
                        && fmts.get(i).colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    this.surfaceFormat = VK_FORMAT_B8G8R8A8_UNORM;
                    this.surfaceColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
                    break;
                }
            }
            // A LAYER IS DRAWN INTO WITH BLENDING AND SAMPLED BACK IN THE SURFACE FORMAT; WITHOUT ALL THREE FEATURES THE
            // TREE DRAWS UNCACHED
            final VkFormatProperties formatProps = VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(this.physicalDevice, this.surfaceFormat, formatProps);
            final int layerFeatures = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT
                    | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            this.layerSupport = (formatProps.optimalTilingFeatures() & layerFeatures) == layerFeatures;
            if (!this.layerSupport) WaterMedia.LOGGER.warn(IT, "Surface format {} cannot back the tree cache; the UI repaints whole while the CRT bands move", this.surfaceFormat);

            // RESETTABLE COMMAND POOLS: THE FRAME POOL RECORDS FRAMES AND THE UPLOAD POOL STAGES TEXTURES, APART SO A
            // MID-FRAME createTexture() NEVER RACES THE FRAME POOL
            final VkCommandPoolCreateInfo cpci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(this.queueFamily);
            final LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(this.device, cpci, null, pPool), "create command pool");
            this.cmdPool = pPool.get(0);
            check(vkCreateCommandPool(this.device, cpci, null, pPool), "create upload command pool");
            this.uploadPool = pPool.get(0);

            final VkCommandBufferAllocateInfo ucbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(this.uploadPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            final PointerBuffer pUp = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(this.device, ucbai, pUp), "alloc upload command buffer");
            this.uploadCmd = new VkCommandBuffer(pUp.get(0), this.device);
            final VkFenceCreateInfo ufci = VkFenceCreateInfo.calloc(stack).sType$Default();
            final LongBuffer pUf = stack.mallocLong(1);
            check(vkCreateFence(this.device, ufci, null, pUf), "create upload fence");
            this.uploadFence = pUf.get(0);

            // LINEAR CLAMP SAMPLER, AS THE GL CLAMP_TO_EDGE LINEAR TEXTURES
            final VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK).unnormalizedCoordinates(false);
            final LongBuffer ps = stack.mallocLong(1);
            check(vkCreateSampler(this.device, sci, null, ps), "create sampler");
            this.sampler = ps.get(0);

            // DESCRIPTOR SET LAYOUT: ONE COMBINED IMAGE SAMPLER AT BINDING 0 (FRAGMENT)
            final VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binds.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            final VkDescriptorSetLayoutCreateInfo dlci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            final LongBuffer pdl = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(this.device, dlci, null, pdl), "create descriptor set layout");
            this.descLayout = pdl.get(0);

            // PIPELINE LAYOUT: SET 0 PLUS PUSH CONSTANTS { mat4 proj; int useTexture } VISIBLE TO BOTH STAGES
            final VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(PUSH_BYTES);
            final VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(this.descLayout)).pPushConstantRanges(pcr);
            final LongBuffer ppl = stack.mallocLong(1);
            check(vkCreatePipelineLayout(this.device, plci, null, ppl), "create pipeline layout");
            this.pipeLayout = ppl.get(0);

            // SDF PIPELINE LAYOUT: NO DESCRIPTOR SET (NO TEXTURE), A LARGER PUSH RANGE FOR THE SOFT-RECT PARAMS
            final VkPushConstantRange.Buffer spcr = VkPushConstantRange.calloc(1, stack);
            spcr.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(PUSH_SDF_BYTES);
            final VkPipelineLayoutCreateInfo splci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pPushConstantRanges(spcr);
            final LongBuffer sppl = stack.mallocLong(1);
            check(vkCreatePipelineLayout(this.device, splci, null, sppl), "create SDF pipeline layout");
            this.sdfPipeLayout = sppl.get(0);
        }

        this.renderPass = this.createRenderPass(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        this.layerPass = this.createRenderPass(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.createPipelines();
        this.createFrames();

        // 1x1 WHITE DUMMY AND ITS SET, BOUND FOR NON-TEXTURED DRAWS: THE FRAGMENT SHADER STATICALLY REFERENCES
        // THE SAMPLER, SO A VALID DESCRIPTOR MUST ALWAYS BE BOUND
        try (final MemoryStack stack = stackPush()) {
            final ByteBuffer white = stack.malloc(4);
            white.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
            final TextureRecord dummy = this.uploadTexture(1, 1, white);
            this.dummyImg = dummy.image();
            this.dummyMem = dummy.memory();
            this.dummyView = dummy.view();

            final VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack);
            size.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            final VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(size);
            final LongBuffer pdp = stack.mallocLong(1);
            check(vkCreateDescriptorPool(this.device, dpci, null, pdp), "create dummy descriptor pool");
            this.dummyPool = pdp.get(0);
            this.dummySet = this.allocSet(stack, this.dummyPool);
            this.writeTextureSet(stack, this.dummySet, this.dummyView);
        }

        this.coreReady = true;
    }

    private long createRenderPass(final int finalLayout) {
        // THE WINDOW PASS ENDS IN PRESENT_SRC AND THE LAYER PASS IN SHADER_READ_ONLY; EVERYTHING ELSE MATCHES, SO THE TWO
        // STAY COMPATIBLE AND THE PIPELINES BUILT ON THE WINDOW PASS ALSO DRAW INTO LAYERS
        try (final MemoryStack stack = stackPush()) {
            final VkAttachmentDescription.Buffer color = VkAttachmentDescription.calloc(1, stack);
            color.get(0).format(this.surfaceFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR) // clear() AND beginLayer() DRIVE THE PASS BEGIN
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(finalLayout);

            final VkAttachmentReference.Buffer ref = VkAttachmentReference.calloc(1, stack);
            ref.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            final VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(ref);

            // EXTERNAL -> 0 WAITS FOR THE imageAvailable SEMAPHORE STAGE AND FOR EARLIER WRITES AND SAMPLES OF A LAYER
            // BEFORE ITS LAYOUT TRANSITION; 0 -> EXTERNAL MAKES A LAYER'S WRITES VISIBLE TO THE SAMPLING THAT FOLLOWS
            final VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(2, stack);
            dep.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            dep.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            final VkRenderPassCreateInfo rpci = VkRenderPassCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(color).pSubpasses(subpass).pDependencies(dep);
            final LongBuffer prp = stack.mallocLong(1);
            check(vkCreateRenderPass(this.device, rpci, null, prp), "create render pass");
            return prp.get(0);
        }
    }

    private void createPipelines() {
        final long vert = this.compileShaderModule("vulkan/ui.vert", shaderc_glsl_vertex_shader);
        final long frag = this.compileShaderModule("vulkan/ui.frag", shaderc_glsl_fragment_shader);
        final long sdfVert = this.compileShaderModule("vulkan/sdf.vert", shaderc_glsl_vertex_shader);
        final long sdfFrag = this.compileShaderModule("vulkan/sdf.frag", shaderc_glsl_fragment_shader);
        try {
            // NO TRIANGLE_FAN PIPELINE: FAN TOPOLOGY IS NOT PORTABLE (MoltenVK AND PORTABILITY-SUBSET DEVICES REJECT IT),
            // SO draw() EXPANDS FANS INTO THE TRIANGLE LIST
            this.pipeTriList = this.createPipeline(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, vert, frag, this.pipeLayout);
            this.pipeLineList = this.createPipeline(VK_PRIMITIVE_TOPOLOGY_LINE_LIST, vert, frag, this.pipeLayout);
            this.pipeLineStrip = this.createPipeline(VK_PRIMITIVE_TOPOLOGY_LINE_STRIP, vert, frag, this.pipeLayout);
            this.sdfPipe = this.createPipeline(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, sdfVert, sdfFrag, this.sdfPipeLayout);
        } finally {
            vkDestroyShaderModule(this.device, vert, null);
            vkDestroyShaderModule(this.device, frag, null);
            vkDestroyShaderModule(this.device, sdfVert, null);
            vkDestroyShaderModule(this.device, sdfFrag, null);
        }
    }

    private long createPipeline(final int topology, final long vert, final long frag, final long layout) {
        try (final MemoryStack stack = stackPush()) {
            final VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));

            // VERTEX LAYOUT: 8 FLOATS PER VERTEX, pos.xy @0, uv.xy @8, color.rgba @16; STRIDE 32
            final VkVertexInputBindingDescription.Buffer bind = VkVertexInputBindingDescription.calloc(1, stack);
            bind.get(0).binding(0).stride(VERTEX_BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            final VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(3, stack);
            attrs.get(0).location(0).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
            attrs.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(8);
            attrs.get(2).location(2).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(16);
            final VkPipelineVertexInputStateCreateInfo vin = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                    .pVertexBindingDescriptions(bind).pVertexAttributeDescriptions(attrs);

            final VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(topology).primitiveRestartEnable(false);

            final VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1); // DYNAMIC: NO pViewports OR pScissors

            final VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .lineWidth(1.0f).depthClampEnable(false).rasterizerDiscardEnable(false).depthBiasEnable(false);

            final VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT).sampleShadingEnable(false);

            // BLEND: SRC_ALPHA / ONE_MINUS_SRC_ALPHA ON COLOR AND ONE / ONE_MINUS_SRC_ALPHA ON ALPHA (MATCHES
            // glBlendFuncSeparate): ALPHA ACCUMULATES AS COVERAGE, SO A TRANSPARENT LAYER ENDS PREMULTIPLIED
            final VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blend.get(0).blendEnable(true)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            final VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                    .logicOpEnable(false).pAttachments(blend);

            final VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_LINE_WIDTH));

            final VkGraphicsPipelineCreateInfo.Buffer gpci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            gpci.get(0).sType$Default().pStages(stages).pVertexInputState(vin).pInputAssemblyState(ia)
                    .pViewportState(vp).pRasterizationState(rs).pMultisampleState(ms).pColorBlendState(cb).pDynamicState(dyn)
                    .layout(layout).renderPass(this.renderPass).subpass(0);
            final LongBuffer pPipe = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(this.device, VK_NULL_HANDLE, gpci, null, pPipe), "create graphics pipeline");
            return pPipe.get(0);
        }
    }

    private void createFrames() {
        try (final MemoryStack stack = stackPush()) {
            final VkCommandBufferAllocateInfo cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(this.cmdPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(MAX_FRAMES_IN_FLIGHT);
            final PointerBuffer pCmd = stack.mallocPointer(MAX_FRAMES_IN_FLIGHT);
            check(vkAllocateCommandBuffers(this.device, cbai, pCmd), "alloc frame command buffers");

            // FENCES START SIGNALED SO THE FIRST beginFrame() WAIT RETURNS IMMEDIATELY
            final VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            final VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                final Frame f = new Frame();
                f.cmd = new VkCommandBuffer(pCmd.get(i), this.device);
                final LongBuffer p = stack.mallocLong(1);
                check(vkCreateSemaphore(this.device, sci, null, p), "create image-available semaphore");
                f.imageAvailable = p.get(0);
                check(vkCreateFence(this.device, fci, null, p), "create in-flight fence");
                f.inFlightFence = p.get(0);
                this.frames[i] = f;
                this.allocVbo(f, MAX_VERTICES);
            }
        }
    }

    private boolean buildSwapchain() {
        try (final MemoryStack stack = stackPush()) {
            final VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(this.physicalDevice, this.surface, caps), "query surface caps");

            // currentExtent WHEN FIXED, ELSE THE GLFW FRAMEBUFFER SIZE CLAMPED TO THE ALLOWED RANGE; A ZERO EXTENT
            // (MINIMIZED) RETURNS false AND THE FRAMES ARE SKIPPED UNTIL THE WINDOW IS RESTORED
            final int w, h;
            if (caps.currentExtent().width() != 0xFFFFFFFF) {
                w = caps.currentExtent().width();
                h = caps.currentExtent().height();
            } else {
                final IntBuffer fbw = stack.mallocInt(1);
                final IntBuffer fbh = stack.mallocInt(1);
                glfwGetFramebufferSize(this.window, fbw, fbh);
                w = MathUtil.clamp(fbw.get(0), caps.minImageExtent().width(), caps.maxImageExtent().width());
                h = MathUtil.clamp(fbh.get(0), caps.minImageExtent().height(), caps.maxImageExtent().height());
            }
            if (w == 0 || h == 0) return false;
            this.extentW = w;
            this.extentH = h;

            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount()) imageCount = caps.maxImageCount();

            final VkSwapchainCreateInfoKHR sci = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
                    .surface(this.surface).minImageCount(imageCount)
                    .imageFormat(this.surfaceFormat).imageColorSpace(this.surfaceColorSpace)
                    .imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE) // SINGLE QUEUE FAMILY
                    .preTransform(caps.currentTransform()).compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(VK_PRESENT_MODE_FIFO_KHR) // VSYNC, AS GL glfwSwapInterval(1)
                    .clipped(true).oldSwapchain(VK_NULL_HANDLE);
            sci.imageExtent().width(w).height(h);
            final LongBuffer pSwap = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(this.device, sci, null, pSwap), "create swapchain");
            this.swapchain = pSwap.get(0);

            final IntBuffer imgCount = stack.mallocInt(1);
            check(vkGetSwapchainImagesKHR(this.device, this.swapchain, imgCount, null), "count swapchain images");
            final int n = imgCount.get(0);
            final LongBuffer pImages = stack.mallocLong(n);
            check(vkGetSwapchainImagesKHR(this.device, this.swapchain, imgCount, pImages), "get swapchain images");

            this.swapImages = new long[n];
            this.swapViews = new long[n];
            this.framebuffers = new long[n];
            this.renderFinished = new long[n];
            this.imagesInFlight = new long[n];

            final VkSemaphoreCreateInfo semci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            for (int i = 0; i < n; i++) {
                this.swapImages[i] = pImages.get(i);
                this.swapViews[i] = this.createView(this.swapImages[i], this.surfaceFormat);

                final VkFramebufferCreateInfo fbci = VkFramebufferCreateInfo.calloc(stack).sType$Default()
                        .renderPass(this.renderPass).pAttachments(stack.longs(this.swapViews[i]))
                        .width(w).height(h).layers(1);
                final LongBuffer pFb = stack.mallocLong(1);
                check(vkCreateFramebuffer(this.device, fbci, null, pFb), "create framebuffer");
                this.framebuffers[i] = pFb.get(0);

                final LongBuffer pSem = stack.mallocLong(1);
                check(vkCreateSemaphore(this.device, semci, null, pSem), "create render-finished semaphore");
                this.renderFinished[i] = pSem.get(0);
            }
        }
        return true;
    }

    private void recreateSwapchain() {
        // ONLY THE RENDER QUEUE TOUCHES THE SWAPCHAIN, SO IT ALONE DRAINS, UNDER THE LOCK THE ENGINE'S UPLOADS SHARE; A
        // DEVICE-WIDE WAIT WOULD RACE FFMPEG'S DECODE SUBMISSIONS ON ITS OWN QUEUES. THE RENDER PASS AND PIPELINES
        // SURVIVE BECAUSE THE FORMAT NEVER CHANGES
        synchronized (this.queueLock) {
            vkQueueWaitIdle(this.queue);
            this.destroySwapchainObjects();
            this.buildSwapchain();
        }
    }

    private void destroySwapchainObjects() {
        // THE LAYERS SHARE THE SWAPCHAIN SIZE, SO THEY GO WITH IT AND THE ENGINE REPAINTS ITS TREE CACHE
        for (int i = 0; i < 2; i++) this.destroyLayer(i);
        this.layerEpoch++;
        for (final long fb: this.framebuffers) if (fb != 0L) vkDestroyFramebuffer(this.device, fb, null);
        for (final long v: this.swapViews) if (v != 0L) vkDestroyImageView(this.device, v, null);
        for (final long s: this.renderFinished) if (s != 0L) vkDestroySemaphore(this.device, s, null);
        if (this.swapchain != 0L) vkDestroySwapchainKHR(this.device, this.swapchain, null);
        this.swapchain = 0L;
        this.framebuffers = new long[0];
        this.swapViews = new long[0];
        this.renderFinished = new long[0];
        this.swapImages = new long[0];
        this.imagesInFlight = new long[0];
    }

    // ==========================================================================
    // TREE CACHE LAYERS
    // ==========================================================================
    private boolean createLayer(final int index) {
        // A DEVICE-LOCAL IMAGE THE LAYER PASS RENDERS INTO AND THE WINDOW PASS SAMPLES; A FAILURE DISABLES THE CACHE
        try (final MemoryStack stack = stackPush()) {
            final VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(this.surfaceFormat).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ici.extent().width(this.extentW).height(this.extentH).depth(1);
            final LongBuffer pImg = stack.mallocLong(1);
            check(vkCreateImage(this.device, ici, null, pImg), "create layer image");
            this.layerImage[index] = pImg.get(0);

            final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(this.device, this.layerImage[index], req);
            final int type = this.memType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (type < 0) throw new IllegalStateException("VulkanRenderBackend: no device-local memory for a layer");
            final VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(req.size()).memoryTypeIndex(type);
            final LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(this.device, mai, null, pMem), "alloc layer memory");
            this.layerMemory[index] = pMem.get(0);
            check(vkBindImageMemory(this.device, this.layerImage[index], this.layerMemory[index], 0), "bind layer memory");
            this.layerView[index] = this.createView(this.layerImage[index], this.surfaceFormat);

            final VkFramebufferCreateInfo fbci = VkFramebufferCreateInfo.calloc(stack).sType$Default()
                    .renderPass(this.layerPass).pAttachments(stack.longs(this.layerView[index]))
                    .width(this.extentW).height(this.extentH).layers(1);
            final LongBuffer pFb = stack.mallocLong(1);
            check(vkCreateFramebuffer(this.device, fbci, null, pFb), "create layer framebuffer");
            this.layerFramebuffer[index] = pFb.get(0);
            return true;
        } catch (final RuntimeException e) {
            this.destroyLayer(index);
            this.layerSupport = false;
            WaterMedia.LOGGER.warn(IT, "Cannot build a tree cache layer; the UI repaints whole while the CRT bands move", e);
            return false;
        }
    }

    private void destroyLayer(final int index) {
        // NULL-SAFE: ALSO UNDOES A HALF-BUILT LAYER
        if (this.layerFramebuffer[index] != 0L) vkDestroyFramebuffer(this.device, this.layerFramebuffer[index], null);
        if (this.layerView[index] != 0L) vkDestroyImageView(this.device, this.layerView[index], null);
        if (this.layerImage[index] != 0L) vkDestroyImage(this.device, this.layerImage[index], null);
        if (this.layerMemory[index] != 0L) vkFreeMemory(this.device, this.layerMemory[index], null);
        this.layerFramebuffer[index] = 0L;
        this.layerView[index] = 0L;
        this.layerImage[index] = 0L;
        this.layerMemory[index] = 0L;
    }

    // ==========================================================================
    // DRAWING
    // ==========================================================================
    private void beginRenderPass() {
        try (final MemoryStack stack = stackPush()) {
            final VkClearValue.Buffer clear = VkClearValue.calloc(1, stack);
            clear.get(0).color(c -> c.float32(0, this.clearR).float32(1, this.clearG).float32(2, this.clearB).float32(3, this.clearA));
            final VkRenderPassBeginInfo rp = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                    .renderPass(this.renderPass).framebuffer(this.framebuffers[this.imageIndex]).pClearValues(clear);
            rp.renderArea(a -> a.offset(o -> o.x(0).y(0)).extent(e -> e.width(this.extentW).height(this.extentH)));
            vkCmdBeginRenderPass(this.frames[this.currentFrame].cmd, rp, VK_SUBPASS_CONTENTS_INLINE);
        }
        this.renderPassActive = true;
    }

    private VkRect2D.Buffer scissor(final MemoryStack stack) {
        int sx, sy, sw, sh;
        if (this.clipEnabled) {
            sx = this.clipX;
            sy = this.clipY;
            sw = this.clipW;
            sh = this.clipH;
        } else {
            sx = 0;
            sy = 0;
            sw = this.extentW;
            sh = this.extentH;
        }
        // VULKAN FORBIDS A SCISSOR OUTSIDE THE FRAMEBUFFER
        if (sx < 0) { sw += sx; sx = 0; }
        if (sy < 0) { sh += sy; sy = 0; }
        if (sx + sw > this.extentW) sw = this.extentW - sx;
        if (sy + sh > this.extentH) sh = this.extentH - sy;
        if (sw < 0) sw = 0;
        if (sh < 0) sh = 0;
        final int fx = sx, fy = sy, fw = sw, fh = sh;
        final VkRect2D.Buffer r = VkRect2D.calloc(1, stack);
        r.get(0).offset(o -> o.x(fx).y(fy)).extent(e -> e.width(fw).height(fh));
        return r;
    }

    // ==========================================================================
    // PER-FRAME VERTEX BUFFER (GROWABLE)
    // ==========================================================================
    private void allocVbo(final Frame f, final int capVerts) {
        try (final MemoryStack stack = stackPush()) {
            final long size = (long) capVerts * VERTEX_BYTES;
            final VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            final LongBuffer pBuf = stack.mallocLong(1);
            check(vkCreateBuffer(this.device, bci, null, pBuf), "create vertex buffer");
            final long buf = pBuf.get(0);

            final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(this.device, buf, req);
            final int type = this.memType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            if (type < 0) throw new IllegalStateException("VulkanRenderBackend: no host-visible coherent memory for vertices");
            final VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(req.size()).memoryTypeIndex(type);
            final LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(this.device, mai, null, pMem), "alloc vertex memory");
            final long mem = pMem.get(0);
            check(vkBindBufferMemory(this.device, buf, mem, 0), "bind vertex memory");

            final PointerBuffer pp = stack.mallocPointer(1);
            check(vkMapMemory(this.device, mem, 0, req.size(), 0, pp), "map vertex memory");
            f.vbo = buf;
            f.vboMem = mem;
            f.vboMapped = pp.get(0);
            f.vboCapVerts = capVerts;
        }
    }

    private void growVbo(final Frame f, final int neededVerts) {
        f.retired.add(new long[]{f.vbo, f.vboMem});
        this.allocVbo(f, Math.max(neededVerts, f.vboCapVerts * 2));
        f.vertexOffset = 0;
    }

    private void freeRetired(final Frame f) {
        for (final long[] r: f.retired) {
            vkDestroyBuffer(this.device, r[0], null);
            vkFreeMemory(this.device, r[1], null); // IMPLICITLY UNMAPS
        }
        f.retired.clear();
    }

    // ==========================================================================
    // DESCRIPTOR SETS (PER-FRAME, GROWABLE POOL CHAIN)
    // ==========================================================================
    private long textureSet(final MemoryStack stack, final Frame f, final long view) {
        // A FRAME SAMPLES FEW DISTINCT VIEWS, SO A LINEAR SCAN OVER ITS VIEW -> SET PAIRS BEATS A BOXED MAP
        for (int i = 0; i < f.viewSetCount; i++) if (f.viewSets[i * 2] == view) return f.viewSets[i * 2 + 1];
        if (f.descPoolUsed >= SETS_PER_POOL) {
            f.descPoolIndex++;
            f.descPoolUsed = 0;
        }
        if (f.descPoolIndex >= f.descPools.size()) f.descPools.add(this.createDescPool());
        final long pool = f.descPools.get(f.descPoolIndex);
        final long set = this.allocSet(stack, pool);
        f.descPoolUsed++;
        this.writeTextureSet(stack, set, view);
        if (f.viewSetCount * 2 == f.viewSets.length) f.viewSets = Arrays.copyOf(f.viewSets, f.viewSets.length * 2);
        f.viewSets[f.viewSetCount * 2] = view;
        f.viewSets[f.viewSetCount * 2 + 1] = set;
        f.viewSetCount++;
        return set;
    }

    private long createDescPool() {
        try (final MemoryStack stack = stackPush()) {
            final VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack);
            size.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SETS_PER_POOL);
            final VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(SETS_PER_POOL).pPoolSizes(size);
            final LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorPool(this.device, dpci, null, p), "create descriptor pool");
            return p.get(0);
        }
    }

    private long allocSet(final MemoryStack stack, final long pool) {
        final VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(this.descLayout));
        final LongBuffer p = stack.mallocLong(1);
        check(vkAllocateDescriptorSets(this.device, dsai, p), "alloc descriptor set");
        return p.get(0);
    }

    private void writeTextureSet(final MemoryStack stack, final long set, final long view) {
        final VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).sampler(this.sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        final VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
        write.get(0).sType$Default().dstSet(set).dstBinding(0).dstArrayElement(0)
                .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
        vkUpdateDescriptorSets(this.device, write, null);
    }

    // ==========================================================================
    // TEXTURES
    // ==========================================================================
    private TextureRecord uploadTexture(final int w, final int h, final ByteBuffer rgba) {
        // A SAMPLED R8G8B8A8_UNORM DEVICE-LOCAL IMAGE: THE TIGHTLY PACKED RGBA GOES THROUGH A HOST-VISIBLE STAGING
        // BUFFER AND THE IMAGE ENDS IN SHADER_READ_ONLY, SUBMITTED UNDER THE SHARED QUEUE LOCK
        final long[] im = this.createImage(w, h);
        final long view = this.createView(im[0], VK_FORMAT_R8G8B8A8_UNORM);
        final long size = (long) w * h * 4;

        long staging = 0L, stagingMem = 0L;
        try (final MemoryStack stack = stackPush()) {
            final VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            final LongBuffer pBuf = stack.mallocLong(1);
            check(vkCreateBuffer(this.device, bci, null, pBuf), "create staging buffer");
            staging = pBuf.get(0);

            final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(this.device, staging, req);
            final int type = this.memType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            if (type < 0) throw new IllegalStateException("VulkanRenderBackend: no host-visible coherent memory for staging");
            final VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(req.size()).memoryTypeIndex(type);
            final LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(this.device, mai, null, pMem), "alloc staging memory");
            stagingMem = pMem.get(0);
            check(vkBindBufferMemory(this.device, staging, stagingMem, 0), "bind staging memory");

            final PointerBuffer pp = stack.mallocPointer(1);
            check(vkMapMemory(this.device, stagingMem, 0, size, 0, pp), "map staging memory");
            MemoryUtil.memCopy(MemoryUtil.memAddress(rgba), pp.get(0), size);
            vkUnmapMemory(this.device, stagingMem);

            vkResetCommandBuffer(this.uploadCmd, 0);
            final VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(this.uploadCmd, begin);
            this.barrier(stack, this.uploadCmd, im[0],
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
            final VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0); // TIGHTLY PACKED: ROW LENGTH IS width
            region.get(0).imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1));
            region.get(0).imageOffset(o -> o.x(0).y(0).z(0));
            region.get(0).imageExtent(e -> e.width(w).height(h).depth(1));
            vkCmdCopyBufferToImage(this.uploadCmd, staging, im[0], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            this.barrier(stack, this.uploadCmd, im[0],
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            vkEndCommandBuffer(this.uploadCmd);

            vkResetFences(this.device, this.uploadFence);
            final VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(this.uploadCmd));
            synchronized (this.queueLock) {
                check(vkQueueSubmit(this.queue, si, this.uploadFence), "submit texture upload");
            }
            vkWaitForFences(this.device, this.uploadFence, true, BLOCK);
        } finally {
            if (staging != 0L) vkDestroyBuffer(this.device, staging, null);
            if (stagingMem != 0L) vkFreeMemory(this.device, stagingMem, null);
        }
        return new TextureRecord(im[0], im[1], view);
    }

    private long[] createImage(final int w, final int h) {
        try (final MemoryStack stack = stackPush()) {
            final VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ici.extent().width(w).height(h).depth(1);
            final LongBuffer pImg = stack.mallocLong(1);
            check(vkCreateImage(this.device, ici, null, pImg), "create image");
            final long img = pImg.get(0);

            final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(this.device, img, req);
            final int type = this.memType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (type < 0) throw new IllegalStateException("VulkanRenderBackend: no device-local memory for image");
            final VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(req.size()).memoryTypeIndex(type);
            final LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(this.device, mai, null, pMem), "alloc image memory");
            final long mem = pMem.get(0);
            check(vkBindImageMemory(this.device, img, mem, 0), "bind image memory");
            return new long[]{img, mem};
        }
    }

    private long createView(final long image, final int format) {
        try (final MemoryStack stack = stackPush()) {
            final VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            vci.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            final LongBuffer p = stack.mallocLong(1);
            check(vkCreateImageView(this.device, vci, null, p), "create image view");
            return p.get(0);
        }
    }

    private void barrier(final MemoryStack stack, final VkCommandBuffer cmd, final long image,
                         final int oldLayout, final int newLayout,
                         final int srcStage, final int srcAccess, final int dstStage, final int dstAccess) {
        final VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
        b.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
        b.get(0).subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, b);
    }

    private int memType(final int typeBits, final int props) {
        final int count = this.memProps.memoryTypeCount();
        for (int i = 0; i < count; i++) if ((typeBits & (1 << i)) != 0 && (this.memProps.memoryTypes(i).propertyFlags() & props) == props) return i;
        return -1;
    }

    private long compileShaderModule(final String name, final int kind) {
        // THE BUNDLED GLSL COMPILES TO SPIR-V AT RUNTIME THROUGH shaderc
        final String glsl;
        try { glsl = IOTool.shader(name); }
        catch (final IOException e) { throw new IllegalStateException("VulkanRenderBackend: cannot read the shader " + name, e); }
        final long compiler = shaderc_compiler_initialize();
        if (compiler == 0L) throw new IllegalStateException("VulkanRenderBackend: shaderc_compiler_initialize failed");
        long result = 0L;
        try {
            result = shaderc_compile_into_spv(compiler, glsl, kind, name, "main", 0L);
            if (result == 0L) throw new IllegalStateException("VulkanRenderBackend: shaderc compile returned null");
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                throw new IllegalStateException("VulkanRenderBackend: shader compile failed: " + shaderc_result_get_error_message(result));
            final ByteBuffer spv = shaderc_result_get_bytes(result);
            if (spv == null) throw new IllegalStateException("VulkanRenderBackend: shaderc produced no SPIR-V");
            try (final MemoryStack stack = stackPush()) {
                final VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spv);
                final LongBuffer p = stack.mallocLong(1);
                check(vkCreateShaderModule(this.device, smci, null, p), "create shader module");
                return p.get(0);
            }
        } finally {
            if (result != 0L) shaderc_result_release(result);
            shaderc_compiler_release(compiler);
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static boolean hasDeviceExtension(final VkPhysicalDevice pd, final MemoryStack stack, final String name) {
        final IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, count, null);
        final VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, count, props);
        for (int i = 0; i < props.capacity(); i++) if (name.equals(props.get(i).extensionNameString())) return true;
        return false;
    }

    private static void check(final int result, final String op) {
        if (result != VK_SUCCESS) throw new IllegalStateException("VulkanRenderBackend: " + op + " failed (VkResult " + result + ")");
    }

    private static void putSoftVertex(final FloatBuffer dst, final float x, final float y) {
        dst.put(x).put(y).put(0f).put(0f).put(0f).put(0f).put(0f).put(0f);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // ONE FRAME IN FLIGHT: ITS COMMAND BUFFER, SYNC PRIMITIVES, GROWABLE VERTEX BUFFER AND DESCRIPTOR POOLS
    private static final class Frame {
        VkCommandBuffer cmd;
        long imageAvailable;
        long inFlightFence;
        long vbo, vboMem, vboMapped;
        int vboCapVerts;
        int vertexOffset;
        final List<long[]> retired = new ArrayList<>(); // {buffer, memory} RETIRED ON GROWTH, FREED AFTER THE FENCE
        final List<Long> descPools = new ArrayList<>();
        int descPoolIndex;
        int descPoolUsed;
        // VIEW, SET PAIRS WRITTEN THIS FRAME; THEY DIE WITH THE POOL RESET THAT STARTS THE NEXT USE OF THIS FRAME
        long[] viewSets = new long[64];
        int viewSetCount;
    }

    // AN APP TEXTURE: ITS IMAGE, BACKING MEMORY AND SAMPLEABLE VIEW
    private record TextureRecord(long image, long memory, long view) {}

    // AN ENGINE DESTRUCTOR AND THE FRAME AFTER WHICH IT MAY RUN
    private record Retire(long due, Runnable run) {}
}
