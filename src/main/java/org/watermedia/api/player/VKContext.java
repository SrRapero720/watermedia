package org.watermedia.api.player;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkQueue;

import java.util.List;

/**
 * Bridge a Vulkan consumer implements to lend {@link VKEngine}
 * the device it renders with.
 * <p>
 * The engine never creates or owns a Vulkan device: it borrows the consumer's
 * {@link VkInstance}/{@link VkDevice} so the {@code VkImageView} it produces is sampleable by the
 * consumer's own pipeline. Ownership stays with the consumer, and the engine must never destroy
 * any object returned here. Every object must outlive every {@code VKEngine} built over this
 * context.
 * <p>
 * Method names are chosen so a Minecraft mod can implement this interface directly on Mojang's
 * {@code VulkanDevice} through a mixin and hand the cast device to
 * {@code PlayerAPI.vkEngine}. No accessor name clashes with an existing {@code VulkanDevice} member,
 * which keeps stack traces and crash reports unambiguous, and {@link #vkDevice()} matches that
 * class's own {@code vkDevice()} accessor verbatim.
 */
public interface VKContext {

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** The Vulkan instance {@link #vkDevice()} was created from. */
    VkInstance vkInstance();

    /** The physical device backing {@link #vkDevice()}. */
    VkPhysicalDevice physicalDevice();

    /** The logical device the engine uploads and converts through. */
    VkDevice vkDevice();

    /**
     * A queue the engine submits its transfer and compute work to. It may be shared with the
     * consumer's render queue; in that case {@link #queueLock()} serializes the submissions.
     */
    VkQueue queue();

    /** Queue-family index of {@link #queue()}; the engine creates its command pool on it. */
    int queueFamily();

    /**
     * Monitor that serializes submissions to {@link #queue()}. Vulkan requires queue submission to
     * be externally synchronized, so the engine holds this lock around every {@code vkQueueSubmit};
     * a consumer sharing the queue must hold the same lock around its own submits and presents.
     */
    Object queueLock();

    /** Memory properties of {@link #physicalDevice()}, used to pick memory types for buffers and images. */
    VkPhysicalDeviceMemoryProperties memoryProperties();

    /**
     * Whether {@code VK_EXT_external_memory_host} is enabled on {@link #vkDevice()}, letting the engine
     * import the decoder's host buffers directly (zero-copy) instead of staging a copy through
     * device-local memory.
     */
    boolean hostImportSupported();

    /**
     * Required base-address and size alignment for host-pointer imports
     * ({@code minImportedHostPointerAlignment}), or 0 when {@link #hostImportSupported()} is false.
     */
    long minImportedHostPointerAlignment();

    /**
     * Whether the {@code samplerYcbcrConversion} feature (Vulkan 1.1) was enabled on
     * {@link #vkDevice()}. When true the engine may convert multi-planar YUV content through a
     * {@code VkSamplerYcbcrConversion} immutable sampler instead of its arithmetic compute shader.
     */
    boolean ycbcrSampler();

    /**
     * Schedules a destruction callback to run once the consumer's in-flight frames that might still
     * reference the engine's resources have completed. The engine calls this from {@code release()}
     * and whenever a superseded view retires, instead of destroying its images/views immediately, so
     * a {@code VkImageView} is never destroyed while a render command buffer still samples it. The
     * callback runs on the consumer's render thread; the consumer must also run any still-pending
     * callbacks when it tears down the device.
     */
    void retire(Runnable destroy);

    /**
     * How {@link #vkDevice()} was created, so FFmpeg decodes video with Vulkan Video and the engine
     * samples the decoded images without a trip through host memory. The default, {@code null}, keeps
     * decoding off Vulkan: frames are decoded elsewhere and uploaded from host memory.
     * <p>
     * Decoding needs a Vulkan 1.3 instance and device with {@code timelineSemaphore} and
     * {@code synchronization2} enabled, plus {@code VK_KHR_video_queue}, {@code VK_KHR_video_decode_queue}
     * and the {@code VK_KHR_video_decode_*} extensions of the codecs to accelerate. FFmpeg then decodes in
     * one of two places:
     * <ul>
     *   <li><b>This device</b>, when it also has queues FFmpeg can own: a compute-capable and a
     *   transfer-capable queue other than {@link #queue()}, plus a video decode queue. {@link #queue()}
     *   must be the last queue created in {@link #queueFamily()}. Every other queue of every family goes
     *   to FFmpeg, which submits to them from its own threads, so while players exist the consumer never
     *   touches those queues nor calls {@code vkDeviceWaitIdle}; it waits on its own queue instead.</li>
     *   <li><b>A twin</b>, when the device has no such queues but enables
     *   {@code VK_KHR_external_memory_win32} and {@code VK_KHR_external_semaphore_win32} on Windows, or
     *   {@code VK_KHR_external_memory_fd} and {@code VK_KHR_external_semaphore_fd} elsewhere. The engine
     *   creates a second device on the same GPU with this feature chain and these extensions, FFmpeg
     *   decodes there, and the engine imports every decoded image into this device. The consumer's
     *   queues stay its own.</li>
     * </ul>
     * Either way, every player must be released before the consumer destroys this device or its
     * instance: FFmpeg uses them, the twin included, until its last decoder is gone.
     * <p>
     * The decoded frames then go through libplacebo, which turns any decoded layout into RGBA and maps HDR
     * to SDR, when the device FFmpeg decodes on enables {@code hostQueryReset}, {@code runtimeDescriptorArray},
     * {@code shaderStorageImageArrayNonUniformIndexing} and {@code maintenance4}. On this device libplacebo
     * also needs a graphics queue other than {@link #queue()}, so {@link #queueFamily()} must hold a second
     * queue; a twin enables those features itself when the GPU offers them. Without them the engine
     * converts the decoded frames with its own shaders.
     */
    default DeviceInfo deviceInfo() { return null; }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    /**
     * What a device was created with: the feature chain given to {@code vkCreateDevice} (kept allocated and
     * unchanged while the device lives), the enabled instance and device extensions and the queues created per family index.
     */
    record DeviceInfo(VkPhysicalDeviceFeatures2 features, List<String> instanceExtensions, List<String> deviceExtensions, int[] queueCounts) {}
}
