# 🦆 简介
WATERMeDIA 是一个提供多媒体 API 的库，可以播放、解码、编码和处理视频、音频与图片。如果你用过 _ExoPlayer_ 或
_VLCJ_，会觉得很熟悉，不过这里的一切都已准备就绪：你永远不用和缓冲区、平面、字节序或步长打交道。

它会解析媒体链接（YouTube、Twitch、直链文件等等），完成解码，再把画面帧交给你的图形引擎（OpenGL、Vulkan、AWT、JavaFX），
把声音交给你的音频引擎（OpenAL、Java Sound）。图片和动画由我们自己的纯 Java 解码器处理；视频和音频则交给 _FFmpeg_，
它打包在配套模组 _WATERMeDIA: Binaries_ 中。需要 Java 17 或更高版本。

本指南中的每个示例都是集成代码片段：`renderThread`、`renderExecutor` 或 `mrl` 这类变量来自你自己的代码。

# 🪁 版本规则
> [!IMPORTANT]
> 本规则不适用于实验性 API 或内部代码

每个数字都会告诉你改了什么，以及是否可能造成破坏。

**版本号递增规则**
``X.Y.Z-<channel>.W``
- ``X``：完全重写，你对这个库了解的一切都被推翻，并从零重新构建
- ``Y``：破坏性变更，即可能或一定会破坏你现有实现的重构、重命名和 API 移除
- ``Z``：热修复、改进、新 API 和弃用注解，不含任何破坏性变更。
- ``<channel>``：ALPHA 或 BETA 渠道，正式版不使用这个版本后缀
  - 注意：有时会故意省略它，因为有些模组加载器遇到它会弄坏版本范围。
- ``W``：ALPHA 或 BETA 的发布序号，在正式版渠道上省略

这是一项**保证：``Z`` 升级绝不会提交任何破坏性变更**，所以你可以放心地按 ``[3.0.1,3.1)`` 的范围依赖 watermedia

# 🗺 索引
选择与你要构建的东西相符的路线。

## MINECRAFT：添加视频的模组开发者
1. [环境准备](#0-环境准备)：添加依赖并遵守版本范围。
2. [软件限制](#11-软件限制)：支持的 Minecraft 和加载器版本。
3. [管理链接](#2-管理链接)：把链接变成媒体源。
4. [创建与控制播放器](#31-创建与控制播放器)。
5. [选择引擎](#33-选择引擎)：在游戏自己的上下文上使用 OpenGL、Vulkan 和 OpenAL。
6. [空间音频](#34-空间音频)：带位置的屏幕、立体声对和扬声器。
7. [同步播放](#35-同步播放)：每位玩家看到的都是同一时刻。
8. [上传与下载文件](#4-上传与下载文件)：通过文件服务器分享文件。

## 桌面应用
1. [环境准备](#0-环境准备)：Gradle 依赖、运行时库和[配置](#02-配置)。
2. [启动](#1-启动)：只启动一次 WATERMeDIA，并显示加载界面。
3. [管理链接](#2-管理链接)与[搜索](#23-搜索)。
4. [创建与控制播放器](#31-创建与控制播放器)以及[音轨](#32-音轨与语言)。
5. [选择引擎](#33-选择引擎)：AWT、JavaFX、OpenGL 或 Vulkan，再加上 OpenAL 或 Java Sound。
6. [诊断与示例](#13-诊断与示例)。

## 代码工具
无需窗口即可运行的命令行工具、机器人、转换器和服务。

1. [环境准备](#0-环境准备)与[启动](#1-启动)：哪些需要启动，哪些不需要。
2. [平台](#22-平台)与[搜索](#23-搜索)：用 `PlatformAPI` 解析和搜索链接，无需启动。
3. [下载](#42-下载)：使用 `NetRequest` 进行有大小上限且经过校验的下载。
4. [解码器](#51-解码器)与[编码器](#52-编码器)：图片、动画和视频文件。
5. [无头引擎](#33-选择引擎)：无需图形上下文即可捕获解码后的帧。
6. [同步播放](#35-同步播放)：`ServerMediaPlayer` 在服务器上也能运行。

## AI 智能体
编写集成代码之前，请先阅读[启动](#1-启动)、[公开包与内部包](#12-公开包与内部包)、[管理链接](#2-管理链接)、
[创建与控制播放器](#31-创建与控制播放器)和[选择引擎](#33-选择引擎)。以下规则是硬性要求：

- 只使用公开包（`org.watermedia`、`api.codecs`、`api.network`、`api.platform`、`api.player`、`api.util`，
  不含它们的子包）。播放器和引擎只能从 `PlayerAPI` 获取；引擎类都是密封类（sealed）。
- 在 Minecraft 中绝不要调用 `WaterMedia.start`：加载器会替你调用。在其他环境中，每个进程只调用一次，并且绝不要在
  WATERMeDIA 的回调或工作线程中调用。不存在停止操作。
- 检查 `WaterMedia.loaded()` 以及你需要的能力（例如 `CodecsAPI.ffmpegLoaded()`），不要想当然地认为它们可用。
- 解析是异步的：在 tick 中轮询 `mrl.status()`，绝不要在游戏线程或渲染线程上调用 `mrl.await(...)`。
- `PlayerAPI.player(...)` 可能返回 null。当 `start()` 抛出异常或宿主丢弃绘制表面时，释放播放器。
- 以 supplier 的形式传入引擎，绝不要在多个播放器之间共享同一个引擎，并让播放器持有它们。
- 每次绘制时都在渲染线程上重新读取 `player.texture()`；0 表示没有可绘制的内容。
- 绝不要在状态监听器或播放线程中调用 `release()`；在销毁播放器所用的 OpenGL、Vulkan 或 OpenAL
  上下文之前，先释放每个播放器。
- OpenGL 执行器运行在 GL 线程上，每帧都要驱动（pump）它，`release()` 之后也要再驱动一次。OpenAL
  要求每个驱动音频的线程都把它的上下文设为当前上下文。在 Vulkan 上，你自己的提交（submit）和呈现（present）要用
  `queueLock()` 包住，并且只要还有播放器存在，就绝不要调用 `vkDeviceWaitIdle`。
- 状态监听器会在状态转入 `STOPPED`、`ENDED` 或 `ERROR` 时被消耗掉；想继续监听，就在回调中重新注册。
- 布尔型控制方法在调用生效时返回 `true`，被拒绝时返回 `false`；请检查返回值，不要想当然。
- 时间以毫秒为单位（编码器时间戳以微秒为单位），音量范围是 0 到 100，速度范围是略大于 0 到 4。
- `ImageReader.next()` 会复用它的缓冲区：在下一次调用前先复制一份。`CodecsAPI` 的 decode、reader、sniff 和 encoder
  调用是实验性的：不要在模组中使用；它的状态检查方法可以放心使用。
- 专用服务器上可用的有 `ServerMediaPlayer`、`PlatformAPI`、`NetworkAPI` 上传、文件服务器和纯 Java
  图片编解码器；在那里使用播放器和引擎会抛出异常。

# 0. 环境准备
在 Minecraft 上，只需把 WATERMeDIA 和 _WATERMeDIA: Binaries_ 添加为依赖（见
[Binaries 与 FFmpeg](#01-binaries-与-ffmpeg)），并在你的模组中按[软件限制](#11-软件限制)中的范围把 WATERMeDIA
声明为必需依赖；运行时由加载器提供它们。

```gradle
repositories {
    maven { url "https://jitpack.io" }
}

dependencies {
    // HEY NEWBIE CODER: implementation IN MODDING ENVIRONMENTS DOESN'T SHIP DEPENDENCIES IN YOUR BUILT JAR
    // AND IS NEEDED SO THE NEOFORGE OR FABRIC RUNTIME CAN LOAD THEM
    implementation "com.github.SrRapero720:watermedia:$watermedia_version" // A RELEASE TAG, SUCH AS 3.0.1
    implementation "com.github.SrRapero720:watermedia-binaries:$binaries_version" // FFMPEG, YT-DLP AND BOTGUARD, OPTIONAL
}
```

在自定义 Java 应用或 Java 游戏中，运行环境必须提供：

```gradle
repositories {
    mavenCentral()
    maven { url "https://jitpack.io" }
}

dependencies {
    implementation "com.github.SrRapero720:watermedia:$watermedia_version" // A RELEASE TAG, SUCH AS 3.0.1
    implementation "com.github.SrRapero720:watermedia-binaries:$binaries_version" // FFMPEG, YT-DLP AND BOTGUARD, OPTIONAL
    implementation "com.github.SrRapero720:waterconfig:1.0.0.1" // MIN WATERCONFIG VERSION
    implementation "com.google.code.gson:gson:2.10" // MIN GSON VERSION
    implementation "org.apache.logging.log4j:log4j-api:2.26.1" // MIN LOG4J VERSION, PLUS THE BACKEND YOUR APP LOGS WITH
    implementation "org.joml:joml:1.10.8" // MIN JOML VERSION
    // LWJGL 3.3.6 MODULES, ONLY FOR THE ENGINES YOU USE (SEE THE TABLE BELOW)
}
```

| 引擎 | 额外的运行时库 |
| --- | --- |
| `GLEngine` | _LWJGL_ 的 `lwjgl` 和 `lwjgl-opengl`，以及它们的原生库 |
| `VKEngine` | _LWJGL_ 的 `lwjgl`、`lwjgl-vulkan` 和 `lwjgl-shaderc`（着色器在运行时编译），以及它们的原生库（`lwjgl-vulkan` 只在 macOS 上有原生库；Windows 和 Linux 使用系统自带的 Vulkan） |
| `ALEngine` | _LWJGL_ 的 `lwjgl` 和 `lwjgl-openal`，以及它们的原生库 |
| `JFXEngine` | JavaFX 13+ 运行时 |
| `AWTEngine`、`JSEngine`、`HeadlessGFXEngine` | 无需额外内容：JDK 已经涵盖 |

- 在 Forge、NeoForge 和 Fabric 上，WATERMeDIA 会把 _WATERCoNFIG_ 作为嵌套 JAR 打包，其他库则由游戏本身提供。
- 独立启动器会在需要时下载它缺少的库，并且只使用与构建时固定的 SHA-256 一致的库。
- 嵌入 WATERMeDIA 的应用自带类路径：开发时能编译通过，并不代表某个 Minecraft 安装会提供这些库。
- Binaries 内置了 _XZ_，WATERMeDIA 则自带 _JavaCPP_ 和 _FFmpeg_ 绑定。

_FFmpeg_ 原生库覆盖以下平台；图片在任何能运行 Java 的地方都能解码。

| 架构 | Windows | Linux | macOS | Android |
| --- | --- | --- | --- | --- |
| x86_64 | ✅ | ✅ | ✅ | ⛔ |
| aarch64 | ⛔ | ✅ | ✅ | ⛔ |

> [!WARNING]
> ARM64 上的 Windows 和 Android 都没有 _FFmpeg_ 原生库：在那里图片仍然能解码，但视频和音频无法播放，而且除非开启
> `codecs.ffmpeg.disable`，已安装的 Binaries 会导致启动失败。

## 0.1 BINARIES 与 FFMPEG
> [!IMPORTANT]
> 硬件视频解码需要能解码该编码格式的 GPU 和驱动：NVIDIA（CUDA）、Intel（QSV）、AMD（AMF），Windows 上的 Direct3D 11 或
> 12，macOS 上的 VideoToolbox，Linux 上的 VAAPI、VDPAU 或 DRM，或者 [Vulkan](#vulkan-解码)。其他情况，或者 GPU
> 跟不上时，会改用软件解码；`codecs.ffmpeg.hardwareAccel = false` 则始终使用软件解码。

> [!NOTE]
> 可配置项：`codecs.ffmpeg.disable`、`customPath`、`hardwareAccel`、`analyzeDuration`、`probeSize`、`trackPacketReads`、
> `cache` 和 `cacheMaxResponse`。请查看 `config/watermedia.toml` 中它们的注释。

_WATERMeDIA: Binaries_ 随 WATERMeDIA 一起加载，并在网络、编解码器和播放器之前准备好原生库。

- Minecraft 客户端必须安装它；专用服务器可以不装，因为它在那里什么都不提供。
- 嵌入 Java API 的应用也可以不装，仍能保留图片解码和不依赖原生库的平台解析器；但会失去视频解码以及由 _yt-dlp_
  支持的平台。
- 禁用 _FFmpeg_（`codecs.ffmpeg.disable`）会跳过解压。
- Binaries 没有用来启动或关闭它的 API：原生库文件在整个进程期间都保留在原处。
- _yt-dlp_ 和 _BotGuard_ 会在首次使用时下载，并在运行前完成校验。系统属性 `watermedia.ytdlp.binary` 和
  `watermedia.botguard.binary` 可以改为指向你自己的可执行文件。

使用自定义 _FFmpeg_ 构建时，把存放其原生库的文件夹（而不是 `ffmpeg.exe`）填到 `codecs.ffmpeg.customPath`
中。任何许可证或版本都可以，只要它带有兼容的 JNI 绑定，以及与内置构建相同的 TLS 支持（见
[HTTPS 信任与凭据](#25-https-信任与凭据)）。WATERMeDIA 只校验内置原生库的完整性。

## 0.2 配置
> [!NOTE]
> 每个字段都在它的注释中自带说明，取什么值由你决定。本指南的每个章节都会列出影响该章节的字段。

WATERMeDIA 会把设置写入工作目录下的 `config/watermedia.toml`，每个字段上方都有一条注释。默认值开箱即用，
启动时读取的字段会在下次启动时生效；当某个后端看起来不可用时，首先应该检查的就是这个文件。

- `codecs.*`：_FFmpeg_、硬件解码和图片解码器。
- `player.*`：默认画质和缓存。
- `platforms.*`：成人内容、搜索和平台账号。
- `network.*`：文件服务器和网络请求。

# 1. 启动
在 Forge、NeoForge 和 Fabric 上，加载器会替你启动 WATERMeDIA：跳过本节即可。

**示例：**
```java
import org.watermedia.WaterMedia;

WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
boolean ready = WaterMedia.loaded(); // true ONCE EVERY MODULE FINISHED; CHECK THE CAPABILITY YOU NEED TOO
String module = WaterMedia.loading(); // MODULE CURRENTLY LOADING
int step = WaterMedia.step(); // CURRENT LOADING STEP
int steps = WaterMedia.steps(); // TOTAL LOADING STEPS
```

它按以下顺序加载：
- 配置（`watermedia.toml`）
- Binaries（仅当它的 JAR 存在时）
- `NetworkAPI`
- `CodecsAPI`
- `PlayerAPI`

有 Binaries 时 `steps()` 为 5，没有时为 4。你可以在任何线程上读取 `loading()`、`step()` 和 `steps()`，用于加载界面。

- `start` 每个进程只运行一次：没有停止操作，第二次调用 `start` 会抛出异常，失败的启动也无法重试。
- 传 null 时，临时目录为 `<java.io.tmpdir>/watermedia`，工作目录则为当前目录。最后一个参数表示是否为客户端；
  在专用服务器上，Binaries、编解码器和播放器都不会启动。
- 如果某个可选部分（文件服务器、_FFmpeg_）失败，它会保持关闭，加载照常继续：请检查你需要的能力，例如
  `CodecsAPI.ffmpegLoaded()`。
- 其他任何失败（配置、已安装的 Binaries）都会让 `start` 抛出 `IllegalStateException`，并以该失败作为异常原因，`loaded()`
  保持为 false。
- 绝不要在 WATERMeDIA 的线程或回调中调用 `start`：它会拒绝运行。

每个 API 都知道它的模块是否已加载：

- 在播放器启动之前，`PlayerAPI.mrl` 会抛出 `IllegalStateException`，在专用服务器上则始终如此。
- 在网络启动之前，或者传入的不是现有文件时，`NetworkAPI.upload`
  会抛出异常；服务器宕机或配置错误则会体现在它返回的状态中。
- `PlatformAPI` 无需启动：`register`、`fetch` 和 `search` 始终可用。使用 Binaries
  的平台（YouTube、_yt-dlp_）在它加载之前会以 `PlatformException` 失败，在专用服务器上则始终失败；没有 Binaries JAR
  时，`fetch` 对它们的链接返回 null。
- 通过 `CodecsAPI` 进行的纯 Java 图片解码、`ServerMediaPlayer`、`MathUtil` 和 `MathEases` 都不需要启动。

播放器持有你的 supplier 创建的引擎。请传入
supplier，而不是已经构建好的引擎：这样媒体源不存在时，不会留下任何悬空的原生资源。OpenGL、Vulkan 和 OpenAL
上下文归你所有：销毁它们之前先释放每个播放器，重新加载设备或上下文时要创建新的引擎。JVM
运行期间，原生库永远不会卸载，也不会切换版本。

## 1.1 软件限制
### 被屏蔽的启动器
当游戏由 _TLauncher_ 启动时，WATERMeDIA 在 Minecraft
上不会启动，报错信息会说明是哪些特征暴露了它。旧安装的残留只会记录一条警告，嵌入 WATERMeDIA 的应用会跳过这项检查。

### 兼容版本
Forge、NeoForge 和 Fabric 会在以下版本的客户端和专用服务器上加载 WATERMeDIA，并拒绝其他版本：

| Minecraft | Forge | NeoForge | Fabric Loader |
| --- | --- | --- | --- |
| 1.18.2 | 40.3.10 或更新的 40.x | ⛔ | 0.14 或更新的 0.x |
| 1.19.2 | 43.5.0 或更新的 43.x | ⛔ | 0.14 或更新的 0.x |
| 1.20.1 | 47.4.2 或更新的 47.x | ⛔ | 0.14 或更新的 0.x |
| 1.21.1 | 52.1.1 或更新的 52.x | 21.1.0 或更新的 21.1.x | 0.14 或更新的 0.x |
| 1.21.11 | 61.0.5 或更新的 61.x | 21.11.0 或更新的 21.11.x | 0.14 或更新的 0.x |
| 26.1.2 | 64.x | 26.1.2.x | 0.14 或更新的 0.x |
| 26.2 | 65.x | 26.2.0.x | 0.14 或更新的 0.x |
| 26.3 | 66.x | 26.3.0.x | 0.14 或更新的 0.x |

- 在 Minecraft 上，接受 Java 17 到 25。
- WATERMeDIA 与 Binaries 互相接受的版本范围是 3.0.0 到 3.1.0（不含 3.1.0）；旧的 3.0.0 BETA 构建则不行。
- Fabric 无法只在客户端上要求某个依赖，所以在那里由引导程序在客户端上要求 Binaries。

## 1.2 公开包与内部包
公开 API 由以下包组成：`org.watermedia`、`api.codecs`、`api.network`、`api.platform`、`api.player` 和
`api.util`，不含它们的子包。其余一切都是内部的。

没有什么会阻止你访问内部代码，但请使用 `PlayerAPI.player`、`PlatformAPI.fetch`
这类公开入口，不要碰它们背后的东西。如果你参与贡献，`gradle javadocInventory` 会列出缺少文档的公开方法。

> [!WARNING]
> `CodecsAPI` 是实验性的：`decode`、`reader`、`sniff`、`encoder` 以及与它们相关的读取器、
> 编码器和滤镜可能在任何更新中变化，且不遵循版本规则。暂时不要在你的模组中使用它们；播放器已经替你使用了它们。
> 状态检查方法（`ffmpegLoaded()`、`ffmpegError()`、`vulkanDecode()`）可以放心使用。

## 1.3 诊断与示例
项目的日志记录器是 `watermedia` 和 `watermedia_binaries`；诊断时把这两个名称设为 DEBUG 级别。

- 日志会完整显示链接，方便调试，但会隐藏机密：链接中的密码、JWT 令牌、`Authorization`、`Cookie`、Minecraft
  会话，以及任何看起来像机密的 `key=value`。应用的日志上传也经过同一个过滤器。
- 会重复出现的问题（缓冲区过短、布局被拒绝、回调失败、上传被拒绝）在每个引擎、播放器、
  服务器或缓存中只在第一次出现时记录。过慢的迭代会被计数，并在结束时汇报一次。
- _FFmpeg_ 仍会把它自己的日志写到错误输出，不经过项目日志记录器及其机密过滤器。
- `-Dwatermedia.glchecks=true` 会在上传帧时开启 OpenGL 错误检查。

`src/test/java/org/watermedia/ApiGuideExample.java`
包含每个引擎以及同步播放（时钟和跟随者）的可编译示例；其中的图片示例在 `ApiGuideExampleTest`
中运行。如果你参与贡献，`-Prequire_natives=true` 会让测试在缺少 _FFmpeg_ 原生库时失败，而不是跳过。

# 2. 管理链接
> [!NOTE]
> 可配置项：`platforms.allowMatureContent`、`searchLimit`、`searchCleanupInterval`、`biliBiliCookie` 和
> `player.mrlCleanupInterval`。请查看 `config/watermedia.toml` 中它们的注释。

MRL（Multimedia Resource Location，多媒体资源位置）会把任何链接变成它所包含的媒体：一个或多个媒体源，
每个源都带有自己的画质、音轨和元数据。它在后台解析，并保留在缓存中。

## 2.1 解析链接
```java
MRL mrl = PlayerAPI.mrl("https://example.com/movie.mp4");    // A WEB LINK, A FILE UNDER THE WORKING DIRECTORY OR A water:// LINK
MRL[] queue = PlayerAPI.preload(firstUri, secondUri);        // START RESOLVING SEVERAL AT ONCE

// FROM A GAME TICK OR A UI TIMER
switch (mrl.status()) {
    case FETCHING -> { }                                     // STILL RESOLVING
    case LOADED -> play(mrl);                                // READY TO PLAY
    case BLOCKED -> showMatureWarning();                     // MATURE CONTENT WHILE IT'S NOT ALLOWED
    case ERROR -> showError(mrl.exception());                // WHY IT FAILED; A MALFORMED LINK STARTS HERE
    case EXPIRED, FORGOTTEN -> mrl = mrl.reload();           // STALE OR EVICTED: KEEP THE HANDLE reload() RETURNS
}

mrl.subscribe(done -> refreshUi());                          // OR GET CALLED ONCE WHEN IT FINISHES OR FAILS
boolean finished = mrl.await(5_000);                         // OR WAIT, BUT NEVER ON THE GAME OR RENDER THREAD
```

```java
// ONE LINK CAN HOLD SEVERAL SOURCES, SUCH AS A GALLERY WITH AN IMAGE AT 0 AND A VIDEO AT 1
MRL.Source first = mrl.source(0);                            // null FOR A WRONG INDEX
MRL.Source video = mrl.sourceByType(MediaType.VIDEO);        // THE FIRST VIDEO, OR null
List<MRL.Source> all = mrl.sources();

MediaType type = video.type();                               // IMAGE, VIDEO, AUDIO...
Metadata info = video.metadata();                            // TITLE, DESCRIPTION, AUTHOR, DURATION...
URI thumbnail = video.thumbnail();
URI link = video.uri(MediaQuality.HIGH);                     // THE CLOSEST QUALITY AVAILABLE
List<Track> dubs = video.audioTracks();                      // SEPARATE AUDIO TRACKS, THE ORIGINAL FIRST

MediaPlayer player = PlayerAPI.player(mrl, 1, gfx, sfx);     // PLAY THE SOURCE AT INDEX 1 (SEE PLAYING MEDIA)
```

当服务器返回错误状态或非媒体响应时，直链会解析失败；HLS 播放列表会作为一个视频打开，IPTV
播放列表则每个频道对应一个媒体源。

## 2.2 平台
以下平台的链接开箱即可解析：

- 📺 YouTube、Twitch、Kick、BiliBili、Odysee、D.tube、VidLii
- 🐦 Twitter（X）、Bluesky、TikTok、Facebook、Instagram
- 🎵 SoundCloud、Newgrounds（影片、音乐和美术作品）
- 🖼️ Imgur、Lightshot、Medal、Streamable、Sendvid
- 📁 Google Drive、Dropbox、MediaFire
- 🔞 PornHub（仅在开启 `platforms.allowMatureContent` 时）
- 🔗 任何视频、音乐、图片或播放列表的直链，本地文件，以及 [`water://` 链接](#24-water-协议)

YouTube、SoundCloud、Facebook 和 Instagram 通过 _yt-dlp_ 播放，因此需要 Binaries。BiliBili 最高播放 480p；如果在
`platforms.biliBiliCookie` 中填入你的会话 Cookie，最高可达 1080p（VIP 可达 8K）。

```java
Platform.Resolution resolved = PlatformAPI.fetch(uri);       // THE RESOLUTION WITHOUT AN MRL, FOR TOOLS; null WHEN NO PLATFORM HANDLES THE LINK

// YOUR OWN SITE: CHECKED BEFORE THE BUILT-IN PLATFORMS, AND IT NEEDS NO STARTUP
PlatformAPI.register(new Platform() {
    @Override
    public String name() { return "MySite"; }

    @Override
    public Platform.Resolution resolve(final URI uri) throws PlatformException {
        if (!"mysite.com".equals(uri.getHost())) return null;            // NOT YOURS: THE NEXT PLATFORM TRIES
        if (isProfile(uri)) throw PlatformException.unsupported(this.name(), uri); // YOURS BUT NOTHING TO PLAY: MRL GOES ERROR
        if (isMature(uri)) throw new MatureContentException(this.name(), "Mature video"); // MRL GOES BLOCKED

        final Platform.Quality quality = new Platform.Quality(findVideo(uri), 1920, 1080); // REAL SIZE, 0 WHEN UNKNOWN
        final Platform.Source source = new Platform.Source(MediaType.VIDEO, null, null, null, List.of(quality), null, null);
        return new Platform.Resolution(null, source);                    // null EXPIRY: THE LINKS NEVER EXPIRE
    }
});
```

你的平台发生的其他任何失败同样是 `PlatformException`；相册或播放列表会跳过出错的条目，而不是整体失败。

## 2.3 搜索
YouTube（需要 Binaries）、Twitch、Kick 和 Imgur 支持按关键词搜索，你的平台只要重写 `search(query, limit)` 就能加入它们。

```java
List<PlatformResult> results = PlatformAPI.search("lofi");   // RETURNS AT ONCE; THE LIST FILLS AS PLATFORMS ANSWER
boolean searching = PlatformAPI.searching("lofi");           // false ONCE EVERY PLATFORM ANSWERED; POLL BOTH FROM THE UI
for (final PlatformResult hit: results) {
    show(hit.title(), hit.thumbnail(), hit.platform().name()); // WHEN PICKED, PlayerAPI.mrl(hit.uri()) OPENS IT LIKE ANY LINK
}
List<PlatformResult> fresh = PlatformAPI.search("lofi", true); // ASK THE PLATFORMS AGAIN INSTEAD OF THE CACHE
```

新的查询会替换上一个查询；用户还在输入时，它会稍等片刻，等待下一次按键。Twitch 和 Kick 只列出正在直播的频道。

## 2.4 WATER 协议
```java
PlayerAPI.mrl("water://local/config/song.mp3");              // ./config/song.mp3, RESOLVED AGAINST THE WORKING DIRECTORY
PlayerAPI.mrl("water://remote/xyz12345");                    // A FILE ON YOUR FILE SERVER (SEE UPLOADING AND DOWNLOADING FILES)
PlayerAPI.mrl("water://global/abcd6789");                    // A FILE ON OUR GLOBAL SERVER
```

远程（remote）和全局（global）链接携带的是服务器分配的文件 ID，而不是文件夹路径。

## 2.5 HTTPS 信任与凭据
对于 Java 已经信任的证书，HTTPS 都开箱即用，而且这项校验永远不会关闭。

使用自签名证书或你自己的证书颁发机构？把它导入一个信任库，并在 WATERMeDIA 启动之前让 Java 指向它（在 Minecraft
上，把同样的 `-D` 选项放进启动器的 JVM 参数）：

```shell
keytool -importcert -alias my-server -file my-server.crt -keystore my-truststore.jks -storepass changeit -noprompt
java -Djavax.net.ssl.trustStore=my-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit -jar my-app.jar
```

- 空的或损坏的信任库会导致 _FFmpeg_ 无法启动，所以在修复之前视频都不可用。
- 自定义的 _FFmpeg_ 构建需要与内置构建相同的 TLS 支持。
- 登录用的请求头和
  Cookie（`Authorization`、`Cookie`、`X-WaterMedia-Token`）绝不会跟随重定向发给其他服务器，而且重定向只会指向 http 或
  https 链接。

# 3. 播放媒体
> [!NOTE]
> 可配置项：`player.defaultQuality`、`player.cacheBudget`、`player.watertx.cache` 和
> `player.watertx.texturesBudget`。请查看 `config/watermedia.toml` 中它们的注释。

## 3.1 创建与控制播放器
```java
MediaPlayer player = PlayerAPI.player(mrl, 0,
        () -> PlayerAPI.glEngine(renderThread, renderExecutor),
        PlayerAPI::jsEngine);
if (player == null) {
    throw new IllegalStateException("Source is unavailable or its backend failed");
}
try {
    if (!player.start()) throw new IllegalStateException("Player refused to start");
} catch (RuntimeException | Error failure) {
    player.release();
    throw failure;
}
```

当媒体源仍在解析中、索引不存在、缺少后端或构造时抛出 `Exception` 时，`player` 返回 null（`Error`
则会继续向上抛出）。重试之前，先检查 MRL 的状态。

- supplier 只在需要时运行，图片永远不会调用音频 supplier。
- supplier 返回 null 时会省略对应的输出，例如在纯音频播放时用 `() -> null` 作为图形引擎。
- 绝不要在多个播放器之间共享引擎。

`TxMediaPlayer` 播放支持的图片和动画，`FFMediaPlayer` 通过 _FFmpeg_ 播放音频和视频，`ServerMediaPlayer`
则是一个同步时钟，不做解码，也没有原生引擎。

这三种播放器共用同一套控制方法：调用生效时返回 `true`，被拒绝时返回
`false`（当前无法执行、已经释放，或者速度超出范围）。输出设置方法没有返回值。

```java
// PLAY, PAUSE AND STOP
boolean started = player.start();                 // PLAYS FROM THE BEGINNING; ALSO RESTARTS AN ENDED, STOPPED OR FAILED PLAYER
boolean startedPaused = player.startPaused();     // THE SAME, BUT LANDS PAUSED (SAFER THAN start() FOLLOWED BY pause())
boolean paused = player.pause();                  // false WHEN ALREADY PAUSED, ENDED, STOPPED OR FAILED; WHILE LOADING IT WAITS FOR THE PIPELINE
boolean resumed = player.resume();                // false UNLESS PAUSED
boolean pauseChanged = player.pause(wantPaused);  // pause() OR resume() FROM A FLAG, SUCH AS A CHECKBOX
boolean toggled = player.togglePlay();            // PAUSES, RESUMES, OR STARTS AN ENDED OR STOPPED PLAYER AGAIN
boolean stopped = player.stop();                  // STOPPED WITH time() AT 0; false WHEN ALREADY STOPPED OR NEVER STARTED

// MOVE THROUGH THE MEDIA (MILLISECONDS, CLAMPED TO THE DURATION)
boolean sought = player.seek(15_000);             // PRECISE; ON AN ENDED OR STOPPED PLAYER IT RESTARTS PAUSED AT THE TARGET
boolean soughtQuick = player.seekQuick(15_000);   // FASTER, LESS PRECISE ON VIDEO; THE SAME AS seek() ON IMAGES
boolean skipped = player.skipTime(-10_000);       // RELATIVE TO time(); NEGATIVE GOES BACK
boolean forwarded = player.forward();             // 5 SECONDS AHEAD (1 SECOND ON ANIMATIONS)
boolean rewound = player.rewind();                // 5 SECONDS BACK (1 SECOND ON ANIMATIONS)
boolean stepped = player.nextFrame();             // ONLY WHILE PAUSED AND NOT FOLLOWING A CLOCK
boolean steppedBack = player.previousFrame();     // ONLY WHILE PAUSED AND NOT FOLLOWING A CLOCK

// PLAYBACK OPTIONS
boolean speedChanged = player.speed(1.25f);       // (0, 4]; false ON LIVE STREAMS, JAVA SOUND OR A RATE THE AUDIO ENGINE REFUSES
boolean repeatChanged = player.repeat(true);      // false ONLY AFTER release()
boolean trackChanged = player.audioTrack(track);  // SEE AUDIO TRACKS AND LANGUAGES
boolean placed = player.spatialAudio(position);   // SEE SPATIAL AUDIO; false WITHOUT A SPATIAL OPENAL ENGINE

// OUTPUT SETTINGS: ALWAYS TAKEN, SO THEY RETURN NOTHING
player.volume(50);                                // 0 TO 100, CLAMPED; MUTE NEVER CHANGES IT
player.mute(true);
player.quality(MediaQuality.HIGH);                // THE CLOSEST AVAILABLE; HOT SWITCH ON VIDEO, NEXT START ON IMAGES
player.scale(1280, 720);                          // UPLOAD SIZE CAP PER AXIS, 0 FOR NO CAP
player.lod(MediaPlayer.LodLevel.FAR);             // UPLOAD SIZE BY DISTANCE: MAX 100%, CLOSE 75%, NEAR 50%, FAR 25%, FAR_AWAY 10%
player.displayTime(10_000);                       // A STILL IMAGE ENDS AFTER THIS AND NEVER LOOPS; 0 KEEPS IT UNTIL STOPPED
player.audioLanguage("es-MX");                    // PREFERRED AUDIO LANGUAGE FROM THE NEXT START

// WHAT THE MEDIA ALLOWS: ASK BEFORE SHOWING A CONTROL
boolean playable = player.canPlay();              // FROM THE FIRST SUCCESSFUL LOAD (FROM CREATION ON A CLOCK) UNTIL IT FAILS OR IS RELEASED
boolean seekable = player.canSeek();              // false UNTIL THE SOURCE OPENS, ON LIVE STREAMS AND ON STILL IMAGES
boolean speedable = player.canSpeed();            // A PURE QUERY, SAFE FROM ANY THREAD ON EVERY FRAME
boolean live = player.live();                     // false UNTIL THE SOURCE OPENS
boolean positional = player.spatial();            // POSITIONAL AUDIO IS AVAILABLE

// WHERE IT IS
MediaPlayer.Status status = player.status();      // ALSO playing(), paused(), buffering(), ended(), error()...
long time = player.time();                        // 0 BEFORE THE FIRST FRAME, THE DURATION ONCE ENDED
long duration = player.duration();                // 0 WHEN UNKNOWN OR LIVE
int buffered = player.buffered();                 // 0 TO 100: REAL PROGRESS WHILE A VIDEO LOADS OR BUFFERS; IMAGES AND CLOCKS REPORT 100 OR 0
float fps = player.fps();                         // 0 UNTIL KNOWN
Throwable failure = player.exception();           // WHY IT LANDED IN ERROR
int width = player.width();                       // UPLOADED SIZE; sourceWidth() AND sourceHeight() GIVE THE NATIVE ONE
int height = player.height();

// DRAW: ON THE RENDER THREAD, READ FRESH ON EVERY FRAME
long texture = player.texture();                  // OPENGL TEXTURE NAME OR VULKAN VkImageView HANDLE; 0 MEANS NOTHING TO DRAW

// CLEAN UP WHEN THE HOST DROPS THE SURFACE, FAILED PLAYBACK INCLUDED
player.release();                                 // IDEMPOTENT, LEAVES status() AT STOPPED; NEVER FROM A LISTENER OR PLAYBACK THREAD
```

请在音频上下文为当前上下文时调用 `speed(...)`：时钟只会采用音频引擎接受了的速率。内置图形引擎暂时还不接受压缩的 BC
纹理。

```java
final MediaPlayer.StatusListener playlist = new MediaPlayer.StatusListener() {
    @Override
    public void onStatus(final MediaPlayer source, final MediaPlayer.Status previous, final MediaPlayer.Status current) {
        // EVERY REAL TRANSITION, ONE AT A TIME AND IN ORDER; KEEP IT SHORT AND NEVER BLOCK
        if (current != MediaPlayer.Status.ENDED) return;
        source.onStatus(this); // ENDED, STOPPED AND ERROR CONSUME THE LISTENERS: REGISTER AGAIN TO KEEP HEARING
        source.start();        // RESTARTING HERE IS FINE; source.release() NEVER IS
    }
};
player.onStatus(playlist); // ADDING IT TWICE KEEPS IT ONCE
```

- 监听器运行在驱动这次变化的线程上：播放线程、同步计时器（ticker），或者在图片上，调用 `status()`、`time()` 或
  `texture()` 的线程。
- 持有播放器的锁时，或者在 `release()` 正在等待的线程上，监听器绝不会运行。`release()` 发出的 `STOPPED`
  会在一切（包括引擎）都释放之后才到达。

| 播放器状态 | 含义 |
| --- | --- |
| `WAITING` | 已创建，正在等待启动条件。 |
| `LOADING` | 正在加载或准备媒体。 |
| `BUFFERING` | 正在等待足够的数据以继续播放。 |
| `PLAYING` | 正在播放媒体。 |
| `PAUSED` | 已暂停，可随时恢复。 |
| `STOPPED` | 已停止，可从头开始播放。 |
| `ENDED` | 已播放到媒体末尾。 |
| `ERROR` | 播放遇到了故障。 |

`Status.allows(next)` 用来判断暂停、恢复、缓冲、结束、停止或失败能否直接转到
`next`。启动、跳转和重新启动遵循各自的规则：`LOADING` 在管线打开时（或者停止、失败时）结束，在 `ENDED` 或 `STOPPED`
上跳转会重新启动，而 `STOPPED` 没有任何直接转换。

## 3.2 音轨与语言
配音视频以及带有多个音频流的文件会播放原始音频（或文件的默认音频），除非你另行指定。

```java
player.audioLanguage("es-MX"); // PREFERRED FROM THE NEXT START; null CLEARS IT
for (final MediaPlayer.AudioTrack track: player.audioTracks()) {
    if ("es".equals(track.lang())) player.audioTrack(track); // SWITCHES RIGHT AWAY ON A RUNNING PLAYER
}
```

- 选择顺序是：你用 `audioTrack(...)` 选的音轨，然后是
  `audioLanguage(...)`，然后是原始音轨（或文件的默认流），否则就是第一个。`es-MX` 会先选 `es-MX`，然后是 `es-419`、`es`
  或 `spa`。没有 `audioLanguage(...)` 时播放的是原始音频，绝不会自动选用配音。
- `audioTracks()` 为每个流列出一个
  `AudioTrack(track, stream, name, lang)`：先是主文件的流，然后是每个独立音轨的流。`track` 是 `AudioTrack.MAIN`
  或独立音轨的索引，`stream` 是其中的 _FFmpeg_ 索引。
- 尚未播放过的独立音轨会显示为一个 `provisional()` 条目（`stream` 为 -1），带有平台提供的名称和语言，
  所以列出音轨时不会打开每一条配音。一旦播放过，它的真实音频流就会取而代之。这个列表不可变，任何线程都可以读取。
- 选择发生变化时，`audioTrack(track)` 返回 `true`。
  正在运行的播放器会在当前时间重建它的管线（直播流会跳到直播的最新位置），而且这个选择在切换画质和重新启动后依然保留。
  `audioTrack()` 返回当前正在播放的音轨。图片播放器、服务器播放器以及没有音频的播放器不会列出任何内容。

## 3.3 选择引擎
Vulkan 优先，OpenGL 继续受支持。使用哪个后端由你的渲染层决定。

| 工厂方法 | 宿主的责任 |
| --- | --- |
| `PlayerAPI.glEngine(renderThread, executor)` | 执行器运行在 OpenGL 上下文的线程上：每帧都要运行它的任务，`release()` 之后也要再运行一次。两个参数都为 null 时，调用线程必须持有该上下文。绝不要把引擎移到另一个上下文。 |
| `PlayerAPI.vkEngine(context)` | 传入一个生命周期比引擎更长的 `VKContext`：你的提交和呈现要用 `queueLock()` 包住，并在你正在处理中的帧完成后运行 `retire(...)` 回调（设备销毁时要全部运行）。要在 GPU 上解码，请参见 [Vulkan 解码](#vulkan-解码)。 |
| `PlayerAPI.awtEngine(onFrame)` | 在 AWT/Swing 界面中绘制发布出来的图像；按需把可选回调切换到 UI 线程上执行。 |
| `PlayerAPI.jfxEngine(onFrame)` | 需要 JavaFX 13+ 运行时；把引擎的图像绑定到你的界面上。没有该运行时，工厂方法会抛出 `IllegalStateException`，而不是链接错误。 |
| `PlayerAPI.headlessEngine()` / `headlessEngine(preload)` | 无需图形上下文即可捕获帧，适合测试和图像处理。和所有引擎一样，它只存在于客户端；专用服务器只能创建 `ServerMediaPlayer` 时钟。 |
| `PlayerAPI.alEngine()` | OpenAL 上下文必须在音频线程上是当前上下文。 |
| `PlayerAPI.alEngine(true)` | 与上面相同的 OpenAL 约定，通过带位置的单声道输出端播放（立体声对和额外扬声器）。 |
| `PlayerAPI.jsEngine()` | 有可用的 Java Sound 设备和线路（line）；不支持定位音频。 |

> [!IMPORTANT]
> **OpenGL**：在 GPU 上转换 YUV 和灰度视频需要 OpenGL 3.2 core（GLSL 150）；不满足时，帧会在 CPU 上转换为
> BGRA。直接从解码线程上传需要 OpenGL 4.4 或 `ARB_buffer_storage`；较旧的驱动会在渲染线程上通过两个像素缓冲区上传。

> [!IMPORTANT]
> **Vulkan**：`queue()` 必须能接受计算和传输工作。
> - **零拷贝**：当设备启用 `VK_EXT_external_memory_host`（`hostImportSupported()` 返回 true）且平面缓冲区按
>   `minImportedHostPointerAlignment()` 对齐时，解码后的平面无需 CPU 拷贝即可到达 GPU；否则会通过暂存缓冲区拷贝。
> - **硬件 YUV 转换**：当设备启用 `samplerYcbcrConversion`（Vulkan 1.1，`ycbcrSampler()` 返回
>   true）时，尺寸为偶数且不使用 BT.2020 原色的 8 位 SDR NV12 以及 YUV 4:2:0、4:2:2 和 4:4:4
>   帧会在采样器硬件中转换；其他所有布局都使用计算通道（compute pass）。
> - **布局**：除了 8 位的 BGRA、RGBA、YUYV 和 UYVY
>   之外，其他布局需要它的平面格式支持采样、线性过滤和传输写入，否则会回退到 BGRA。RGB 总是会回退。
> - **GPU 解码**：见 [Vulkan 解码](#vulkan-解码)。

引擎是密封的：你提供的是上下文和执行器，而不是你自己的引擎。每个引擎自行管理它的纹理、音源和缓冲区。

每个引擎都遵循同一套生命周期：

- `release()` 是幂等的，绝不能与向它输送数据的播放器同时运行，调用后引擎就不再工作：`released()` 为 true，`texture()`
  返回 0。
- 播放器在上传前会检查 `supports(format, bits)`，引擎不接受某个布局时会回退到 8 位 BGRA。
- 上传永远不会抛出异常：驱动出错时会丢弃该帧并记录一条错误日志，屏幕上保留最后一帧。
- 软件引擎和无头引擎暴露的是一个非零的哨兵值，而不是 GPU 句柄，所以只需把句柄和 0 比较。
- `PixelFormat.planeWidth`、`planeHeight`、`texelBytes` 和 `frameBytes`
  计算引擎每个平面的尺寸，例如用来了解它占用多少内存。它们像 _FFmpeg_ 一样向上取整，所以奇数尺寸不会丢失色度。

色彩处理：

- OpenGL 和 Vulkan 引擎会按照每个视频声明的矩阵（BT.601、BT.709 或 BT.2020）、范围和色度位置来转换
  YUV。视频什么都没声明时，它们会像 _mpv_ 一样推测：SD 帧（宽度小于 1280 像素且高度不超过 576 行）用 BT.601，其他情况用
  BT.709。
- PQ 和 HLG HDR 会以 SDR 显示，BT.2020 色彩映射到 BT.709，高光经过压缩，使 1000 尼特的峰值亮度刚好达到白色。
- 同一个上下文或设备上的引擎共享这套转换，它会随最后一个引擎一起消失。在销毁上下文或设备之前，请释放每个引擎，
  并让它的清理工作执行完（在 OpenGL 执行器上或在 `VKContext.retire` 中）。
- 软件引擎拿到的是由 _FFmpeg_ 按声明的矩阵和范围转换出的 BGRA，不做 HDR 色调映射。

OpenAL 要求每个播放器线程（包括同步计时器）都把它的上下文设为当前上下文。Java Sound
不需要额外加锁；当音频块放不进线路时，它会打开一条更宽的线路，并丢弃旧线路中还留着的内容。

> [!IMPORTANT]
> **OpenAL**：在普通引擎上，4、6、7 和 8 声道音频需要
> `AL_EXT_MCFORMATS`（空间引擎会把一切都折叠为立体声）；单声道和立体声的 float 与 double 采样需要 `AL_EXT_FLOAT32` 和
> `AL_EXT_DOUBLE`（环绕声的 float 随 `AL_EXT_MCFORMATS` 提供）；而要让时钟计入设备的输出延迟，则需要
> `AL_SOFT_source_latency`。缺少这些扩展时，音频会转换为设备能播放的格式。使用 EFX 的[环境处理器](#环境处理器)需要
> `ALC_EXT_EFX`。

独立应用（直接打开 WATERMeDIA JAR）默认使用 Vulkan，除非你选择了 OpenGL；没有 Vulkan 时，它这一次会改用 OpenGL
启动，但不会改变你的选择。`-Dwatermedia.engine=opengl` 或 `-Dwatermedia.engine=vulkan`
可以为一次启动强制指定引擎。它的设置（引擎、界面缩放、CRT 效果和点击音效）保存在 `config/app.properties` 中。

### Vulkan 解码
默认情况下 `VKContext.deviceInfo()` 返回 null，视频从内存上传。重写它，告诉它你是如何创建设备的（传给 `vkCreateDevice`
的特性链，设备存活期间不能释放或改动它；已启用的实例扩展和设备扩展；以及每个队列族创建的队列），_FFmpeg_ 就会用 Vulkan
Video 解码 H.264、H.265 和 AV1，无需经过内存中转。

> [!IMPORTANT]
> Vulkan 解码需要：
> - 启用了 `timelineSemaphore` 和 `synchronization2` 的 Vulkan 1.3 实例和设备。
> - `VK_KHR_video_queue`、`VK_KHR_video_decode_queue`，以及每种需要加速的编码格式对应的解码扩展：
>   `VK_KHR_video_decode_h264`、`VK_KHR_video_decode_h265` 或 `VK_KHR_video_decode_av1`。
> - 你的设备上有空闲队列，或者具备用于孪生设备的外部内存和信号量扩展，两者都在下文说明。
> - 支持 Vulkan Video 的原生库（`CodecsAPI.vulkanDecode()` 返回 true），并开启 `codecs.ffmpeg.hardwareAccel`。

此时 _FFmpeg_ 会在以下两处之一解码：

- **你的设备**：当它有可以留给 _FFmpeg_ 的空闲队列时，也就是除 `queue()`
  之外的一个计算队列（它也承担传输工作）和一个视频解码队列，并且 `queue()` 是 `queueFamily()` 中最后创建的队列。_FFmpeg_
  会占用每个队列族中的其他所有队列，并从它的线程向这些队列提交，所以只要还有播放器存在，就不要碰这些队列，也不要调用
  `vkDeviceWaitIdle`；请等待你自己的队列（在 `queueLock()` 内调用 `vkQueueWaitIdle`）。
- **孪生设备**：当你的设备没有这样的队列，但在 Windows 上启用了 `VK_KHR_external_memory_win32` 和
  `VK_KHR_external_semaphore_win32`，或在其他系统上启用了 `VK_KHR_external_memory_fd` 和 `VK_KHR_external_semaphore_fd`
  时。引擎会在同一块 GPU 上创建第二个设备，_FFmpeg_ 在那里解码，每张图像再导入你的设备。你的队列仍然完全归你所有。

无论哪种方式，都要在销毁设备或它的实例之前释放每个播放器。

> [!IMPORTANT]
> 4:2:2、4:4:4 和 12 位视频只能通过 _libplacebo_ 在 GPU 上解码，它会把每种解码后的布局都转换为 SDR RGBA（HDR
> 会做色调映射）。它要求解码设备启用
> `hostQueryReset`、`runtimeDescriptorArray`、`shaderStorageImageArrayNonUniformIndexing` 和
> `maintenance4`，在你自己的设备上解码时还需要 `queueFamily()` 中有第二个队列；当 GPU
> 支持这些特性时，孪生设备会自行启用它们。没有 _libplacebo_ 时，只有 4:2:0 的流（8 位和 10 位）能在 GPU
> 上解码，引擎会用自己的着色器转换它们。

## 3.4 空间音频
在构建播放器之前，用 `PlayerAPI.alEngine(true)` 创建引擎；空间模式在整个生命周期内固定不变。
立体声和环绕声音频会折叠为立体声（单声道保持单声道），并通过输出端（sink）播放，也就是带位置的单声道音源。普通的
`alEngine()` 会在一个没有位置的音源上播放一切，而 Java Sound 不支持空间音频（它返回 `false`）。

引擎一开始只有一个主输出端，使用 `Mix.MONO`，没有位置。传入一个 `SpatialAudio` 可以把它放到世界中，传入 `null`
则回到普通播放，没有位置也没有效果。音量、静音、速度、暂停和 `sync` 作用于每个输出端。

```java
import org.watermedia.api.player.SFXEngine.SpatialAudio;

final ALEngine audio = PlayerAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

请在宿主的声音执行器上创建、更新和释放引擎，并确保它的 OpenAL
上下文是当前上下文。设备、上下文和听者归你所有，它们的生命周期必须比使用它们的播放器更长。

- `FFMediaPlayer`
  使用它自己的线程，所以上下文必须对整个进程是当前上下文。只按线程（线程局部）设置当前上下文的宿主不受支持。
- 引擎会拒绝不同的或缺失的上下文，也绝不会切换或重建你的上下文。
- 在设备重新加载之前释放播放器，然后在新的上下文中创建新的引擎。
- `FFMediaPlayer.release()`
  会先停止并等待（join）它的管线结束，再删除音源：请在销毁上下文之前调用它，绝不要在该管线的回调中调用。

`SpatialAudio` 会拒绝 OpenAL 浮点数无法容纳的位置、任何不满足 `0 < referenceDistance < maxDistance`
的距离，以及负的衰减系数（rolloff，为 0 时关闭衰减）。如何衰减取决于你的宿主的距离模型；API
绝不会改动它、听者或音源速度，所以 `maxDistance` 并不总是意味着静音。

### 输出端：立体声对与额外扬声器
一次解码就能供给所有输出端，所以立体声对或额外的扬声器都不需要第二次下载或解码。每个输出端播放一种
`ALEngine.Mix`：`LEFT`、`RIGHT` 或
`MONO`（两者的平均值）。在单声道媒体上，每种混音听起来都一样，所以立体声对绝不会变成无声。

```java
// A STEREO PICTURE: THE PRIMARY TURNS INTO THE LEFT EDGE AND A NEW SINK PLAYS THE RIGHT ONE
final ALEngine.Sink left = player.sinks().get(0);
left.mix(ALEngine.Mix.LEFT);
final ALEngine.Sink right = player.addSink(ALEngine.Mix.RIGHT);
left.spatialAudio(new SpatialAudio(leftX, y, z, 8.0f, 64.0f, 1.0f, false, environment));
right.spatialAudio(new SpatialAudio(rightX, y, z, 8.0f, 64.0f, 1.0f, false, environment));

// A SPEAKER BLOCK: ONE MORE SINK AT ITS OWN POSITION, RELEASED WHEN THE BLOCK GOES AWAY
final ALEngine.Sink speaker = player.addSink(ALEngine.Mix.MONO);
speaker.spatialAudio(new SpatialAudio(speakerX, speakerY, speakerZ, 8.0f, 64.0f, 1.0f, false, environment));
speaker.release();
```

- 主输出端持有 `player.audioSource()`，驱动时钟，存活时间与引擎相同（它的 `release()` 返回 `false`）。`player.sinks()`
  会把主输出端排在第一位；没有空间 OpenAL 引擎时，`sinks()` 返回空列表，`addSink(...)` 返回 `null`。
- 每个输出端都排队相同的采样，并且一起受控，所以立体声对能保持采样级对齐。在播放过程中添加输出端，
  或在立体声媒体上更改混音，会让队列重新与主输出端对齐，伴随一次极短的暂停。
- 每个输出端都有自己的 `spatialAudio(...)`：环境适配器拿到的是该输出端的音源，`sink.release()`
  会在删除音源之前先重置它。释放播放器会释放所有输出端。
- 请在声音执行器上、上下文为当前上下文时添加、更改和释放输出端；它们的查询方法（`source()`、`mix()`、`spatialAudio()`、
  `released()`）不需要上下文。
- 宿主可以在自己的声音系统中使用输出端的音源，并更改它的增益、音高和位置，但绝不能播放、排队、出队或删除它。
  新的输出端以引擎的增益和音高开始。

### 环境处理器
`SpatialAudio` 的最后一个参数是可选的 `SpatialAudio.Environment`，用来把你自己的效果（例如遮挡或混响）应用到每个 OpenAL
音源上。它是对接空间音效模组的入口：在你的模组中写一个小适配器，把每个音源转交给它们，
并且只在该模组存在时加载这个适配器。

```java
final SpatialAudio.Environment environment = new SpatialAudio.Environment() {
    @Override
    public void process(final int source, final SpatialAudio audio) {
        // EVERY spatialAudio(...) UPDATE: APPLY YOUR WHOLE ENVIRONMENT (EFX FILTERS AND SENDS) TO source
    }

    @Override
    public void reset(final int source) {
        // THE PROCESSOR IS REPLACED, REMOVED OR THE SINK IS RELEASED: REMOVE YOUR EFFECTS FROM source
    }
};
player.spatialAudio(new SpatialAudio(x, y, z, 8.0f, 64.0f, 1.0f, false, environment));
```

- 在多次更新之间保持同一个稳定的处理器实例，并在宿主的声音执行器上、上下文为当前上下文时调用 `spatialAudio(...)`。
- 每次调用 `process` 之前，位置都会恢复，音源的滤波器和发送（send）都会被清除，所以你可以移动
  `AL_POSITION`，但必须在每次调用时应用完整的环境。
- 请在宿主中限制更新频率（而不是在处理器内部提前返回），播放器很多时，把它们分散到不同的 tick 中。
- 引擎只删除它的音源和缓冲区，绝不会删除处理器的滤波器或效果槽。
- 如果 `process` 或 `reset` 抛出异常，效果会被清除，异常会继续向上抛出；`reset` 失败也不会阻止音源在释放时被删除。
- `auxOnly = true` 要求只输出反射声，但不会让直达声路径静音：需要完全静音时，请将播放器静音。
- 处理器只在你更新时运行，绝不会在解码过程中运行。基本的定位不需要 EFX；使用 EFX 的处理器需要一个支持 EFX 的设备。

### 验证
`SpatialAudioTest` 和 `ALSinkTest` 会用真实的 OpenAL
测试空间音频和输出端：参数、衰减、环境、异常、上下文、混音和欠载。它们需要 OpenAL 输出设备，解码相关的用例还需要
_FFmpeg_ 原生库。

## 3.5 同步播放
`PlayerAPI.serverPlayer()` 会构建一个 `ServerMediaPlayer`，这是一个不含媒体也没有引擎的时钟，在客户端和服务端都能运行。
它负责记录时间（启动、暂停、跳转、速度、重复播放和结束），每次变更都会让 `revision()` 递增。其余的归你负责：
谁来驱动时钟、快照如何送达客户端，以及你把位置保存在哪里。

```java
ServerMediaPlayer clock = PlayerAPI.serverPlayer();
clock.syncDuration(durationMs);
clock.start();
// ON EVERY SERVER TICK: SEND clock.snapshot() WITH YOUR OWN PACKET WHEN clock.revision() MOVED
MediaPlayer player = PlayerAPI.player(mrl, gfxSupplier, sfxSupplier);
player.sync(snapshot); // ON THE CLIENT, FOR EVERY SNAPSHOT RECEIVED
```

通过 `sync(PlayerState)`（或用于编码形式的 `sync(ByteBuffer)`）接收过快照的播放器，从那以后就会跟随时钟。它每 50 ms 会：

- 同步快照的状态：启动、暂停和停止。`BUFFERING` 快照（只有手动构建的快照才会携带）会像暂停一样保持不动。
- 采用它的重复播放设置和速度。
- 当 `drift()` 超过 `tolerance(ms)`（默认一秒）时，用 `seekQuick`（限频）进行校正。

最近收到的快照总是优先，所以请按顺序发送。`authority()` 返回该快照，`authorityTime()`
估算时钟现在所处的位置，`following()`
则表示播放器是否正在跟随某个时钟。校正绝不会改动速度，加载或缓冲时会跳过，在重复播放的媒体上则绕循环走较短的那条路。

在跟随时钟的播放器上调用控制方法，会在下一个 tick 被撤销，所以请用你的数据包把用户的控制操作发给时钟，
再让快照把结果带回来。逐帧步进会被拒绝，而音量、静音、缩放和空间音频始终只在本地生效。

在 `syncDuration(ms)` 或 `live(true)` 告诉时钟正在播放什么之前，时钟会停在原位（`start()`
之后为零）。请由一个已经打开媒体源的客户端发送一次时长（第一个正值生效），并在 `start()` 之后用 `syncTime(ms)`
恢复已保存的位置，因为 `start()` 会从零开始。没有时长或处于直播时，无法跳转（`canSeek()` 为 false）。`release()`
会冻结位置，并让时钟停在
`STOPPED`：丢弃它之前请发送最后这份快照，否则客户端会一直停留在上一份。会话结束时，释放对应的每个播放器。

### 快照格式
`MediaPlayer.PlayerState` 是一个固定 27 字节的大端序记录（版本、修订号、状态、时间、时长、速度、标志位）。
`PlayerState.read(ByteBuffer)` 只读取这些字节，不论缓冲区的字节序如何，并把其余数据留给你的传输层；它拒绝某份快照时，
缓冲区的读取位置不会移动。请自己核实每份快照的发送者：这个库不识别任何人。

# 4. 上传与下载文件
> [!WARNING]
> 服务器使用的是明文 HTTP：面向互联网时，请把它放在 HTTPS 代理后面。使用默认令牌时，它拒绝在 `127.0.0.1`
> 以外的地址上启动：请设置你自己的令牌，并在每次更新服务器时更换它，这样陌生人就无法上传文件。

> [!NOTE]
> 可配置项：`network.server`、`serverBindAddress`、`serverPort`、`remoteUrl`、`token`、`maxServerRequests`、
> `serverTimeout`、`maxUploadSize` 和 `maxStorageSize`。请查看 `config/watermedia.toml` 中它们的注释。

可选的文件服务器会存储你的玩家上传的文件，并通过 `water://remote/<id>` 链接分享给所有人。使用方法：

1. 用 `network.server` 开启它：`DEDICATED` 让它在专用服务器上运行，`ALWAYS` 则在客户端上也运行。
2. 向外部开放它的端口：服务器不会自己去做。
3. 在服务器和每个客户端上，把 `network.remoteUrl` 指向服务器的公网地址和端口。
4. 给服务器和每个客户端设置相同的自定义 `network.token`。

上传的文件存放在服务器工作目录下的 `watermedia/files` 中；在那里删除它们即可释放空间。

## 4.1 上传
```java
NetworkAPI.UploadStatus status = NetworkAPI.upload(new File("clip.mp4"));
// LATER, FROM A TICK OR A UI TIMER
if (status.completed()) {
    URI link = status.uri(); // water://remote/<id>, READY FOR PlayerAPI.mrl
} else if (status.failed()) {
    String reason = status.error();
}
```

`NetworkAPI.upload` 在后台上传，并为每个文件返回一个 `UploadStatus`：`percentage()` 和 `displaySpeed()`
跟踪传输进度，`cancel()` 停止传输，`error()` 说明失败的原因。

## 4.2 下载
> [!NOTE]
> 可配置项：`network.timeout`、`maxRedirects` 和 `maxTextBytes`。请查看 `config/watermedia.toml` 中它们的注释。

像播放其他任何链接一样播放远程文件：`PlayerAPI.mrl("water://remote/<id>")`。对于普通的 HTTP 工作，`NetRequest`（位于
`org.watermedia.api.util`）会以与播放相同的安全规则发送一个请求：

```java
try (NetRequest request = new NetRequest("https://example.com/pack.zip").httpsOnly().maxBytes(64L * 1024 * 1024)) {
    request.send();
    request.download(Path.of("pack.zip"), expectedSha256); // THE DIGEST IS MANDATORY
}
```

- `download(path, sha256)` 会写入一个新文件并校验它，任何环节失败时都会删除该文件。
- 每个请求只发送一次：在同一个线程上配置它、调用 `send()` 并读取响应。
- 遇到错误状态时，`text()` 和 `inputStream()` 会返回响应体而不是抛出异常，`json(type)` 在响应体是 JSON
  时会绑定它（其他类型则返回 null）；请检查 `statusCode()`。

# 5. 编解码器
> [!WARNING]
> 实验性功能：`decode`、`reader`、`sniff` 和 `encoder`
> 可能在任何更新中发生变化，所以暂时不要在你的模组中使用它们（见[公开包与内部包](#12-公开包与内部包)）。

> [!NOTE]
> 可配置项：`codecs.maxImageSourceSize`、`codecs.png.failOnCorruptedData`、`codecs.png.useBKGDChunk`、
> `codecs.svg.maxSize` 和 `codecs.vp8.brokenTokens`。请查看 `config/watermedia.toml` 中它们的注释。

`CodecsAPI` 提供解码器（`ImageReader`）和编码器（`Encoder`）。图片使用我们的纯 Java 编解码器，既不需要启动，也不需要
_FFmpeg_；视频则交给 _FFmpeg_。

## 5.1 解码器
### 静态图片
PNG、JPEG、WebP、SVG 和 Netpbm。

```java
ImageData image = CodecsAPI.decode(bytes);                   // A byte[], A ByteBuffer OR AN InputStream
ByteBuffer pixels = image.frames()[0];                       // TIGHTLY PACKED; JPEG AND WEBP COME ROTATED TO THEIR EXIF ORIENTATION
PixelFormat format = image.format();                         // BGRA (GRAY FOR GRAYSCALE JPEGS)
int width = image.width();
int height = image.height();

ImageData rgba = CodecsAPI.decode(bytes, PixelFormat.RGBA);  // ASK FOR BGRA, RGBA OR GRAY AND IT CONVERTS
```

### 动态图片
GIF、APNG 和动态 WebP。读取器会逐帧遍历，而不是把所有帧都保存在内存中。

```java
try (ImageReader reader = CodecsAPI.reader(buffer)) {        // DON'T CHANGE buffer UNTIL THE READER CLOSES
    int frames = reader.frameCount();
    boolean forever = reader.repeat() == ImageData.REPEAT_FOREVER;
    reader.addFilter(new DownscaleFilter(0.5f));             // OPTIONAL: SHRINKS EVERY FRAME, NEVER ENLARGES

    while (reader.hasNext()) {
        ByteBuffer frame = reader.next();                    // REUSED ON THE NEXT CALL: COPY IT TO KEEP IT
        long delayMs = reader.delay();                       // HOW LONG THIS FRAME STAYS ON SCREEN
    }
    boolean rewound = reader.reset();                        // BACK TO THE FIRST FRAME; false WHEN IT CAN'T
    ImageData all = reader.readAll();                        // OR EVERY FRAME LEFT AT ONCE, AS COPIES
}
```

### 任意图片
```java
MediaType type = CodecsAPI.sniff(buffer);                    // IMAGE, VIDEO, AUDIO... FROM THE FIRST BYTES
ImageMetadata info = reader.metadata();                      // ON AN OPEN READER: title(), authors()... AND value(CodecsAPI.METAKEY_EXIF)

try {
    ImageData image = CodecsAPI.decode(bytes);
} catch (UnsupportedFormatException unsupported) {
    // A VALID FILE USING A FEATURE THE DECODER LACKS: FALL BACK TO ANOTHER DECODER
} catch (CodecException broken) {
    // A BROKEN OR OVERSIZED FILE
}
```

`sniff` 能识别为图片的某些格式（BMP、TIFF、AVIF……）还没有解码器。对于你自己的引擎，`new BcReader(buffer)` 可以读取 DDS
纹理中压缩的 BC1、BC3 和 BC7 块；内置引擎暂时还不能绘制它们。

### 视频
视频和音频在播放器内部解码：用 `PlayerAPI.player` 创建一个播放器（见[播放媒体](#3-播放媒体)）。

## 5.2 编码器
```java
Set<VideoCodec> videoCodecs = Container.MP4.video();         // WHAT EACH CONTAINER ACCEPTS; A TRACK THAT
Set<AudioCodec> audioCodecs = Container.MP4.audio();         // DOESN'T FIT IS REFUSED BEFORE THE FILE IS TOUCHED
```

每个编码器每次调用接收一帧，格式为 `Encoder.Video`
中指定的格式，时间戳以微秒为单位且持续递增；每一帧会一直持续到下一帧。请从单个线程向每个编码器输送数据。

### 静态与动态图片
PNG（收到多于一帧时写成 APNG）和 GIF，无需 _FFmpeg_。它们接受 RGBA、BGRA、RGB 或 GRAY 帧。

```java
// STATIC: ONE FRAME AND NO AUDIO TRACK
Encoder.Video png = new Encoder.Video(VideoCodec.PNG, 512, 512, PixelFormat.RGBA, 1);
try (Encoder encoder = CodecsAPI.encoder(Container.PNG, Path.of("logo.png"), png, null)) {
    encoder.video(pixels, 0);
}

// ANIMATED: MORE FRAMES; THE FULL CONSTRUCTOR ADDS QUALITY (0 TO 100, 50 BY DEFAULT) AND PLAYS (0 LOOPS FOREVER)
Encoder.Video gif = new Encoder.Video(VideoCodec.GIF, 320, 240, PixelFormat.BGRA, 30, 80, 0);
try (Encoder encoder = CodecsAPI.encoder(Container.GIF, Path.of("clip.gif"), gif, null)) {
    for (int i = 0; i < frames.length; i++) encoder.video(frames[i], i * 33_333L);
}
```

### 视频文件
通过 _FFmpeg_ 支持 MP4、MOV、MKV 和 WebM，另外还有仅限音频的 Ogg。它们接受任何原始 `PixelFormat`。

```java
Encoder.Video video = new Encoder.Video(VideoCodec.H264, 1280, 720, PixelFormat.BGRA, 60); // THE FULL CONSTRUCTOR SETS QUALITY, LIKE ON IMAGES
Encoder.Audio audio = new Encoder.Audio(AudioCodec.AAC, 48_000, 2, SampleType.S16);
try (Encoder encoder = CodecsAPI.encoder(Container.MP4, Path.of("clip.mp4"), video, audio)) { // IOException WITHOUT FFMPEG
    encoder.video(frame, ptsUs);                             // EXACTLY video.frameBytes() BYTES
    encoder.audio(pcm);                                      // INTERLEAVED SAMPLES FROM TIME ZERO: PAD A LATE START WITH SILENCE
}                                                            // close() FINISHES THE FILE, WRITTEN AS <file>.part UNTIL THEN
```
