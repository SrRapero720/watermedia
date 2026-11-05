# 🦆 INTRODUCTION
WATERMeDIA is a library with a multimedia API to play, decode, encode and handle video,
audio and pictures. Using it is fairly simple if you've worked with libraries like ExoPlayer
(on Android) or VLCJ, but here everything is far more focused, automated and ready to use,
so you never deal with very advanced and technical details such as buffer counts, planes per frame,
byte order or data strides.

It resolves media links (YouTube, Twitch, direct files and many more), decodes them and hands the
frames to your graphics engine (OpenGL, Vulkan, AWT, JavaFX) and the sound to your audio engine
(OpenAL, Java Sound). Pictures and animations go through our own pure-Java decoders; video and
audio go through _FFmpeg_, bundled in the companion mod _WATERMeDIA: Binaries_. It needs Java
17 or newer.

Every example in this guide is an integration fragment: variables such as `renderThread`,
`renderExecutor` or `mrl` come from your own code.

# 🪁 VERSION SCHEMA
> [!IMPORTANT]
> This schema doesn't apply to experimental APIs or internal code

A lot of devs use the base schema and most of them just stay for a long time on 0.x.y, classifying rewrites
as patches; here the rule is water clear. This schema helps identify what bumping a number does

**BUMPING SCHEMA**
``X.Y.Z-<channel>.W``
- ``X``: Full rewrite: everything you know about the library got scrapped and rebuilt from scratch
- ``Y``: Breaking changes: Refactors, renames, and API removals that might or will break your implementation
- ``Z``: Hotfix, Enhancements, new API and deprecation Annotations, nothing breaking.
- ``<channel>``: ALPHA or BETA channels, release doesn't use this version suffix
  - Note: on some worst case scenarios, this suffix might be omitted on purpose, modloaders don't handle those
    correctly and tend to break version ranges.
- ``W``: ALPHA or BETA version drop, omitted when on the release channel

This is a WARRANTY that NO BREAKING CHANGES WILL BE COMMITTED ON ``Z`` BUMPS, so it is safe for you to require
watermedia in a range of ``[3.0.1,3.1)``

# 🗺 INDEX

Pick the path that matches what you're building; each one lists the sections it needs.

## MINECRAFT: MODDERS ADDING VIDEO

1. [Setup](#0-setup): add the dependency and respect the version range.
2. [Software restrictions](#11-software-restrictions): supported Minecraft and loader versions.
3. [Managing links](#2-managing-links): turn a link into media sources.
4. [Creating and controlling a player](#31-creating-and-controlling-a-player).
5. [Choosing engines](#33-choosing-engines): OpenGL, Vulkan and OpenAL on the game's own contexts.
6. [Spatial audio](#34-spatial-audio): positional screens, stereo pairs and speakers.
7. [Synchronized playback](#35-synchronized-playback): every player watches the same moment.
8. [Uploading and downloading files](#4-uploading-and-downloading-files): share files through the file server.

## DESKTOP APPLICATIONS

1. [Setup](#0-setup): Gradle dependencies, runtime libraries and [configuration](#02-configuration).
2. [Startup](#1-startup): start WATERMeDIA once and show a loading screen.
3. [Managing links](#2-managing-links) and [searching](#23-searching).
4. [Creating and controlling a player](#31-creating-and-controlling-a-player) and
   [audio tracks](#32-audio-tracks-and-languages).
5. [Choosing engines](#33-choosing-engines): AWT, JavaFX, OpenGL or Vulkan, plus OpenAL or Java Sound.
6. [Diagnostics and examples](#13-diagnostics-and-examples).

## CODE TOOLS

Command-line tools, bots, converters and services that work without a window.

1. [Setup](#0-setup) and [startup](#1-startup): what needs a start and what doesn't.
2. [Platforms](#22-platforms) and [searching](#23-searching): resolve and search links with `PlatformAPI`, no startup
   needed.
3. [Downloads](#42-downloads): `NetRequest` with bounded, verified downloads.
4. [Decoders](#51-decoders) and [encoders](#52-encoders): pictures, animations and video files.
5. [Headless engine](#33-choosing-engines): capture decoded frames without a graphics context.
6. [Synchronized playback](#35-synchronized-playback): `ServerMediaPlayer` runs on servers too.

## AI AGENTS

Read [startup](#1-startup), [public and internal packages](#12-public-and-internal-packages),
[managing links](#2-managing-links), [creating and controlling a player](#31-creating-and-controlling-a-player) and
[choosing engines](#33-choosing-engines) before writing integration code. These rules are hard requirements:

- Use only the public packages (`org.watermedia`, `api.codecs`, `api.network`, `api.platform`, `api.player`, `api.util`,
  without their subpackages). Players and engines come only from `PlayerAPI`; engine classes are sealed.
- Inside Minecraft never call `WaterMedia.start`: the loader does it. Elsewhere call it once per process, never from a
  WATERMeDIA callback or worker thread. There's no stop.
- Check `WaterMedia.loaded()` and the capability you need (such as `CodecsAPI.ffmpegLoaded()`) instead of assuming them.
- Resolution is asynchronous: poll `mrl.status()` from a tick and never call `mrl.await(...)` on the game or render
  thread.
- `PlayerAPI.player(...)` can return null. Release the player when `start()` throws or the host drops the surface.
- Pass engines as suppliers, never share one between players, and let the player own them.
- Read `player.texture()` fresh on every draw, on the render thread; 0 means nothing to draw.
- Never call `release()` from a status listener or a playback thread, and release every player before destroying the
  OpenGL, Vulkan or OpenAL context it uses.
- The OpenGL executor runs on the GL thread and is pumped every frame, once more after `release()` too. OpenAL needs its
  context current on every thread driving audio. On Vulkan, hold `queueLock()` around your own submits and presents, and
  never call `vkDeviceWaitIdle` while players exist.
- Status listeners are consumed by the transition into `STOPPED`, `ENDED` or `ERROR`; register again from the callback
  to keep listening.
- Boolean controls return `true` when the call took effect and `false` when it was refused; check the result instead of
  assuming.
- Times are in milliseconds (encoder timestamps in microseconds), volume goes from 0 to 100 and speed from just above 0
  up to 4.
- `ImageReader.next()` reuses its buffer: copy it before the next call. The `CodecsAPI` decode, reader, sniff and
  encoder calls are experimental: don't use them in mods; its status checks are safe.
- Dedicated servers get `ServerMediaPlayer`, `PlatformAPI`, `NetworkAPI` uploads, the file server and the pure Java
  image codecs; players and engines throw there.

# 0. SETUP

On Minecraft environments nothing from here is required except following the version range of the jar: add WATERMeDIA as
a compile-time dependency, declare it as a required dependency of your mod within the ranges listed in
[software restrictions](#11-software-restrictions), and the loader provides it at runtime. Clients also need
_WATERMeDIA: Binaries_ installed (see [Binaries and FFmpeg](#01-binaries-and-ffmpeg)).

```gradle
repositories {
    maven { url "https://jitpack.io" }
}

dependencies {
    compileOnly "com.github.SrRapero720:watermedia:$watermedia_version" // A RELEASE TAG, SUCH AS 3.0.0
}
```

On custom Java applications or Java games, the environment must provide:

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

| Engine | Extra runtime libraries |
| --- | --- |
| `GLEngine` | _LWJGL_ `lwjgl` and `lwjgl-opengl`, with their natives |
| `VKEngine` | _LWJGL_ `lwjgl`, `lwjgl-vulkan` and `lwjgl-shaderc` (shaders compile at runtime), with their natives (`lwjgl-vulkan` only has natives on macOS; Windows and Linux use the system's Vulkan) |
| `ALEngine` | _LWJGL_ `lwjgl` and `lwjgl-openal`, with their natives |
| `JFXEngine` | A JavaFX 13+ runtime |
| `AWTEngine`, `JSEngine`, `HeadlessGFXEngine` | Nothing extra: the JDK covers them |

- On Forge, NeoForge and Fabric, WATERMeDIA bundles _WATERCoNFIG_ as a nested JAR, and the game already ships the other
  libraries.
- The standalone launcher extracts that JAR to a temporary file, adds it to the relaunched JVM's classpath and downloads
  the other runtime libraries it lists as needed; a cached or downloaded library joins the classpath only when it
  matches the SHA-256 pinned at build time.
- An embedding app brings its own classpath. Development Gradle dependencies don't prove a Minecraft installation ships
  the other libraries.
- The Binaries JAR embeds and relocates its _XZ_ dependency, and the WATERMeDIA JAR carries its _JavaCPP_ and _FFmpeg_
  bindings.

The _FFmpeg_ natives cover these platforms; pictures decode everywhere Java runs.

| Architecture | Windows | Linux | macOS | Android |
| --- | --- | --- | --- | --- |
| x86_64 | ✅ | ✅ | ✅ | ⛔ |
| aarch64 | ⛔ | ✅ | ✅ | ⛔ |

> [!WARNING]
> There are no _FFmpeg_ natives for Windows on ARM64 or for Android: pictures still decode there, video and audio don't
> play, and an installed Binaries fails the startup unless `codecs.ffmpeg.disable` is on.

## 0.1 BINARIES AND FFMPEG

> [!IMPORTANT]
> Hardware video decoding needs a GPU and driver that decode that codec: NVIDIA (CUDA), Intel (QSV), AMD (AMF), Direct3D
> 11 or 12 on Windows, VideoToolbox on macOS, VAAPI, VDPAU or DRM on Linux, or [Vulkan](#vulkan-decoding). Anything
> else, or a GPU that can't keep up, decodes in software, and `codecs.ffmpeg.hardwareAccel = false` always does.

> [!NOTE]
> Configurable: `codecs.ffmpeg.disable`, `customPath`, `hardwareAccel`, `analyzeDuration`, `probeSize`,
> `trackPacketReads`, `cache` and `cacheMaxResponse`. See their comments in `config/watermedia.toml`.

_WATERMeDIA: Binaries_ needs WATERMeDIA and loads with it, provisioning the natives right after the configuration and
before the network, codecs and player modules.

- Minecraft clients require it; dedicated servers can skip it, since it provisions nothing there.
- Apps embedding the Java API can skip it too and keep image decoding and the native-free platform resolvers; they lose
  video decoding and the platforms backed by _yt-dlp_.
- Disabling _FFmpeg_ (`codecs.ffmpeg.disable`) skips the extraction.
- The native paths stay published for the life of the process, and there's no separate Binaries init or shutdown API.
- _yt-dlp_ and _BotGuard_ download on first use and are verified before they run. The system properties
  `watermedia.ytdlp.binary` and `watermedia.botguard.binary` point at your own executables instead.

Custom _FFmpeg_ builds go in `codecs.ffmpeg.customPath`: a folder with the native libraries, not an `ffmpeg.exe`
program. They may use GPL, LGPL or any other declared license and version: the loader checks the required native
capabilities, not a version or license allowlist, and verifies integrity only for the bundled natives. They still need
JNI bindings compatible with the Java API WATERMeDIA uses, plus the TLS support of the bundled build (see
[HTTPS trust and credentials](#25-https-trust-and-credentials)).

## 0.2 CONFIGURATION

> [!NOTE]
> Every field is configurable and describes itself: its comment in `config/watermedia.toml` is the documentation of how
> it works, and the values are your decision. Each section of this guide lists the fields that shape it.

WATERMeDIA writes its settings to `config/watermedia.toml` under the working directory, with a comment above every field.
The defaults work out of the box, fields read at startup apply on the next launch, and when a backend looks unavailable this
file is the first place to check.

- `codecs.*`: _FFmpeg_, hardware decoding and the picture decoders.
- `player.*`: the default quality and the caches.
- `platforms.*`: mature content, search and platform accounts.
- `network.*`: the file server and web requests.

# 1. STARTUP

On popular, natively supported modloaders such as Forge, NeoForge and Fabric, the loader starts WATERMeDIA for you: you
don't need to follow this section.

**EXAMPLE:**
```java
import org.watermedia.WaterMedia;

WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
boolean ready = WaterMedia.loaded(); // true ONCE EVERY MODULE FINISHED; CHECK THE CAPABILITY YOU NEED TOO
String module = WaterMedia.loading(); // MODULE CURRENTLY LOADING
int step = WaterMedia.step(); // CURRENT LOADING STEP
int steps = WaterMedia.steps(); // TOTAL LOADING STEPS
```

It loads, in this order:
- Configuration (`watermedia.toml`)
- Binaries (only when its JAR is present)
- `NetworkAPI`
- `CodecsAPI`
- `PlayerAPI`

So `steps()` is 5 with Binaries and 4 without, and `loading()`, `step()` and `steps()` are safe to poll from any thread
for a loading screen.

- `start` loads every module once for the life of the process. Nothing ever unloads: there's no stop, a second `start`
  throws, and a failed start can't be retried in the same process.
- A null temporary directory becomes `<java.io.tmpdir>/watermedia` and a null working directory the current one. The
  last argument picks the client environment; client-only modules (Binaries, codecs, player) skip their startup on a
  dedicated server.
- Optional parts (the file server, _FFmpeg_) log their failure and stay off while the load finishes, so check the
  capability you need, such as `CodecsAPI.ffmpegLoaded()`.
- Any other failure (the configuration, an installed Binaries module) makes `start` throw `IllegalStateException` with
  that failure as its cause, and `loaded()` stays false.
- `start` refuses to run from a WATERMeDIA worker thread, so never call it from one of its callbacks.

Each API knows by itself whether its module loaded:

- `PlayerAPI.mrl` and the players from `PlayerAPI.player` throw `IllegalStateException` until the player module starts,
  and always do on dedicated servers.
- `NetworkAPI.upload` throws until the network module starts. It also rejects anything that isn't an existing file right
  away; an unreachable or misconfigured remote server fails the returned status instead.
- `PlatformAPI` needs no startup: its registry is static and `register`, `fetch` and `search` work anytime. Handlers
  that need Binaries (YouTube, _yt-dlp_) fail with their own `PlatformException` until it loads, and always on dedicated
  servers; without the Binaries JAR they don't exist, so `fetch` returns null for their links.
- Pure Java image decoding through `CodecsAPI`, `ServerMediaPlayer`, `MathUtil` and `MathEases` need no startup at all.

A player owns the engines created by the suppliers you pass to `PlayerAPI.player`. Create engines lazily through
suppliers, so an unavailable source can't leak preallocated native resources. Your app owns its OpenGL, Vulkan and
OpenAL contexts: release every player on the right host context before tearing those contexts down, and create new
engines after a device or context reload. Native libraries never unload and can't switch versions inside a running JVM.

## 1.1 SOFTWARE RESTRICTIONS

### Blocked launchers

The Minecraft bootstraps refuse to start when _TLauncher_ launched the game. They spot it through its injected skin mod,
classes, files on the class path, JVM options, threads and parent processes, and fail naming the evidence; leftovers of
an old install in the game directory or the user profile only log a warning. Embedding apps skip this check.

### Compatible versions

Forge, NeoForge and Fabric load WATERMeDIA on clients and dedicated servers, and reject Minecraft versions outside this
list:

| Minecraft | Forge | NeoForge | Fabric Loader |
| --- | --- | --- | --- |
| 1.18.2 | 40.3.10 or newer 40.x | ⛔ | 0.14 or newer 0.x |
| 1.19.2 | 43.5.0 or newer 43.x | ⛔ | 0.14 or newer 0.x |
| 1.20.1 | 47.4.2 or newer 47.x | ⛔ | 0.14 or newer 0.x |
| 1.21.1 | 52.1.1 or newer 52.x | 21.1.0 or newer 21.1.x | 0.14 or newer 0.x |
| 1.21.11 | 61.0.5 or newer 61.x | 21.11.0 or newer 21.11.x | 0.14 or newer 0.x |
| 26.1.2 | 64.x | 26.1.2.x | 0.14 or newer 0.x |
| 26.2 | 65.x | 26.2.0.x | 0.14 or newer 0.x |

- On Minecraft, Java 17 up to 25 is accepted.
- WATERMeDIA and Binaries accept each other from 3.0.0 up to (not including) 3.1.0; older 3.0.0 BETA builds are
  rejected.
- Fabric can't limit a dependency to clients, so its bootstrap requires Binaries on clients and its manifest rejects
  incompatible Binaries versions.

## 1.2 PUBLIC AND INTERNAL PACKAGES

The public API is the packages `org.watermedia`, `api.codecs`, `api.network`, `api.platform`, `api.player` and
`api.util`, without their subpackages. Everything else is internal: codec readers, encoders and chunk structures, player
queues and caches, the built-in platforms, the bootstrap, `org.watermedia.tools` and the bundled _FFmpeg_ bindings.
Binaries also uses `org.watermedia.tools` for its digest, file and JSON utilities.

The JAR declares no Java module, so no loader blocks internal packages; treat the boundary as the contract anyway and
use the public entry points, such as `PlayerAPI.player` and `PlatformAPI.fetch`, instead of the internal classes behind
them. Contributors can run `gradle javadocInventory` from the project root to list missing public-method docs with file
names and line numbers; it only reports, and only checks the public packages listed in `public_packages` of
`gradle.properties`.

> [!WARNING]
> `CodecsAPI` is experimental: `decode`, `reader`, `sniff` and `encoder`, plus the `ImageReader`, `Encoder` and filters
> they hand out, can change or break in any update without following the version schema, so don't use them in your mods
> yet. The media players already use the codecs for you, and the status checks (`ffmpegLoaded()`, `ffmpegError()`,
> `vulkanDecode()`) are safe to call.

## 1.3 DIAGNOSTICS AND EXAMPLES

The project loggers are `watermedia` and `watermedia_binaries`; set those names to DEBUG for diagnostics.

- Project log messages show media links in full, so paths and IDs stay useful for debugging. They mask link passwords
  (`user:password@`), bare JWT tokens, `Authorization` and `Cookie` values, Minecraft session arguments and
  secret-looking `key=value` pairs (the app's log uploads go through the same filter). Exception traces carrying a
  secret keep their type, stack, causes and suppressed failures as sanitized text.
- Nothing is aggregated per time window: an anomaly that can repeat (a short frame buffer, a refused layout, a failing
  callback, a rejected upload) is logged on its first occurrence in each engine, player, server or cache and then stays
  quiet, and slow playback iterations are counted and reported once when the run ends.
- _FFmpeg_ still writes its own native log lines to the process error output, outside the project loggers and their
  redaction; routing them through the project logger is ready but disabled until macOS and Linux are validated.
- `-Dwatermedia.glchecks=true` turns on OpenGL error checks in the engine's upload path.

`src/test/java/org/watermedia/ApiGuideExample.java` compiles factory usage for every graphics and audio engine and both
sides of synchronized playback, the clock and a follower. Its image decoding example, which filters every frame through
a `DownscaleFilter`, runs in `ApiGuideExampleTest`; GUI, audio-device and Minecraft behavior needs the matching host
integration. The regular playback and spatial test suites cover real _FFmpeg_ and OpenAL behavior separately. Test runs
started with `-Prequire_natives=true` (the CI native jobs) fail instead of skipping when the _FFmpeg_ natives are
missing.

# 2. MANAGING LINKS

> [!NOTE]
> Configurable: `platforms.allowMatureContent`, `searchLimit`, `searchCleanupInterval`, `biliBiliCookie` and
> `player.mrlCleanupInterval`. See their comments in `config/watermedia.toml`.

An MRL (Multimedia Resource Location) turns any link into the media it holds: one or more sources, each with its
qualities, audio tracks and metadata. It resolves in the background and stays cached.

## 2.1 RESOLVING LINKS

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

A direct link fails when the server answers with an error status, a web page or a script; HLS playlists open as one
video and IPTV playlists as one source per channel.

## 2.2 PLATFORMS

Links from these platforms resolve out of the box:

- 📺 YouTube, Twitch, Kick, BiliBili, Odysee, D.tube, VidLii
- 🐦 Twitter (X), Bluesky, TikTok, Facebook, Instagram
- 🎵 SoundCloud, Newgrounds (movies, music and art)
- 🖼️ Imgur, Lightshot, Medal, Streamable, Sendvid
- 📁 Google Drive, Dropbox, MediaFire
- 🔞 PornHub (only with `platforms.allowMatureContent` on)
- 🔗 Direct links to any video, music, picture or playlist, local files and [`water://` links](#24-the-water-protocol)

YouTube, SoundCloud, Facebook and Instagram play through _yt-dlp_, which requires Binaries. BiliBili plays up to 480p,
or up to 1080p (8K with VIP) with your session cookie in `platforms.biliBiliCookie`.

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

Any other failure of your platform is a `PlatformException` too, and an album or playlist skips a bad item instead of
failing whole.

## 2.3 SEARCHING

YouTube (with Binaries), Twitch, Kick and Imgur search by keyword, and your platform joins them by overriding
`search(query, limit)`.

```java
List<PlatformResult> results = PlatformAPI.search("lofi");   // RETURNS AT ONCE; THE LIST FILLS AS PLATFORMS ANSWER
boolean searching = PlatformAPI.searching("lofi");           // false ONCE EVERY PLATFORM ANSWERED; POLL BOTH FROM THE UI
for (final PlatformResult hit: results) {
    show(hit.title(), hit.thumbnail(), hit.platform().name()); // WHEN PICKED, PlayerAPI.mrl(hit.uri()) OPENS IT LIKE ANY LINK
}
List<PlatformResult> fresh = PlatformAPI.search("lofi", true); // ASK THE PLATFORMS AGAIN INSTEAD OF THE CACHE
```

A new query replaces the one in flight, and a query that looks like the previous one still being typed waits a moment
for the next keystroke. Twitch and Kick only list channels that are live right now.

## 2.4 THE WATER PROTOCOL

```java
PlayerAPI.mrl("water://local/config/song.mp3");              // ./config/song.mp3, RESOLVED AGAINST THE WORKING DIRECTORY
PlayerAPI.mrl("water://remote/xyz12345");                    // A FILE ON YOUR FILE SERVER (SEE UPLOADING AND DOWNLOADING FILES)
PlayerAPI.mrl("water://global/abcd6789");                    // A FILE ON OUR GLOBAL SERVER
```

Remote and global links carry the file ID the server gave, not a folder path.

## 2.5 HTTPS TRUST AND CREDENTIALS

HTTPS works out of the box with any certificate Java already trusts. Playback checks every certificate against the JVM
truststore WATERMeDIA reads when it starts, and that check never turns off.

To use a self-signed certificate or your own certificate authority, import it into a truststore and point Java at it
before WATERMeDIA starts (inside Minecraft, add the same `-D` options to the launcher's JVM arguments):

```shell
keytool -importcert -alias my-server -file my-server.crt -keystore my-truststore.jks -storepass changeit -noprompt
java -Djavax.net.ssl.trustStore=my-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit -jar my-app.jar
```

- An empty or broken truststore keeps _FFmpeg_ from starting, so video stays off until it's fixed.
- A custom _FFmpeg_ build needs the same TLS support as the bundled one.
- Login headers and cookies (`Authorization`, `Cookie`, `X-WaterMedia-Token`) only reach the server of the first
  request, never a redirect to another one, and redirects may only point to http or https links.

# 3. PLAYING MEDIA

> [!NOTE]
> Configurable: `player.defaultQuality`, `player.cacheBudget`, `player.watertx.cache` and
> `player.watertx.texturesBudget`. See their comments in `config/watermedia.toml`.

## 3.1 CREATING AND CONTROLLING A PLAYER

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

`player` returns null when the source is still fetching, the index is invalid, the needed backend is unavailable or
construction throws an `Exception`; an `Error` propagates. Check the MRL status before deciding to retry.

- Suppliers run only when needed, and images never call the audio supplier.
- A supplier returning null omits that output, such as `() -> null` as the graphics engine for audio-only playback.
- Never share an engine between players.

`TxMediaPlayer` plays supported images and animations, `FFMediaPlayer` plays audio and video through _FFmpeg_, and
`ServerMediaPlayer` is a sync clock with no decoding or native engines.

All three players share the same controls. The playback controls return `true` when the call took effect and `false`
when it was refused: impossible right now, after `release()` or a speed out of range; the output settings return
nothing.

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

Call `speed(...)` where the audio context is current: the player clock only follows a rate the audio engine took.
Scaling and level of detail work on the supported pixel layouts, and the built-in graphics engines don't accept
compressed BC textures yet.

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

- A listener runs on whichever thread drove the change: a playback thread, the shared sync ticker, or the caller of
  `status()`, `time()` or `texture()` on image players whose clock resolves the end lazily.
- It never runs while the player holds one of its locks, nor on a playback thread `release()` is waiting for.
  `release()` delivers its `STOPPED` once it finishes and the engines are freed, so nothing of the player is still
  pending when that callback runs.

| Player state | Meaning |
| --- | --- |
| `WAITING` | Created and waiting for startup conditions. |
| `LOADING` | Loading or preparing media. |
| `BUFFERING` | Waiting for enough data to continue. |
| `PLAYING` | Playing media. |
| `PAUSED` | Paused and ready to resume. |
| `STOPPED` | Stopped and ready to start from the beginning. |
| `ENDED` | Reached the end of the media. |
| `ERROR` | Playback hit a failure. |

`Status.allows(next)` tells whether a pause, resume, buffering, end, stop or failure can move a status straight to
`next`. Starting, seeking and restarting take their own paths: a player leaves `LOADING` only when its pipeline opens
(or it stops or fails), a seek on `ENDED` or `STOPPED` restarts it, and `STOPPED` allows no direct move.

## 3.2 AUDIO TRACKS AND LANGUAGES

Dubbed videos and files with several audio streams play their original (or default) audio unless you ask for something
else. Platforms list a source's separate audio tracks with the original first, and inside a file or an HLS stream the
stream flagged as default wins.

```java
player.audioLanguage("es-MX"); // PREFERRED FROM THE NEXT START; null CLEARS IT
for (final MediaPlayer.AudioTrack track: player.audioTracks()) {
    if ("es".equals(track.lang())) player.audioTrack(track); // SWITCHES RIGHT AWAY ON A RUNNING PLAYER
}
```

- The pick order is: your `audioTrack(...)` pick, then `audioLanguage(...)`, then the original (the first separate
  track) or, without separate tracks, the file's default stream, then the first one. A language tag matches the same tag
  first, then the same language, so `es-MX` takes `es-419`, `es` or the `spa` a file carries. There's no automatic
  language: without `audioLanguage(...)` the original plays, never an automatic dub.
- `audioTracks()` lists one `AudioTrack(track, stream, name, lang)` per audio stream: the main input's first, then each
  separate track of `source().audioTracks()`. `track` is `AudioTrack.MAIN` or the separate track's index and `stream` is
  the _FFmpeg_ stream index inside it.
- A separate track that hasn't played yet shows as one `provisional()` entry (`stream` is -1) built from the platform's
  name and language, so listing never opens every dub up front. Once it plays, its real streams take its place. The list
  is an immutable snapshot any thread can read.
- `audioTrack(track)` returns `true` when the pick changed. A running player rebuilds its pipeline at the current time
  (a live stream restarts at its edge), and the pick survives quality switches and restarts. `audioTrack()` returns what
  plays now. Image and server players, and players without an audio engine, list nothing.

## 3.3 CHOOSING ENGINES

Vulkan development comes first; OpenGL stays supported as the secondary backend. Backend decisions belong in your
rendering layer.

| Factory | Host obligation |
| --- | --- |
| `PlayerAPI.glEngine(renderThread, executor)` | The executor runs tasks on the thread owning the OpenGL context and keeps being pumped every frame while playback or release may wait on it, once more after `release()` too. With both arguments null, the calling thread must own the context. Never move an engine between contexts. |
| `PlayerAPI.vkEngine(context)` | Supply a `VKContext` whose Vulkan objects and retirement mechanism outlive the engine: hold `queueLock()` around your own submits and presents, and run the `retire(...)` callbacks once your in-flight frames finish (all of them when the device goes away). To decode video on the GPU, see [Vulkan decoding](#vulkan-decoding). |
| `PlayerAPI.awtEngine(onFrame)` | Paint the published image from the AWT/Swing UI; hop the optional callback to the UI thread as needed. |
| `PlayerAPI.jfxEngine(onFrame)` | Needs a JavaFX 13+ runtime; bind the engine's image to your UI. Without it the factory throws `IllegalStateException` instead of a linkage error. |
| `PlayerAPI.headlessEngine()` / `headlessEngine(preload)` | Captures uploaded frames without a graphics context, handy for tests and image processing. Like every engine and media player it only exists on a client; a dedicated server can only create `ServerMediaPlayer` clocks. |
| `PlayerAPI.alEngine()` | The OpenAL context is already current and stays valid on the threads doing audio work. |
| `PlayerAPI.alEngine(true)` | Same OpenAL contract, playing through positional mono sinks (stereo pairs and extra speakers). |
| `PlayerAPI.jsEngine()` | A Java Sound device and line are available; no positional audio. |

> [!IMPORTANT]
> **OpenGL:** converting YUV and grayscale video on the GPU needs OpenGL 3.2 core (GLSL 150); without it the frames
> convert to BGRA on the CPU. Uploading straight from the decoding thread needs OpenGL 4.4 or `ARB_buffer_storage`;
> older drivers upload on the render thread through two pixel buffers.

> [!IMPORTANT]
> **Vulkan:** `queue()` must accept compute and transfer work.
> - **Zero copy:** decoded planes reach the GPU without a CPU copy when the device enables `VK_EXT_external_memory_host`
>   (`hostImportSupported()` returns true) and the plane buffer is aligned to `minImportedHostPointerAlignment()`;
>   otherwise the plane is copied through a staging buffer.
> - **Hardware YUV conversion:** 8-bit SDR NV12 and YUV 4:2:0, 4:2:2 and 4:4:4 frames with even sizes and no BT.2020
>   primaries convert in sampler hardware when the device enables `samplerYcbcrConversion` (Vulkan 1.1, `ycbcrSampler()`
>   returns true); every other layout uses a compute pass.
> - **Layouts:** besides 8-bit BGRA, RGBA, YUYV and UYVY, a layout needs plane formats that support sampling, linear
>   filtering and transfer writes, or it falls back to BGRA. RGB always falls back.
> - **GPU decoding:** see [Vulkan decoding](#vulkan-decoding).

Engine base classes are sealed: your integration supplies host contexts and executors, not custom engine
implementations. Engines manage their own playback resources, including textures, audio sources and buffers.

Every engine follows one lifecycle:

- `release()` is idempotent, must never run at the same time as the player feeding the engine, and leaves it inert:
  `released()` is true and `texture()` returns 0.
- The player checks `supports(format, bits)` before uploading and falls back to 8-bit BGRA when the engine declines a
  layout.
- Uploads never throw: a driver failure drops the frame with one logged error, and the last frame stays on screen.
- Software and headless engines expose a non-zero sentinel instead of a GPU handle, so only test the handle against 0.
- `PixelFormat.planeWidth`, `planeHeight`, `texelBytes` and `frameBytes` size each plane of an engine's `format()`,
  `width()`, `height()` and `bits()`, for example to budget its memory. Subsampled planes round up like _FFmpeg_, so odd
  sizes keep their last chroma column and row.

Color handling:

- The OpenGL and Vulkan engines convert YUV with the matrix (BT.601, BT.709 or BT.2020), range and chroma position each
  video declares. When it declares nothing they guess like _mpv_: BT.601 for SD frames (narrower than 1280 pixels and up
  to 576 lines tall), BT.709 otherwise.
- PQ and HLG HDR show as SDR, with BT.2020 colors mapped to BT.709 and highlights compressed so a 1000-nit peak reaches
  white.
- Engines on one OpenGL context or Vulkan device build that conversion once and share it. It goes away with the last
  engine's release work (the task `release()` queues on the OpenGL render executor, or the `VKContext.retire`
  callbacks), so release every engine and let that work run before destroying the context or device.
- Software engines get BGRA that _FFmpeg_ converts with the declared matrix and range, without HDR tone mapping.

OpenAL needs its original context current on every thread driving the player, the sync ticker included. The Java Sound
engine serializes its own calls, so it needs no extra locking, and reopens a wider line when an audio chunk exceeds the
line buffer, dropping whatever the old line still held.

> [!IMPORTANT]
> **OpenAL:** 4, 6, 7 and 8-channel audio needs `AL_EXT_MCFORMATS` on a plain engine (spatial engines fold everything to
> stereo), mono and stereo float and double samples need `AL_EXT_FLOAT32` and `AL_EXT_DOUBLE` (surround float comes with
> `AL_EXT_MCFORMATS`), and a clock that accounts for the device's output latency needs `AL_SOFT_source_latency`; without
> them the audio converts to what the device plays. [Environment processors](#environment-processors) that use EFX need
> `ALC_EXT_EFX`.

The standalone app (opening the WATERMeDIA JAR) renders with Vulkan unless the user picks OpenGL; when Vulkan is
unavailable it runs that launch on OpenGL and keeps the saved choice. `-Dwatermedia.engine=opengl` or
`-Dwatermedia.engine=vulkan` overrides the engine for one launch without saving it. The app keeps its engine, interface
scale (`0` follows the monitor, otherwise a factor from 1 to 3 in 0.25 steps, shown in Settings as `Auto`, `x1.0`,
`x1.25` and so on), CRT effect and click sound in `config/app.properties` under its working directory.

### Vulkan decoding

By default `VKContext.deviceInfo()` returns null and videos decode elsewhere, then upload from RAM. Override it with how
your device was created (the feature chain given to `vkCreateDevice`, kept allocated and unchanged while the device
lives, plus the enabled instance and device extensions and the queues created per family) and _FFmpeg_ decodes H.264,
H.265 and AV1 with Vulkan Video, while the engine samples the decoded images without a trip through RAM.

> [!IMPORTANT]
> Vulkan decoding needs:
> - A Vulkan 1.3 instance and device with `timelineSemaphore` and `synchronization2` enabled.
> - `VK_KHR_video_queue`, `VK_KHR_video_decode_queue` and the decode extension of each codec to accelerate:
>   `VK_KHR_video_decode_h264`, `VK_KHR_video_decode_h265` or `VK_KHR_video_decode_av1`.
> - Spare queues on your device, or the external memory and semaphore extensions for a twin, both described below.
> - Natives with Vulkan Video (`CodecsAPI.vulkanDecode()` returns true) and `codecs.ffmpeg.hardwareAccel` on.

_FFmpeg_ then decodes in one of two places:

- **Your device**, when it also has queues _FFmpeg_ can own: a compute-capable queue (which also takes transfers) other
  than `queue()`, plus a video decode queue, with `queue()` created last in `queueFamily()`. Every other queue of every
  family goes to _FFmpeg_, which submits to them from its own threads, so while players exist you never touch those
  queues nor call `vkDeviceWaitIdle`; wait on your own queue instead (`vkQueueWaitIdle` under `queueLock()`).
- **A twin**, when your device has no such queues but enables `VK_KHR_external_memory_win32` and
  `VK_KHR_external_semaphore_win32` on Windows, or `VK_KHR_external_memory_fd` and `VK_KHR_external_semaphore_fd`
  elsewhere. The engine creates a second device on the same GPU with your feature chain and extensions, _FFmpeg_ decodes
  there, and the engine imports every decoded image into your device. Your queues stay your own.

Either way, release every player before destroying the device or its instance: _FFmpeg_ uses them, the twin included,
until its last decoder is gone.

> [!IMPORTANT]
> 4:2:2, 4:4:4 and 12-bit videos only decode on the GPU through _libplacebo_, which turns every decoded layout into SDR
> RGBA (HDR tone-mapped). It needs `hostQueryReset`, `runtimeDescriptorArray`,
> `shaderStorageImageArrayNonUniformIndexing` and `maintenance4` on the decoding device and, on your own device, a
> second queue in `queueFamily()`; a twin enables those features by itself when the GPU has them. Without _libplacebo_
> only 4:2:0 streams (8 and 10-bit) decode on the GPU, and the engine converts them with its own shaders.

## 3.4 SPATIAL AUDIO

Create an OpenAL engine with `PlayerAPI.alEngine(true)` before building the player. Spatial mode is fixed for the
engine's lifetime: _FFmpeg_ folds stereo and surround sources into stereo (the center channel reaches both sides, mono
sources stay mono) and the engine plays them through sinks, which are positional mono sources. The plain `alEngine()`
factory plays every channel on one dry, listener-relative source, though layouts the device can't play may still be
converted to the closest channel count. Java Sound returns `false` from spatial updates and exposes no native source.

The engine starts with one sink, the primary, playing dry, listener-relative `Mix.MONO` audio. Pass a `SpatialAudio`
state to place it in the world, or `null` to go back to dry, listener-relative playback. Volume, mute, speed, pause and
clock following (`sync`) don't depend on spatial settings and reach every sink.

```java
import org.watermedia.api.player.SFXEngine.SpatialAudio;

final ALEngine audio = PlayerAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

Create the engine, update it and release it on the host's sound executor, with the original OpenAL context current. The
host owns the device, context and listener, and they must outlive every player using them.

- `FFMediaPlayer` uses private playback threads and, like ordinary `ALEngine` playback, currently needs the source's
  context to be the process-current context on those threads. Hosts that expose their audio context only through
  thread-local binding aren't supported: binding it on the caller's sound thread alone isn't enough.
- Native engine operations reject a different or missing context; they never switch or recreate the host's context.
- Release players before a device reload, then create new engines in the new context.
- `FFMediaPlayer.release()` interrupts and joins its playback pipeline before deleting the audio source; run it before
  the context is destroyed, never as a callback from that pipeline.

`SpatialAudio` validates finite positions OpenAL floats can hold, `0 < referenceDistance < maxDistance` and a finite,
non-negative rolloff; zero rolloff turns distance attenuation off. The host's distance model decides how these
parameters attenuate audio, and the API never touches the global distance model, the listener or the source velocity, so
`maxDistance` isn't a universal silence radius under every OpenAL model.

### Sinks: stereo pairs and extra speakers

One decode feeds every sink of a spatial engine, so a stereo picture or a speaker placed somewhere else costs no second
download or decode. Each sink plays one `ALEngine.Mix` of the decoded audio: `LEFT`, `RIGHT` or `MONO` (the average of
both). A mono source plays the same samples on every mix, so a stereo pair never goes silent on mono media.

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

- The primary sink owns `player.audioSource()`. It drives the media clock and lives as long as the engine; its
  `release()` returns `false`. `player.sinks()` lists the primary first, and both `sinks()` and `addSink(...)` give an
  empty list or `null` without a spatial OpenAL engine.
- Every sink queues the same samples, and play, pause, flush, speed and volume run as one batch on all of them, so a
  stereo pair stays sample aligned. Adding a sink during playback, or changing a mix on stereo media, rebuilds every
  queue on the primary's sample, which pauses the sinks for about one mixer update.
- Each sink has its own `spatialAudio(...)`: the environment adapter gets that sink's source in `process` and `reset`,
  and `sink.release()` resets it before deleting the source. Releasing the player releases every sink.
- Add, change and release sinks on the host's sound executor with the engine's context current, like every other spatial
  update. Their queries (`source()`, `mix()`, `spatialAudio()`, `released()`) need no context.
- A host may adopt a sink's source into its own sound system and write its gain, pitch and position, but it must never
  play, queue, unqueue or delete it: the engine owns its transport and buffers. A new sink starts at the engine's
  current gain and pitch.

### Environment processors

The last `SpatialAudio` argument is an optional `SpatialAudio.Environment` that applies your own effects, such as
occlusion or reverb, to each OpenAL source. It's the door for spatial sound mods: write a small adapter in your mod that
forwards each source to them, and load it only when that mod is present.

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

- Keep one stable processor instance between updates, and call `spatialAudio(...)` from the host's sound executor with
  the context current.
- Each update restores the original world position and clears the source's previous filters and auxiliary sends before
  calling `process`, so a processor may move `AL_POSITION` and must apply its full environment on every call.
- Throttle updates in the host instead of returning early inside the processor, and spread many players across ticks.
- The engine deletes only its own source and buffers, never the processor's filters or effect slots.
- If `process` or `reset` throws, the source effects are cleared and the exception propagates; a failing `reset` can't
  stop the source from being deleted during release.
- `auxOnly = true` asks the processor for reflected-only output; it doesn't silence the direct path by itself, so mute
  the player when you need strict silence.
- The processor only runs during your explicit updates, never from the _FFmpeg_ decode or playback loop. Basic
  positioning works without EFX; a processor using EFX needs a device that exposes it.

### Verification

`SpatialAudioTest` covers invalid argument rejection, real OpenAL source coordinates and attenuation, callback origin
restore, effect clearing, exception handling, mismatched contexts and late calls after release. It also generates a
stereo WAV and plays it through `FFMediaPlayer`, checking that the stereo decode reaches every sink as mono PCM in real
OpenAL buffers. `ALSinkTest` covers the sink set, the mixes in every sample type, batched controls, backpressure, sinks
joining on the primary's sample and underrun recovery. The device tests need an OpenAL output device, and the decoding
cases also need the _FFmpeg_ natives.

## 3.5 SYNCHRONIZED PLAYBACK

`PlayerAPI.serverPlayer()` builds a `ServerMediaPlayer`, a clock with no media or engines that runs on either side. It
owns the time math: start, pause, seek, speed, repeat and the loop wrap or `ENDED` transition run on the system clock,
and every successful change bumps `revision()`. The host owns everything else: who may drive the clock, how its
snapshots reach the clients and where its position is saved. Engines and players have no public constructors: every
instance comes from `PlayerAPI`.

```java
ServerMediaPlayer clock = PlayerAPI.serverPlayer();
clock.syncDuration(durationMs);
clock.start();
// ON EVERY SERVER TICK: SEND clock.snapshot() WITH YOUR OWN PACKET WHEN clock.revision() MOVED
MediaPlayer player = PlayerAPI.player(mrl, gfxSupplier, sfxSupplier);
player.sync(snapshot); // ON THE CLIENT, FOR EVERY SNAPSHOT RECEIVED
```

A player fed a snapshot through `sync(PlayerState)` (or `sync(ByteBuffer)` for the encoded form) follows the clock from
then on. Every 50 ms it:

- Mirrors the snapshot's status: start, pause and stop. A `BUFFERING` snapshot, which only a hand-built one carries,
  holds like a pause.
- Adopts its repeat and speed.
- Pulls playback back with a rate-limited `seekQuick` once `drift()` exceeds `tolerance(ms)` (one second by default).

The latest snapshot fed always wins, so send them in the order the clock produced them. `authority()` returns that
snapshot, `authorityTime()` extrapolates where the clock is now and `following()` tells whether a player follows one.
Correction never trims the playback speed; it skips correction while the player loads or buffers and takes the short way
around a loop boundary on repeating media.

A control called on a following player applies at once and is undone by the next tick that disagrees, so route user
controls to the clock through your own packets and let the snapshot carry the result. Frame stepping is refused while
following, and volume, mute, scaling and spatial audio always stay local.

The clock holds its position (zero after `start()`) until `syncDuration(ms)` or `live(true)` tells it which timeline it
runs on. Report the duration once from a client that opened the source (the first positive value wins for the clock's
lifetime and later different values are ignored), and restore a saved position with `syncTime(ms)` after starting the
clock, since `start()` restarts from zero. `canSeek()` is false and `seek(...)` returns `false` until a duration is
known, and always on a live session. `release()` freezes the position and leaves the clock `STOPPED` with a new
revision, so save and send that last snapshot before dropping it, or the players keep extrapolating the previous one.
Release each player when its host session ends.

### Snapshot format

`MediaPlayer.PlayerState` is a fixed 27-byte big-endian record (version, revision, status, time, duration, speed,
flags). `PlayerState.read(ByteBuffer)` consumes only those bytes and leaves trailing data for your transport; the
buffer's own byte order doesn't matter, and a rejected payload leaves the position untouched. Check who sent a snapshot
in your transport: the library never identifies peers.

# 4. UPLOADING AND DOWNLOADING FILES

> [!WARNING]
> The server speaks plain HTTP: put it behind your own HTTPS proxy when it faces the internet. It refuses to listen
> beyond `127.0.0.1` with the default token, and changing the token on every server update keeps strangers from
> uploading.

> [!NOTE]
> Configurable: `network.server`, `serverBindAddress`, `serverPort`, `remoteUrl`, `token`, `maxServerRequests`,
> `serverTimeout`, `maxUploadSize` and `maxStorageSize`. See their comments in `config/watermedia.toml`.

The optional file server stores the files your players upload and shares them with everyone through
`water://remote/<id>` links. To use it:

1. Turn it on with `network.server`: `DEDICATED` runs it on dedicated servers, `ALWAYS` on clients too.
2. Open its port to the outside: the server doesn't do it by itself.
3. Point `network.remoteUrl` at the server's public address and port, on the server and on every client.
4. Give the server and every client the same custom `network.token`.

Uploaded files land in `watermedia/files` under the server's working directory; delete them there to free space.

## 4.1 UPLOADS

```java
NetworkAPI.UploadStatus status = NetworkAPI.upload(new File("clip.mp4"));
// LATER, FROM A TICK OR A UI TIMER
if (status.completed()) {
    URI link = status.uri(); // water://remote/<id>, READY FOR PlayerAPI.mrl
} else if (status.failed()) {
    String reason = status.error();
}
```

`NetworkAPI.upload` uploads in the background and returns one `UploadStatus` per file: `percentage()` and
`displaySpeed()` follow the transfer, `cancel()` stops it and `error()` tells why one failed.

## 4.2 DOWNLOADS

> [!NOTE]
> Configurable: `network.timeout`, `maxRedirects` and `maxTextBytes`. See their comments in `config/watermedia.toml`.

Play remote files like any other link: `PlayerAPI.mrl("water://remote/<id>")`. For plain HTTP work, `NetRequest` (in
`org.watermedia.api.util`) sends one request with the same safety rules as playback:

```java
try (NetRequest request = new NetRequest("https://example.com/pack.zip").httpsOnly().maxBytes(64L * 1024 * 1024)) {
    request.send();
    request.download(Path.of("pack.zip"), expectedSha256); // THE DIGEST IS MANDATORY
}
```

- `download(path, sha256)` writes a new file, verifies it and deletes it when anything fails.
- Each request is sent once: configure it, `send()` it and read its response on one thread.
- `text()` and `inputStream()` return the body of an error status instead of throwing, and `json(type)` binds it when
  it's JSON (null for any other type); check `statusCode()`.

# 5. CODECS

> [!WARNING]
> Experimental: `decode`, `reader`, `sniff` and `encoder` can change in any update, so don't use them in your mods yet
> (see [public and internal packages](#12-public-and-internal-packages)).

> [!NOTE]
> Configurable: `codecs.maxImageSourceSize`, `codecs.png.failOnCorruptedData`, `codecs.png.useBKGDChunk`,
> `codecs.svg.maxSize` and `codecs.vp8.brokenTokens`. See their comments in `config/watermedia.toml`.

`CodecsAPI` hands out a decoder (`ImageReader`) and an encoder (`Encoder`). Images, static or animated, use our own
pure-Java codecs and need neither a startup nor _FFmpeg_; video goes through _FFmpeg_.

## 5.1 DECODERS

### Static images

PNG, JPEG, WebP, SVG and Netpbm.

```java
ImageData image = CodecsAPI.decode(bytes);                   // A byte[], A ByteBuffer OR AN InputStream
ByteBuffer pixels = image.frames()[0];                       // TIGHTLY PACKED; JPEG AND WEBP COME ROTATED TO THEIR EXIF ORIENTATION
PixelFormat format = image.format();                         // BGRA (GRAY FOR GRAYSCALE JPEGS)
int width = image.width();
int height = image.height();

ImageData rgba = CodecsAPI.decode(bytes, PixelFormat.RGBA);  // ASK FOR BGRA, RGBA OR GRAY AND IT CONVERTS
```

### Animated images

GIF, APNG and animated WebP. A reader walks the frames one by one instead of holding them all in memory.

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

### Any image

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

Some formats `sniff` recognizes as images (BMP, TIFF, AVIF...) have no decoder yet. For your own engines,
`new BcReader(buffer)` reads the compressed BC1, BC3 and BC7 blocks of a DDS texture; the built-in engines don't draw
them yet.

### Video

Video and audio decode inside the players: create one with `PlayerAPI.player` (see [playing media](#3-playing-media)).

## 5.2 ENCODERS

```java
Set<VideoCodec> videoCodecs = Container.MP4.video();         // WHAT EACH CONTAINER ACCEPTS; A TRACK THAT
Set<AudioCodec> audioCodecs = Container.MP4.audio();         // DOESN'T FIT IS REFUSED BEFORE THE FILE IS TOUCHED
```

Every encoder takes one frame per call, in the `Encoder.Video` format, with timestamps in microseconds that keep
increasing; each frame lasts until the next one. Feed each encoder from a single thread.

### Static and animated images

PNG (written as APNG when it gets more than one frame) and GIF, without _FFmpeg_. They take RGBA, BGRA, RGB or GRAY
frames.

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

### Video files

MP4, MOV, MKV and WebM through _FFmpeg_, plus Ogg for audio only. They take any raw `PixelFormat`.

```java
Encoder.Video video = new Encoder.Video(VideoCodec.H264, 1280, 720, PixelFormat.BGRA, 60); // THE FULL CONSTRUCTOR SETS QUALITY, LIKE ON IMAGES
Encoder.Audio audio = new Encoder.Audio(AudioCodec.AAC, 48_000, 2, SampleType.S16);
try (Encoder encoder = CodecsAPI.encoder(Container.MP4, Path.of("clip.mp4"), video, audio)) { // IOException WITHOUT FFMPEG
    encoder.video(frame, ptsUs);                             // EXACTLY video.frameBytes() BYTES
    encoder.audio(pcm);                                      // INTERLEAVED SAMPLES FROM TIME ZERO: PAD A LATE START WITH SILENCE
}                                                            // close() FINISHES THE FILE, WRITTEN AS <file>.part UNTIL THEN
```
