package org.watermedia.bootstrap.app.render.vulkan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRVideoDecodeQueue.VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;

/**
 * Verifies that the app's Vulkan backend brings up its device over a hidden window and lends it as a {@link VKContext}
 * whose {@link VKContext#deviceInfo()} lets a player decode on it exactly when the device was created with the video
 * extensions. Skipped without a display, a Vulkan loader, a device or the FFmpeg natives.
 */
@DisplayName("VulkanRenderBackend")
public class VulkanRenderBackendTest {

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Test
    @DisplayName("The lent device describes its creation and a player decodes on it exactly when it has the video extensions")
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
                    assumeTrue(false, "no usable Vulkan device: " + e.getMessage());
                }
                final VKContext.DeviceInfo info = backend.deviceInfo();
                assertNotNull(info.features(), "the feature chain stays allocated with the device");
                assertEquals(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, info.features().sType());
                assertTrue(info.deviceExtensions().contains(VK_KHR_SWAPCHAIN_EXTENSION_NAME), "the swapchain extension is enabled");
                assertTrue(info.queueCounts()[backend.queueFamily()] >= 1, "the render family has its queue");
                final boolean decodes = CodecsAPI.vulkanDecode() && info.deviceExtensions().contains(VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME);
                System.out.println("[VulkanRenderBackendTest] " + backend.deviceName() + ": extensions " + info.deviceExtensions() + ", queues "
                        + Arrays.toString(info.queueCounts()) + ", decoding on the device " + decodes);

                // A PLAYER ON THE BACKEND'S ENGINE: ITS FRAMES STAY ON THE DEVICE EXACTLY WHEN THE DEVICE WAS CREATED FOR DECODING
                final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
                assertTrue(mrl.await(10_000L), "the fixture resolves");
                final VulkanRenderBackend context = backend;
                final VKEngine[] engine = new VKEngine[1];
                player = PlayerAPI.player(mrl, () -> engine[0] = PlayerAPI.vkEngine(context), () -> null);
                assertNotNull(player, "a player on the backend's engine");
                final MediaPlayer started = player;
                assertTrue(started.start());
                assertTrue(PlayerWait.awaitStatus(started, 15_000L, MediaPlayer.Status.PLAYING, MediaPlayer.Status.BUFFERING, MediaPlayer.Status.PAUSED));
                assertTrue(PlayerWait.awaitCondition(() -> started.texture() != 0L, 10_000L), "a frame reached the backend's engine");
                final boolean onDevice = log.events().stream().anyMatch(event -> event.getMessage().getFormattedMessage().contains("frames stay on the GPU"));
                assertEquals(decodes, onDevice, "the player decodes on the device exactly when it was created for decoding");
                // THE ENGINE COUNTS THE FRAMES IT SAMPLED IN PLACE; NONE ARRIVE WHEN THE DEVICE DOES NOT DECODE
                if (decodes) assertTrue(PlayerWait.awaitCondition(() -> decodedFrames(engine[0]) >= 3L, 10_000L), "decoded frames reach the engine");
                else assertEquals(0L, decodedFrames(engine[0]), "no decoded frame reaches the engine");
            } finally {
                // THE PLAYER RELEASES ITS ENGINE, WHOSE RETIRED OBJECTS THE BACKEND DESTROYS BEFORE ITS DEVICE
                if (player != null) player.release();
                if (backend != null) backend.cleanup();
                if (window != 0L) glfwDestroyWindow(window);
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
