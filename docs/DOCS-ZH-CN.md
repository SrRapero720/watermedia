# 🦆 简介
WATERMeDIA 是一个提供多媒体 API 的库，可以播放、解码、编码和处理视频、音频与图片。如果你用过 ExoPlayer（Android 上）或
VLCJ 这类库，使用它会相当简单；不过这里的一切都更专注、更自动化，开箱即用，所以你永远不用操心缓冲区数量、每帧的平面数、
字节序或数据步长这类非常高深的技术细节。

它会解析媒体链接（YouTube、Twitch、直链文件等等），完成解码，再把画面帧交给你的图形引擎（OpenGL、Vulkan、AWT、JavaFX），
把声音交给你的音频引擎（OpenAL、Java Sound）。图片和动画由我们自己的纯 Java 解码器处理；视频和音频则交给 _FFmpeg_，
它打包在配套模组 _WATERMeDIA: Binaries_ 中。需要 Java 17 或更高版本。

本指南中的每个示例都是集成代码片段：`renderThread`、`renderExecutor` 或 `mrl` 这类变量来自你自己的代码。

# 🪁 版本规则
> [!IMPORTANT]
> 本规则不适用于实验性 API 或内部代码

很多开发者用的是基础版本规则，而且大多数人会在 0.x.y
上待很久，把重写也算作补丁；在这里，规则就像水一样清澈见底。这套规则能帮你看懂升级每个数字意味着什么

**版本号递增规则**
``X.Y.Z-<channel>.W``
- ``X``：完全重写，你对这个库了解的一切都被推翻，并从零重新构建
- ``Y``：破坏性变更，即可能或一定会破坏你现有实现的重构、重命名和 API 移除
- ``Z``：热修复、改进、新 API 和弃用注解，不含任何破坏性变更。
- ``<channel>``：ALPHA 或 BETA 渠道，正式版不使用这个版本后缀
  - 注意：在一些最糟糕的情况下，这个后缀可能会被故意省略，因为模组加载器处理不好它们，常常会弄坏版本范围。
- ``W``：ALPHA 或 BETA 的发布序号，在正式版渠道上省略

这是一项**保证：``Z`` 升级绝不会提交任何破坏性变更**，所以你可以放心地按 ``[3.0.1,3.1)`` 的范围依赖 watermedia

# 🗺 索引

选择与你要构建的东西相符的路线；每条路线都列出了它需要的章节。

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

在 Minecraft 环境中，这里的内容除了遵守 jar 的版本范围之外都不是必需的：把 WATERMeDIA 添加为编译期依赖，
按照[软件限制](#11-软件限制)中列出的范围把它声明为你模组的必需依赖，运行时由加载器提供它。客户端还需要安装 _WATERMeDIA:
Binaries_（见 [Binaries 与 FFmpeg](#01-binaries-与-ffmpeg)）。

```gradle
repositories {
    maven { url "https://jitpack.io" }
}

dependencies {
    compileOnly "com.github.SrRapero720:watermedia:$watermedia_version" // A RELEASE TAG, SUCH AS 3.0.0
}
```

在自定义 Java 应用或 Java 游戏中，运行环境必须提供：

```gradle
repositories {
    mavenCentral()
    maven { url "https://jitpack.io" }
}

dependencies {
    implementation "com.github.SrRapero720:watermedia:$watermedia_version"
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
- 独立启动器会把这个 JAR 解压到临时文件，加入重新启动的 JVM
  的类路径，并按需下载它列出的其他运行时库；缓存或下载的库只有在与构建时固定的 SHA-256 一致时才会加入类路径。
- 嵌入 WATERMeDIA 的应用自带它自己的类路径。开发时的 Gradle 依赖并不能证明某个 Minecraft 安装会提供其他库。
- Binaries JAR 内嵌并重定位了它的 _XZ_ 依赖，WATERMeDIA JAR 则自带 _JavaCPP_ 和 _FFmpeg_ 绑定。

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

_WATERMeDIA: Binaries_ 依赖 WATERMeDIA 并随它一起加载，在配置之后、网络、编解码器和播放器模块之前准备好原生库。

- Minecraft 客户端必须安装它；专用服务器可以不装，因为它在那里什么都不提供。
- 嵌入 Java API 的应用也可以不装，仍能保留图片解码和不依赖原生库的平台解析器；但会失去视频解码以及由 _yt-dlp_
  支持的平台。
- 禁用 _FFmpeg_（`codecs.ffmpeg.disable`）会跳过解压。
- 原生库路径在整个进程生命周期内保持发布状态，Binaries 也没有单独的初始化或关闭 API。
- _yt-dlp_ 和 _BotGuard_ 会在首次使用时下载，并在运行前完成校验。系统属性 `watermedia.ytdlp.binary` 和
  `watermedia.botguard.binary` 可以改为指向你自己的可执行文件。

自定义的 _FFmpeg_ 构建放在 `codecs.ffmpeg.customPath` 中：这是一个存放原生库的文件夹，而不是 `ffmpeg.exe` 程序。
它们可以使用 GPL、LGPL 或任何其他已声明的许可证和版本：加载器检查的是所需的原生能力，而不是版本或许可证白名单，
并且只对内置的原生库校验完整性。它们仍然需要与 WATERMeDIA 所用 Java API 兼容的 JNI 绑定，以及与内置构建相同的 TLS
支持（见 [HTTPS 信任与凭据](#25-https-信任与凭据)）。

## 0.2 配置

> [!NOTE]
> 每个字段都可以配置，并且自带说明：它在 `config/watermedia.toml`
> 中的注释就是它工作方式的文档，取什么值由你决定。本指南的每个章节都会列出影响该章节的字段。

WATERMeDIA 会把设置写入工作目录下的 `config/watermedia.toml`，每个字段上方都有一条注释。默认值开箱即用，
启动时读取的字段会在下次启动时生效；当某个后端看起来不可用时，首先应该检查的就是这个文件。

- `codecs.*`：_FFmpeg_、硬件解码和图片解码器。
- `player.*`：默认画质和缓存。
- `platforms.*`：成人内容、搜索和平台账号。
- `network.*`：文件服务器和网络请求。

# 1. 启动

在 Forge、NeoForge 和 Fabric 这类原生支持的主流模组加载器上，加载器会替你启动 WATERMeDIA：你不需要按本节操作。

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

因此有 Binaries 时 `steps()` 为 5，没有时为 4；`loading()`、`step()` 和 `steps()`
可以在任何线程上安全轮询，用于加载界面。

- `start` 会在进程生命周期内把每个模块加载一次。任何东西都不会卸载：没有停止操作，第二次调用 `start`
  会抛出异常，失败的启动也无法在同一进程中重试。
- 临时目录传 null 时会变成 `<java.io.tmpdir>/watermedia`，工作目录传 null
  时则使用当前目录。最后一个参数选择客户端环境；仅限客户端的模块（Binaries、编解码器、播放器）在专用服务器上会跳过启动。
- 可选部分（文件服务器、_FFmpeg_）失败时会记录日志并保持关闭，加载过程照常完成，所以请检查你需要的能力，例如
  `CodecsAPI.ffmpegLoaded()`。
- 其他任何失败（配置、已安装的 Binaries 模块）都会让 `start` 抛出
  `IllegalStateException`，并以该失败作为异常原因，`loaded()` 保持为 false。
- `start` 拒绝在 WATERMeDIA 的工作线程上运行，所以绝不要在它的回调中调用。

每个 API 自己知道它的模块是否已加载：

- 在播放器模块启动之前，`PlayerAPI.mrl` 和 `PlayerAPI.player` 返回的播放器都会抛出
  `IllegalStateException`，在专用服务器上则始终如此。
- 在网络模块启动之前，`NetworkAPI.upload`
  会抛出异常。它还会立即拒绝任何不是现有文件的输入；而远程服务器无法访问或配置错误时，则是返回的状态变为失败。
- `PlatformAPI` 无需启动：它的注册表是静态的，`register`、`fetch` 和 `search` 随时可用。需要 Binaries
  的处理器（YouTube、_yt-dlp_）在它加载之前会以它们自己的 `PlatformException` 失败，在专用服务器上则始终失败；没有
  Binaries JAR 时它们根本不存在，所以 `fetch` 对它们的链接返回 null。
- 通过 `CodecsAPI` 进行的纯 Java 图片解码、`ServerMediaPlayer`、`MathUtil` 和 `MathEases` 完全不需要启动。

播放器持有由你传给 `PlayerAPI.player` 的 supplier 所创建的引擎。请通过 supplier 延迟创建引擎，
这样不可用的媒体源就不会泄漏预先分配的原生资源。你的应用持有它自己的 OpenGL、Vulkan 和 OpenAL 上下文：
在销毁这些上下文之前，先在正确的宿主上下文上释放每个播放器；设备或上下文重新加载之后，要创建新的引擎。
原生库永远不会卸载，也无法在运行中的 JVM 内切换版本。

## 1.1 软件限制

### 被屏蔽的启动器

当游戏由 _TLauncher_ 启动时，Minecraft 引导程序会拒绝启动。它们通过它注入的皮肤模组、类、类路径上的文件、JVM
选项、线程和父进程来识别它，并在失败时指明证据；游戏目录或用户目录中旧安装的残留只会记录一条警告。嵌入 WATERMeDIA
的应用会跳过这项检查。

### 兼容版本

Forge、NeoForge 和 Fabric 会在客户端和专用服务器上加载 WATERMeDIA，并拒绝此列表之外的 Minecraft 版本：

| Minecraft | Forge | NeoForge | Fabric Loader |
| --- | --- | --- | --- |
| 1.18.2 | 40.3.10 或更新的 40.x | ⛔ | 0.14 或更新的 0.x |
| 1.19.2 | 43.5.0 或更新的 43.x | ⛔ | 0.14 或更新的 0.x |
| 1.20.1 | 47.4.2 或更新的 47.x | ⛔ | 0.14 或更新的 0.x |
| 1.21.1 | 52.1.1 或更新的 52.x | 21.1.0 或更新的 21.1.x | 0.14 或更新的 0.x |
| 1.21.11 | 61.0.5 或更新的 61.x | 21.11.0 或更新的 21.11.x | 0.14 或更新的 0.x |
| 26.1.2 | 64.x | 26.1.2.x | 0.14 或更新的 0.x |
| 26.2 | 65.x | 26.2.0.x | 0.14 或更新的 0.x |

- 在 Minecraft 上，接受 Java 17 到 25。
- WATERMeDIA 与 Binaries 互相接受的版本范围是 3.0.0 到 3.1.0（不含 3.1.0）；更早的 3.0.0 BETA 构建会被拒绝。
- Fabric 无法把依赖限定为仅客户端，所以它的引导程序会在客户端上要求 Binaries，它的清单文件也会拒绝不兼容的 Binaries
  版本。

## 1.2 公开包与内部包

公开 API 由以下包组成：`org.watermedia`、`api.codecs`、`api.network`、`api.platform`、`api.player` 和 `api.util`，
不含它们的子包。其余一切都是内部的：编解码器的读取器、编码器和数据块结构，播放器的队列和缓存，内置平台，引导程序，
`org.watermedia.tools` 以及内置的 _FFmpeg_ 绑定。Binaries 也使用 `org.watermedia.tools` 中的摘要、文件和 JSON 工具。

这个 JAR 没有声明 Java 模块，所以没有任何加载器会屏蔽内部包；尽管如此，请把这条边界当作约定，使用
`PlayerAPI.player`、`PlatformAPI.fetch` 这类公开入口，而不是它们背后的内部类。贡献者可以在项目根目录运行
`gradle javadocInventory`，列出缺少文档的公开方法及其文件名和行号；它只做报告，并且只检查 `gradle.properties` 的
`public_packages` 中列出的公开包。

> [!WARNING]
> `CodecsAPI` 是实验性的：`decode`、`reader`、`sniff` 和 `encoder`，以及它们返回的 `ImageReader`、`Encoder` 和滤镜，
> 可能在任何更新中变化或失效，且不遵循版本规则，所以暂时不要在你的模组中使用它们。媒体播放器已经替你使用了这些编解码器，
> 而状态检查方法（`ffmpegLoaded()`、`ffmpegError()`、`vulkanDecode()`）可以放心调用。

## 1.3 诊断与示例

项目的日志记录器是 `watermedia` 和 `watermedia_binaries`；诊断时把这两个名称设为 DEBUG 级别。

- 项目日志会完整显示媒体链接，让路径和 ID 在调试时依然有用。日志会遮蔽链接中的密码（`user:password@`）、裸露的 JWT
  令牌、`Authorization` 和 `Cookie` 的值、Minecraft 会话参数以及看起来像机密的 `key=value`
  键值对（应用的日志上传也经过同一个过滤器）。携带机密的异常堆栈会以脱敏文本的形式保留它的类型、调用栈、
  原因和被抑制的异常。
- 不会按时间窗口聚合任何日志：可能重复出现的异常情况（帧缓冲区过短、布局被拒绝、回调失败、上传被拒绝）在每个引擎、
  播放器、服务器或缓存中只在第一次出现时记录，之后保持安静；过慢的播放迭代会被计数，并在运行结束时汇报一次。
- _FFmpeg_ 仍会把它自己的原生日志行写到进程的错误输出，不经过项目日志记录器及其脱敏处理；
  通过项目日志记录器转发这些日志的功能已经就绪，但在 macOS 和 Linux 验证完成之前保持禁用。
- `-Dwatermedia.glchecks=true` 会在引擎的上传路径中开启 OpenGL 错误检查。

`src/test/java/org/watermedia/ApiGuideExample.java`
编译了每个图形和音频引擎的工厂用法，以及同步播放的两端：时钟和跟随者。其中的图片解码示例会用 `DownscaleFilter`
过滤每一帧，它在 `ApiGuideExampleTest` 中运行；GUI、音频设备和 Minecraft
相关的行为需要对应的宿主集成。常规的播放和空间音频测试套件会分别覆盖真实的 _FFmpeg_ 和 OpenAL 行为。使用
`-Prequire_natives=true` 启动的测试（CI 的原生任务）在缺少 _FFmpeg_ 原生库时会失败，而不是跳过。

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

当服务器返回错误状态、网页或脚本时，直链会解析失败；HLS 播放列表会作为一个视频打开，IPTV
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

新的查询会替换正在进行的查询；如果某个查询看起来像是上一个查询还在输入中，它会稍等片刻，等待下一次按键。Twitch 和 Kick
只列出当前正在直播的频道。

## 2.4 WATER 协议

```java
PlayerAPI.mrl("water://local/config/song.mp3");              // ./config/song.mp3, RESOLVED AGAINST THE WORKING DIRECTORY
PlayerAPI.mrl("water://remote/xyz12345");                    // A FILE ON YOUR FILE SERVER (SEE UPLOADING AND DOWNLOADING FILES)
PlayerAPI.mrl("water://global/abcd6789");                    // A FILE ON OUR GLOBAL SERVER
```

远程（remote）和全局（global）链接携带的是服务器分配的文件 ID，而不是文件夹路径。

## 2.5 HTTPS 信任与凭据

对于 Java 已经信任的任何证书，HTTPS 都开箱即用。播放时会用 WATERMeDIA 启动时读取的 JVM
信任库校验每一张证书，而且这项校验永远不会关闭。

要使用自签名证书或你自己的证书颁发机构，请把它导入一个信任库，并在 WATERMeDIA 启动之前让 Java 指向它（在 Minecraft
中，把同样的 `-D` 选项加到启动器的 JVM 参数里）：

```shell
keytool -importcert -alias my-server -file my-server.crt -keystore my-truststore.jks -storepass changeit -noprompt
java -Djavax.net.ssl.trustStore=my-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit -jar my-app.jar
```

- 空的或损坏的信任库会导致 _FFmpeg_ 无法启动，所以在修复之前视频都不可用。
- 自定义的 _FFmpeg_ 构建需要与内置构建相同的 TLS 支持。
- 登录用的请求头和 Cookie（`Authorization`、`Cookie`、`X-WaterMedia-Token`）只会发送给第一个请求的服务器，
  绝不会发给重定向到的其他服务器；而且重定向只能指向 http 或 https 链接。

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

当媒体源仍在获取中、索引无效、所需后端不可用或构造时抛出 `Exception` 时，`player` 返回 null；`Error`
则会继续向上抛出。决定是否重试之前，先检查 MRL 的状态。

- supplier 只在需要时运行，图片永远不会调用音频 supplier。
- supplier 返回 null 时会省略对应的输出，例如在纯音频播放时用 `() -> null` 作为图形引擎。
- 绝不要在多个播放器之间共享引擎。

`TxMediaPlayer` 播放支持的图片和动画，`FFMediaPlayer` 通过 _FFmpeg_ 播放音频和视频，`ServerMediaPlayer`
则是一个同步时钟，不做解码，也没有原生引擎。

这三种播放器共用同一套控制方法。播放控制方法在调用生效时返回 `true`，被拒绝时返回 `false`：当前无法执行、已经调用过
`release()`，或者速度超出范围；输出设置方法则没有返回值。

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

请在音频上下文为当前上下文的地方调用 `speed(...)`：播放器时钟只会跟随音频引擎接受了的速率。
缩放和细节层级（LOD）适用于受支持的像素布局，内置图形引擎暂时还不接受压缩的 BC 纹理。

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

- 监听器运行在驱动这次变化的那个线程上：播放线程、共享的同步计时器（ticker），或者在时钟延迟判定结束的图片播放器上，调用
  `status()`、`time()` 或 `texture()` 的线程。
- 播放器持有它的任何一把锁时，监听器绝不会运行，也绝不会在 `release()` 正在等待的播放线程上运行。`release()`
  会在完成且引擎释放之后才发出它的 `STOPPED`，所以该回调运行时，播放器已经没有任何待处理的事情。

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

`Status.allows(next)` 用来判断暂停、恢复、缓冲、结束、停止或失败能否把某个状态直接转到
`next`。启动、跳转和重新启动走的是各自的路径：播放器只有在管线打开时（或者停止、失败时）才会离开 `LOADING`，在 `ENDED`
或 `STOPPED` 上跳转会重新启动它，而 `STOPPED` 不允许任何直接转换。

## 3.2 音轨与语言

配音视频以及带有多个音频流的文件会播放原始（或默认）音频，除非你另有要求。平台会列出媒体源的独立音轨，
原始音轨排在第一位；而在文件或 HLS 流内部，标记为默认的流优先。

```java
player.audioLanguage("es-MX"); // PREFERRED FROM THE NEXT START; null CLEARS IT
for (final MediaPlayer.AudioTrack track: player.audioTracks()) {
    if ("es".equals(track.lang())) player.audioTrack(track); // SWITCHES RIGHT AWAY ON A RUNNING PLAYER
}
```

- 选择顺序是：你用 `audioTrack(...)` 选的音轨，然后是 `audioLanguage(...)`，然后是原始音轨（第一个独立音轨），
  没有独立音轨时则是文件的默认流，最后是第一个流。语言标签先匹配相同的标签，再匹配相同的语言，所以 `es-MX` 会接受
  `es-419`、`es` 或文件携带的 `spa`。不存在自动选择语言：没有 `audioLanguage(...)` 时播放的是原始音频，
  绝不会自动选用配音。
- `audioTracks()` 为每个音频流列出一个 `AudioTrack(track, stream, name, lang)`：先是主输入的音频流，然后是
  `source().audioTracks()` 中的每个独立音轨。`track` 是 `AudioTrack.MAIN` 或独立音轨的索引，`stream` 是其中的 _FFmpeg_
  流索引。
- 尚未播放过的独立音轨会显示为一个 `provisional()` 条目（`stream` 为 -1），由平台提供的名称和语言构成，
  所以列出音轨时不会预先打开每一条配音。一旦播放过，它的真实音频流就会取而代之。这个列表是一个不可变快照，
  任何线程都可以读取。
- 选择发生变化时，`audioTrack(track)` 返回 `true`。
  正在运行的播放器会在当前时间重建它的管线（直播流会从直播的最新位置重新开始），
  而且这个选择在切换画质和重新启动后依然保留。`audioTrack()` 返回当前正在播放的音轨。图片播放器、
  服务器播放器以及没有音频引擎的播放器不会列出任何内容。

## 3.3 选择引擎

Vulkan 的开发优先；OpenGL 作为次要后端继续受支持。后端的选择应当放在你的渲染层中决定。

| 工厂方法 | 宿主的责任 |
| --- | --- |
| `PlayerAPI.glEngine(renderThread, executor)` | 执行器在持有 OpenGL 上下文的线程上运行任务；只要播放或释放可能在等待它，就要每帧持续驱动它，`release()` 之后也要再驱动一次。两个参数都为 null 时，调用线程必须持有该上下文。绝不要把引擎移到另一个上下文。 |
| `PlayerAPI.vkEngine(context)` | 提供一个 `VKContext`，它的 Vulkan 对象和回收机制的生命周期要比引擎更长：你自己的提交和呈现要用 `queueLock()` 包住，并在你正在处理中的帧完成后运行 `retire(...)` 回调（设备销毁时要全部运行）。要在 GPU 上解码视频，请参见 [Vulkan 解码](#vulkan-解码)。 |
| `PlayerAPI.awtEngine(onFrame)` | 在 AWT/Swing 界面中绘制发布出来的图像；按需把可选回调切换到 UI 线程上执行。 |
| `PlayerAPI.jfxEngine(onFrame)` | 需要 JavaFX 13+ 运行时；把引擎的图像绑定到你的界面上。没有该运行时，工厂方法会抛出 `IllegalStateException`，而不是链接错误。 |
| `PlayerAPI.headlessEngine()` / `headlessEngine(preload)` | 无需图形上下文即可捕获上传的帧，适合测试和图像处理。和所有引擎及媒体播放器一样，它只存在于客户端；专用服务器只能创建 `ServerMediaPlayer` 时钟。 |
| `PlayerAPI.alEngine()` | OpenAL 上下文已经是当前上下文，并在执行音频工作的线程上保持有效。 |
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

引擎基类是密封的：你的集成提供的是宿主上下文和执行器，而不是自定义的引擎实现。引擎自行管理它们的播放资源，包括纹理、
音源和缓冲区。

每个引擎都遵循同一套生命周期：

- `release()` 是幂等的，绝不能与向该引擎输送数据的播放器同时运行，调用后引擎就不再工作：`released()` 为
  true，`texture()` 返回 0。
- 播放器在上传前会检查 `supports(format, bits)`，引擎不接受某个布局时会回退到 8 位 BGRA。
- 上传永远不会抛出异常：驱动出错时会丢弃该帧并记录一条错误日志，屏幕上保留最后一帧。
- 软件引擎和无头引擎暴露的是一个非零的哨兵值，而不是 GPU 句柄，所以只需把句柄和 0 比较。
- `PixelFormat.planeWidth`、`planeHeight`、`texelBytes` 和 `frameBytes` 根据引擎的 `format()`、`width()`、`height()` 和
  `bits()` 计算每个平面的尺寸，例如用来规划它的内存预算。子采样平面像 _FFmpeg_
  一样向上取整，所以奇数尺寸会保留最后一列和最后一行色度。

色彩处理：

- OpenGL 和 Vulkan 引擎会按照每个视频声明的矩阵（BT.601、BT.709 或 BT.2020）、范围和色度位置来转换
  YUV。视频什么都没声明时，它们会像 _mpv_ 一样推测：SD 帧（宽度小于 1280 像素且高度不超过 576 行）用 BT.601，其他情况用
  BT.709。
- PQ 和 HLG HDR 会以 SDR 显示，BT.2020 色彩映射到 BT.709，高光经过压缩，使 1000 尼特的峰值亮度刚好达到白色。
- 同一个 OpenGL 上下文或 Vulkan 设备上的引擎只构建一次这套转换并共享它。它会随最后一个引擎的释放工作一起消失（即
  `release()` 在 OpenGL 渲染执行器上排队的任务，或 `VKContext.retire`
  回调），所以在销毁上下文或设备之前，请释放每个引擎并让这些工作执行完。
- 软件引擎拿到的是由 _FFmpeg_ 按声明的矩阵和范围转换出的 BGRA，不做 HDR 色调映射。

OpenAL 要求每个驱动播放器的线程（包括同步计时器）都把它原来的上下文设为当前上下文。Java Sound 引擎会自行串行化它的调用，
所以不需要额外加锁；当音频块超出线路缓冲区时，它会重新打开一条更宽的线路，并丢弃旧线路中还没播放的内容。

> [!IMPORTANT]
> **OpenAL**：在普通引擎上，4、6、7 和 8 声道音频需要
> `AL_EXT_MCFORMATS`（空间引擎会把一切都折叠为立体声）；单声道和立体声的 float 与 double 采样需要 `AL_EXT_FLOAT32` 和
> `AL_EXT_DOUBLE`（环绕声的 float 随 `AL_EXT_MCFORMATS` 提供）；而要让时钟计入设备的输出延迟，则需要
> `AL_SOFT_source_latency`。缺少这些扩展时，音频会转换为设备能播放的格式。使用 EFX 的[环境处理器](#环境处理器)需要
> `ALC_EXT_EFX`。

独立应用（直接打开 WATERMeDIA JAR）默认使用 Vulkan 渲染，除非用户选择了 OpenGL；Vulkan 不可用时，它会在这次启动中改用
OpenGL，并保留已保存的选择。`-Dwatermedia.engine=opengl` 或 `-Dwatermedia.engine=vulkan`
可以只为一次启动覆盖引擎，而不保存它。应用会把它的引擎、界面缩放（`0` 表示跟随显示器，否则为 1 到 3 之间、步长 0.25
的倍数，在设置界面中显示为 `Auto`、`x1.0`、`x1.25` 等）、CRT 效果和点击音效保存在它工作目录下的 `config/app.properties`
中。

### Vulkan 解码

默认情况下 `VKContext.deviceInfo()` 返回
null，视频会在别处解码，然后从内存上传。重写它，告诉它你的设备是如何创建的（传给 `vkCreateDevice`
的特性链，在设备存活期间保持已分配且不变，再加上已启用的实例扩展和设备扩展，以及每个队列族创建的队列），_FFmpeg_ 就会用
Vulkan Video 解码 H.264、H.265 和 AV1，而引擎会直接采样解码后的图像，无需经过内存中转。

> [!IMPORTANT]
> Vulkan 解码需要：
> - 启用了 `timelineSemaphore` 和 `synchronization2` 的 Vulkan 1.3 实例和设备。
> - `VK_KHR_video_queue`、`VK_KHR_video_decode_queue`，以及每种需要加速的编码格式对应的解码扩展：
>   `VK_KHR_video_decode_h264`、`VK_KHR_video_decode_h265` 或 `VK_KHR_video_decode_av1`。
> - 你的设备上有空闲队列，或者具备用于孪生设备的外部内存和信号量扩展，两者都在下文说明。
> - 支持 Vulkan Video 的原生库（`CodecsAPI.vulkanDecode()` 返回 true），并开启 `codecs.ffmpeg.hardwareAccel`。

此时 _FFmpeg_ 会在以下两处之一解码：

- **你的设备**：当它还有 _FFmpeg_ 可以独占的队列时，也就是除 `queue()`
  之外的一个支持计算的队列（它也承担传输工作），外加一个视频解码队列，并且 `queue()` 是 `queueFamily()`
  中最后创建的队列。所有队列族中的其他所有队列都归 _FFmpeg_
  使用，它会从自己的线程向这些队列提交，所以只要还有播放器存在，你就绝不能碰这些队列，也不能调用
  `vkDeviceWaitIdle`；请改为等待你自己的队列（在 `queueLock()` 内调用 `vkQueueWaitIdle`）。
- **孪生设备**：当你的设备没有这样的队列，但在 Windows 上启用了 `VK_KHR_external_memory_win32` 和
  `VK_KHR_external_semaphore_win32`，或在其他系统上启用了 `VK_KHR_external_memory_fd` 和 `VK_KHR_external_semaphore_fd`
  时。引擎会用你的特性链和扩展在同一块 GPU 上创建第二个设备，_FFmpeg_
  在那里解码，引擎再把每张解码后的图像导入你的设备。你的队列仍然完全归你所有。

无论哪种方式，都要在销毁设备或它的实例之前释放每个播放器：在最后一个解码器消失之前，_FFmpeg_
一直在使用它们，孪生设备也不例外。

> [!IMPORTANT]
> 4:2:2、4:4:4 和 12 位视频只能通过 _libplacebo_ 在 GPU 上解码，它会把每种解码后的布局都转换为 SDR RGBA（HDR
> 会做色调映射）。它要求解码设备启用
> `hostQueryReset`、`runtimeDescriptorArray`、`shaderStorageImageArrayNonUniformIndexing` 和
> `maintenance4`，在你自己的设备上解码时还需要 `queueFamily()` 中有第二个队列；当 GPU
> 支持这些特性时，孪生设备会自行启用它们。没有 _libplacebo_ 时，只有 4:2:0 的流（8 位和 10 位）能在 GPU
> 上解码，引擎会用自己的着色器转换它们。

## 3.4 空间音频

在构建播放器之前，用 `PlayerAPI.alEngine(true)` 创建一个 OpenAL 引擎。空间模式在引擎的整个生命周期内固定不变：_FFmpeg_
会把立体声和环绕声音源折叠为立体声（中置声道会同时进入左右两侧，单声道音源保持单声道），
引擎再通过输出端（sink）播放它们，也就是带位置的单声道音源。普通的 `alEngine()` 工厂方法会在一个干声、
相对听者的音源上播放所有声道，不过设备无法播放的布局仍可能被转换为最接近的声道数。Java Sound 在空间更新时返回 `false`，
也不暴露原生音源。

引擎一开始只有一个输出端，也就是主输出端，播放干声、相对听者的 `Mix.MONO` 音频。传入一个 `SpatialAudio`
状态可以把它放到世界中，传入 `null`
则回到干声、相对听者的播放。音量、静音、速度、暂停和时钟跟随（`sync`）不依赖空间设置，并且作用于每个输出端。

```java
import org.watermedia.api.player.SFXEngine.SpatialAudio;

final ALEngine audio = PlayerAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

请在宿主的声音执行器上创建、更新和释放引擎，并确保原来的 OpenAL
上下文是当前上下文。设备、上下文和听者归宿主所有，它们的生命周期必须比使用它们的每个播放器都长。

- `FFMediaPlayer` 使用私有的播放线程，并且和普通的 `ALEngine` 播放一样，
  目前要求音源所属的上下文在这些线程上是进程级的当前上下文。只通过线程局部绑定暴露音频上下文的宿主不受支持：
  仅在调用方的声音线程上绑定是不够的。
- 原生引擎操作会拒绝不同的或缺失的上下文；它们绝不会切换或重建宿主的上下文。
- 在设备重新加载之前释放播放器，然后在新的上下文中创建新的引擎。
- `FFMediaPlayer.release()`
  会先中断并等待（join）它的播放管线结束，再删除音源；请在上下文销毁之前调用它，绝不要在该管线的回调中调用。

`SpatialAudio` 会校验：位置是 OpenAL 浮点数能容纳的有限值，`0 < referenceDistance < maxDistance`，
以及衰减系数（rolloff）为有限的非负数；衰减系数为零时会关闭距离衰减。这些参数如何衰减音频由宿主的距离模型决定，而 API
绝不会改动全局距离模型、听者或音源速度，所以在各种 OpenAL 模型下，`maxDistance` 并不是一个通用的静音半径。

### 输出端：立体声对与额外扬声器

一次解码就能供给空间引擎的所有输出端，所以立体声声像或放在别处的扬声器都不需要第二次下载或解码。
每个输出端播放解码音频的一种 `ALEngine.Mix`：`LEFT`、`RIGHT` 或 `MONO`（两者的平均值）。
单声道音源在每种混音上播放相同的采样，所以立体声对在单声道媒体上也绝不会变成无声。

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

- 主输出端持有 `player.audioSource()`。它驱动媒体时钟，与引擎同生共存；它的 `release()` 返回 `false`。`player.sinks()`
  会把主输出端排在第一位；没有空间 OpenAL 引擎时，`sinks()` 和 `addSink(...)` 分别返回空列表和 `null`。
- 每个输出端都排队相同的采样，播放、暂停、清空、速度和音量会作为一个批次同时作用于所有输出端，
  所以立体声对能保持采样级对齐。在播放过程中添加输出端，或在立体声媒体上更改混音，
  会以主输出端的采样位置为基准重建所有队列，这会让输出端暂停大约一次混音器更新的时间。
- 每个输出端都有自己的 `spatialAudio(...)`：环境适配器会在 `process` 和 `reset` 中拿到该输出端的音源，`sink.release()`
  会在删除音源之前先重置它。释放播放器会释放所有输出端。
- 和其他所有空间更新一样，请在宿主的声音执行器上、引擎的上下文为当前上下文时添加、更改和释放输出端。
  它们的查询方法（`source()`、`mix()`、`spatialAudio()`、`released()`）不需要上下文。
- 宿主可以把输出端的音源纳入自己的声音系统，并写入它的增益、音高和位置，但绝不能播放、排队、出队或删除它：
  它的播放控制和缓冲区归引擎所有。新的输出端以引擎当前的增益和音高开始。

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
- 每次更新在调用 `process` 之前，都会恢复原始的世界位置，并清除音源之前的滤波器和辅助发送（auxiliary
  send），所以处理器可以移动 `AL_POSITION`，并且必须在每次调用时应用它完整的环境。
- 请在宿主中限制更新频率，而不是在处理器内部提前返回；播放器很多时，把它们分散到不同的 tick 中。
- 引擎只删除它自己的音源和缓冲区，绝不会删除处理器的滤波器或效果槽。
- 如果 `process` 或 `reset` 抛出异常，音源的效果会被清除，异常会继续向上抛出；`reset`
  失败也无法阻止音源在释放过程中被删除。
- `auxOnly = true` 要求处理器只输出反射声；它本身不会让直达声路径静音，所以需要严格静音时，请将播放器静音。
- 处理器只在你显式更新时运行，绝不会在 _FFmpeg_ 的解码或播放循环中运行。基本的定位无需 EFX 也能工作；使用 EFX
  的处理器需要一个提供 EFX 的设备。

### 验证

`SpatialAudioTest` 覆盖了无效参数的拒绝、真实 OpenAL
音源的坐标和衰减、回调后原始位置的恢复、效果清除、异常处理、上下文不匹配以及释放后的迟到调用。它还会生成一个立体声
WAV，并通过 `FFMediaPlayer` 播放，检查立体声解码结果是否以单声道 PCM 的形式进入每个输出端的真实 OpenAL
缓冲区。`ALSinkTest`
覆盖了输出端集合、每种采样类型下的混音、批量控制、背压、以主输出端的采样位置加入的输出端以及欠载恢复。设备测试需要
OpenAL 输出设备，解码相关的用例还需要 _FFmpeg_ 原生库。

## 3.5 同步播放

`PlayerAPI.serverPlayer()` 会构建一个 `ServerMediaPlayer`，这是一个不含媒体也没有引擎的时钟，在客户端和服务端都能运行。
它负责时间计算：启动、暂停、跳转、速度、重复播放，以及循环回绕或转入 `ENDED`，都基于系统时钟运行，每次成功的变更都会让
`revision()` 递增。其他一切都归宿主负责：谁可以驱动时钟、它的快照如何送达客户端，以及它的位置保存在哪里。
引擎和播放器都没有公开构造函数：每个实例都来自 `PlayerAPI`。

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
- 一旦 `drift()` 超过 `tolerance(ms)`（默认一秒），就用限频的 `seekQuick` 把播放拉回来。

最近一次传入的快照总是优先，所以请按时钟生成它们的顺序发送。`authority()` 返回该快照，`authorityTime()`
推算时钟现在所处的位置，`following()` 则表示播放器是否正在跟随某个时钟。校正绝不会微调播放速度；
播放器加载或缓冲时会跳过校正，在重复播放的媒体上跨越循环边界时则走较短的那条路。

在跟随中的播放器上调用控制方法会立即生效，但会被下一个与之不一致的 tick 撤销，
所以请通过你自己的数据包把用户的控制操作发给时钟，再让快照把结果带回来。跟随期间会拒绝逐帧步进，而音量、静音、
缩放和空间音频始终只在本地生效。

在 `syncDuration(ms)` 或 `live(true)` 告诉时钟它运行在哪条时间线上之前，时钟会停在原位（`start()` 之后为零）。
请由一个已经打开媒体源的客户端汇报一次时长（在时钟的整个生命周期内，第一个正值生效，之后不同的值都会被忽略），
并在启动时钟之后用 `syncTime(ms)` 恢复已保存的位置，因为 `start()` 会从零重新开始。在时长已知之前，`canSeek()` 为
false，`seek(...)` 返回 `false`，在直播会话中则始终如此。`release()` 会冻结位置，让时钟带着一个新的修订号停在
`STOPPED`，所以在丢弃它之前请保存并发送最后这份快照，否则播放器会继续按上一份快照推算。宿主会话结束时，
释放对应的每个播放器。

### 快照格式

`MediaPlayer.PlayerState` 是一个固定 27 字节的大端序记录（版本、修订号、状态、时间、时长、速度、标志位）。
`PlayerState.read(ByteBuffer)` 只读取这些字节，把后续数据留给你的传输层；缓冲区自身的字节序无关紧要，
被拒绝的数据也不会改动缓冲区的读取位置。请在你的传输层中核实快照的发送者：这个库从不识别对端身份。

# 4. 上传与下载文件

> [!WARNING]
> 服务器使用的是明文 HTTP：面向互联网时，请把它放在你自己的 HTTPS 代理后面。使用默认令牌时，它拒绝在 `127.0.0.1`
> 以外的地址上监听；每次更新服务器时更换令牌，可以防止陌生人上传。

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

`CodecsAPI` 提供解码器（`ImageReader`）和编码器（`Encoder`）。图片无论静态还是动态，都使用我们自己的纯 Java
编解码器，既不需要启动，也不需要 _FFmpeg_；视频则交给 _FFmpeg_。

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
