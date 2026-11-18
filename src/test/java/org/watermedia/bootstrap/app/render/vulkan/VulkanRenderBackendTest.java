package org.watermedia.bootstrap.app.render.vulkan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.watermedia.WaterMedia;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.player.MRL;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.api.player.VKContext;
import org.watermedia.api.player.VKEngine;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;
import org.watermedia.tools.DataTool;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_NO_API;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeAV1.VK_KHR_VIDEO_DECODE_AV1_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeH264.VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeH265.VK_KHR_VIDEO_DECODE_H265_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeQueue.VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeQueue.VK_QUEUE_VIDEO_DECODE_BIT_KHR;
import static org.lwjgl.vulkan.KHRVideoQueue.VK_KHR_VIDEO_QUEUE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * Verifies that the app's Vulkan backend brings up its device over a hidden window, enables the video decode
 * extensions exactly when the GPU offers what decoding needs, and lends the device as a {@link VKContext} whose
 * {@link VKContext#deviceInfo()} lets a player decode on it exactly then. Skipped without a display, a Vulkan loader,
 * a Vulkan device or the FFmpeg natives; any other failure to build the device fails.
 */
@DisplayName("VulkanRenderBackend")
public class VulkanRenderBackendTest {

    private static final long TIMEOUT_MS = 10_000L;
    private static final long PLAY_TIMEOUT_MS = 15_000L;
    private static final List<String> CODECS = List.of(VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME, VK_KHR_VIDEO_DECODE_H265_EXTENSION_NAME,
            VK_KHR_VIDEO_DECODE_AV1_EXTENSION_NAME); // THE DECODE EXTENSIONS THE BACKEND KNOWS

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Test
    @DisplayName("the lent device enables the video extensions the GPU offers, and a player decodes on it exactly then")
    void deviceInfoFeedsDecoding() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "the FFmpeg natives");
        assumeTrue(glfwInit(), "GLFW initializes (a display is present)");
        try {
            assumeTrue(glfwVulkanSupported(), "a Vulkan loader is present");
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            final long window = glfwCreateWindow(64, 64, "VulkanRenderBackendTest", 0L, 0L);
            assumeTrue(window != 0L, "a hidden window");
            VulkanRenderBackend backend = null;
            MediaPlayer player = null;
            try (final LogCapture log = new LogCapture(WaterMedia.ID)) {
                try {
                    backend = new VulkanRenderBackend(window);
                } catch (final IllegalStateException e) {
                    // ONLY A MACHINE WITHOUT A USABLE DEVICE SKIPS; A DEVICE THAT FAILS TO COME UP IS THE REGRESSION THIS TEST CATCHES
                    final String message = String.valueOf(e.getMessage());
                    assumeFalse(message.startsWith(DataTool.errCode(WaterMedia.ERR_APP_VK_NO_DEVICES))
                            || message.startsWith(DataTool.errCode(WaterMedia.ERR_APP_VK_NO_QUEUE_FAMILY)), "no usable Vulkan device: " + message);
                    throw e;
                }
                final VKContext.DeviceInfo info = backend.deviceInfo();
                assertNotNull(info.features(), "the feature chain stays allocated with the device");
                assertEquals(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, info.features().sType());
                assertTrue(info.deviceExtensions().contains(VK_KHR_SWAPCHAIN_EXTENSION_NAME), "the swapchain extension is enabled");
                assertTrue(info.queueCounts()[backend.queueFamily()] >= 1, "the render family has its queue");

                // WHAT THE GPU OFFERS, READ FROM THE PHYSICAL DEVICE AND NOT FROM THE BACKEND: DECODING NEEDS VULKAN 1.3, THE VIDEO
                // QUEUE EXTENSIONS WITH A CODEC, A VIDEO DECODE FAMILY AND A COMPUTE QUEUE APART FROM THE RENDER ONE
                final Set<String> supported = new HashSet<>();
                final boolean offered;
                try (final MemoryStack stack = stackPush()) {
                    final VkPhysicalDevice gpu = backend.physicalDevice();
                    final VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                    vkGetPhysicalDeviceProperties(gpu, props);
                    final int api = Math.min(props.apiVersion(), backend.vkInstance().getCapabilities().apiVersion);
                    final IntBuffer count = stack.mallocInt(1);
                    vkEnumerateDeviceExtensionProperties(gpu, (ByteBuffer) null, count, null);
                    final VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(count.get(0), stack);
                    vkEnumerateDeviceExtensionProperties(gpu, (ByteBuffer) null, count, extensions);
                    for (final VkExtensionProperties extension: extensions) supported.add(extension.extensionNameString());
                    vkGetPhysicalDeviceQueueFamilyProperties(gpu, count, null);
                    final VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
                    vkGetPhysicalDeviceQueueFamilyProperties(gpu, count, families);
                    boolean decodeFamily = false;
                    boolean compute = families.get(backend.queueFamily()).queueCount() >= 2;
                    for (final VkQueueFamilyProperties family: families) {
                        decodeFamily |= (family.queueFlags() & VK_QUEUE_VIDEO_DECODE_BIT_KHR) != 0;
                        compute |= (family.queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0 && (family.queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0;
                    }
                    offered = api >= VK_API_VERSION_1_3 && supported.contains(VK_KHR_VIDEO_QUEUE_EXTENSION_NAME)
                            && supported.contains(VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME) && CODECS.stream().anyMatch(supported::contains)
                            && decodeFamily && compute;
                }
                final Set<String> video = new HashSet<>();
                if (offered) {
                    video.addAll(List.of(VK_KHR_VIDEO_QUEUE_EXTENSION_NAME, VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME));
                    for (final String codec: CODECS) if (supported.contains(codec)) video.add(codec);
                }
                assertEquals(video, info.deviceExtensions().stream().filter(name -> name.startsWith("VK_KHR_video_")).collect(Collectors.toSet()),
                        "the device enables every video decode extension the GPU offers for decoding, and none without it");
                // THE FIXTURE IS H.264, SO ONLY A GPU THAT DECODES H.264 KEEPS ITS FRAMES
                final boolean decodes = CodecsAPI.vulkanDecode() && offered && supported.contains(VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME);
                System.out.println("[VulkanRenderBackendTest] " + backend.deviceName() + ": extensions " + info.deviceExtensions() + ", queues "
                        + Arrays.toString(info.queueCounts()) + ", the GPU offers decoding " + offered + ", decoding on the device " + decodes);

                // A PLAYER ON THE BACKEND'S ENGINE: ITS FRAMES STAY ON THE DEVICE EXACTLY WHEN THE DEVICE WAS CREATED FOR DECODING
                final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
                assertTrue(mrl.await(TIMEOUT_MS), "the fixture resolves");
                final VulkanRenderBackend context = backend;
                final VKEngine[] engine = new VKEngine[1];
                player = PlayerAPI.player(mrl, () -> engine[0] = PlayerAPI.vkEngine(context), () -> null);
                assertNotNull(player, "a player on the backend's engine");
                final MediaPlayer started = player;
                assertTrue(started.start(), "the player starts");
                assertTrue(PlayerWait.awaitStatus(started, PLAY_TIMEOUT_MS, MediaPlayer.Status.PLAYING, MediaPlayer.Status.BUFFERING, MediaPlayer.Status.PAUSED),
                        "the player leaves its opening state");
                assertTrue(PlayerWait.awaitCondition(() -> started.texture() != 0L, TIMEOUT_MS), "a frame reached the backend's engine");
                final boolean onDevice = log.events().stream().anyMatch(event -> event.getMessage().getFormattedMessage().contains("frames stay on the GPU"));
                assertEquals(decodes, onDevice, "the player decodes on the device exactly when it was created for decoding");
                // THE ENGINE COUNTS THE FRAMES IT SAMPLED IN PLACE; NONE ARRIVE WHEN THE DEVICE DOES NOT DECODE
                if (decodes) assertTrue(PlayerWait.awaitCondition(() -> decodedFrames(engine[0]) >= 3L, TIMEOUT_MS), "decoded frames reach the engine");
                else assertEquals(0L, decodedFrames(engine[0]), "no decoded frame reaches the engine");
            } finally {
                // THE PLAYER RELEASES ITS ENGINE, WHOSE RETIRED OBJECTS THE BACKEND DESTROYS BEFORE ITS DEVICE
                if (player != null) player.release();
                if (backend != null) backend.cleanup();
                glfwDestroyWindow(window);
            }
        } finally {
            glfwTerminate();
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // READS THE ENGINE'S PRIVATE COUNTER OF FRAMES SAMPLED IN PLACE; IT IS NOT PART OF THE API
    private static long decodedFrames(final VKEngine engine) {
        try {
            final Field field = VKEngine.class.getDeclaredField("decodedFrames");
            field.setAccessible(true);
            return field.getLong(engine);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("VKEngine has no decodedFrames field", e);
        }
    }
}
