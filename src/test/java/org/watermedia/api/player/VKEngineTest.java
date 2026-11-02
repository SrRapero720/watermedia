package org.watermedia.api.player;

import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVHWDeviceContext;
import org.bytedeco.ffmpeg.avutil.AVHWFramesContext;
import org.bytedeco.ffmpeg.avutil.AVVulkanDeviceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.*;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.decoders.Placebo;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;
import org.watermedia.test.support.YuvFrames;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_PRI_BT709;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_MPEG;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT709;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_TRC_BT709;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_VULKAN;
import static org.bytedeco.ffmpeg.global.avutil.av_buffer_unref;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_clone;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_unref;
import static org.bytedeco.ffmpeg.global.avutil.av_make_q;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_hwframe_ctx_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_hwframe_ctx_init;
import static org.bytedeco.ffmpeg.global.avutil.av_hwframe_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_hwframe_transfer_data;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memAlignedAlloc;
import static org.lwjgl.system.MemoryUtil.memAlignedFree;
import static org.lwjgl.system.MemoryUtil.memCopy;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.*;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.KHRPortabilitySubset.*;
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
import static org.watermedia.test.support.YuvFrames.assertUniform;

/** Verifies {@link VKEngine} conversions and FFmpeg-decoded frames on a headless Vulkan 1.1 device, with and without its optional features. */
@DisplayName("VKEngine")
public class VKEngineTest {

    private static final long FRAME_TIMEOUT_NS = 5_000_000_000L;
    private static final int READBACK_PUSH_BYTES = 8;
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";
    // THE PLATFORM'S OPAQUE-HANDLE MEMORY AND SEMAPHORE EXTENSIONS, WHICH A DEVICE LENDING FFMPEG NO QUEUES ENABLES TO DECODE ON A TWIN
    private static final List<String> EXTERNAL = Platform.get() == Platform.WINDOWS
            ? List.of(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME, VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME)
            : List.of(VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
    // STANDARD LUMA WEIGHTS, WRITTEN OUT SO A WRONG MATRIX CONSTANT CANNOT HIDE BEHIND THE SAME CONSTANT HERE
    private static final double KR_601 = 0.299, KB_601 = 0.114, KR_709 = 0.2126, KB_709 = 0.0722;
    private static final Colorimetry BT601_LIMITED = new Colorimetry(Colorimetry.Matrix.BT601, false, true, false, Colorimetry.Transfer.SDR, false);
    private static final int AV_FRAME_FLAG_INTERLACED = 1 << 3; // libavutil/frame.h; A MACRO THE BINDINGS LEAVE OUT

    // SAMPLES THE PUBLISHED VIEW THE WAY A RENDERER DOES AND PACKS EACH TEXEL INTO A HOST-VISIBLE BUFFER; A COPY WOULD NEED
    // TRANSFER_SRC USAGE, WHICH THE ENGINE'S MANAGED IMAGES DO NOT CARRY
    private static final String READBACK_GLSL = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 0) uniform sampler2D frame;
            layout(binding = 1, std430) writeonly buffer Texels { uint texels[]; };
            layout(push_constant) uniform PC { int width; int height; } pc;

            void main() {
                ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
                if (gid.x >= pc.width || gid.y >= pc.height) return;
                texels[gid.y * pc.width + gid.x] = packUnorm4x8(texelFetch(frame, gid, 0));
            }
            """;

    // ERRORS THE VALIDATION LAYER REPORTED SINCE THE LAST CHECK
    private static final List<String> ERRORS = Collections.synchronizedList(new ArrayList<>());
    // ALIGNED PLANE COPIES AN ENGINE MAY HAVE IMPORTED; FREED ONLY AFTER ITS RELEASE
    private static final List<ByteBuffer> ALIGNED = new ArrayList<>();

    private static VkInstance instance;
    private static VkDebugUtilsMessengerCallbackEXT callback;
    private static long messenger;
    private static VkPhysicalDevice physical;
    // THE SUITE'S DEVICE, ITS RENDER QUEUE AND READBACK
    private static Renderer renderer;
    private static int family;
    private static VkPhysicalDeviceMemoryProperties memory;
    private static boolean ycbcr, hostImport, validation;
    // THE DEVICE CAN SAMPLE 8-BIT 4:2:0 THROUGH A YCBCR CONVERSION AT EITHER CHROMA SITING, SO THE ENGINE MUST ROUTE IT THERE
    private static boolean ycbcr420;
    private static long alignment;
    private static String name;
    // THE FEATURE CHAIN THE DEVICES ARE CREATED WITH (SEE VKContext#deviceInfo), WHETHER THE SUITE'S CAN DECODE VIDEO FOR FFMPEG, WHETHER THE
    // GPU OFFERS THE EXTERNAL EXTENSIONS A TWIN NEEDS AND THE FEATURES LIBPLACEBO AND FFMPEG'S FILTER SHADERS NEED
    private static VkPhysicalDeviceFeatures2 features;
    private static VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrEnabled;
    private static VkPhysicalDeviceVulkan12Features vulkan12Enabled;
    private static VkPhysicalDeviceVulkan13Features vulkan13Enabled;
    private static boolean decoding, shareable, filters;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @BeforeAll
    static void open() {
        MediaBootstrap.client();
        try (final MemoryStack stack = stackPush()) {
            if (VK.getInstanceVersionSupported() < VK_API_VERSION_1_1) throw new IllegalStateException("the Vulkan loader predates 1.1");
            // INSTANCE: KHRONOS VALIDATION AND ITS ERROR MESSENGER WHEN INSTALLED, PORTABILITY ENUMERATION FOR MoltenVK.
            // EXTENSION LISTS GO ON THE HEAP: A DRIVER CAN ADVERTISE MORE THAN THE DEFAULT 64 KiB STACK HOLDS
            final IntBuffer count = stack.mallocInt(1);
            check(vkEnumerateInstanceLayerProperties(count, null), "count layers");
            final VkLayerProperties.Buffer layers = VkLayerProperties.create(count.get(0));
            check(vkEnumerateInstanceLayerProperties(count, layers), "enumerate layers");
            for (final VkLayerProperties layer: layers) validation |= VALIDATION_LAYER.equals(layer.layerNameString());
            check(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null), "count instance extensions");
            final VkExtensionProperties.Buffer instanceExtensions = VkExtensionProperties.create(count.get(0));
            check(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, instanceExtensions), "enumerate instance extensions");
            boolean debug = false, portability = false;
            for (final VkExtensionProperties extension: instanceExtensions) {
                debug |= VK_EXT_DEBUG_UTILS_EXTENSION_NAME.equals(extension.extensionNameString());
                portability |= VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME.equals(extension.extensionNameString());
            }
            debug &= validation;
            final PointerBuffer instanceEnabled = stack.mallocPointer(2);
            if (debug) instanceEnabled.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            if (portability) instanceEnabled.put(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME));
            instanceEnabled.flip();
            // A 1.3 INSTANCE WHEN THE LOADER OFFERS IT: FFMPEG DECODES ONLY THROUGH ONE, AND THE ENGINE STAYS 1.1-CORE
            final int apiVersion = VK.getInstanceVersionSupported() >= VK_API_VERSION_1_3 ? VK_API_VERSION_1_3 : VK_API_VERSION_1_1;
            final VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType$Default()
                    .flags(portability ? VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0)
                    .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default().pApplicationName(stack.UTF8("VKEngineTest")).apiVersion(apiVersion))
                    .ppEnabledLayerNames(validation ? stack.pointers(stack.UTF8(VALIDATION_LAYER)) : null)
                    .ppEnabledExtensionNames(instanceEnabled);
            final PointerBuffer handle = stack.mallocPointer(1);
            check(vkCreateInstance(ici, null, handle), "create instance");
            instance = new VkInstance(handle.get(0), ici);
            final LongBuffer p = stack.mallocLong(1);
            if (debug) {
                callback = VkDebugUtilsMessengerCallbackEXT.create((final int severity, final int types, final long data, final long user) -> {
                    ERRORS.add(VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
                    return VK_FALSE;
                });
                check(vkCreateDebugUtilsMessengerEXT(instance, VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                        .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback(callback), null, p), "create debug messenger");
                messenger = p.get(0);
            }

            // THE FIRST 1.1 DEVICE WITH A GRAPHICS AND COMPUTE FAMILY: THE ENGINE'S BARRIERS NAME THE FRAGMENT STAGE, WHICH A
            // COMPUTE-ONLY QUEUE REJECTS, SO IT GETS THE KIND OF QUEUE A RENDERER LENDS
            check(vkEnumeratePhysicalDevices(instance, count, null), "count devices");
            final PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, devices), "enumerate devices");
            final VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            final int queueFlags = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
            for (int i = 0; i < devices.capacity() && physical == null; i++) {
                final VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
                vkGetPhysicalDeviceProperties(candidate, props);
                if (props.apiVersion() < VK_API_VERSION_1_1) continue;
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
                final VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
                for (int f = 0; f < families.capacity() && physical == null; f++) {
                    if ((families.get(f).queueFlags() & queueFlags) != queueFlags) continue;
                    physical = candidate;
                    family = f;
                    name = props.deviceNameString();
                }
            }
            if (physical == null) throw new IllegalStateException("no Vulkan 1.1 device with a graphics and compute queue");

            // OPTIONAL FEATURES THE ENGINE CAN USE: THE YCBCR SAMPLER AND ZERO-COPY HOST IMPORT, AND THE VIDEO DECODING FFMPEG RUNS ON
            // A 1.3 DEVICE WITH THE VIDEO EXTENSIONS, TIMELINE SEMAPHORES, synchronization2 AND QUEUES OF ITS OWN NEXT TO THE RENDER QUEUE
            final int api = Math.min(props.apiVersion(), apiVersion);
            final VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeatures = VkPhysicalDeviceSamplerYcbcrConversionFeatures.calloc(stack).sType$Default();
            final VkPhysicalDeviceVulkan12Features vulkan12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            final VkPhysicalDeviceVulkan13Features vulkan13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
            if (api >= VK_API_VERSION_1_2) ycbcrFeatures.pNext(vulkan12.address());
            if (api >= VK_API_VERSION_1_3) vulkan12.pNext(vulkan13.address());
            // RAW ADDRESSES: LWJGL'S TYPED pNext PREPENDS AND WOULD CUT THE CHAIN BEHIND THE STRUCT
            vkGetPhysicalDeviceFeatures2(physical, VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(ycbcrFeatures.address()));
            ycbcr = ycbcrFeatures.samplerYcbcrConversion();
            final VkFormatProperties planar = VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(physical, VK_FORMAT_G8_B8_R8_3PLANE_420_UNORM, planar);
            final int planarNeed = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT
                    | VK_FORMAT_FEATURE_COSITED_CHROMA_SAMPLES_BIT | VK_FORMAT_FEATURE_MIDPOINT_CHROMA_SAMPLES_BIT;
            ycbcr420 = ycbcr && (planar.optimalTilingFeatures() & planarNeed) == planarNeed;
            check(vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, null), "count device extensions");
            final VkExtensionProperties.Buffer deviceExtensions = VkExtensionProperties.create(count.get(0));
            check(vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, deviceExtensions), "enumerate device extensions");
            boolean subset = false, videoQueue = false, decodeQueue = false;
            int external = 0;
            final List<String> codecs = new ArrayList<>();
            for (final VkExtensionProperties extension: deviceExtensions) {
                final String name = extension.extensionNameString();
                hostImport |= VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME.equals(name);
                subset |= VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME.equals(name);
                videoQueue |= VK_KHR_VIDEO_QUEUE_EXTENSION_NAME.equals(name);
                decodeQueue |= VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME.equals(name);
                if (EXTERNAL.contains(name)) external++;
                if (VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME.equals(name) || VK_KHR_VIDEO_DECODE_H265_EXTENSION_NAME.equals(name)
                        || VK_KHR_VIDEO_DECODE_AV1_EXTENSION_NAME.equals(name)) codecs.add(name);
            }
            shareable = external == EXTERNAL.size();
            if (hostImport) {
                final VkPhysicalDeviceExternalMemoryHostPropertiesEXT host = VkPhysicalDeviceExternalMemoryHostPropertiesEXT.calloc(stack).sType$Default();
                vkGetPhysicalDeviceProperties2(physical, VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(host.address()));
                alignment = host.minImportedHostPointerAlignment();
            }
            final List<String> enabled = new ArrayList<>();
            if (hostImport) enabled.add(VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME);
            if (subset) enabled.add(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);
            // FFMPEG'S QUEUES: A DECODE FAMILY AND COMPUTE/TRANSFER QUEUES THAT ARE NOT THE RENDER QUEUE, WHICH STAYS THE LAST OF ITS FAMILY
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            final VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
            final int[] counts = new int[families.capacity()];
            counts[family] = 1;
            if (api >= VK_API_VERSION_1_3 && videoQueue && decodeQueue && !codecs.isEmpty() && vulkan12.timelineSemaphore() && vulkan13.synchronization2()) {
                int decode = -1, compute = -1, transfer = -1;
                for (int f = 0; f < families.capacity(); f++) {
                    final int flags = families.get(f).queueFlags();
                    if (decode < 0 && (flags & VK_QUEUE_VIDEO_DECODE_BIT_KHR) != 0) decode = f;
                    if (compute < 0 && (flags & VK_QUEUE_COMPUTE_BIT) != 0 && (flags & VK_QUEUE_GRAPHICS_BIT) == 0) compute = f;
                    if (transfer < 0 && (flags & VK_QUEUE_TRANSFER_BIT) != 0 && (flags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT | VK_QUEUE_VIDEO_DECODE_BIT_KHR)) == 0) transfer = f;
                }
                // LIBPLACEBO ALSO TAKES A GRAPHICS QUEUE, THE FIRST OF THE RENDER FAMILY WHEN IT HAS TWO, LIKE THE APP'S DEVICE
                final boolean spare = families.get(family).queueCount() >= 2;
                if (compute < 0 && spare) compute = family;
                if (decode >= 0 && compute >= 0) {
                    counts[decode] = 1;
                    if (compute != family) counts[compute] = 1;
                    if (spare) counts[family] = 2;
                    if (transfer >= 0) counts[transfer] = 1;
                    enabled.add(VK_KHR_VIDEO_QUEUE_EXTENSION_NAME);
                    enabled.add(VK_KHR_VIDEO_DECODE_QUEUE_EXTENSION_NAME);
                    enabled.addAll(codecs);
                    decoding = true;
                }
            }
            // THE FEATURE CHAIN OUTLIVES THE DEVICES: FFMPEG READS IT THROUGH VKContext#deviceInfo
            features = VkPhysicalDeviceFeatures2.calloc().sType$Default();
            ycbcrEnabled = VkPhysicalDeviceSamplerYcbcrConversionFeatures.calloc().sType$Default().samplerYcbcrConversion(ycbcr);
            features.pNext(ycbcrEnabled.address());
            // PLUS WHAT LIBPLACEBO AND FFMPEG'S FILTER SHADERS NEED, AS THE APP'S DEVICE ENABLES IT
            filters = api >= VK_API_VERSION_1_3 && vulkan12.hostQueryReset() && vulkan12.runtimeDescriptorArray()
                    && vulkan12.shaderStorageImageArrayNonUniformIndexing() && vulkan13.maintenance4();
            if (api >= VK_API_VERSION_1_2) {
                vulkan12Enabled = VkPhysicalDeviceVulkan12Features.calloc().sType$Default().timelineSemaphore(vulkan12.timelineSemaphore())
                        .hostQueryReset(vulkan12.hostQueryReset()).runtimeDescriptorArray(vulkan12.runtimeDescriptorArray())
                        .shaderStorageImageArrayNonUniformIndexing(vulkan12.shaderStorageImageArrayNonUniformIndexing());
                ycbcrEnabled.pNext(vulkan12Enabled.address());
            }
            if (api >= VK_API_VERSION_1_3) {
                vulkan13Enabled = VkPhysicalDeviceVulkan13Features.calloc().sType$Default().synchronization2(vulkan13.synchronization2())
                        .maintenance4(vulkan13.maintenance4());
                vulkan12Enabled.pNext(vulkan13Enabled.address());
            }
            final List<String> instanceNames = new ArrayList<>();
            if (debug) instanceNames.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            if (portability) instanceNames.add(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
            memory = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(physical, memory);
            renderer = new Renderer(new VKContext.DeviceInfo(features, instanceNames, enabled, counts));
        } catch (final RuntimeException | LinkageError e) {
            close();
            assumeTrue(false, "No usable Vulkan 1.1 device, skipping: " + e);
        } catch (final OutOfMemoryError e) {
            // LWJGL REPORTS AN EXHAUSTED THREAD STACK AS AN OOM, WHICH ABORTS THE WHOLE TEST RUN: new VkInstance LISTS EVERY
            // DEVICE EXTENSION ON THAT STACK, AND SOME DRIVERS ADVERTISE MORE THAN ITS DEFAULT 64 KiB HOLD
            close();
            throw new AssertionError("Vulkan is present but the LWJGL stack cannot list its extensions; raise org.lwjgl.system.stackSize", e);
        }
        System.out.println("[VKEngineTest] " + name + ": samplerYcbcrConversion=" + ycbcr + " (4:2:0 " + ycbcr420 + "), hostImport=" + hostImport
                + " (alignment " + alignment + "), videoDecode=" + decoding + ", external handles=" + shareable + ", filter features=" + filters
                + ", validation layer " + (validation ? "on" : "not installed"));
    }

    // UNWINDS WHATEVER open() BUILT, NEWEST FIRST; THE RENDERER DRAINS AND RUNS THE RETIRED CALLBACKS BEFORE ITS DEVICE DIES
    @AfterAll
    static void close() {
        if (renderer != null) renderer.close();
        renderer = null;
        if (vulkan13Enabled != null) vulkan13Enabled.free();
        if (vulkan12Enabled != null) vulkan12Enabled.free();
        if (ycbcrEnabled != null) ycbcrEnabled.free();
        if (features != null) features.free();
        vulkan13Enabled = null;
        vulkan12Enabled = null;
        ycbcrEnabled = null;
        features = null;
        if (memory != null) memory.free();
        memory = null;
        if (messenger != VK_NULL_HANDLE) vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
        messenger = VK_NULL_HANDLE;
        if (instance != null) vkDestroyInstance(instance, null);
        instance = null;
        if (callback != null) callback.free();
        callback = null;
    }

    @AfterEach
    void validation() {
        // KHRONOS VALIDATION ERRORS FAIL THE TEST WHEN THE LAYER IS INSTALLED
        final List<String> errors = List.copyOf(ERRORS);
        ERRORS.clear();
        assertTrue(errors.isEmpty(), "Vulkan validation errors: " + errors);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // A DEVICE ON THE SUITE'S GPU THAT LENDS FFMPEG NO QUEUE: THE SUITE'S FEATURE CHAIN AND EXTENSIONS PLUS THE EXTERNAL ONES, BUT ONLY THE
    // RENDER QUEUE, SO AN ENGINE ON IT DECODES ON A TWIN AND IMPORTS EVERY DECODED IMAGE
    private static Renderer bare() {
        final List<String> extensions = new ArrayList<>(renderer.deviceInfo().deviceExtensions());
        extensions.addAll(EXTERNAL);
        final int[] counts = new int[renderer.deviceInfo().queueCounts().length];
        counts[family] = 1;
        return new Renderer(new VKContext.DeviceInfo(features, renderer.deviceInfo().instanceExtensions(), extensions, counts));
    }

    // A 64x32 NV12 POOL ON deviceRef AND ITS FIRST FRAME, HOLDING A 60x30 PICTURE FILLED THE WAY FFMPEG UPLOADS SOFTWARE FRAMES INTO IT: Y 120,
    // Cb 90, Cr 200 INSIDE THE PICTURE AND BLACK PADDING BEYOND IT, OR THE PICTURE'S COLOR AS AN ENCODER'S EDGE EXTENSION WHEN NOT black.
    // THE CALLER UNREFERENCES THE POOL AND FREES BOTH FRAMES
    private static AVBufferRef picture(final AVBufferRef deviceRef, final AVFrame hw, final AVFrame sw, final boolean black) {
        final int w = 64, h = 32, visibleW = 60, visibleH = 30;
        final AVBufferRef framesRef = av_hwframe_ctx_alloc(deviceRef);
        final AVHWFramesContext fc = new AVHWFramesContext(framesRef.data());
        fc.format(AV_PIX_FMT_VULKAN).sw_format(AV_PIX_FMT_NV12).width(w).height(h).initial_pool_size(2);
        assertTrue(av_hwframe_ctx_init(framesRef) >= 0, "the device allocates NV12 pools");
        assertTrue(av_hwframe_get_buffer(framesRef, hw, 0) >= 0, "a pool frame");
        sw.format(AV_PIX_FMT_NV12).width(w).height(h);
        assertTrue(av_frame_get_buffer(sw, 0) >= 0, "a software frame");
        for (int row = 0; row < h; row++)
            for (int col = 0; col < w; col++) {
                final boolean inside = !black || row < visibleH && col < visibleW;
                sw.data(0).put((long) row * sw.linesize(0) + col, (byte) (inside ? 120 : 16));
            }
        for (int row = 0; row < h / 2; row++)
            for (int col = 0; col < w / 2; col++) {
                final boolean inside = !black || row < visibleH / 2 && col < visibleW / 2;
                sw.data(1).put((long) row * sw.linesize(1) + col * 2L, (byte) (inside ? 90 : 128));
                sw.data(1).put((long) row * sw.linesize(1) + col * 2L + 1, (byte) (inside ? 200 : 128));
            }
        assertTrue(av_hwframe_transfer_data(hw, sw, 0) >= 0, "the upload into the pool frame");
        hw.width(visibleW).height(visibleH);
        return framesRef;
    }

    // THE ENGINE SAMPLES THE FRAME OF picture AT FULL AND HALF SIZE, READ BACK THROUGH on
    private static void poolFrame(final Renderer on, final VKEngine engine, final AVBufferRef deviceRef) {
        final int visibleW = 60, visibleH = 30;
        final AVFrame hw = av_frame_alloc(), sw = av_frame_alloc();
        final AVBufferRef framesRef = picture(deviceRef, hw, sw, true);
        try {
            assertTrue(engine.samples(hw), "the engine samples the pool's frames instead of bringing them down to RAM");

            // THE LAST COLUMN AND ROW BLEND THEIR CHROMA WITH THE PADDING, AS AN ENCODER'S EDGE EXTENSION WOULD HIDE; EVERY
            // OTHER PIXEL IS THE PICTURE, WHICH WITHOUT THE CROP WOULD ALREADY SAMPLE THE PADDING FROM COLUMN 56 ON
            final int[] bt601 = YuvFrames.rgb(KR_601, KB_601, false, 8, 120, 90, 200);
            for (final int divisor: new int[] { 1, 2 }) {
                final int outW = visibleW / divisor, outH = visibleH / divisor;
                assertTrue(engine.format(PixelFormat.NV12, outW, outH, 8, BT601_LIMITED));
                // HANDED OVER AS THE PLAYER DOES, THEN READ BACK ONCE texture() PUBLISHES IT
                final long before = engine.texture();
                engine.upload(hw);
                assertTrue(PlayerWait.awaitCondition(() -> engine.texture() != before, TimeUnit.NANOSECONDS.toMillis(FRAME_TIMEOUT_NS)),
                        "the decoded frame was published within the timeout");
                System.out.println("[VKEngineTest] decoded frame " + outW + "x" + outH + " -> " + ((int) field(engine, "ycbcrFmt") != 0 ? "YCbCr sampler" : "compute"));
                final ByteBuffer rgba = on.readback(engine.texture(), outW, outH);
                for (int y = 0; y < outH - 1; y++)
                    for (int x = 0; x < outW - 1; x++)
                        for (int c = 0; c < 3; c++) {
                            final int shown = rgba.get((y * outW + x) * 4 + c) & 0xFF;
                            assertTrue(Math.abs(shown - bt601[c]) <= 3, "1/" + divisor + " size: pixel " + x + "," + y + " channel " + c + " shows " + shown + " instead of " + bt601[c]);
                        }
            }
            assertEquals(2L, (long) field(engine, "decodedFrames"));
        } finally {
            // THE ENGINE KEEPS ITS OWN REFERENCES TO THE FRAME AND ITS POOL UNTIL IT IS RELEASED
            av_frame_free(hw);
            av_frame_free(sw);
            if (framesRef != null) av_buffer_unref(framesRef);
        }
    }

    // THE EDGE-EXTENDED FRAME OF picture, TAGGED BT.709 LIMITED AND MARKED INTERLACED (WHICH MUST NOT STALL THE GRAPH), THROUGH THE DECODER'S LIBPLACEBO
    // GRAPH: THE RGBA IT SENDS HAS THE PICTURE'S SIZE, AND THE ENGINE SHOWS IT AT FULL AND HALF SIZE WITH THE PICTURE'S BT.709 COLOR, READ BACK THROUGH on
    private static void placeboFrame(final Renderer on, final VKEngine engine, final AVBufferRef deviceRef) {
        final int visibleW = 60, visibleH = 30;
        final AVFrame hw = av_frame_alloc(), sw = av_frame_alloc(), out = av_frame_alloc();
        final AVBufferRef framesRef = picture(deviceRef, hw, sw, false);
        Placebo placebo = null;
        try {
            hw.colorspace(AVCOL_SPC_BT709).color_range(AVCOL_RANGE_MPEG).color_primaries(AVCOL_PRI_BT709).color_trc(AVCOL_TRC_BT709)
                    .flags(hw.flags() | AV_FRAME_FLAG_INTERLACED);
            placebo = Placebo.open(hw, deviceRef, av_make_q(1, 25));
            // ONE FRAME COMES OUT ONCE THE NEXT ONE GOES IN, THE LAST ONE ON THE FLUSH (IT NEEDS ITS DURATION); THE GRAPH TAKES EACH PUSHED REFERENCE
            for (int pts = 0; pts < 2; pts++) {
                final AVFrame copy = av_frame_clone(hw);
                copy.pts(pts).duration(1);
                assertTrue(placebo.push(copy) >= 0, "libplacebo takes the decoded frame");
                av_frame_free(copy);
            }
            assertTrue(placebo.pull(out) >= 0, "libplacebo sends the first frame once the second arrives");
            av_frame_unref(out);
            assertTrue(placebo.push(null) >= 0, "libplacebo takes the flush");
            assertTrue(placebo.pull(out) >= 0, "libplacebo sends the last frame on the flush");
            assertEquals(AV_PIX_FMT_VULKAN, out.format(), "the frame stays on the GPU");
            assertEquals(AV_PIX_FMT_RGBA, new AVHWFramesContext(out.hw_frames_ctx().data()).sw_format(), "libplacebo sends RGBA");
            assertEquals(visibleW, out.width(), "the picture's width, without the pool's padding");
            assertEquals(visibleH, out.height(), "the picture's height, without the pool's padding");
            assertTrue(engine.samples(out), "the engine samples libplacebo's frames instead of bringing them down to RAM");

            // LIBPLACEBO DITHERS ITS 8-BIT OUTPUT, SO EACH CHANNEL MAY SIT A CODE OR TWO OFF THE EXACT CONVERSION
            final int[] bt709 = YuvFrames.rgb(KR_709, KB_709, false, 8, 120, 90, 200);
            for (final int divisor: new int[] { 1, 2 }) {
                final int outW = visibleW / divisor, outH = visibleH / divisor;
                assertTrue(engine.format(PixelFormat.RGBA, outW, outH, 8, Colorimetry.guess(PixelFormat.RGBA, outW, outH)));
                final long before = engine.texture();
                engine.upload(out);
                assertTrue(PlayerWait.awaitCondition(() -> engine.texture() != before, TimeUnit.NANOSECONDS.toMillis(FRAME_TIMEOUT_NS)),
                        "the filtered frame was published within the timeout");
                final ByteBuffer rgba = on.readback(engine.texture(), outW, outH);
                for (int y = 0; y < outH; y++)
                    for (int x = 0; x < outW; x++)
                        for (int c = 0; c < 3; c++) {
                            final int shown = rgba.get((y * outW + x) * 4 + c) & 0xFF;
                            assertTrue(Math.abs(shown - bt709[c]) <= 3, "1/" + divisor + " size: pixel " + x + "," + y + " channel " + c + " shows " + shown + " instead of " + bt709[c]);
                        }
            }
            assertEquals(2L, (long) field(engine, "decodedFrames"));
        } finally {
            // THE ENGINE KEEPS ITS OWN REFERENCES TO THE FRAME AND ITS POOL UNTIL IT IS RELEASED
            if (placebo != null) placebo.close();
            av_frame_free(out);
            av_frame_free(hw);
            av_frame_free(sw);
            av_buffer_unref(framesRef);
        }
    }

    // UPLOADS ONE FRAME AS A PRODUCER DOES (ALIGNED COPIES WHEN THE ENGINE CAN IMPORT THEM), WAITS FOR texture() TO PUBLISH
    // IT AND READS THE PUBLISHED VIEW BACK AS RGBA BYTES, ROW 0 FIRST
    private static ByteBuffer frame(final VKEngine engine, final ByteBuffer[] planes, final int[] strides) {
        final long before = engine.texture();
        final int align = engine.alignment();
        final ByteBuffer[] sent = planes.clone();
        for (int i = 0; i < planes.length && align > 0; i++) {
            sent[i] = memAlignedAlloc(align, (planes[i].remaining() + align - 1) / align * align);
            memCopy(planes[i], sent[i]);
            ALIGNED.add(sent[i]);
        }
        engine.upload(sent, strides);
        assertTrue(PlayerWait.awaitCondition(() -> engine.texture() != before, TimeUnit.NANOSECONDS.toMillis(FRAME_TIMEOUT_NS)),
                "the frame was published within the timeout");
        final long view = engine.texture();

        final String route = ((int) field(engine, "ycbcrFmt") != 0 ? "YCbCr sampler" : "compute") + ", "
                + (((Map<?, ?>) field(engine, "importCache")).isEmpty() ? "staging" : "host import");
        final Object ctx = field(engine, "ctx");
        System.out.println("[VKEngineTest] " + ctx + " " + engine.format() + " " + engine.width() + "x" + engine.height()
                + " " + engine.bits() + "-bit " + engine.color + " -> " + route);
        if (ctx == Features.FALLBACK) assertEquals("compute, staging", route, "the fallback context forces the compute shader and staging copies");
        return renderer.readback(view, engine.width(), engine.height());
    }

    // RELEASES AN ENGINE LIKE ITS RENDERER: RETIRED OBJECTS DIE ONCE THE DEVICE IT WAS LENT IS IDLE (A FEATURES CONTEXT LENDS THE
    // SUITE'S), AND ONLY THEN THE IMPORTED PLANES
    private static void release(final VKEngine engine) {
        engine.release();
        (field(engine, "ctx") instanceof final Renderer lent ? lent : renderer).idle();
        for (final ByteBuffer plane: ALIGNED) memAlignedFree(plane);
        ALIGNED.clear();
    }

    // READS A PRIVATE FIELD OF THE ENGINE: ITS ROUTE AND IMPORT STATE ARE NOT PART OF THE API
    private static Object field(final VKEngine engine, final String member) {
        try {
            final Field field = VKEngine.class.getDeclaredField(member);
            field.setAccessible(true);
            return field.get(engine);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("VKEngine has no field " + member, e);
        }
    }

    private static void check(final int result, final String op) {
        if (result != VK_SUCCESS) throw new IllegalStateException(op + " failed (VkResult " + result + ")");
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // THE SUITE'S DEVICE AS ITS RENDERER LENDS IT: DEVICE OFFERS EVERY FEATURE IT HAS, FALLBACK HIDES THE YCBCR SAMPLER AND HOST
    // IMPORT SO EVERY FRAME TAKES THE COMPUTE SHADER AND STAGING COPIES
    enum Features implements VKContext {
        DEVICE, FALLBACK;

        @Override public VkInstance vkInstance() { return renderer.vkInstance(); }
        @Override public VkPhysicalDevice physicalDevice() { return renderer.physicalDevice(); }
        @Override public VkDevice vkDevice() { return renderer.vkDevice(); }
        @Override public VkQueue queue() { return renderer.queue(); }
        @Override public int queueFamily() { return renderer.queueFamily(); }
        @Override public Object queueLock() { return renderer.queueLock(); }
        @Override public VkPhysicalDeviceMemoryProperties memoryProperties() { return renderer.memoryProperties(); }
        @Override public boolean hostImportSupported() { return this == DEVICE && renderer.hostImportSupported(); }
        @Override public long minImportedHostPointerAlignment() { return this.hostImportSupported() ? alignment : 0L; }
        @Override public boolean ycbcrSampler() { return this == DEVICE && renderer.ycbcrSampler(); }
        @Override public void retire(final Runnable destroy) { renderer.retire(destroy); }
        @Override public DeviceInfo deviceInfo() { return this == DEVICE ? renderer.deviceInfo() : null; }
    }

    // A DEVICE ON THE SUITE'S GPU AS A RENDERER HOLDS IT: ITS RENDER QUEUE AND THE LOCK AROUND IT, THE CALLBACKS ITS ENGINES RETIRE
    // AND THE OBJECTS THAT READ BACK WHAT AN ENGINE PUBLISHES. THE SUITE OPENS ONE AND A TEST MAY OPEN ANOTHER
    private static final class Renderer implements VKContext {
        private final DeviceInfo info;
        private final VkDevice device;
        private final VkQueue queue;
        // ONE QUEUE SHARED BY THE ENGINES AND THE READBACK, SERIALIZED LIKE A RENDERER'S
        private final Object lock = new Object();
        // DESTRUCTION CALLBACKS THE ENGINES HAND TO retire(), RUN ONCE THE DEVICE IS IDLE
        private final List<Runnable> retired = new ArrayList<>();
        // READBACK: ONE COMMAND BUFFER AND FENCE, A NEAREST SAMPLER AND THE PACKING PIPELINE
        private VkCommandBuffer cmd;
        private long cmdPool, fence, sampler, setLayout, descPool, descSet, pipeLayout, pipeline;

        // CREATES THE DEVICE info DESCRIBES, TAKES THE LAST QUEUE OF THE RENDER FAMILY AS ITS RENDER QUEUE AND BUILDS THE READBACK;
        // A FAILURE DESTROYS WHATEVER WAS BUILT
        Renderer(final DeviceInfo info) {
            this.info = info;
            try (final MemoryStack stack = stackPush()) {
                final int[] counts = info.queueCounts();
                int used = 0;
                for (final int queues: counts) if (queues > 0) used++;
                final VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(used, stack);
                for (int f = 0, j = 0; f < counts.length; f++) {
                    if (counts[f] <= 0) continue;
                    final FloatBuffer priorities = stack.mallocFloat(counts[f]);
                    for (int k = 0; k < counts[f]; k++) priorities.put(k, 1.0f);
                    queues.get(j++).sType$Default().queueFamilyIndex(f).pQueuePriorities(priorities);
                }
                final PointerBuffer extensions = stack.mallocPointer(info.deviceExtensions().size());
                for (final String extension: info.deviceExtensions()) extensions.put(stack.UTF8(extension));
                final VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default()
                        .pNext(info.features().address()).pQueueCreateInfos(queues).ppEnabledExtensionNames(extensions.flip());
                final PointerBuffer handle = stack.mallocPointer(1);
                check(vkCreateDevice(physical, dci, null, handle), "create device");
                this.device = new VkDevice(handle.get(0), physical, dci);
                vkGetDeviceQueue(this.device, family, counts[family] - 1, handle);
                this.queue = new VkQueue(handle.get(0), this.device);

                final LongBuffer p = stack.mallocLong(1);
                check(vkCreateCommandPool(this.device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, p), "create command pool");
                this.cmdPool = p.get(0);
                check(vkAllocateCommandBuffers(this.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(this.cmdPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), handle), "allocate command buffer");
                this.cmd = new VkCommandBuffer(handle.get(0), this.device);
                check(vkCreateFence(this.device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, p), "create fence");
                this.fence = p.get(0);
                check(vkCreateSampler(this.device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, p), "create sampler");
                this.sampler = p.get(0);
                final VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
                bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                check(vkCreateDescriptorSetLayout(this.device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, p), "create set layout");
                this.setLayout = p.get(0);
                final VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
                check(vkCreateDescriptorPool(this.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, p), "create descriptor pool");
                this.descPool = p.get(0);
                check(vkAllocateDescriptorSets(this.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(this.descPool).pSetLayouts(stack.longs(this.setLayout)), p), "allocate descriptor set");
                this.descSet = p.get(0);
                final VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
                range.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(READBACK_PUSH_BYTES);
                check(vkCreatePipelineLayout(this.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                        .pSetLayouts(stack.longs(this.setLayout)).pPushConstantRanges(range), null, p), "create pipeline layout");
                this.pipeLayout = p.get(0);
                // SPIR-V FROM shaderc, THE COMPILER THE ENGINE ITSELF USES
                final long compiler = shaderc_compiler_initialize();
                if (compiler == NULL) throw new IllegalStateException("shaderc did not initialize");
                final long result = shaderc_compile_into_spv(compiler, READBACK_GLSL, shaderc_compute_shader, "readback.comp", "main", NULL);
                try {
                    if (result == NULL || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                        throw new IllegalStateException("the readback shader did not compile: " + (result == NULL ? "no result" : shaderc_result_get_error_message(result)));
                    check(vkCreateShaderModule(this.device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(shaderc_result_get_bytes(result)), null, p), "create shader module");
                } finally {
                    if (result != NULL) shaderc_result_release(result);
                    shaderc_compiler_release(compiler);
                }
                final long module = p.get(0);
                final VkComputePipelineCreateInfo.Buffer pipelines = VkComputePipelineCreateInfo.calloc(1, stack);
                pipelines.get(0).sType$Default().layout(this.pipeLayout)
                        .stage(s -> s.sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main")));
                final int created = vkCreateComputePipelines(this.device, VK_NULL_HANDLE, pipelines, null, p);
                vkDestroyShaderModule(this.device, module, null);
                check(created, "create readback pipeline");
                this.pipeline = p.get(0);
            } catch (final RuntimeException | Error e) {
                this.close();
                throw e;
            }
        }

        // ======================================================================
        // PUBLIC API
        // ======================================================================
        @Override public VkInstance vkInstance() { return instance; }
        @Override public VkPhysicalDevice physicalDevice() { return physical; }
        @Override public VkDevice vkDevice() { return this.device; }
        @Override public VkQueue queue() { return this.queue; }
        @Override public int queueFamily() { return family; }
        @Override public Object queueLock() { return this.lock; }
        @Override public VkPhysicalDeviceMemoryProperties memoryProperties() { return memory; }
        @Override public boolean hostImportSupported() { return hostImport; }
        @Override public long minImportedHostPointerAlignment() { return alignment; }
        @Override public boolean ycbcrSampler() { return ycbcr; }
        @Override public void retire(final Runnable destroy) { this.retired.add(destroy); }
        @Override public DeviceInfo deviceInfo() { return this.info; }

        // ======================================================================
        // INTERNAL API
        // ======================================================================
        // SAMPLES THE VIEW WITH THE READBACK PIPELINE INTO A HOST-VISIBLE BUFFER AND RETURNS ITS RGBA BYTES
        ByteBuffer readback(final long view, final int width, final int height) {
            final int bytes = width * height * 4;
            try (final MemoryStack stack = stackPush()) {
                final LongBuffer p = stack.mallocLong(1);
                check(vkCreateBuffer(this.device, VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                        .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, p), "create readback buffer");
                final long buffer = p.get(0);
                long mem = VK_NULL_HANDLE;
                try {
                    final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
                    vkGetBufferMemoryRequirements(this.device, buffer, req);
                    final int wanted = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                    int type = -1;
                    for (int i = 0; i < memory.memoryTypeCount() && type < 0; i++)
                        if ((req.memoryTypeBits() & 1 << i) != 0 && (memory.memoryTypes(i).propertyFlags() & wanted) == wanted) type = i;
                    check(vkAllocateMemory(this.device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                            .allocationSize(req.size()).memoryTypeIndex(type), null, p), "allocate readback memory");
                    mem = p.get(0);
                    check(vkBindBufferMemory(this.device, buffer, mem, 0), "bind readback memory");
                    final PointerBuffer mapped = stack.mallocPointer(1);
                    check(vkMapMemory(this.device, mem, 0, bytes, 0, mapped), "map readback memory");

                    // BINDING 0 = THE PUBLISHED VIEW IN THE LAYOUT THE ENGINE HANDS OVER, BINDING 1 = THE HOST-VISIBLE BUFFER
                    final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
                    writes.get(0).sType$Default().dstSet(this.descSet).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(this.sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
                    writes.get(1).sType$Default().dstSet(this.descSet).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer).offset(0).range(VK_WHOLE_SIZE));
                    vkUpdateDescriptorSets(this.device, writes, null);

                    check(vkResetCommandBuffer(this.cmd, 0), "reset readback commands");
                    check(vkBeginCommandBuffer(this.cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin readback");
                    // THE ENGINE HANDED ITS WRITES TO FRAGMENT SAMPLING; THIS MAKES THEM VISIBLE TO THE READBACK COMPUTE STAGE TOO
                    vkCmdPipelineBarrier(this.cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                            VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT), null, null);
                    vkCmdBindPipeline(this.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
                    vkCmdBindDescriptorSets(this.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeLayout, 0, stack.longs(this.descSet), null);
                    vkCmdPushConstants(this.cmd, this.pipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(width, height));
                    vkCmdDispatch(this.cmd, (width + 7) / 8, (height + 7) / 8, 1);
                    vkCmdPipelineBarrier(this.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
                            VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT), null, null);
                    check(vkEndCommandBuffer(this.cmd), "end readback");
                    synchronized (this.lock) {
                        check(vkQueueSubmit(this.queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(this.cmd)), this.fence), "submit readback");
                    }
                    check(vkWaitForFences(this.device, this.fence, true, FRAME_TIMEOUT_NS), "wait for the readback");
                    check(vkResetFences(this.device, this.fence), "reset readback fence");
                    final ByteBuffer rgba = BufferUtils.createByteBuffer(bytes);
                    memCopy(mapped.get(0), memAddress(rgba), bytes);
                    return rgba;
                } finally {
                    vkDestroyBuffer(this.device, buffer, null);
                    if (mem != VK_NULL_HANDLE) vkFreeMemory(this.device, mem, null); // FREEING ALSO UNMAPS
                }
            }
        }

        // WAITS FOR EVERY SUBMISSION ON THE DEVICE, THEN RUNS THE DESTRUCTION CALLBACKS ITS ENGINES RETIRED
        void idle() {
            synchronized (this.lock) {
                check(vkDeviceWaitIdle(this.device), "wait for the device");
            }
            for (final Runnable destroy: this.retired) destroy.run();
            this.retired.clear();
        }

        // DRAINS THE DEVICE AND RUNS THE RETIRED CALLBACKS, THEN DESTROYS THE READBACK, NEWEST FIRST, AND THE DEVICE ITSELF
        void close() {
            if (this.device == null) return;
            this.idle();
            if (this.pipeline != VK_NULL_HANDLE) vkDestroyPipeline(this.device, this.pipeline, null);
            if (this.pipeLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(this.device, this.pipeLayout, null);
            if (this.descPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(this.device, this.descPool, null);
            if (this.setLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(this.device, this.setLayout, null);
            if (this.sampler != VK_NULL_HANDLE) vkDestroySampler(this.device, this.sampler, null);
            if (this.fence != VK_NULL_HANDLE) vkDestroyFence(this.device, this.fence, null);
            if (this.cmdPool != VK_NULL_HANDLE) vkDestroyCommandPool(this.device, this.cmdPool, null);
            vkDestroyDevice(this.device, null);
        }
    }

    @Nested
    @DisplayName("Planar frames")
    class Planar {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("An odd-width YUV420P frame keeps its last chroma column, with padded planes ending on a tight row")
        void oddWidthKeepsLastChromaColumn(final Features features) {
            final int w = 853;
            final int h = 3;
            final int cw = 427; // FFMPEG ROUNDS 4:2:0 CHROMA UP: A 853x3 FRAME CARRIES 427x2 CHROMA SAMPLES
            final int ch = 2;
            // Y AND U ROWS ARE PADDED BUT EACH PLANE STOPS RIGHT AFTER ITS LAST ROW; U MARKS ONLY ITS LAST COLUMN (STRONG BLUE)
            final ByteBuffer y = YuvFrames.plane(864, w, h, (row, col) -> 128);
            final ByteBuffer u = YuvFrames.plane(448, cw, ch, (row, col) -> col == cw - 1 ? 255 : 128);
            final ByteBuffer v = YuvFrames.plane(0, cw, ch, (row, col) -> 128);
            final VKEngine engine = PlayerAPI.vkEngine(features);
            try {
                assertTrue(engine.format(PixelFormat.YUV420P, w, h, 8, BT601_LIMITED));
                final ByteBuffer rgba = frame(engine, new ByteBuffer[] { y, u, v }, new int[] { 864, 448, 0 });
                final int gray = YuvFrames.rgb(KR_601, KB_601, false, 8, 128, 128, 128)[2];
                final int first = rgba.get(2) & 0xFF;
                final int middle = rgba.get(400 * 4 + 2) & 0xFF;
                final int last = rgba.get((w - 1) * 4 + 2) & 0xFF;
                assertTrue(Math.abs(first - gray) <= 3, "the minimum-size planes were accepted and converted: " + first + " vs " + gray);
                assertTrue(Math.abs(middle - first) <= 3, "neutral chroma stays neutral: " + first + " vs " + middle);
                assertTrue(last > first + 80, "the last pixel samples the last chroma column: blue " + last + " vs " + first);
            } finally {
                release(engine);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("A colored YUV420P, NV12, NV21 and YUVA420P frame shows the BT.601 and BT.709 colors of its samples")
        void matrices(final Features features) {
            final int w = 64;
            final int h = 32;
            final ByteBuffer luma = YuvFrames.plane(0, w, h, (row, col) -> 120);
            final ByteBuffer cb = YuvFrames.plane(0, w / 2, h / 2, (row, col) -> 90);
            final ByteBuffer cr = YuvFrames.plane(0, w / 2, h / 2, (row, col) -> 200);
            final ByteBuffer alpha = YuvFrames.plane(0, w, h, (row, col) -> 77);
            final ByteBuffer uv = YuvFrames.plane(0, w, h / 2, (row, col) -> col % 2 == 0 ? 90 : 200);
            final ByteBuffer vu = YuvFrames.plane(0, w, h / 2, (row, col) -> col % 2 == 0 ? 200 : 90);
            final VKEngine engine = PlayerAPI.vkEngine(features);
            try {
                for (final PixelFormat format: new PixelFormat[] { PixelFormat.YUV420P, PixelFormat.NV12, PixelFormat.NV21, PixelFormat.YUVA420P }) {
                    final ByteBuffer[] planes = switch (format) {
                        case NV12 -> new ByteBuffer[] { luma, uv };
                        case NV21 -> new ByteBuffer[] { luma, vu };
                        case YUVA420P -> new ByteBuffer[] { luma, cb, cr, alpha };
                        default -> new ByteBuffer[] { luma, cb, cr };
                    };
                    final int a = format == PixelFormat.YUVA420P ? 77 : 255;
                    assertTrue(engine.format(format, w, h, 8, BT601_LIMITED));
                    final int[] bt601 = YuvFrames.rgb(KR_601, KB_601, false, 8, 120, 90, 200);
                    assertUniform(frame(engine, planes, new int[planes.length]), new int[] { bt601[0], bt601[1], bt601[2], a }, 3, format + " BT.601");
                    assertTrue(engine.format(format, w, h, 8, new Colorimetry(Colorimetry.Matrix.BT709, false, true, false, Colorimetry.Transfer.SDR, false)));
                    final int[] bt709 = YuvFrames.rgb(KR_709, KB_709, false, 8, 120, 90, 200);
                    assertUniform(frame(engine, planes, new int[planes.length]), new int[] { bt709[0], bt709[1], bt709[2], a }, 3, format + " BT.709");
                }
            } finally {
                release(engine);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("Luma 16 with neutral chroma shows 16 in full range and black in limited range")
        void range(final Features features) {
            final ByteBuffer[] planes = {
                    YuvFrames.plane(0, 16, 16, (row, col) -> 16),
                    YuvFrames.plane(0, 8, 8, (row, col) -> 128),
                    YuvFrames.plane(0, 8, 8, (row, col) -> 128)
            };
            final VKEngine engine = PlayerAPI.vkEngine(features);
            try {
                for (final boolean full: new boolean[] { true, false }) {
                    assertTrue(engine.format(PixelFormat.YUV420P, 16, 16, 8, new Colorimetry(Colorimetry.Matrix.BT601, full, true, false, Colorimetry.Transfer.SDR, false)));
                    final int gray = YuvFrames.rgb(KR_601, KB_601, full, 8, 16, 128, 128)[0];
                    assertEquals(full ? 16 : 0, gray, "the reference itself");
                    assertUniform(frame(engine, planes, new int[3]), new int[] { gray, gray, gray, 255 }, 2, full ? "full range" : "limited range");
                }
            } finally {
                release(engine);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("Chroma lands on the even luma columns when co-sited and between them when centered")
        void chromaSiting(final Features features) {
            // Cb ALTERNATES 128/200 PER CHROMA COLUMN: CO-SITED, PIXEL 2 SITS ON COLUMN 1 (200); CENTERED, COLUMN 1 SITS BETWEEN
            // PIXELS 2 AND 3, SO PIXEL 2 BLENDS 3/4 OF IT WITH 1/4 OF COLUMN 0 (182)
            final ByteBuffer[] planes = {
                    YuvFrames.plane(0, 8, 2, (row, col) -> 60),
                    YuvFrames.plane(0, 4, 1, (row, col) -> col % 2 == 1 ? 200 : 128),
                    YuvFrames.plane(0, 4, 1, (row, col) -> 128)
            };
            final VKEngine engine = PlayerAPI.vkEngine(features);
            try {
                for (final boolean cosited: new boolean[] { true, false }) {
                    assertTrue(engine.format(PixelFormat.YUV420P, 8, 2, 8, new Colorimetry(Colorimetry.Matrix.BT601, false, cosited, false, Colorimetry.Transfer.SDR, false)));
                    final ByteBuffer rgba = frame(engine, planes, new int[3]);
                    // A YCBCR SAMPLER WITHOUT LINEAR CHROMA FILTERING TAKES THE NEAREST COLUMN INSTEAD OF BLENDING
                    final boolean nearest = (int) field(engine, "ycbcrFmt") != 0 && (int) field(engine, "ycbcrFilter") == VK_FILTER_NEAREST;
                    final int blue = YuvFrames.rgb(KR_601, KB_601, false, 8, 60, cosited || nearest ? 200 : 182, 128)[2];
                    final int shown = rgba.get(2 * 4 + 2) & 0xFF;
                    assertTrue(Math.abs(shown - blue) <= 3, (cosited ? "co-sited" : "centered") + " chroma: pixel 2 blue " + shown + " vs " + blue);
                }
            } finally {
                release(engine);
            }
        }

        @ParameterizedTest(name = "{0} {1}-bit")
        @CsvSource({ "DEVICE, 10", "DEVICE, 8", "FALLBACK, 10", "FALLBACK, 8" })
        @DisplayName("A PQ gray shows its SDR level at 100 nits and peak white at 1000 nits")
        void pq(final Features features, final int bits) {
            // THE 8-BIT FRAME QUALIFIES FOR THE YCBCR SAMPLER BY SIZE AND DEPTH, WHICH HAS NO PQ CURVE, SO IT MUST STAY ON COMPUTE
            final VKEngine engine = PlayerAPI.vkEngine(features);
            try {
                assumeTrue(engine.supports(PixelFormat.YUV420P, bits), "the device cannot sample " + bits + "-bit planes");
                final int unit = 1 << (bits - 8);
                for (final int nits: new int[] { 100, 1_000 }) {
                    final int code = (int) Math.round(16 * unit + YuvFrames.pq(nits) * 219 * unit);
                    final ByteBuffer[] planes = bits == 8
                            ? new ByteBuffer[] { YuvFrames.plane(0, 16, 16, (row, col) -> code), YuvFrames.plane(0, 8, 8, (row, col) -> 128),
                                    YuvFrames.plane(0, 8, 8, (row, col) -> 128) }
                            : new ByteBuffer[] { YuvFrames.plane16(16, 16, (row, col) -> code), YuvFrames.plane16(8, 8, (row, col) -> 512),
                                    YuvFrames.plane16(8, 8, (row, col) -> 512) };
                    assertTrue(engine.format(PixelFormat.YUV420P, 16, 16, bits, new Colorimetry(Colorimetry.Matrix.BT2020, false, true, false, Colorimetry.Transfer.PQ, true)));
                    assertEquals(0, (int) field(engine, "ycbcrFmt"), "the sampler has no PQ curve, so PQ stays on compute");
                    final int gray = nits == 100 ? YuvFrames.hdrGray(nits) : 255;
                    assertUniform(frame(engine, planes, new int[3]), new int[] { gray, gray, gray, 255 }, 3, bits + "-bit " + nits + " nits");
                }
            } finally {
                release(engine);
            }
        }
    }

    @Nested
    @DisplayName("Packed frames")
    class Packed {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("An odd-width YUYV or UYVY frame gives every pixel the luma of its own pair")
        void oddWidthPackedLuma(final Features features) {
            // FIVE PIXELS ARE THREE PAIRS; THE LAST SECOND LUMA IS PADDING NO PIXEL SHOWS
            final int[] luma = { 50, 100, 150, 200, 250 };
            final VKEngine engine = PlayerAPI.vkEngine(features);
            try {
                for (final PixelFormat format: new PixelFormat[] { PixelFormat.YUYV, PixelFormat.UYVY }) {
                    // YUYV CARRIES LUMA ON THE EVEN BYTES, UYVY ON THE ODD ONES
                    final int lumaByte = format == PixelFormat.YUYV ? 0 : 1;
                    final ByteBuffer packed = YuvFrames.plane(0, 12, 1, (row, col) -> col % 2 != lumaByte ? 128 : col / 2 < luma.length ? luma[col / 2] : 0);
                    assertTrue(engine.format(format, 5, 1, 8, BT601_LIMITED));
                    final ByteBuffer rgba = frame(engine, new ByteBuffer[] { packed }, new int[] { 0 });
                    for (int x = 0; x < luma.length; x++) {
                        final int gray = YuvFrames.rgb(KR_601, KB_601, false, 8, luma[x], 128, 128)[0];
                        final int shown = rgba.get(x * 4) & 0xFF;
                        assertTrue(Math.abs(shown - gray) <= 3, format + " pixel " + x + " shows " + shown + " instead of " + gray);
                    }
                }
            } finally {
                release(engine);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("A YUYV stride that is not a whole number of texels drops the frame and keeps the last one")
        void raggedStrideDropsTheFrame(final Features features) {
            // FOUR PIXELS ARE TWO 4-BYTE TEXELS; A 10-BYTE STRIDE IS NO WHOLE ROW LENGTH IN TEXELS, WHICH A BUFFER-TO-IMAGE COPY
            // CANNOT EXPRESS, SO THE ENGINE DROPS IT INSTEAD OF SHEARING THE ROWS
            final VKEngine engine = PlayerAPI.vkEngine(features);
            try {
                assertTrue(engine.format(PixelFormat.YUYV, 4, 2, 8, BT601_LIMITED));
                final ByteBuffer shown = frame(engine, new ByteBuffer[] { YuvFrames.plane(0, 8, 2, (row, col) -> col % 2 == 1 ? 128 : 60) }, new int[] { 0 });
                final long view = engine.texture();
                engine.upload(new ByteBuffer[] { YuvFrames.plane(10, 8, 2, (row, col) -> col % 2 == 1 ? 128 : 200) }, new int[] { 10 });
                renderer.idle();
                assertEquals(view, engine.texture(), "no new frame was published");
                assertEquals(shown, renderer.readback(view, 4, 2), "the last frame stays on screen");
            } finally {
                release(engine);
            }
        }
    }

    @Nested
    @DisplayName("Shared objects")
    class Shared {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("Engines on one device share the kit and its YCbCr conversion until the last one is released")
        void kitOutlivesEveryEngineButTheLast(final Features features) {
            final int w = 64;
            final int h = 32;
            final ByteBuffer[] planes = {
                    YuvFrames.plane(0, w, h, (row, col) -> 120),
                    YuvFrames.plane(0, w / 2, h / 2, (row, col) -> 90),
                    YuvFrames.plane(0, w / 2, h / 2, (row, col) -> 200) };
            final int[] bt601 = YuvFrames.rgb(KR_601, KB_601, false, 8, 120, 90, 200);
            final int[] rgba = { bt601[0], bt601[1], bt601[2], 255 };
            final VKEngine first = PlayerAPI.vkEngine(features);
            final VKEngine second = PlayerAPI.vkEngine(features);
            final Object kit;
            try {
                assertTrue(first.format(PixelFormat.YUV420P, w, h, 8, BT601_LIMITED));
                assertTrue(second.format(PixelFormat.YUV420P, w, h, 8, BT601_LIMITED));
                assertUniform(frame(first, planes, new int[3]), rgba, 3, "first engine");
                assertUniform(frame(second, planes, new int[3]), rgba, 3, "second engine");
                kit = field(first, "kit");
                assertSame(kit, field(second, "kit"), "engines on one device share the kit");
                final boolean sampler = (int) field(first, "ycbcrFmt") != 0;
                assertEquals(features == Features.DEVICE && ycbcr420, sampler, "8-bit SDR 4:2:0 takes the YCbCr sampler whenever the device offers it");
                if (sampler) {
                    assertNotNull(field(first, "ycbcr"), "the sampler route takes the kit's conversion");
                    assertSame(field(first, "ycbcr"), field(second, "ycbcr"), "the same layout shares one conversion");
                }

                // THE SECOND ENGINE STILL IMPORTS THE ALIGNED PLANES, SO THEY STAY ALLOCATED UNTIL IT IS RELEASED TOO
                first.release();
                renderer.idle();
                assertUniform(frame(second, planes, new int[3]), rgba, 3, "the remaining engine still converts");
            } finally {
                first.release();
                release(second);
            }

            final VKEngine third = PlayerAPI.vkEngine(features);
            try {
                assertNotSame(kit, field(third, "kit"), "the last engine released destroyed the kit");
                assertTrue(third.format(PixelFormat.YUV420P, w, h, 8, BT601_LIMITED));
                assertUniform(frame(third, planes, new int[3]), rgba, 3, "a fresh kit converts");
            } finally {
                release(third);
            }
        }
    }

    @Nested
    @DisplayName("Decoded frames")
    class Decoded {

        @Test
        @DisplayName("A frame of an FFmpeg pool on the device is sampled in place, cropped to its visible size and scaled to the layout")
        void poolFrameSamplesInPlace() {
            assumeTrue(MediaBootstrap.ffmpegAvailable() && CodecsAPI.vulkanDecode(), "FFmpeg with the Vulkan bindings");
            final VKEngine engine = PlayerAPI.vkEngine(Features.DEVICE);
            final AVBufferRef deviceRef = engine.hwDevice(AV_CODEC_ID_H264);
            try {
                assumeTrue(deviceRef != null, "the test device cannot decode H.264 (" + name + ")");
                poolFrame(renderer, engine, deviceRef);
            } finally {
                release(engine);
                if (deviceRef != null) av_buffer_unref(deviceRef);
            }
        }

        @Test
        @DisplayName("A frame of an FFmpeg pool on a twin is imported and shows exact colors, cropped and scaled")
        void poolFrameImportsFromTheTwin() {
            assumeTrue(MediaBootstrap.ffmpegAvailable() && CodecsAPI.vulkanDecode(), "FFmpeg with the Vulkan bindings");
            // NO VIDEO SESSION OPENS HERE, SO THE VALIDATION LAYER RUNS AND CHECKS THE IMPORT, THE EXTERNAL TRANSFERS AND THE TIMELINES
            assumeTrue(decoding && renderer.deviceInfo().deviceExtensions().contains(VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME),
                    "the test device cannot decode H.264 (" + name + ")");
            assumeTrue(shareable, "the test device cannot share memory and semaphores with another device (" + name + ")");
            final Renderer bare = bare();
            // TORN DOWN INSIDE OUT: THE POOL, THE ENGINE ON THIS DEVICE'S RENDERER, THE LAST REFERENCE TO THE TWIN AND THE DEVICE LAST
            try {
                final VKEngine engine = PlayerAPI.vkEngine(bare);
                final AVBufferRef deviceRef = engine.hwDevice(AV_CODEC_ID_H264);
                try {
                    assertNotNull(deviceRef, "the GPU decodes H.264, so FFmpeg gets a device to decode on");
                    final long decoder = new AVVulkanDeviceContext(new AVHWDeviceContext(deviceRef.data()).hwctx()).act_dev();
                    assertNotEquals(bare.vkDevice().address(), decoder, "FFmpeg decodes on a twin, not on the device that lends it no queue");
                    poolFrame(bare, engine, deviceRef);
                    assertEquals(2L, (long) field(engine, "importedFrames"), "both frames came from the twin");
                } finally {
                    release(engine);
                    if (deviceRef != null) av_buffer_unref(deviceRef);
                }
            } finally {
                bare.close();
            }
        }

        @Test
        @DisplayName("A player decodes H.264 on the device and the engine shows its frames without a download")
        void playerDecodesOnTheDevice() {
            assumeTrue(MediaBootstrap.ffmpegAvailable() && CodecsAPI.vulkanDecode(), "FFmpeg with the Vulkan bindings");
            // THE KHRONOS VALIDATION LAYER (SDK 1.4.363) CRASHES THE PROCESS INSIDE FFMPEG'S VIDEO SESSION COMMANDS, WHILE THE SAME
            // DECODE RUNS CLEAN WITHOUT IT: SET VK_LOADER_LAYERS_DISABLE=~all~ TO RUN THIS CASE ON A MACHINE WITH THE SDK
            assumeTrue(!validation, "the Khronos validation layer crashes on Vulkan Video decoding; run with VK_LOADER_LAYERS_DISABLE=~all~");
            final VKEngine engine = PlayerAPI.vkEngine(Features.DEVICE);
            final AVBufferRef deviceRef = engine.hwDevice(AV_CODEC_ID_H264);
            if (deviceRef != null) av_buffer_unref(deviceRef);
            else release(engine);
            assumeTrue(deviceRef != null, "the test device cannot decode H.264 (" + name + ")");
            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
            assertTrue(mrl.await(10_000L));
            final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, engine, null);
            try {
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, 15_000L, MediaPlayer.Status.PLAYING, MediaPlayer.Status.BUFFERING, MediaPlayer.Status.PAUSED));
                assertTrue(PlayerWait.awaitCondition(() -> (long) field(engine, "decodedFrames") >= 3L, 10_000L), "frames decoded on the device reach the engine");
                assertTrue(player.hwAccel(), "the player decodes in hardware");
                assertEquals(engine.placebo() ? PixelFormat.RGBA : PixelFormat.NV12, engine.format(), "libplacebo's RGBA, or the pool's layout the engine samples itself");
                final ByteBuffer rgba = renderer.readback(engine.texture(), engine.width(), engine.height());
                int lit = 0;
                for (int i = 0; i < rgba.capacity(); i += 4) if ((rgba.get(i) & 0xFF) > 32 || (rgba.get(i + 1) & 0xFF) > 32 || (rgba.get(i + 2) & 0xFF) > 32) lit++;
                assertTrue(lit > 0, "the published frame carries the picture, not a blank image");
            } finally {
                player.release();
                release(engine);
            }
        }

        @Test
        @DisplayName("A host device without decoding queues decodes on a twin and the engine shows the imported frames")
        void twinDecodesForADeviceWithoutQueues() {
            assumeTrue(MediaBootstrap.ffmpegAvailable() && CodecsAPI.vulkanDecode(), "FFmpeg with the Vulkan bindings");
            // THE SAME VALIDATION-LAYER CRASH AS playerDecodesOnTheDevice: FFMPEG RUNS ITS VIDEO SESSION ON THE TWIN
            assumeTrue(!validation, "the Khronos validation layer crashes on Vulkan Video decoding; run with VK_LOADER_LAYERS_DISABLE=~all~");
            assumeTrue(decoding && renderer.deviceInfo().deviceExtensions().contains(VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME),
                    "the test device cannot decode H.264 (" + name + ")");
            assumeTrue(shareable, "the test device cannot share memory and semaphores with another device (" + name + ")");
            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
            assertTrue(mrl.await(10_000L));
            final Renderer bare = bare();
            // TORN DOWN INSIDE OUT: THE PLAYER, THEN THE ENGINE, WHOSE RETIRED OBJECTS AND LAST REFERENCE TO THE TWIN DIE ON THIS DEVICE'S
            // RENDERER, AND THE DEVICE LAST
            try {
                final VKEngine engine = PlayerAPI.vkEngine(bare);
                try {
                    final AVBufferRef deviceRef = engine.hwDevice(AV_CODEC_ID_H264);
                    assertNotNull(deviceRef, "the GPU decodes H.264, so FFmpeg gets a device to decode on");
                    final long decoder = new AVVulkanDeviceContext(new AVHWDeviceContext(deviceRef.data()).hwctx()).act_dev();
                    av_buffer_unref(deviceRef);
                    assertNotEquals(bare.vkDevice().address(), decoder, "FFmpeg decodes on a twin, not on the device that lends it no queue");
                    final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, engine, null);
                    try {
                        assertTrue(player.start());
                        assertTrue(PlayerWait.awaitStatus(player, 15_000L, MediaPlayer.Status.PLAYING, MediaPlayer.Status.BUFFERING, MediaPlayer.Status.PAUSED));
                        assertTrue(PlayerWait.awaitCondition(() -> (long) field(engine, "importedFrames") >= 3L, 10_000L), "frames decoded on the twin are imported into the device");
                        assertTrue(player.hwAccel(), "the player decodes in hardware");
                        assertEquals(engine.placebo() ? PixelFormat.RGBA : PixelFormat.NV12, engine.format(), "libplacebo's RGBA, or the pool's layout the engine samples itself");
                        final ByteBuffer rgba = bare.readback(engine.texture(), engine.width(), engine.height());
                        int lit = 0;
                        for (int i = 0; i < rgba.capacity(); i += 4) if ((rgba.get(i) & 0xFF) > 32 || (rgba.get(i + 1) & 0xFF) > 32 || (rgba.get(i + 2) & 0xFF) > 32) lit++;
                        assertTrue(lit > 0, "the published frame carries the picture, not a blank image");
                    } finally {
                        player.release();
                    }
                } finally {
                    release(engine);
                }
            } finally {
                bare.close();
            }
        }
    }

    // A STALLED GRAPH SPINS INSIDE av_buffersink_get_frame: THE TIMEOUT FAILS THE TEST INSTEAD OF HANGING THE RUN
    @Nested
    @DisplayName("Filtered frames")
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    class Filtered {

        @Test
        @DisplayName("libplacebo turns a pool frame marked interlaced into RGBA on the device, which the engine shows in place at full and half size")
        void placeboOnTheDevice() {
            assumeTrue(MediaBootstrap.ffmpegAvailable() && CodecsAPI.vulkanDecode(), "FFmpeg with the Vulkan bindings");
            final VKEngine engine = PlayerAPI.vkEngine(Features.DEVICE);
            final AVBufferRef deviceRef = engine.hwDevice(AV_CODEC_ID_H264);
            try {
                assumeTrue(deviceRef != null, "the test device cannot decode H.264 (" + name + ")");
                assumeTrue(filters, "the GPU lacks the features libplacebo and FFmpeg's filter shaders need (" + name + ")");
                assertTrue(engine.placebo(), "a device with those features and a graphics queue of FFmpeg's own runs libplacebo");
                placeboFrame(renderer, engine, deviceRef);
                assertEquals(0L, (long) field(engine, "importedFrames"), "the RGBA frames were sampled in place");
            } finally {
                release(engine);
                if (deviceRef != null) av_buffer_unref(deviceRef);
            }
        }

        @Test
        @DisplayName("A device created without those features decodes on a twin that enables them, and the engine imports libplacebo's RGBA")
        void placeboOnATwinThatAddsTheFeatures() {
            assumeTrue(MediaBootstrap.ffmpegAvailable() && CodecsAPI.vulkanDecode(), "FFmpeg with the Vulkan bindings");
            // NO VIDEO SESSION OPENS HERE, SO THE VALIDATION LAYER CHECKS THE TWIN'S FEATURES, LIBPLACEBO AND THE IMPORTS
            assumeTrue(decoding && renderer.deviceInfo().deviceExtensions().contains(VK_KHR_VIDEO_DECODE_H264_EXTENSION_NAME),
                    "the test device cannot decode H.264 (" + name + ")");
            assumeTrue(shareable && filters, "the GPU cannot share memory with a twin or lacks the filter features (" + name + ")");
            // THE SUITE'S CHAIN WITHOUT THE FILTER FEATURES, AS A RENDERER THAT NEVER HEARD OF LIBPLACEBO CREATES ITS DEVICE
            final VkPhysicalDeviceVulkan13Features plain13 = VkPhysicalDeviceVulkan13Features.calloc().sType$Default().synchronization2(true);
            final VkPhysicalDeviceVulkan12Features plain12 = VkPhysicalDeviceVulkan12Features.calloc().sType$Default().timelineSemaphore(true).pNext(plain13.address());
            final VkPhysicalDeviceSamplerYcbcrConversionFeatures plainYcbcr = VkPhysicalDeviceSamplerYcbcrConversionFeatures.calloc().sType$Default()
                    .samplerYcbcrConversion(ycbcr).pNext(plain12.address());
            final VkPhysicalDeviceFeatures2 plain = VkPhysicalDeviceFeatures2.calloc().sType$Default().pNext(plainYcbcr.address());
            try {
                final List<String> extensions = new ArrayList<>(renderer.deviceInfo().deviceExtensions());
                extensions.addAll(EXTERNAL);
                final int[] counts = new int[renderer.deviceInfo().queueCounts().length];
                counts[family] = 1;
                final Renderer host = new Renderer(new VKContext.DeviceInfo(plain, renderer.deviceInfo().instanceExtensions(), extensions, counts));
                // TORN DOWN INSIDE OUT: THE ENGINE, THE LAST REFERENCE TO THE TWIN, THE DEVICE AND ITS FEATURE CHAIN LAST
                try {
                    final VKEngine engine = PlayerAPI.vkEngine(host);
                    final AVBufferRef deviceRef = engine.hwDevice(AV_CODEC_ID_H264);
                    try {
                        assertNotNull(deviceRef, "the GPU decodes H.264, so FFmpeg gets a device to decode on");
                        final long decoder = new AVVulkanDeviceContext(new AVHWDeviceContext(deviceRef.data()).hwctx()).act_dev();
                        assertNotEquals(host.vkDevice().address(), decoder, "FFmpeg decodes on a twin, not on the device that lends it no queue");
                        assertTrue(engine.placebo(), "the twin enables what the device left out, so libplacebo runs there");
                        placeboFrame(host, engine, deviceRef);
                        assertEquals(2L, (long) field(engine, "importedFrames"), "both RGBA frames came from the twin");
                    } finally {
                        release(engine);
                        if (deviceRef != null) av_buffer_unref(deviceRef);
                    }
                } finally {
                    host.close();
                }
            } finally {
                plain.free();
                plainYcbcr.free();
                plain12.free();
                plain13.free();
            }
        }

        @Test
        @DisplayName("A lent device whose render queue is alone in its family keeps the engine's own conversion, as libplacebo would submit to that queue")
        void placeboNeedsAGraphicsQueueOfItsOwn() {
            assumeTrue(MediaBootstrap.ffmpegAvailable() && CodecsAPI.vulkanDecode(), "FFmpeg with the Vulkan bindings");
            assumeTrue(decoding && filters, "the test device cannot decode or lacks the filter features (" + name + ")");
            // A LONE RENDER QUEUE STILL DECODES WHEN A COMPUTE-ONLY FAMILY GIVES FFMPEG ITS COMPUTE QUEUE
            final int[] counts = renderer.deviceInfo().queueCounts().clone();
            try (final MemoryStack stack = stackPush()) {
                final IntBuffer count = stack.mallocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
                final VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.malloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(physical, count, properties);
                boolean compute = false;
                for (int f = 0; f < properties.capacity(); f++)
                    compute |= f != family && counts[f] > 0 && (properties.get(f).queueFlags() & (VK_QUEUE_COMPUTE_BIT | VK_QUEUE_GRAPHICS_BIT)) == VK_QUEUE_COMPUTE_BIT;
                assumeTrue(compute, "only a compute-only family lets FFmpeg decode next to a lone render queue (" + name + ")");
            }
            counts[family] = 1;
            final Renderer lone = new Renderer(new VKContext.DeviceInfo(features, renderer.deviceInfo().instanceExtensions(), renderer.deviceInfo().deviceExtensions(), counts));
            try {
                final VKEngine engine = PlayerAPI.vkEngine(lone);
                final AVBufferRef deviceRef = engine.hwDevice(AV_CODEC_ID_H264);
                try {
                    assertNotNull(deviceRef, "FFmpeg still decodes on the device with its compute, transfer and decode queues");
                    assertEquals(lone.vkDevice().address(), new AVVulkanDeviceContext(new AVHWDeviceContext(deviceRef.data()).hwctx()).act_dev(),
                            "FFmpeg decodes on the device itself");
                    assertFalse(engine.placebo(), "the render queue is the device's only graphics queue, which libplacebo must not take");
                } finally {
                    release(engine);
                    if (deviceRef != null) av_buffer_unref(deviceRef);
                }
            } finally {
                lone.close();
            }
        }
    }
}
