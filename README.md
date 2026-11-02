[![CurseForge downloads](https://cf.way2muchnoise.eu/watermedia.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/watermedia)
[![CurseForge](https://img.shields.io/curseforge/v/869524?style=for-the-badge&label=curseforge&labelColor=%232d2d2d&color=%23e04e14&link=https%3A%2F%2Fwww.curseforge.com%2Fminecraft%2Fmc-mods%2Fwatermedia%2Ffiles)](https://www.curseforge.com/minecraft/mc-mods/watermedia/files)
[![Minecraft versions supported](https://cf.way2muchnoise.eu/versions/Supports_watermedia_all.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/watermedia/files)
[![JitPack](https://img.shields.io/jitpack/version/com.github.SrRapero720/watermedia?style=for-the-badge&label=JITPACK&color=34495e&link=https%3A%2F%2Fjitpack.io%2F%23SrRapero720%2Fwatermedia)](https://jitpack.io/#SrRapero720/watermedia)
[![Build status](https://img.shields.io/github/actions/workflow/status/SrRapero720/watermedia/build-test.yml?branch=master&style=for-the-badge)](https://github.com/SrRapero720/watermedia/actions/workflows/build-test.yml)

![Discord](https://img.shields.io/discord/486853064284831744?style=for-the-badge&logo=discord&logoColor=white&label=DISCORD&color=7289DA)

![Banner](src/main/resources/assets/watermedia/textures/banner.png)

## 🦆 WATERMeDIA: Multimedia API 
A library to play media and to decode and encode videos and pictures, working with Vulkan, OpenGL, JavaFX,
Java AWT and more coming soon.

Built mainly for Minecraft modding on Forge, NeoForge and Fabric, covering almost every version that runs on
Java 17 or newer.

_FFmpeg_ comes ready to use in the companion mod _WATERMeDIA: Binaries_: nothing to compile or install, just
plug and play.

# 🧩 Projects using WATERMeDIA
|           Name           |                 link                  |
|:------------------------:|:-------------------------------------:|
|      🖼️ Waterframes      | [CurseForge](https://mods.cf/834607)  |
|      📺 Watervision      | [CurseForge](https://mods.cf/1167473) |
|       🧱 Fancymenu       | [CurseForge](https://mods.cf/367706)  |
| 📽️ Holographic Renderers | [CurseForge](https://mods.cf/532110)  |
|    📽️ BBS CML Edition    | [CurseForge](https://mods.cf/1392794) |
|  🖼️ LittlePictureFrames  | [CurseForge](https://mods.cf/435557)  |
| ⏪ WaterFramesBackported | [CurseForge](https://mods.cf/1178892) |
|  💻 Conditional Videos   | [CurseForge](https://mods.cf/1521845) |
|   ⏯️  OwlLeaf playback    | [CurseForge](https://mods.cf/1224054) |


# 💰 Donations
> [!NOTE]
> Whatever you'd donate to us, consider giving half to the _FFmpeg_ developers: without them this project
> wouldn't exist. 🫶<br>
> https://www.ffmpeg.org/donations.html
> 
[![Support me on Patreon](https://img.shields.io/badge/Patreon-F96854?style=for-the-badge&logo=patreon&logoColor=white)](https://patreon.com/SrRapero720)
[![Support me via Paypal](https://img.shields.io/badge/PayPal-00457C?style=for-the-badge&logo=paypal&logoColor=white)](https://paypal.me/SrRapero720)
[![Support me on Ko-Fi](https://img.shields.io/badge/Ko--fi-F16061?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/srrapero720)
[![Support me via Revolut](https://img.shields.io/badge/REVOLUT-DONATE%20DIRECTLY-191C1F?style=for-the-badge&logo=revolut&logoColor=black&labelColor=ffffff)](https://revolut.me/srrap720)

# 💻 BASIC USAGE
## CREATE AN MRL AND A PLAYER
```java
MRL mrl = PlayerAPI.mrl(URI.create("https://imgur.com/gallery/snow-ducks-YcDd9x"));

// IN A TICK-LOOP METHOD
if (mrl.status() == MRL.Status.LOADED) {
    MediaPlayer player = PlayerAPI.player(mrl,
            () -> PlayerAPI.glEngine(renderThread, renderExecutor),
            () -> PlayerAPI.alEngine());
}
```

Two classes do the work:
- `MRL` (Multimedia Resource Location) connects to the link, finds its media sources and quality variants and
  keeps them with their metadata.
- `MediaPlayer` opens an MRL source, gives you the playback controls and uploads the picture and sound to your
  engines.

A failed MRL means there's nothing WATERMeDIA can play there (a broken link, or you're trying to open a `.docx`).

## 🚙 ENGINES
`GLEngine`: OpenGL video output. It's self-contained: it saves and restores the host's GL state around every
upload, so state managers with sensitive caches (like Minecraft's `GlStateManager` or _Sodium_) never desync.
No GL wiring needed.

`VKEngine`: Vulkan video output. It never creates or owns a Vulkan device: your app lends it one through a
`VKContext`, and every frame comes back as a ready-to-sample `VkImageView`. See the Vulkan section below.

`ALEngine`: OpenAL sound output.

> [!NOTE]
> Suppliers that return null (`() -> null`) turn video or audio output off.

## 🌋 VULKAN (VKEngine)
Unlike OpenGL there's no global context to detect, so you lend the engine the device you render with by
implementing `VKContext`. The engine only creates its own resources on top of it and never destroys anything
you returned:

```java
public final class MyVKContext implements VKContext {
    public VkInstance vkInstance() { return this.instance; }
    public VkPhysicalDevice physicalDevice() { return this.physicalDevice; }
    public VkDevice vkDevice() { return this.device; }
    public VkQueue queue() { return this.graphicsQueue; }
    public int queueFamily() { return this.graphicsFamily; }
    public Object queueLock() { return this.queueLock; }
    public VkPhysicalDeviceMemoryProperties memoryProperties() { return this.memProps; }
    public boolean hostImportSupported() { return this.hasExtMemoryHost; }
    public long minImportedHostPointerAlignment() { return this.hostPointerAlign; }
    public boolean ycbcrSampler() { return this.hasYcbcrFeature; }
    public void retire(Runnable destroy) { this.endOfFrameQueue.add(destroy); }
}
```

```java
MediaPlayer player = PlayerAPI.player(mrl,
        () -> PlayerAPI.vkEngine(myContext),
        () -> null);

// EACH FRAME: texture() IS A VkImageView HANDLE (RGBA, SHADER_READ_ONLY); 0 MEANS NO FRAME YET
long imageView = player.texture();
```

The contract in short:
- **`queueLock()`**: Vulkan needs queue submission to be externally synchronized. The engine holds this monitor
  around every `vkQueueSubmit`; if you share the queue, hold the same lock around your own submits and presents.
- **`retire(Runnable)`**: the engine never destroys an image view your in-flight frames might still sample. Run
  the callback once those frames complete (end-of-frame destruction queue), and flush any pending callbacks
  before tearing down the device.
- **Everything you return must outlive every engine built on the context.**

Optional device features the engine uses when you enable them at device creation:
- `VK_EXT_external_memory_host`: zero-copy uploads, importing decoder buffers straight into a `VkBuffer` (no CPU
  copy). Report it through `hostImportSupported()` and the physical device's `minImportedHostPointerAlignment`.
  Without it the engine falls back to staging buffers.
- `samplerYcbcrConversion` (Vulkan 1.1): YUV to RGB conversion in sampler hardware for NV12 and planar YUV;
  otherwise a compute pass does the exact same BT.709 math.
- **Vulkan Video decoding**: override `deviceInfo()` with the feature chain, extension lists and queue counts you
  created the device with, and the player decodes H.264, H.265 and AV1 on the GPU with nothing passing through
  RAM. It needs a 1.3 instance and device with `timelineSemaphore` and `synchronization2`, `VK_KHR_video_queue`,
  `VK_KHR_video_decode_queue` and the `VK_KHR_video_decode_*` codec extensions. Where _FFmpeg_ decodes it also
  runs _libplacebo_, which turns every decoded layout into SDR RGBA (tone-mapping HDR) before the engine draws
  it; that takes `hostQueryReset`, `runtimeDescriptorArray`, `shaderStorageImageArrayNonUniformIndexing` and
  `maintenance4`. Without _libplacebo_ only 4:2:0 streams decode this way (the engine samples their NV12 and
  P010 pools itself), and without `deviceInfo()` the engine keeps the host-import path above. _FFmpeg_ decodes
  in one of two places:
  - **Your device**, when you also create queues _FFmpeg_ can own: a compute-capable and a transfer-capable
    queue other than `queue()`, plus a video decode queue, with `queue()` being the last queue created in its
    family. The engine samples the decoded images in place. _FFmpeg_ submits to those queues from its own
    threads, so never call `vkDeviceWaitIdle` while players exist: wait on your own queue. _libplacebo_ runs
    here only with the features above and a second queue in the family of `queue()`, since it needs a graphics
    queue that isn't yours.
  - **A twin**, when you create no extra queues but enable `VK_KHR_external_memory_win32` and
    `VK_KHR_external_semaphore_win32` on Windows, or `VK_KHR_external_memory_fd` and
    `VK_KHR_external_semaphore_fd` elsewhere. The engine creates a second device on the same GPU with your
    feature chain and extensions, plus _libplacebo_'s features when your chain lacks them and the GPU has them.
    _FFmpeg_ decodes there and the engine imports every decoded image into your device, so your queues stay
    yours. If an image can't be imported (natives built without _FFmpeg_'s export fix, for instance), its
    frames come down to RAM instead and playback goes on.

  Either way, release every player before destroying the device or its instance: _FFmpeg_ keeps using them (and
  the twin lives on your instance) until its last decoder is gone.

```java
public VKContext.DeviceInfo deviceInfo() {
    return new VKContext.DeviceInfo(this.features2, this.instanceExtensions, this.deviceExtensions, this.queueCounts);
}
```

### Minecraft 26.x (Vulkan backend)
WATERMeDIA stays engine-agnostic and never touches Mojang's Blaze3D internals: bridging is the mod's job. The
recommended pattern is a **mixin that implements `VKContext` directly on Mojang's `VulkanDevice`**, so the device
itself IS the context and `PlayerAPI.vkEngine` only needs a cast. `VKContext` method names avoid overloading that
class's members by return type alone (the merged bytecode would be legal, since the JVM resolves by full
descriptor, but duplicate names muddy stack traces and crash reports), and MC's own `vkDevice()` accessor already
satisfies the interface as is:

```java
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin implements VKContext {
    @Shadow public abstract VulkanInstance instance();
    @Shadow public abstract VulkanQueue graphicsQueue();
    @Shadow public abstract VkDevice vkDevice(); // ALREADY SATISFIES VKContext#vkDevice()

    @Unique private VkPhysicalDeviceMemoryProperties wm$memProps;

    @Override public VkInstance vkInstance() { return this.instance().vkInstance(); }
    @Override public VkPhysicalDevice physicalDevice() { return this.vkDevice().getPhysicalDevice(); }
    @Override public VkQueue queue() { return this.graphicsQueue().vkQueue(); }
    @Override public int queueFamily() { return this.graphicsQueue().queueFamilyIndex(); }
    @Override public Object queueLock() { return this; } // THE DEVICE ITSELF SERVES AS THE MONITOR

    @Override public VkPhysicalDeviceMemoryProperties memoryProperties() {
        if (this.wm$memProps == null) { // MC DOES NOT EXPOSE THEM, QUERY ONCE
            this.wm$memProps = VkPhysicalDeviceMemoryProperties.calloc();
            VK10.vkGetPhysicalDeviceMemoryProperties(this.physicalDevice(), this.wm$memProps);
        }
        return this.wm$memProps;
    }

    @Override public boolean hostImportSupported() { return false; }  // MC DOES NOT ENABLE THE EXTENSION
    @Override public long minImportedHostPointerAlignment() { return 0; }
    @Override public boolean ycbcrSampler() { return false; }         // NOT ENABLED EITHER
    @Override public void retire(Runnable destroy) { RenderSystem.queueFencedTask(destroy); }
}
```

Unwrap the backend once (`GpuDevice#backend` is private: one accessor mixin or AT/AW line) and cast it in the
factory call:

```java
GpuDeviceBackend backend = ((GpuDeviceAccessor) RenderSystem.getDevice()).wm$backend();
MediaPlayer player = PlayerAPI.player(mrl,
        () -> PlayerAPI.vkEngine((VKContext) backend),
        () -> null);
```

Two caveats checked against the 26.2 sources:
- MC submits to the graphics queue **without holding any lock** (the single submit site is
  `VulkanQueue.Submission#close`). Since the engine submits from its producer thread, add one more tiny mixin
  wrapping that method in `synchronized (device)` (the same monitor `queueLock()` returns above), or the two
  submit paths can race.
- MC creates its device without `VK_EXT_external_memory_host` and without `samplerYcbcrConversion`, hence the
  hardcoded `false` above: the engine transparently uses its staging-buffer path and compute-shader YUV
  conversion.

# 🔌 AVAILABLE APIs
- `CodecsAPI` (**experimental**, don't use it in your mods yet): our own picture and animation codecs, plus
  _FFmpeg_-backed audio and video
- `PlayerAPI`: media playback and display
- `NetworkAPI`: host and remote storage for private media
- `PlatformAPI`: web platform support for loading and searching media

The full API guide is in [English](docs/en-us.md) and [Español (México)](docs/es-mx.md).

# 📦 Binaries
_FFmpeg_ natives and a few extra shared libraries ship in the companion jar _WATERMeDIA: Binaries_. Its sources
live in this repo under [`binaries/`](https://github.com/SrRapero720/watermedia/tree/master/binaries).

Minecraft clients require Binaries; dedicated servers can skip it. Apps embedding the Java API can run without
it, keeping picture support but losing video decoding and the executable-backed platform resolvers. When
installed, Binaries loads right after the configuration, and a failed Binaries load ends the startup.

CurseForge and Modrinth list Binaries as required because their dependencies can't express client/server
conditions, so installers may add it to servers too.

## Supported platforms
| Platform | Architecture | Status |
|----------|--------------|:------:|
| Windows  | x86_64       |   ✅    |
| Windows  | aarch64      |   ⛔    |
| Linux    | x86_64       |   ✅    |
| Linux    | aarch64      |   ✅    |
| macOS    | x86_64       |   ✅    |
| macOS    | aarch64      |   ✅    |
| Android  | aarch64      |   ⛔    |
| Android  | x86_64       |   ⛔    |

## Binaries build
_FFmpeg_ is rebuilt from the pinned _JavaCPP_ preset on five native GitHub runners. The pipeline compiles the
static dependencies, parses the _FFmpeg_ headers, regenerates JNI and checks each classifier before repacking the
five archives together.

# ⚖️ License
WATERMeDIA is under the [_Defensive Public Source (Non-Commercial) License v1.0.1_](LICENSE.md)<br>
Copyright SrRapero720 (https://github.com/SrRapero720)<br>
Commercial use is forbidden; contact us to use WATERMeDIA for commercial purposes.

_WATERCoNFIG_ is required at runtime and is licensed All-Rights-Reserved.

_JavaCPP_ bindings for _FFmpeg_ are shaded under _Apache 2.0_.

Full verbatim license texts for shaded third-party dependencies are bundled under
`src/main/resources/META-INF/licenses/` (shipped in the jar as `META-INF/licenses/`).

_WATERMeDIA: Binaries_ uses the same _DPS (NC) License v1.0.1_ for its own code. Its jar also ships third-party
native binaries and libraries, whose license texts and notices are bundled under
`binaries/src/main/resources/META-INF/licenses/` and shipped as `META-INF/licenses/`:

- **LGPL-3.0-or-later**: _FFmpeg_ 9.0.2, built without `--enable-gpl`, _x264_ or _x265_
- **LGPL-2.1-or-later**: _ALSA_ (Linux ARM64 native)
- **GPL-3.0-or-later WITH GCC-exception-3.1**: _libatomic_ on macOS and eligible GCC runtime portions
- **Apache-2.0**: _JavaCPP_ JNI glue (native)
- **MIT / X11**: _libva_, _libva-drm_ and _libdrm_ (Linux x64 native); _rustypipe-botguard_ (downloaded binary)
- **BSD-3-Clause**: _Raspberry Pi userland_ (Linux ARM64 native)
- **MIT / BSD-3-Clause**: _winpthreads_ (Windows native)
- **0BSD**: _XZ for Java_ (shaded)
- **Unlicense**: _yt-dlp_ (downloaded binary)

Other statically linked codecs keep their permissive, LGPL, MPL or Apache licenses. Each component's terms apply
on their own; review the shipped notices and corresponding-source requirements before redistributing a modified
native set. The native package stays replaceable at runtime.
