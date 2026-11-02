# CODE-MAP: WaterMedia

Reference map of this repository for agents. Read it before every task and keep it current: when a task changes
behavior, API, layout or rules, update the matching section (never leave it describing the old code).
Mapped on 2026-10-30 against version `3.0.0` (`RELEASE`); counts (files, latches, throw sites) are as of that date.
Homologation wave of 2026-11-01 (dividers, member order, nested single-owner types, no dashes) folded in.
Symbol names are exact; line numbers are omitted on purpose.
Legend: ⚠ gotcha or hard rule, ↪ reuse pointer ("to do X use Y").

Where to look (task → section → entry point):

| Task | Section | Start at |
|---|---|---|
| Start the library or add a lifecycle module | §5 | `WaterMedia.start`, nested `Module extends WaterMediaModule` |
| Add or read a setting | §14, §5 | `WaterMediaConfig` nested classes (`@Spec.Field`) |
| Decode an image or animation | §6.1 | `CodecsAPI.decode` / `CodecsAPI.reader` + `ImageReader.next()` |
| Classify unknown bytes or a MIME type | §6.1, §11 | `CodecsAPI.sniff`, `MediaType.of` / `ofExtension` |
| Write PNG/APNG/GIF or an FFmpeg container | §6.5 | `CodecsAPI.encoder` + `Encoder.Video` / `Encoder.Audio` |
| Add an image format or a chunk type | §6.2, §6.3 | `ImageReader` subclass + `CodecsAPI.reader` dispatch; `common/png` records |
| Open media and play it | §7.2, §7.1 | `PlayerAPI.mrl` → poll `MRL.status()` → `PlayerAPI.player` → `MediaPlayer` controls |
| Give a player a GPU or audio sink | §8 | `PlayerAPI.glEngine` / `vkEngine` / `alEngine`; the host implements `VKContext` |
| Synchronize players across hosts | §7.4 | `PlayerAPI.serverPlayer()`, `MediaPlayer.PlayerState.toBytes`, `MediaPlayer.sync` |
| Resolve a site URL or add a platform | §9 | `PlatformAPI.fetch` / `register`, implement `Platform`, `WebRequests.fetchJson` |
| HTTP request, download, redirects | §10 | `NetRequest`, `HttpHeaders.defaults` |
| Upload a file to the WaterMedia file server | §10 | `NetworkAPI.upload` → `UploadStatus.uri()` |
| Parse M3U, HLS or IPTV playlists | §11 | `M3UTool.parse` / `fetch` / `qualities` |
| Pixel buffers: blend, scale, orient, premultiply | §11 | `DataTool.blendOver` / `scaleArea` / `orient` / `premultiply` |
| FFmpeg ↔ engine formats and native errors | §11 | `FFTool.pixels` / `avPixelFormat` / `sampleType` / `failure` |
| Threads, digests, bounded reads, shader includes | §11 | `ThreadTool.workerFactory`, `IOTool.sha256` / `readLimited` / `shader` |
| Change the desktop app | §12 | `Screen` subclasses, `Element` widgets, draw through `Canvas` only |
| Build, natives, CI, release | §4 | `build.gradle`, `gradle/common.gradle`, `binaries/tools` |
| Write or find a test | §16 | mirrored package + `test/support` helpers |
| Check what is disabled or missing | §19 | gaps list, `TODO.md`, `PENDING.md` |

## 0. Project declarations

- **Changelog: YES.** `CHANGELOG.md` at the root (library) and `binaries/CHANGELOG.md` (companion mod), both following
  AGENTS.md → "CHANGELOG AND VERSIONING" (format, emoji order, version types, staged bump). The `changelogText`
  expression in `gradle/common.gradle` (`takeWhile { !it.isEmpty() }`) cuts the file at the first empty line, so
  versions are separated by one blank line and entries never are.
- **Writing voice** of `CHANGELOG.md`, `binaries/CHANGELOG.md`, `README.md` and `docs/`: simple, warm and direct.
  No filler, no fact repeated across entries or sections, no "replacing the old X" tails (a relicense names only the
  new license; ⚙️ API migration notes may still name what a new class replaces). Each changelog stands alone, so the
  root and Binaries changelogs may share facts. Inline `code` for identifiers, files, config keys and paths; _italics_ for mods, launchers, external
  programs, libraries and license names (_WATERMeDIA: Binaries_, _FFmpeg_, _libplacebo_, _yt-dlp_, _Sodium_,
  _TLauncher_), while OSes, graphics/audio APIs, loaders, platforms and formats stay plain. The product is WATERMeDIA
  in prose and `WaterMedia` only in code. ⚠ No em or en dashes, code comments included: use a colon, comma,
  semicolon or parentheses.
- **Special rules of this project** (general rules such as Gradle commands, language, code pattern, tests and git live
  in AGENTS.md, kept outside the repository at `A:\ai\AGENTS.md`, and are not repeated here)
  - Two Gradle builds in one tree: the root build `watermedia` and the included build `binaries/` (which includes the
    root back). Binaries sources (`binaries/src/main/java`) compile inside the core build but ship only in the Binaries
    shadow jar; the core jar excludes `org/watermedia/binaries/**`. `gradle build` builds and tests both and needs the
    host natives.
  - Public API = the packages in `public_packages` of `gradle.properties` (exact, no subpackages): `org.watermedia`,
    `api.codecs`, `api.network`, `api.platform`, `api.player`, `api.util`. `org.watermedia.tools` and
    `org.watermedia.bootstrap` are internal (Binaries uses them) and are excluded from public API documentation
    reviews. ⚠ No `module-info.java`: the jar is an automatic/unnamed module, so nothing enforces the boundary at
    runtime. `gradle javadocInventory` must report zero candidates.
  - Ledgers: `PENDING.md` (gitignored, Spanish, ids `H*`, one line deleted per resolved item) and `TODO.md` (future
    3.x features). AGENTS.md owns the rules; this file owns the map.
  - The staged bump covers both builds: the `version`/`versiontype` lines of `gradle.properties` and
    `binaries/gradle.properties` plus `CHANGELOG.md` and `binaries/CHANGELOG.md` stay staged until the bump commit.
  - Natives are reproducible artifacts: `binaries/libs/ffmpeg-<ffmpeg_version>.jar` (JavaCPP API jar of the fork),
    `binaries/src/main/resources/libs/ffmpeg-<platform>.zip` and `binaries/tools/ffmpeg-manifest.properties` are
    written only by `RepackFFmpeg.java` after the `FFmpeg Rebuild` workflow. Never edit them by hand.
  - Documentation ships in two languages, `docs/en-us.md` and `docs/es-mx.md`; every doc change touches both.
  - The standalone app (`bootstrap/app`) is a debugging utility, not API; its UI draws only through `Canvas`.
  - Every logger comes from `WaterMedia.logger(id)` (the core uses the shared `WaterMedia.LOGGER`, Binaries its own
    `WaterMedia.logger("watermedia_binaries")`), so every message is redacted by `DataTool.redact`.
  - Natives are optional in tests unless `-Prequire_natives=true` (the CI native matrix sets it); GPU and OpenAL tests
    skip without a device.

## 1. Project direction

- Keep the WaterMedia core independent of Minecraft/Mojang APIs; host adapters and mixins belong to the consumers.
- Prioritize Vulkan development, with OpenGL as secondary. Graphics backend decisions belong to the rendering layer
  (`src/main/java/org/watermedia/bootstrap/app/render/RenderSystem.java`).
- The Codecs API (`src/main/java/org/watermedia/api/codecs`) implements image and animation formats house-made
  (PNG/APNG, GIF, WebP, JPEG, Netpbm, SVG), including the short audio an animation format may carry, never through
  external codec dependencies; audio and video containers and codecs (MP4, MOV, MKV, WebM, Ogg) go through FFmpeg.
- Keep the players (`api/player`) apart from the codecs: players own timing, sync, engines and controls; codecs own
  containers, decoding and encoding, and never import `api/player`.
- Keep the PNG and GIF chunk/block serialization APIs (`api/codecs/common/png`, `api/codecs/common/gif`) whole, even
  the parts without callers; the PNG and GIF encoders and future writers use them.
- Keep the app UI vocabulary (`Element`, `Group`, `Parent`) and draw only through `Canvas`
  (`src/main/java/org/watermedia/bootstrap/app/element`).
- `org.watermedia.bootstrap` and `org.watermedia.tools` are internal: exclude them from public API documentation reviews.
- APS (`docs/animated-picture-stream-spec.md`) is only a specification example; never implement it.

## 2. Code pattern (WaterMedia specific)

The generic version of these rules lives in AGENTS.md → "Code pattern"; this is how they map onto this codebase.
- Java 17: records, sealed hierarchies (`MediaPlayer`, `GFXEngine`, `SWEngine`, `SFXEngine`, `CodecException`,
  `YtDlpPlatform`, `SvgPaint`, `M3UTool.Playlist`), arrow switches and `instanceof final` patterns.
- Every parameter and local `final`, fields accessed through `this.`, enhanced-for written `for (final T x: xs)`
  (`gradle removeSemicolonSpace` enforces the colon spacing).
- Single-statement `if` without braces, even when the condition wraps; one-line cleanup as `try { x; }` followed by
  `catch (...) { ... }`.
- Digit underscores in numeric literals and unit suffixes in constants (`_MS`, `_NS`, `_BYTES`, `_SIZE`).
- Class order: `private static final Marker IT = MarkerManager.getMarker(X.class.getSimpleName())`, constants grouped
  under uppercase section comments, static state, fields, constructor, public API, protected hooks, private helpers and
  a trailing `// UTILITY` section.
  - Section dividers (owner rule, 2026-11-01): one look everywhere, the three-line box of 81 columns
    (`// ====...====` / `// TITLE` / `// ====...====`, four-space indent). Every class but a tiny single-section one
    (under ~80 lines) is split by visibility in this order: header without a box (`IT`, constants, static state, fields,
    constructors), `PUBLIC API`, `INTERNAL API` (protected and package-private), `HELPERS` (private), `UTILITY`
    (static helpers), `NESTED TYPES` (always last). Long classes keep topic boxes (`DEMUX THREAD`, `MODE 1: STATIC
    IMAGE`, `PUBLIC API: CONTROL`) ordered inside that sequence instead of a `HELPERS` box. No one-line
    `// ==== X ====` banners and no plain `// UTILITY` comments remain. ⚠ No em or en dashes anywhere in the sources,
    comments and log messages included (0 left on 2026-11-01).
  - A marker is shared across a package only for decoder families: `VP8LosslessDecoder.IT` (used by
    `HuffmanDecoder`) and `VP8LossyDecoder.IT` (used by `VP8BoolDecoder`).
  - Lifecycle hooks are nested `public static final class Module extends WaterMediaModule` (`WaterMediaConfig`,
    `NetworkAPI`, `CodecsAPI`, `PlayerAPI`); `WaterMediaBinaries` is the only top-level module. Utility classes are
    `final` with `private X() {}` (39 of them, e.g. `DataTool`, `CodecsAPI`, `PlayerAPI`, `RenderSystem`).
- Shared counters are guarded by the owning state monitor; read them under the lock and log outside it.
- Logging
  - `api` and `tools` static-import `LOGGER` from `WaterMedia`; `bootstrap/app` uses `WaterMedia.LOGGER` qualified;
    Binaries uses `WaterMedia.logger(WaterMediaBinaries.ID)`. `WaterMedia.logger(id)` wraps Log4j and redacts every
    message, parameter and throwable through `DataTool.redact`.
  - Always pass `IT`, use `{}` parameters and the throwable as last argument, never `e.toString()`.
  - DEBUG routine progress, INFO lifecycle summaries, WARN fallbacks/anomalies, ERROR failures.
  - Never aggregate logs per time window; a repeatable anomaly logs once per instance through a `xWarned` boolean
    (30 such latches, e.g. `FFDecoder.placeboWarned`, `GLEngine.failWarned`, `NetworkServer.Running.busyWarned`;
    static decoders pass a `boolean[] warned` latch instead).
- Exceptions: `IllegalArgumentException`/`IllegalStateException` with short sentences, `CodecException` at codec parse
  boundaries (`UnsupportedFormatException` for valid-but-unsupported input), `PlatformException(NAME, msg[, cause])`
  from platform handlers, `IOException` with the operation and native reason (`FFTool.failure(op, code)`).
  - Records validate in static `read()`/`validate()`/`convert()` methods (PNG/GIF chunks, `PlayerState.read`,
    `DdsHeader.read`) because canonical constructors cannot throw `CodecException`.
- Trivially similar one-line members are grouped without blank lines; comments go inside the method body or trailing a
  constant, never between another comment and a Javadoc.
- Javadoc: one line (`/** Returns|Creates|Reads ... */`) by default; multi-line `@param`/`@return`/`@throws` only for
  complex contracts such as the `MediaPlayer` control surface; never `/** @return ... */` one-liners; none on private
  or package-private members (plain comments instead). `gradle javadocInventory` checks only `public_packages`.
- Tests: `public class XTest` with a class Javadoc starting with "Verifies", `@DisplayName` on class and methods,
  explicit static `Assertions` imports, `@Nested` groups; shared helpers in `src/test/java/org/watermedia/test/support`;
  timing assertions bracket the measured call with `System.nanoTime()` instead of fixed tolerances.
- Gradle: tasks registered with `group` and `description`, values captured outside `doLast`, `.jar` suffix
  concatenated instead of braces; constants in `gradle.properties`; `$var` not `${var}` unless `object.field` needs it.

## 3. Repository layout

| Path | Content |
|---|---|
| `src/main/java/org/watermedia/` | `WaterMedia` (entry), `WaterMediaConfig` (TOML spec), `api/` (public API), `bootstrap/` (loader entry points, launcher, app), `tools/` (internal utilities) |
| `src/main/resources/` | `META-INF/mods.toml`, `META-INF/neoforge.mods.toml`, `fabric.mod.json`, `pack.mcmeta`, `icon.png`, `pack.png`, `assets/watermedia/{shaders,font,textures,sounds,catalog}`, `META-INF/licenses/javacpp-LICENSE.txt` |
| `src/test/java/org/watermedia/` | Tests mirroring the main packages, child-JVM probes (`*Probe.java` + `*ProcessTest`), `test/support` helpers; `src/test/resources` fixtures (`pentest/` hostile corpus); `src/test/tools/GeneratePngReference.java` |
| `binaries/` | Included build of "WaterMedia: Binaries": `src/main/java/org/watermedia/binaries` (module + provisioning), `src/main/resources/libs/ffmpeg-*.zip`, `libs/ffmpeg-<ver>.jar`, `tools/` (rebuild and verification scripts), own `gradle.properties`, `CHANGELOG.md`, `.github/workflows` |
| `modloaders/` | Compile-only stubs of Forge/NeoForge/Fabric loader classes (bodies throw "Stub!"), never shipped |
| `gradle/common.gradle` | Shared Java/JUnit setup, loader range math, `processResources` expansion, jar manifest, `publishMods` validation |
| `docs/` | `en-us.md`, `es-mx.md` (API guide), `animated-picture-stream-spec.md` (APS example spec) |
| `config/` | Gitignored runtime files of the app (`watermedia.toml`, `waterconfig.toml`, `app.properties`); the repo copy of `watermedia.toml` is stale (old key names), the Java spec is the authority |
| `run/` | Working directory of the IntelliJ run configuration `WaterMedia App` (`run/config`, `run/logs` gitignored) |
| Root files | `CODE-MAP.md`, `CHANGELOG.md`, `TODO.md`, `PENDING.md` (gitignored), `WATER.md` (`water://` spec), `README.md`, `LICENSE.md` (DPS NC v1.0.1), `jitpack.yml` (JDK 25) |

Package map (`src/main/java/org/watermedia`):
- `api/codecs`: `CodecsAPI`, `ImageReader`, `ImageData`, `ImageMetadata`, `Encoder`, `Container`, `VideoCodec`,
  `AudioCodec`, `BcReader`, `ReaderFilter`, `DownscaleFilter`, `CodecException`, `UnsupportedFormatException`;
  `common/` (`Canvas`, `Limits`, `Tiff`, `Xmp`, `dds/DdsHeader`, `png/*` 25 chunk records + `PngChunk`, `IChunk`,
  `ColorType`, `Zlib`; `gif/*` 4 block records + `IChunk`);
  `readers/` (`PngReader`, `GifReader`, `JpegReader`, `WebPReader`, `NetpbmReader`, `svg/*`, `webp/*`);
  `decoders/` (`FFDecoder`, `FrameQueue`, `PacketQueue`, `Placebo`); `encoders/` (`AnimationEncoder`, `PngEncoder`,
  `GifEncoder`, `FFEncoder`).
- `api/player`: `PlayerAPI`, `MediaPlayer`, `FFMediaPlayer`, `TxMediaPlayer`, `ServerMediaPlayer`, `MRL`,
  `MasterClock`, `NetworkCache`, `GFXEngine`, `GLEngine`, `VKEngine`, `VKContext`, `HeadlessGFXEngine`, `SWEngine`,
  `AWTEngine`, `JFXEngine`, `SFXEngine`, `ALEngine`, `JSEngine`, `Colorimetry`.
- `api/platform`: `PlatformAPI`, `Platform`, `PlatformResult`, `PlatformException`, `MatureContentException`,
  `internal/WaterPlatform`, `web/*` (21 handlers + `WebRequests`).
- `api/network`: `NetworkAPI`, `NetworkServer`. `api/util`: `NetRequest`, `HttpHeaders`, `MediaType`, `MediaQuality`,
  `PixelFormat`, `SampleType`, `Metadata`, `Track`, `MathUtil`, `MathEases`.
- `bootstrap`: `AppBootstrap`, `DefensiveCheck`, `ForgeBootstrap`, `NeoBootstrap`, `FabricBootstrap`,
  `WaterMediaModule`; `bootstrap/app` (runtime), `app/render` (+`opengl`, `vulkan`), `app/element`, `app/ui`,
  `app/screen`.
- `tools`: `DataTool`, `FFTool`, `IOTool`, `JSONTool`, `M3UTool`, `ThreadTool`.

## 4. Build system, CI and release

### Gradle (root `build.gradle` + `gradle/common.gradle`, Gradle 9.6.1, Java release 17)
- Configurations: `include` (merged into the jar: `binaries/libs/ffmpeg-<ffmpeg_version>.jar` + `org.bytedeco:javacpp`),
  `jarJar` (nested under `META-INF/jarjar`: `com.github.SrRapero720:waterconfig:<waterconfig_version>`, range
  `waterconfig_range`), `compileOnlyApi` (xz, `:modloaders`, gson, joml, log4j, LWJGL 3.3.6 modules, JavaFX 21 for the
  host classifier; never packaged), `launcher` (what the standalone launcher downloads: gson, joml, log4j, LWJGL +
  natives for every `lwjgl_platforms` entry; Vulkan natives only on macOS). `runtimeClasspath` and `testImplementation`
  extend `compileOnlyApi` + `include`; tests also get the host LWJGL natives.
- Source sets: `main.java.srcDirs += binaries/src/main/java`; `main`/`test` runtime classpath +=
  `binaries/build/resources/main` (built by the Binaries `:processResources`); `tools.java.srcDirs = src/test/tools`.
- Tasks: `nestedJars` (rewraps jarJar libs with FML `GAMELIBRARY` manifest, `Automatic-Module-Name`, generated
  `fabric.mod.json`, 1980 stamps, writes `metadata.json`), `bootstrapProperties` (launcher catalog with versions, nested
  file list and SHA-256 of every `launcher` jar → `build/bootstrap/bootstrap.properties`), `jar` (Main-Class `AppBootstrap`, merges `include`,
  excludes `META-INF/versions/`, `META-INF/maven/**/*.xml`, `org/watermedia/binaries/**`), `sourcesJar` (same
  exclusion), `javadocInventory` (documentation), `removeSemicolonSpace` (formatting; enhanced-for colons of `src`, `binaries/src`
  and `binaries/tools`, never the ternaries of counted `for` lines), `test` (JUnit Platform,
  `maxHeapSize=test_max_heap`, `-XX:MaxDirectMemorySize=test_max_direct_memory`, `--enable-native-access=ALL-UNNAMED`,
  `--sun-misc-unsafe-memory-access=allow` on Java ≥ 23, properties `watermedia.test.requireNatives`,
  `org.lwjgl.system.stackSize`, `watermedia.test.classpath`), `build` depends on `binaries:build`, `check` on
  `compileToolsJava`.
- `processResources` expands every gradle property, the computed loader ranges and `resourceValues` (`jars` list) into
  `mods.toml`, `neoforge.mods.toml`, `pack.mcmeta`, `fabric.mod.json`.
- Loader ranges (`common.gradle`): each `<name>versions` property lists ascending minimum versions; `ceiling(v, line)`
  increments the component that names the line; `range(...)` yields `[low,high)` intervals (merged when adjacent;
  `release=true` makes `X.Y.Z` an exact match and resumes at `X.Y.Z+1`). Outputs `<name>range` (Maven),
  `<name>depends` and `<name>breaks` (Fabric JSON predicates). Examples: `forgerange=[40.3.10,41),...,[64,66)`,
  `neorange=[21.1.0,21.2),...`, `javarange=[17,26)`, `minecraftrange=[1.18.2],[1.19.2],...` (exact),
  `binariesrange=[3.0.0],[3.0.1,3.1)`. The Binaries build computes `watermediarange` the same way.
- `publishMods`: CurseForge (`curseforgeid`, `CURSEFORGE_TOKEN`, Minecraft + Java versions, client and server required,
  `requires('watermedia-binaries')`) and Modrinth (`modrinthid`, `MODRINTH_TOKEN`, README as description); type
  `ALPHA`/`BETA`/`STABLE` from `versiontype`; validation: `RELEASE` needs `x.y.z`, pre-releases `x.y.z.w`.
- `gradle.properties` groups: runtime (`org.gradle.*`), identity (`id`, `libname`, `group`, `version`, `versiontype`,
  `mainclass`, `github`, `modrinthid`, `curseforgeid`), loader ranges, info (`license`, `authors*`, `credits`,
  `description`), publishing (`modloaders`), plugin/toolchain pins (`osdetector_version`, `publish_version`,
  `gradle_version`, `java_test_version`, `java_compat_version`, `java_release`), library versions (`ffmpeg_version`,
  `xz_version`, `waterconfig_version/_range`, `junit_version`, `log4j_version`, `gson_version`, `lwjgl_version`,
  `joml_version`, `javafx_version`), test runtime (`test_max_heap`, `test_max_direct_memory`, `test_lwjgl_stack`,
  `require_natives`), launcher (`lwjgl_platforms`).

### Binaries build (`binaries/build.gradle`)
- Plugins `java`, `com.gradleup.shadow`, mod-publish. `main.java.srcDirs = []` (the core compiles the sources);
  `tools.java.srcDirs = ['tools']`; test classpath = `library` (`org.watermedia:watermedia`, substituted by the
  included build) + `../build/classes/java/main`.
- `shadowJar` (the only distributed jar): the core's `org/watermedia/binaries/**` classes + `org.tukaani:xz` relocated to
  `org.watermedia.libs.xz`; `jar` disabled. `processResources` adds `tools/ffmpeg-manifest.properties` under `META-INF`.
- `binaries/gradle.properties`: id `watermedia_binaries`, `watermediaversions`, FFmpeg provenance (`ffmpeg_version`
  `9.0.2-1.5.14`, `ffmpeg_variant=lgpl`, `ffmpeg_source_*` of the fork `SrRapero720/javacpp-presets` branch
  `ffmpeg-watermedia-custom`, SHA pins of FFmpeg, libxml2, OpenSSL, Vulkan headers, Maven, CMake, Meson, Microsoft JDK).

### Native rebuild pipeline (`binaries/tools`, workflow `binaries/.github/workflows/ffmpeg-rebuild.yml`)
1. `SetupJava.ps1 -Platform <p>` (pinned Microsoft JDK), `SetupNativeTools.ps1` (Maven, CMake, Meson on Linux),
   `SetupLinuxNative.ps1` (Raspberry Pi userland, linux-arm64 only).
2. `RebuildFFmpeg.ps1 -Platform <p>`: downloads and verifies sources into `build/nr`, patches `cppbuild.sh`/`pom.xml`
   (LGPL/GPL switch, no OpenCL, `ffmpeg-headers.patch`), `mvn package` of the preset, import-closure check, smoke DASH
   fixture, `VerifyFFmpeg.java` (versions, Vulkan bindings, `libplacebo`, `libkvazaar`, licenses) and `VerifyTLS.java`
   (~35 HTTPS/HLS/DASH trust and header-isolation cases) in clean JVMs → `build/rebuilt/<platform>/`.
3. Job `repack`: `java tools/RepackFFmpeg.java . build/rebuilt` verifies the five candidates and writes the zips, the
   wrapper jar and `ffmpeg-manifest.properties` (DEFLATE 9, SHA-256 per native).
- `ffmpeg-headers.patch` makes libavformat send `Authorization`, `Proxy-Authorization`, `Cookie`, `Cookie2`,
  `X-WaterMedia-Token` and a custom `Host` only to the original origin across redirects and nested HLS/DASH reads.

### CI (`.github/workflows`)
- `build-test.yml` (push/PR on `main`, `3.0.x`): job `build` on ubuntu-22.04 (`gradle build --warning-mode fail`
  after `SetupJava.ps1` and the Linux media runtime packages); job `native` matrix (linux x64/arm64, windows, macOS
  x64/arm64, plus a Java 17 "compatibility" leg) running `gradle test -Prequire_natives=true` with explicit `--tests`
  filters (FF players, decoders, encoders; the compat leg: bootstrap, network, AL/AWT/SFX/spatial, NetRequest).
- `build-release.yml` (tags): build, then `publishCurseforge` / `publishModrinth`.
- Loader metadata: `mods.toml` (Forge, `@Mod("watermedia")` scan), `neoforge.mods.toml`, `fabric.mod.json` (entry
  `org.watermedia.bootstrap.FabricBootstrap`, `jars` = nested libs, `breaks.watermedia_binaries`), `pack.mcmeta`; the
  Binaries jar carries twin files with mod id `watermedia_binaries` requiring `watermedia`; both jars mark the companion
  dependency `side="CLIENT"`.

## 5. Lifecycle, configuration and bootstrap

### `WaterMedia` (`org/watermedia/WaterMedia.java`): process singleton and module walker
- Constants `ID="watermedia"`, `NAME`, `VERSION=IOTool.jarVersion()` ("unknown" from class folders), `USER_AGENT`,
  `BROWSER_AGENT` (Chrome UA whose major grows every two weeks), `LOGGER=logger(ID)`.
- `start(String name, Path tmp, Path cwd, boolean clientSide)` (static synchronized): throws `IllegalStateException`
  if called from an owned worker thread (`ThreadTool.workerThread()`) or twice, `IllegalArgumentException` on a blank
  name. Defaults: `tmp` → `<java.io.tmpdir>/watermedia`, `cwd` → current directory. Publishes `instance` first, then
  runs the hardcoded module list on the calling thread: `WaterMediaConfig.Module` → `WaterMediaBinaries` (only when
  `org/watermedia/binaries/WaterMediaBinaries.class` is visible) → `NetworkAPI.Module` → `CodecsAPI.Module` →
  `PlayerAPI.Module`. `Exception | LinkageError` → ERROR log + `IllegalStateException("WaterMedia module X failed")`.
  ⚠ No stop/unload exists; a failed start cannot be retried in-process.
- `loaded()`, `loading()` (module name or null), `step()`, `steps()` (4 or 5): volatile, safe from any thread.
  `cwd()`, `tmp()`, `clientSide()` throw before start. `checkIsClientSideOrThrow(Class)` guards client-only ctors.
  `toId(path)` → `watermedia:path`. `logger(name)` → redacting `ExtendedLoggerWrapper`.
- Gates elsewhere: `PlayerAPI.mrl/preload`, the constructors of players with an MRL (`MediaPlayer.arm()`) and
  `NetworkAPI.upload` throw until `loaded()`; `ServerMediaPlayer` has no MRL and works before the load and on servers.

### `WaterMediaModule` (`bootstrap/WaterMediaModule.java`)
- `protected WaterMediaModule(WaterMedia)`, field `watermedia` (read `clientSide`, `tmp`, `cwd`), `abstract void
  start() throws Exception` (once, on the starting thread), `final String name()` (enclosing class name).
- Modules decide per side themselves; a part that can be missing (file server, FFmpeg, disk cache) logs and continues;
  only escaping exceptions abort the load. ↪ new module: nested `Module`, add it to the `WaterMedia` constructor list.

### `WaterMediaConfig` (`org/watermedia/WaterMediaConfig.java`): WaterConfig TOML spec `watermedia`
- Static tree `codecs`, `player`, `platforms`, `network` (instances of nested `@Spec(disableStatic=true)` classes);
  key path = TOML tables (`codecs.ffmpeg.disable`, `player.watertx.texturesBudget`). Annotations `@Spec.Field`,
  `@Comment`, `@NumberConditions`, `@StringConditions`, controls (SEEKBAR, DROPDOWN, PASSWORD, INPUT_FOLDER).
- `Module.start()`: `WaterConfig.init()`; if no spec `watermedia` exists → `registerBlocking(WaterMediaConfig.class)`;
  else waits on the existing spec monitor until loaded or FAILED, then `refresh()` (one writer per process).
- File `config/watermedia.toml` under the WaterConfig path (CWD `config/`); library `me.srrapero720.waterconfig`
  (nested jar, `requires static waterconfig`, `opens org.watermedia to waterconfig`). Full key table in §14.

### Loader entry points (`bootstrap/`)
- `ForgeBootstrap` / `NeoBootstrap` (`@Mod("watermedia")` constructors): `DefensiveCheck.run(null)` then
  `WaterMedia.start("Forge"|"NeoForge", null, null, dist.isClient())`; NeoForge resolves the dist through
  `FMLLoader.getCurrent().getDist()` (FML 10+) with a reflective fallback to the static `getDist()` (FML ≤ 9).
- `FabricBootstrap.onInitialize()`: `DefensiveCheck.run(gameDir)`; a client without mod `watermedia_binaries` throws
  `IllegalStateException("WaterMedia: Binaries is required on Minecraft clients")`; `WaterMedia.start("Fabric", null,
  gameDir, client)`.
- A failure of `WaterMedia.start` is wrapped in `RuntimeException("Failed to start WaterMedia for <loader>")`; the
  TLauncher check and the Fabric Binaries check run before it and throw their own `IllegalStateException`. Binaries
  ships empty twin bootstraps so loaders recognize its jar as a mod.
- `DefensiveCheck.run(Path gameDir)`: TLauncher refusal on hard evidence only (mod ids `tlskincape`/
  `tlauncher_custom_cape_skin`, `org.tlauncher.*` classes across all class loaders, mixin resources, thread names and
  stack frames, JVM arguments/properties matched by `SIGNATURE`, up to `PARENT_DEPTH=5` parent processes) →
  `IllegalStateException` listing the first `SHOWN_SIZE=4` items; leftovers in the game dir only WARN. ⚠ Not run by
  `AppBootstrap` or `WaterMedia.start`.

### `AppBootstrap` (`bootstrap/AppBootstrap.java`): standalone launcher (`Main-Class`)
- Launcher JVM: `prepare()` extracts `META-INF/jarjar/*` to `<tmpdir>/watermedia/libs`, downloads every `Dependency`
  listed in `bootstrap.properties` (Maven Central, `User-Agent: WaterMedia`, ≤ `MAX_DOWNLOAD_BYTES=256 MiB`, exact
  length, SHA-256 pin, atomic move; optional `lwjgl-vulkan`/`lwjgl-shaderc`), adds extension jars from the CWD named
  `watermedia_*`, `wm_*`, `waterm_*`, `wmedia_*`, then `relaunch(...)` a child `java --enable-native-access=ALL-UNNAMED
  -Dwatermedia.app=true -Dwatermedia.engine=<opengl|vulkan> ... AppBootstrap`. Exit code `RELAUNCH_EXIT=42` relaunches;
  errors show the Swing `BootstrapWindow` (Relaunch/Close) unless headless.
- Child JVM (`-Dwatermedia.app` set): `WaterMediaApp.start(...)` with the `ServiceLoader<AppBootstrap.Extension>`
  loader (`interface Extension { default String name(); void load(); }`).
- Engine choice: `-Dwatermedia.engine` > `config/app.properties` key `engine` > `vulkan`.
- ⚠ Runs before Log4j/Gson exist (plain `HttpURLConnection`, `System.out`); shares `<tmpdir>/watermedia/libs`.

### Binaries module (`binaries/src/main/java/org/watermedia/binaries`)
- `WaterMediaBinaries extends WaterMediaModule`: `ID="watermedia_binaries"`, `FFMPEG_ID="ffmpeg"`, `YTDLP_ID="yt-dlp"`,
  `BOTGUARD_ID="botguard"`. `start()`: dedicated server → nothing; client → registers `yt-dlp` and `botguard`
  directories under `tmp`, and unless `codecs.ffmpeg.disable` runs `FFmpegBinaries.start(<tmp>/ffmpeg)` eagerly; an
  `IOException` aborts the WaterMedia load. `pathOf(id)` → directory or null; `binaryDir(id)` throws when not started.
- `FFmpegBinaries` (package-private): reads `/META-INF/ffmpeg-manifest.properties` (per-platform native SHA-256,
  archive hash and size), extracts `/libs/ffmpeg-<IOTool.platformClassifier()>.zip` into a staged `install-*`
  generation under a `.install.lock` file lock, verifies CRC/size/SHA-256 per entry, publishes with
  `IOTool.publishGeneration` (atomic `current` pointer); a valid current generation is reused (re-hashed every start).
  Old generations are never deleted. `windows-arm64` and unknown platforms → `IOException`.
- `ExecutableBinary` (sealed, permits `YtDlpBinary`, `BotGuardBinary`): `executable()` (synchronized, blocking) resolves
  a system-property override (`watermedia.ytdlp.binary`, `watermedia.botguard.binary`), else verifies the cached
  release (`installed.properties`), else downloads `latest()` with `NetRequest.httpsOnly().maxBytes(512 MiB)
  .deadline(180 s)` + `download(path, sha256)`, extracts, `makeExecutable`, publishes a generation. yt-dlp: GitHub
  latest release + `SHA2-256SUMS`; a failed `latest()` keeps the cached release. BotGuard (`rustypipe-botguard`): local
  pin manifest `/META-INF/botguard-pins.properties` (fails closed), ZIP or `.tar.xz` extraction, `snapshot()` file.
- ⚠ Binaries links core members that must keep their semantics: `IOTool.{platform, platformClassifier,
  currentGeneration, publishGeneration, deleteTree, makeExecutable, sha256, requireSha256, verifySha256}`,
  `JSONTool.parse(String, Class)`, `NetRequest` (`httpsOnly`, `maxBytes`, `deadline`, `maxRedirects`, timeouts,
  `send`, `download`, `text`, `statusCode`, `close`), `WaterMedia`, `WaterMediaConfig`, `WaterMediaModule`.

## 6. Codecs API (`api/codecs`)

⚠ Experimental (owner, 2026-11-01): `CodecsAPI.decode`, `reader`, `sniff`, `encoder` and the readers, encoders and
filters they hand out are documented as "don't use in mods yet" (class Javadoc, README, both docs, changelog); the
status queries `ffmpegLoaded`, `ffmpegError` and `vulkanDecode` stay supported. Players use the codecs internally.

### 6.1 Surface
- `CodecsAPI` (final utility): metadata key constants (`METAKEY_ICC_PROFILE/EXIF/XMP/ORIENTATION/LOOP_COUNT`,
  `PNG_METAKEY_*` 15 keys, `GIF_METAKEY_APPLICATION_EXTENSION`; values are JDK types only).
  - `reader(ByteBuffer[, PixelFormat])`: dispatch by magic bytes at `position()` in order PNG, JPEG (`FF D8`),
    GIF87a/89a, `RIFF....WEBP` (limit clamped to the RIFF size), Netpbm `P1`..`P7`, SVG (`looksLikeSvg(probeText)`);
    else `UnsupportedFormatException`. The reader shares the buffer (`slice()`, no copy): keep its bytes unchanged until
    `close()`. Requested layout must be null, `BGRA`, `RGBA` or `GRAY`. A failing constructor restores the position.
  - `decode(byte[]|ByteBuffer|InputStream[, PixelFormat])` → `ImageData` (reader closed; streams read with
    `IOTool.readLimited(in, ImageReader.MAX_DECODED_BYTES, ...)`).
  - `encoder(Container, Path|OutputStream, Encoder.Video, Encoder.Audio)`: `Encoder.check` first; PNG → `PngEncoder`,
    GIF → `GifEncoder` (lazy `LazyFile`, so a refused encode never creates the file), others → `FFEncoder`.
  - `sniff(InputStream)` (consumes ≤ `PROBE_SIZE=4096` bytes) / `sniff(ByteBuffer)` (pure) → `MediaType` from magic
    numbers (RIFF, ISO BMFF brands, images, EBML/FLV/ASF/MPEG/MXF/TS, audio sync words, Ogg, WebVTT/ASS). ⚠ Many image
    formats sniff as IMAGE without a reader (BMP, TIFF, PSD, JP2, JXL, ICO, DDS, QOI, EXR, HEIF/AVIF...).
  - `ffmpegLoaded()`, `ffmpegError()`, `vulkanDecode()` (FFmpeg lists a `vulkan` hwdevice AND `FFTool.vulkanBindings()`).
  - `Module.start()`: server → return; no Binaries class → WARN; else `startFFmpeg()`: honours `codecs.ffmpeg.disable`,
    builds the preload path (`WaterMediaBinaries.pathOf("ffmpeg")` then `codecs.ffmpeg.customPath`), sets JavaCPP
    properties, logs component versions, probes hwdevice types, `FFTool.startTLS()`, publishes `ffmpegLoaded`. Failure
    → `ffmpegFailure` (boolean accessor only). ⚠ Native FFmpeg log routing (`av_log_set_callback`) is commented out
    (JVM crashes on macOS/Linux; `FFmpegLogRoutingTest` is `@Disabled`).
- `ImageReader` (abstract, `Closeable`): `MAX_DECODED_BYTES = 512 MiB` (aggregate `readAll()` budget, stream read cap,
  BC texture cap). Subclass contract: `width()`, `height()`, `decodedFormat()`, `scan()`, `hasMore()`, `decode()`
  (reader-owned reused direct buffer at position 0), `rewind()`, `dispose()`. Public: `name()`, `frameWidth()/Height()`
  (after filters), `pixelFormat()`, `scan()/repeat()/frameCount()/duration()/delays()/averageFps()/variableFrameRate()`,
  `delay()`, `metadata()` (fresh frozen snapshot), `addFilter(ReaderFilter)`, `hasNext()`, `next()` (decode → convert to
  the requested layout in a reused buffer → filters), `readAll()` (continues from the cursor, detaches copies, budget
  check), `reset()` (false if the reader cannot rewind), `close()` (idempotent, only cross-thread method; `dispose()`
  once). ⚠ `next()` buffers are reused: copy before the next call. Conversion: BGRA↔RGBA swizzle, GRAY→colour, colour→GRAY
  BT.601 `(77r+150g+29b+128)>>8`.
- `ImageData(ByteBuffer[] frames, PixelFormat format, int width, int height, long[] delays, long duration, int repeat)`
  record: one tightly packed plane per frame, `REPEAT_FOREVER=0`, `duration(boolean repeatAware)`; nested
  `Scan(frameCount, delays, duration, repeat)` with `EMPTY` and `of(delays, repeat)`.
- `ImageMetadata`: normalized fields (`title`, `description`, `authors`, `copyright`, `comments`, `creationTime`,
  `software`, `source`) + `values()` map; fluent setters, `put(key, value)` ignores null/blank/empty; `frozen()` deep
  snapshot (arrays shared), `EMPTY`; mutating a frozen instance → `UnsupportedOperationException`.
- `Encoder` (abstract): records `Video(codec, width, height, format, fps, quality, repeat)` (fps 1..1000, quality
  0..100, raw formats only) and `Audio(codec, sampleRate 4 k..384 k, channels 1..8, type, bitrate)`; `DEFAULT_QUALITY=50`;
  final `video(ByteBuffer, long ptsUs)` (exact `frameBytes()`, strictly increasing pts), `audio(ByteBuffer)` (whole
  sample frames), `close()` (→ `finish()`, throws if nothing encoded). Hooks `frame`, `samples`, `finish`.
- `Container` enum: `PNG{PNG}`, `GIF{GIF}`, decode-only `WEBP`, `JPEG`, `NETPBM`, `SVG`, `MP4{H264,HEVC,AV1,VP9 /
  AAC,OPUS,MP3,FLAC}`, `MOV{H264,HEVC,PRORES / AAC,PCM}`, `MKV{H264,HEVC,AV1,VP8,VP9,PRORES / AAC,OPUS,MP3,FLAC,PCM}`,
  `WEBM{VP8,VP9,AV1 / OPUS}`, `OGG{- / OPUS,FLAC}`.
  `VideoCodec {PNG, GIF, H264, HEVC, AV1, VP8, VP9, PRORES}`, `AudioCodec {PCM, OPUS, AAC, MP3, FLAC}`.
- `ReaderFilter.compute(Frame)` (`Frame(buffer, width, height, format)`): never rewrite `src`; return `src` or a fresh
  buffer. `DownscaleFilter(scale | w,h | w,h,scale)`: area average via `DataTool.scaleArea`, never enlarges, no-op for
  non-scalable layouts. Only caller: `TxMediaPlayer.prepareTextures`.
- `BcReader(ByteBuffer)` + `common/dds/DdsHeader`: BC1/BC3/BC7 DX10 DDS texture arrays with an optional `WMTC` delay
  footer; `blocks()`, `next()` → `Block(blocks, delayMs)`. ⚠ Not wired into `CodecsAPI.reader` and no engine accepts
  BC formats (see `TODO.md`).
- Helpers (`common/`): `Canvas.fill/restore/write` (ARGB `int[]` canvas compositing for animated readers), `Limits`
  (`MAX_DIM=16_384`, `MAX_PIXELS=1<<26`, `fits(w,h)`), `Tiff.orientation(byte[])`, `Xmp.read(metadata, bytes)`
  (string-search parser, `MAX_VALUES=64`, `MAX_VALUE_LENGTH=4096`).
- Exceptions: `CodecException extends IOException` (sealed, malformed or over budget) permits
  `UnsupportedFormatException` (valid but unsupported).

### 6.2 Readers (`api/codecs/readers`, all `public final ... extends ImageReader`, single-threaded)
`decodedFormat()` is BGRA for PNG, GIF, Netpbm and WebP; JPEG yields GRAY for one-component images unless BGRA is
requested, and SVG yields RGBA when RGBA is requested (everything else is converted by `ImageReader.next()`).
- `PngReader` (PNG 3rd edition + APNG): constructor `scan()` (frame count/delays/repeat from `acTL`/`fcTL` without CRC),
  `parseUntilFirstFrame()`, skips the default image when `fcTL` precedes it, allocates the ARGB canvas. `decode()`
  collects `IDAT`/`fdAT` until the next `fcTL`/`IEND`, inflates with a reused `Inflater` (expected size gate →
  "Compressed image data exceeds expected size"), unfilters (None/Sub/Up/Average/Paeth, Adam7), converts depths
  1/2/4/8/16 (16 → 8), applies gamma LUT (`gAMA`/`sRGB`/`cICP`, only when it differs from 2.2), optional bKGD flatten
  (`codecs.png.useBKGDChunk`), APNG dispose (NONE/BACKGROUND/PREVIOUS) and blend (SOURCE/OVER via
  `DataTool.blendOver`), writes BGRA with `Canvas.write`. CRC mismatch → `CodecException` when
  `codecs.png.failOnCorruptedData`. Caps: `MAX_ANCILLARY_CHUNKS=64`, `MAX_ANCILLARY_BYTES=4 MiB`, `MAX_TEXT_ENTRIES=256`,
  `MAX_TEXT_BYTES=4 MiB`; unknown critical chunk → `UnsupportedFormatException`; malformed ancillary chunks are skipped
  (warn once `ancillaryWarned`). Metadata: every ancillary chunk mapped to `PNG_METAKEY_*`, iTXt `XML:com.adobe.xmp` →
  `Xmp.read`. ⚠ `fcTL`/`fdAT` sequence numbers are not enforced; no frame-count cap (budget is `readAll`).
- `GifReader` (GIF87a/89a): `ScreenDescriptor`, global `ColorTable`, constructor `scan()` (delays, NETSCAPE/ANIMEXTS
  loop → repeat `n+1`, 0 = forever; comments and application extensions → metadata, caps `MAX_METADATA_RECORDS=1_024`,
  `MAX_METADATA_BYTES=8 MiB`). `hasMore()` walks blocks (`MAX_FRAMES=4_096` per pass); `decode()`: `ImageDescriptor`,
  local table, sub-blocks, LZW (2..8 bit min code, 12-bit codes, stack 4096, stale-table guard, truncation zero-fills),
  disposal of the previous frame (2 → background/transparent, 3 → restore), interlace passes, delay `cs*10` with the
  browser rule `≤10 ms → 100 ms`. Errors are all `CodecException`.
- `NetpbmReader(ByteBuffer, version[, PixelFormat])`: P1..P6 plain/binary bitmap, grey and colour (maxval ≤ 65535 scaled
  to 8 bits) and P7 PAM tuple types `BLACKANDWHITE[_ALPHA]`, `GRAYSCALE[_ALPHA]`, `RGB[_ALPHA]` (others →
  `UnsupportedFormatException`); header caps 4 KiB plain / 64 KiB PAM; one image, no metadata. Nested
  `NetpbmReader.Header.read` (record) and `NetpbmReader.Type` (magic order, never reorder); the `readers/netpbm`
  package is gone.
- `JpegReader`: baseline (SOF0), extended sequential (SOF1, 8-bit) and progressive (SOF2) Huffman, 1 or 3 components;
  lossless/hierarchical/arithmetic/12-bit/CMYK → `UnsupportedFormatException`. Whole image decoded in the constructor:
  marker loop with segment-bounded reads, `EntropyReader` (bit buffer, `FF 00` unstuffing, restart markers),
  `HuffmanTable` (8-bit LUT + slow path), progressive refinement gated per coefficient (`MAX_APPROXIMATION=13`,
  `MAX_SCANS=100`), libjpeg "islow" IDCT, nearest-neighbour chroma upsampling, YCbCr→RGB BT.601 full range or RGB
  (Adobe transform 0). Output BGRA (or GRAY for 1 component unless BGRA requested). Limits: `Limits.fits`,
  `MAX_FRAME_BYTES=512 MiB` working set computed before allocation, duplicate SOF rejected, `MAX_COMMENTS=64`,
  `MAX_ICC_BYTES=8 MiB`. Metadata: EXIF (orientation applied to pixels with `DataTool.orient`; `METAKEY_ORIENTATION`
  keeps the original value ⚠ do not rotate again), XMP, ICC (assembled from APP2 slices, not applied), comments.
- `WebPReader`: RIFF chunks `VP8 ` (key frames only), `VP8L`, `VP8X` (+`ANIM`/`ANMF`, `ALPH`, `ICCP`, `EXIF`, `XMP`;
  ids in the nested `WebPReader.FourCC`);
  `Limits.fits` before allocation, `MAX_FRAMES=4_096` per pass, `MAX_METADATA_BYTES=8 MiB`. Animation: per `ANMF`
  decode, blend (`DataTool.blendOver`) or copy into the canvas, `Canvas.write`, dispose-to-transparent after writing;
  ⚠ `ANIM` background colour is never applied and `rewind()` starts transparent. EXIF orientation applied via
  `DataTool.orient` (VP8X only). `webp/lossless/VP8LosslessDecoder.decode(data, w, h)` (transforms PREDICTOR/COLOR/
  SUBTRACT_GREEN/COLOR_INDEXING each at most once, as the nested `VP8LosslessDecoder.Transform`; colour cache 1..11
  bits, meta prefix image, `HuffmanDecoder`/`HuffmanTable` LSB-first with 7-bit lookup building the nested
  `VP8LosslessDecoder.HuffmanGroup`, `LZ77` distance map, gates `MAX_GROUPS=4_096`, `MIN_GROUP_BITS=20`,
  `MAX_FILL_PIXELS=1<<24`, `MAX_COPY_LENGTH=4_096`); `webp/lossy/VP8LossyDecoder.decode[ToBgra]` (RFC 6386 key frames:
  segmentation, `VP8BoolDecoder` with `MAX_PAD_BYTES=16` EOF latch, token partitions, intra prediction, IDCT/WHT, loop
  filter, `DataTool.yuvToBgra`; `codecs.vp8.brokenTokens` tolerates bad partition sizes); `AlphaDecoder.decode/applyAlpha`
  (raw or VP8L-compressed alpha, filters none/horizontal/vertical/gradient).
- `svg/SvgReader`: StAX parse (`SvgParser`: external entities disabled, empty resolver for DTDs, JDK entity limits,
  `MAX_ELEMENTS=100_000`, `MAX_ATTR_CHARS=1_000_000`, `MAX_DOC_CHARS=16_000_000`), `SvgDocument` (viewBox, intrinsic size,
  `preserveAspectRatio` meet vs none, linear gradients with href chains `MAX_CHAIN=16`, `MAX_STOPS=256`, radial gradient
  → average solid colour, `MAX_DEPTH=512`), shapes `rect`/`circle`/`ellipse`/`line`/`polygon`/`polyline`/`path`
  (`PathParser` full `d` grammar, arcs → cubics), `RenderState` inheritance (fill, stroke, opacity, fill-rule,
  visibility, `currentColor`), `SvgColor.parse` (hex, rgb(a), hsl(a), named), `Path.flatten` (`MAX_SEGMENTS=500_000`,
  `MAX_POINTS=500_000`), `SvgRasterizer` (`fill`/`stroke` entry points flatten a `Path` with the device transform, then
  scanline, 4 vertical sub-samples, analytic horizontal coverage, non-zero or even-odd, round joins/caps only,
  `MAX_EDGES=200_000`, `MAX_CROSSINGS=6_000_000`; the per-pixel colour source is the nested `SvgPaint.Sampler`). Output
  size capped by
  `codecs.svg.maxSize` (default 512, never upscaled). ⚠ No `text`, `use`, `clipPath`, `mask`, `filter`, `pattern`,
  `image`, CSS classes, dashes or radial gradients.

### 6.3 Chunk and block serialization (`common/png`, `common/gif`): keep whole
- `PngChunk(int length, int type, byte[] data, int crc)` record: `read(ByteBuffer[, expectedType])`,
  `read(InputStream)` (cap `MAX_CHUNK_BYTES=256 MiB`), `corrupted()`, `calculateCRC()`, `typeName()`, `isCritical()`,
  `isPublic()`, `isSafeToCopy()`, `crc32(...)`, `create(type, data)`, `write(ByteBuffer)`.
- Every chunk class (`IHDR`, `PLTE`, `IDAT`, `IEND`, `TRNS`, `GAMA`, `CHRM`, `SRGB`, `ICCP`, `ITXT`, `TEXT`, `ZTXT`,
  `BKGD`, `PHYS`, `SBIT`, `SPLT`, `HIST`, `TIME`, `EXIF`, `CICP`, `MDCV`, `CLLI`, `ACTL`, `FCTL`, `FDAT`) is a record
  with `SIGNATURE` and, except `IDAT`/`IEND` (serialization only) and `FDAT` (`convert` only), static `read(ByteBuffer)`
  + `convert(PngChunk)` validators (`TRNS`/`BKGD`/`SBIT` take the colour type, `HIST` the palette size), domain accessors (`GAMA.decodingExponent()`, `BKGD.scaleTo8Bit`, `FCTL.validate(w,h)`,
  `FCTL.delayMs()`, `CICP.isHDR()/isSRGB()`, `TIME.toLocalDateTime()`, `EXIF.orientation()`), and `png.IChunk`
  `toBytes()`/`toChunk()` for writers. `ColorType` enum (ordinal = PNG colour type), `Zlib.inflate(input, maxBytes,
  what, tolerateTruncated)` bounded inflater for ICC/zTXt/iTXt.
- GIF: records `ScreenDescriptor` (7 bytes), `ImageDescriptor` (9 bytes, `validate()`), `GraphicExtension`
  (`GCE_LABEL=0xF9`), `ColorTable(size, colors)` (opaque ARGB), all with a static `read` (`ColorTable.read(size,
  buffer)`, the others `read(ByteBuffer)`) and `gif.IChunk` `toBytes()`. Readers use `read`, encoders use `toBytes`.

### 6.4 FFmpeg decoding (`api/codecs/decoders`)
- `FFDecoder(String group, URI uri, HttpHeaders headers, boolean video, AudioChoice audio, Timeline, Host)` (null
  `audio` = no audio): one run per instance, used only by `FFMediaPlayer`. Nested `Timeline` (implemented by
  `MasterClock`: `consumeSeek`, `seekPending`, `holding`, `awaitChange`, `serial(int)`, `start`,
  `signalDemuxResumed/Finished`, `frameDurationSec`) and `Host` (implemented by `FFMediaPlayer.Callbacks`:
  `cached(URI)`, `vulkan(codecId)` → `Device(AVBufferRef ref, boolean filters)`, `audio(type, channels, rate)` → `Pcm`,
  `duration(ms)`, `opened(decoder)`, `failed(cause)`); records `Seek(targetMs, precise)`, `Device`, `Pcm`,
  `AudioChoice(List<Track> tracks, int track, int stream, String language)` (`MAIN=-1`, `AUTO=-2`) and
  `AudioStream(track, stream, name, lang)`.
  - Audio pick (at open): origin = the picked one, else (`AUTO`) the separate track whose `lang` best `match`es the
    language, else track 0 (the platform's original), else the main input; a track that fails to open falls back to the
    main input. Inside an input, static `audioIndex(ctx, wanted, language, track)`: the wanted stream while it carries
    the language, else language score ×2 + `AV_DISPOSITION_DEFAULT`, else the first. `match(preference, tag)` (package
    visible): 2 same tag, 1 same primary language, 0 otherwise, comparing BCP 47 and ISO 639-2 through `Locale` ISO3
    plus the `BIBLIOGRAPHIC` B→T table (`fre`→`fra`, ...); an HLS `"en,fr"` counts by its first. Names come from
    `title`, then `comment` (an HLS rendition's `NAME`), then the `Track`; `und` is no language. `audioStreams()` (every
    audio stream of the opened inputs, main first) and `audioStream()` are valid from `opened`. `reopenFormat()` keeps
    the stream that played (same index while it keeps its language). ⚠ Every stream nobody decodes gets
    `AVDISCARD_ALL` (`discard`), so an HLS master stops fetching the other renditions.
  - Threads (`ThreadTool.workerFactory("FFThread")`, named `<group>-demux|video|audio`); `decodeLock` guards decode
    thread creation/teardown. Queues: `PacketQueue` video 16 MiB / audio 8 MiB, `FrameQueue` video 3 / audio 9 slots.
    One static `INTERRUPT` callback (JavaCPP caps live callback instances at 10).
  - Input options: `FFTool.configureTLS`, `headers` (`HttpHeaders.toRawString`), `analyzeduration`/`probesize` from
    config, `buffer_size`, `reconnect*`, `timeout=10 s`, `rtsp_transport=tcp`, `max_delay`. Separate audio `Track` →
    second format context (`codecs.ffmpeg.trackPacketReads` per iteration; falls back to the main audio stream).
  - Demux loop: seek → pause park (`holding()`) → track reads → `av_read_frame` → route (video `tryPut` spin, audio
    `put`). EOF: `finish()` both packet queues, `signalDemuxFinished()`, park until a seek (repeat is driven by the
    player). Seek ladder `av_seek_frame(BACKWARD)` → `avformat_seek_file` → `av_seek_frame(0)` → `reopenFormat()`;
    precise seeks stop decode threads, `reset()` queues (serial++), flush codecs and `syncDrain` on the demux thread.
  - Video decode: `receive_frame` → GPU→RAM transfer when a non-Vulkan hwaccel (`HW_TRANSFER_FAIL_LIMIT=30`, perf window
    120 frames at 0.75 of the frame budget → software fallback `reinitVideoCodecSoftware()`) → optional `Placebo` →
    `queueVideo` (best-effort pts, 10 s discontinuity offset `PTS_DISCONTINUITY_SEC`). No `sws_scale` here.
  - Audio decode: `swr` conversion to the engine's `Pcm` in `engineLayout(layout, channels)` order (FFmpeg's default,
    but quad for 4 channels: OpenAL's quad is FL FR BL BR, the default 4.0 is FL FR FC BC); passthrough when interleaved,
    same type/channels and already in that order (an unspecified order counts as it); a mid-stream format change
    rebuilds the resampler, `AUDIO_SAMPLES=2048` output slots, resampler flush when the delay exceeds half a slot.
  - Codec selection: fps from `avg_frame_rate` (plausible 1..1000) else `r_frame_rate`; hardware order: Vulkan on the
    engine's device/twin (only when `Device.filters()` or the format is YUV420P/YUVJ420P/YUV420P10LE), then CUDA, QSV,
    AMF, OHCODEC, D3D11VA, D3D12VA, VIDEOTOOLBOX, VAAPI, VDPAU, DRM, MEDIACODEC, VULKAN (own device, downloads), then
    software (`libdav1d` > `libaom-av1` for AV1; threads 2/4/6/MAX by pixel count capped by `MAX_DECODE_THREADS`).
  - Public: `start`, `abort`, `stop`, `close`, `owns(Thread)`, `videoFrames()`, `audioFrames()`, `video()`, `audio()`,
    `decoding()`, `demuxDied()`, `buffered()`, `hardware()`, `vulkan()`, `filters()`, `videoContext()`, `uri()`,
    `fps()`, `hls()`.
- `FrameQueue(capacity)`: pre-allocated `Slot {AVFrame frame; ptsMs; durationMs; serial; width; height; format}` ring,
  single writer (`peekWritable()` blocks while full, `push()`), single reader (`peek()`, `peekNext()`, `next()` unrefs),
  `flush()`, `abort()`, `reset()`, `free()`. ⚠ A slot is invalid after `next()`.
- `PacketQueue(maxBytes)` (package-private): cloned `AVPacket`s with the queue `serial`; `put` blocks over the byte cap,
  `tryPut`, `get(int[] serialOut)` (caller frees), `flush()` (serial++), `finish()`, `endOfFile()`, `abort()`, `reset()`.
- `Placebo.open(frame, device, timeBase)`: `buffer → libplacebo → buffersink` graph on the decoder pool with
  `OPTIONS="format=rgba:color_primaries=bt709:color_trc=bt709"` (HDR tone-mapped to SDR RGBA); `push`, `pull`, `close`.
  ⚠ Deinterlace is deliberately off: `bwdif` makes `av_buffersink_get_frame` hang on interlaced input (PENDING H14).

### 6.5 Encoders (`api/codecs/encoders`)
- `AnimationEncoder` (package-private base of PNG/GIF): holds one frame until the next pts gives its duration; the last
  frame lasts `1_000_000/fps` µs; hooks `picture(frame, durationUs, index)` and `end(pictures)`; no audio.
- `PngEncoder(OutputStream, Video)`: RGBA/BGRA/RGB/GRAY 8-bit; APNG when > 1 picture (`acTL`, `fcTL`, `fdAT` with the
  shared sequence counter), frames cropped to the changed bounding box, adaptive filtering, `Deflater` default level,
  64 KiB IDAT/fdAT blocks, delays in the finest unit fitting 16 bits. ⚠ Whole file buffered in memory until `close()`.
- `GifEncoder(OutputStream, Video)`: GIF89a, local palette per frame (exact ≤ 256 colours or median-cut quantization on a
  5-bit histogram, Floyd-Steinberg dithering when `quality >= 50`), changed-region frames with KEEP/CLEAR disposal,
  LZW (`compress(1)`-style hash, 12-bit max), NETSCAPE loop when animated and `repeat != 1` (`repeat ≤ 65_536`),
  `MIN_DELAY_CS=2`.
- `FFEncoder(Container, Path file, OutputStream out, Video, Audio)`: MP4/MOV/MKV/WEBM/OGG; writes `<file>.part` (or a
  temp file for streams) and moves on `finish()`; `+faststart` for MP4/MOV; video encoders by preference H264
  `libx264`→`libopenh264`, HEVC `libx265`→`libkvazaar`, AV1 `libsvtav1`→`libaom-av1`, VP8 `libvpx`, VP9 `libvpx-vp9`,
  PRORES `prores_ks`; `quality` 0..100 mapped to each encoder's CRF/QP/preset; pixel conversion with `sws_scale` tagged
  BT.709 limited; time base 1/90_000, GOP 2 s; audio `pcm_s16le`, `libopus`, `aac`, `libmp3lame`, `flac` with
  `swresample` + `AVAudioFifo` chunking. Requires `CodecsAPI.ffmpegLoaded()`.

## 7. Player API (`api/player`)

### 7.1 `MediaPlayer` (abstract sealed, permits `ServerMediaPlayer`, `FFMediaPlayer`, `TxMediaPlayer`)
- Constructors are package-private: create only through `PlayerAPI`. Ctor checks `WaterMedia.checkIsClientSideOrThrow`,
  `mrl.source(index)` non-null, at least one engine. ⚠ Every concrete constructor ends with `arm()` (throws if
  `!WaterMedia.loaded()` when the player has an MRL; `ServerMediaPlayer` is exempt); the shared 50 ms `MediaSync` ticker
  (`TICK_MS=50`, single daemon thread) only ticks armed
  players.
- Status enum `WAITING, LOADING, BUFFERING, PLAYING, PAUSED, STOPPED, ENDED, ERROR` (the `PlayerState` status byte is the
  ordinal: a reorder bumps `PlayerState.VERSION`); `allows(next)` is the one transition map (pause, resume, buffering, end, stop, failure; no
  self-moves, STOPPED allows nothing; starts, seeks and restarts take their own paths); terminal for listeners: STOPPED,
  ENDED, ERROR. `onStatus(StatusListener)` (`onStatus(player, previous, current)`): duplicates ignored; ⚠ listeners are
  consumed (cleared) on a terminal transition, re-register from the callback; delivery is ordered, outside player locks,
  on the pipeline/caller thread; never call `release()` from a listener.
- Control surface (boolean controls answer "did this call change this player's own state"): `start()`,
  `startPaused()` (final, → `launch(paused, -1)`; legal from any status, restarts ENDED/STOPPED/ERROR), `pause()`,
  `resume()`, `pause(boolean)` (latched while LOADING), `stop()` (everyone lands STOPPED, time 0), `togglePlay()`,
  `seek(ms)` (clamped; from ENDED/STOPPED restarts PAUSED at the target), `seekQuick(ms)`, `skipTime(delta)`,
  `forward()`/`rewind()` (±5 s, Tx uses 1 s), `previousFrame()`/`nextFrame()` (refused while following),
  `speed(float)` in (0, 4] (needs `canSpeed()` and the sfx to accept), `repeat(boolean)`, `spatialAudio(SpatialAudio)`.
  Void setters: `volume(int 0..100)`, `mute(boolean)`, `quality(MediaQuality)` (FF switches hot, Tx on next start),
  `scale(w, h)` (upload cap per axis, 0 = none; read back with `scaleWidth()/scaleHeight()`), `lod(LodLevel)` (`MAX 100,
  CLOSE 75, NEAR 50, FAR 25, FAR_AWAY 10` %), `displayTime(ms)` (timed still), `tolerance(ms)`, `onStatus(listener)`,
  `sync(...)`, `release()` (disarm, release gfx then sfx, idempotent; ⚠ may block, never from a callback or playback
  thread). Sentinels `NO_SIZE=0`, `NO_TEXTURE=0L`, `NO_SOURCE=0`, `NO_DURATION=0L`.
- Audio: `audioLanguage(String)` / `audioLanguage()` (final, BCP 47, null = no preference and never the system locale;
  applies at the next open); `audioTracks()` (immutable `AudioTrack(track, stream, name, lang)` list: `track` =
  `AudioTrack.MAIN` (-1) or the index in `source().audioTracks()`, `stream` = FFmpeg stream index or -1 while
  `provisional()`), `audioTrack()`, `audioTrack(AudioTrack)` (hot switch; base: empty/null/false, FF overrides). Pick
  order: explicit pick > language > original/default > first. Sinks: `sinks()` and `addSink(ALEngine.Mix)` delegate to
  a spatial `ALEngine` (empty/null otherwise, IAE on a null mix).
- Queries: `status()` + `waiting()/loading()/buffering()/paused()/playing()/stopped()/ended()/error()`, `time()`,
  `duration()`, `fps()`, `live()`, `canSeek()`, `canPlay()`, `canSpeed()`, `buffered()`, `texture()` (⚠ read fresh on
  every draw on the render thread; GL texture name or `VkImageView` handle, 0 = none), `width()/height()`
  (uploaded), `sourceWidth()/sourceHeight()` (native), `video()`, `audio()`, `audioSource()`, `spatial()`,
  `mrl()`, `sourceIndex()`, `source()`, `exception()` (first failure of the run), `repeat()`, `speed()`, `volume()`,
  `mute()`, `quality()`.
- Follower engine (`sync(PlayerState|ByteBuffer)`, `following()`, `authority()`, `authorityTime()`, `drift()`,
  `tolerance(ms)` default 1 s): latest snapshot wins (no revision compare); the ticker mirrors status (start/
  startPaused/pause/resume/stop), adopts `repeat` and `speed`, and corrects time with `seekQuick(authorityTime)` when
  `|drift| > tolerance` and the 1 s `SEEK_COOLDOWN_NS` elapsed; no rate trimming; ERROR players are left alone.
- Subclass helpers: `launch(paused, seekMs)` (abstract), `holdStatus()`, `tick()`, `queueStatus(prev, next)` +
  `deliverStatus()`, `exception(Throwable)`, `resolveQuality(w, h)` (relabels the MRL's UNKNOWN bucket via
  `MRL.moveQuality`), static `scaled(native, max, percent)`, `mergeFailure`, `nowMs()` (monotonic ms).

### 7.2 `PlayerAPI` (final utility)
- `mrl(String)` (existing file relative to `WaterMedia.cwd()` → file URI, else `URI.create`; malformed → uncached
  `MRL.error`), `mrl(URI)`, `preload(URI...)`: throw `IllegalStateException` before `loaded()` or on a server.
- `player(MRL, [int sourceIndex,] Supplier<GFXEngine>, Supplier<SFXEngine>)`: null when the source is missing (FETCHING
  logs DEBUG), `MediaType.IMAGE` → `TxMediaPlayer` (sfx supplier never called), else `FFMediaPlayer` when
  `CodecsAPI.ffmpegLoaded()`, else null (ERROR "No media backend available"); an `Exception` in construction releases
  the engines and returns null, `Error`s propagate. `serverPlayer()` → `ServerMediaPlayer` (works on servers).
- Engine factories (client only): `glEngine(Thread renderThread, Executor renderEx)` (both null = calling-thread GL),
  `vkEngine(VKContext)`, `jfxEngine(Runnable onFrame)` (throws without JavaFX 13+), `awtEngine(Runnable)`,
  `headlessEngine([boolean preload])`, `alEngine([int buffers][, boolean spatial])`, `jsEngine([int bufferMs])`.
- `Module.start()` (client only): `MRL.start()` then `NetworkCache.start(tmp.resolve("cache"))` (failure logged).

### 7.3 `MRL`: cached async resolution
- Statuses `FETCHING, LOADED, EXPIRED, ERROR, BLOCKED, FORGOTTEN` (`loaded()`, `failed()`, `regenerable()`,
  `disposable()`); `status()` flips LOADED → EXPIRED lazily after `Platform.Resolution.expires()`.
- Session: `ConcurrentHashMap<URI, MRL>`, fixed pool `MRL-Loader` (`min(4, cpus)`); a sweep inside `doLoad()` forgets
  disposable entries once `player.mrlCleanupInterval` minutes passed (no timer: it runs on the next load, never while
  idle). `reload()` returns the active handle (a FORGOTTEN one
  is replaced; a reload during a fetch is deferred). `subscribe(Consumer<MRL>)` fires once on completion or failure
  (dropped afterwards); `await(timeoutMs)` polls.
- `doLoad()`: `PlatformAPI.fetch(uri)`; null → direct probe with `NetRequest.accept(ACCEPT_MEDIA)`: HTTP ≥ 400 fails,
  document content types are rejected (`text/html`, `text/css`, `text/javascript`, `application/xhtml+xml`,
  `application/(x-)javascript`, `application/ecmascript`, `application/json`, `*+json`), M3U shapes parsed with
  `M3UTool` (IPTV → one source per channel, HLS → one VIDEO source), otherwise one source typed by `sniffType`
  (`MediaType.of(contentType)` → `CodecsAPI.sniff(stream)` → `MediaType.ofExtension`). A single UNKNOWN platform source
  is re-sniffed with its headers. Qualities are bucketed per `MediaQuality.of(width, height)`, one URL per bucket
  (widest, tallest, highest fps, first). `MatureContentException` → BLOCKED, any other Throwable → ERROR.
- `MRL.Source(MediaType type, URI thumbnail, Metadata metadata, HttpHeaders headers (frozen), Map<MediaQuality,URI>
  qualities, List<Track> audioTracks, List<Track> subtitleTracks)`: `uri(MediaQuality)` (closest bucket),
  `qualityOf(URI)`. Accessors `uri()`, `status()`, `exception()`, `sources()`, `sourceCount()`, `source(int)`,
  `sourceByType(MediaType)`, `sourcesByType(MediaType)`.

### 7.4 Clocks and synchronization
- `MasterClock` (package-private, `implements FFDecoder.Timeline`, one per `FFMediaPlayer`): ffplay-style pts drift in
  seconds; `time()`/`timeMs()` lock-free; `update(ptsSec, serial)` recalibrates when BUFFERING or drift >
  `DRIFT_RECALIBRATION_SEC=0.050`; `transition`/`exchange` accept only `Status.allows(next)` moves (no table of its own),
  while `start()` (LOADING→PLAYING/PAUSED/BUFFERING), `reload()` (running→LOADING), `requestSeek`, `update`, `halt()` and
  `reset()` own the rest (STOPPED terminal until `reset()`); `halt()`, `reload()`, `start()`, `requestSeek(ms, precise)`
  (serial++, BUFFERING), `paused(boolean)`, `holding()`, `awaitChange`, `awaitNotStatus`, `speed(double)`, `fps(float)`
  (`frameDurationSec`, `skipThresholdMs` ≈ 5 frames), events drained in order to one listener after the lock is
  released.
- `ServerMediaPlayer` (`PlayerAPI.serverPlayer()`): pure `System.nanoTime` clock, no engines, allowed on servers;
  `syncDuration(ms)` (the first non-zero report wins for the lifetime; later reports are ignored and a divergence >
  `DURATION_DIVERGENCE_MS=500` warns once), `syncTime(ms)`,
  `live(boolean)`, `revision()` (AtomicInteger bumped on every change), `snapshot()` → `PlayerState`; held while
  `duration <= 0 && !live`; non-repeat → ENDED at the duration, repeat wraps with carried overshoot.
- `MediaPlayer.PlayerState(int revision, Status status, long time, long duration, float speed, boolean repeat, boolean
  live)` nested record (was the top-level `Sync`): `BYTES=27` big-endian wire form (`VERSION=3`), `toBytes()`,
  `read(ByteBuffer)` (IAE on malformed, position advanced only after a full parse). Transport is the host's; feed with
  `MediaPlayer.sync(...)`.

### 7.5 `FFMediaPlayer`: audio/video/live through `FFDecoder`
- `launch` (under `lifecycleLock`) starts one `FFThread-<n>-lifecycle` run: `clock.reset()`, LOADING, `open()` (quality
  URL, an `FFDecoder.AudioChoice` when an sfx exists: the host's `audioPick` with its language, else `AUTO` with
  `audioLanguage()`; `new FFDecoder(...)`, `decoder.start()`), then the consumption
  loop: `drainAudio` (drop stale serials, stop when more than `AUDIO_MAX_LEAD_SEC=0.5` ahead, `sfx.upload`, clock =
  audible position `pts + duration - sfx.pendingMs()`), one video frame per iteration (wait if `diff > 0.040 s`, drop if
  later than `skipThresholdMs`, adaptive render-debt drops, BUFFERING bypasses sync), `drainAudio` again, 1 ms sleep.
- Video upload: Vulkan frames sampled in place when `VKEngine.samples(frame)` (else `av_hwframe_transfer_data`),
  native planes via `gfx.upload(planes, strides)` with a 4-set rotating plane pool (aligned when `gfx.alignment() > 0`),
  or `sws_scale` (native output if supported else BGRA, `SWS_AREA` when downscaling); `gfx.format(...)` re-issued only
  on format/bits/size/colour change (refusal latches `uploadRefused`); `colorimetry(...)` maps FFmpeg tags to
  `Colorimetry` (matrix BT709/BT601/BT2020, range, siting, PQ/HLG transfer, BT.2020 primaries) with `Colorimetry.guess`
  fallback. Video drives the clock only without audio.
- Starvation → BUFFERING after `STARVATION_THRESHOLD_MS=500` (never a seek); rate monitor warns once outside 0.9..1.1;
  EOF: zero frames decoded → ERROR "emitted no frames", stills honour `displayTime`, `repeat()` → `requestSeek(0)`,
  else ENDED. `quality(q)` or `audioTrack(AudioTrack)` on a live run sets `switchRequest` → `switchSource()` (rebuild
  decoder, VOD re-seeks, LIVE restarts at the edge).
- Audio tracks: the ctor lists one provisional entry (`stream=-1`) per separate track (none without an sfx); `opened`
  rebuilds `audioTracks` from `decoder.audioStreams()` (main input's entries replaced on every open, an opened track's
  streams replace its provisional entry for the player's life) and sets `audioTrack`. `audioPick` (volatile) survives
  quality switches and restarts; a provisional pick equals the stream its origin resolved to.
- `Callbacks` (`FFDecoder.Host`): `cached(uri)` → `NetworkCache.readFile` (skips `.m3u8`/`.mpd`, gated by
  `codecs.ffmpeg.cache` and `cacheMaxResponse`), `vulkan(codecId)` → `new FFDecoder.Device(vk.hwDevice(codecId),
  vk.placebo())` when `gfx instanceof VKEngine`, `audio(...)` negotiates channels/type with `SFXEngine`
  (exact → closest; type kept if supported, else S16, else first), `opened` (HLS probe thread, fps, source size,
  `resolveQuality`, pre-`gfx.format`), `failed` → ERROR.
- `release()`: throws `IllegalStateException` from the lifecycle or decoder threads; aborts, joins outside the lock,
  frees the plane pool, `clock.halt()`, `super.release()`, delivers STOPPED last. `hwAccel()` public extra.
- `live()` = HLS probe result else "opened with unknown duration" (may flip once the probe lands).

### 7.6 `TxMediaPlayer`: still and animated images (no audio)
- `prepare(serial)` on the shared `TxPlayer-SingleFrame` pool: `openSource()` (`NetworkCache.read` whole file in RAM,
  cap `codecs.maxImageSourceSize` MB, `player.watertx.cache`; `CodecsAPI.reader(bytes[, BGRA when the engine rejects
  GRAY])`), `applyTarget()` (`scaled(source, scale, lod)`), `gfx.format(...)`, then one of three modes:
  1. Still → `showStatic` (upload once; `displayTime(ms)` arms a passive clock that ends in ENDED; never loops).
  2. Frame textures when `1 < frameCount ≤ MAX_FRAME_TEXTURES=256`, `gfx.preload()` and
     `frames × outW × outH × 4 ≤ player.watertx.texturesBudget` MB → `readAll()` (+`DownscaleFilter`),
     `gfx.preload(frames)`, passive clock resolved inside `texture()` (`frameAt` binary search → `gfx.frame(i)`); ⚠ an
     undrawn animation does not advance.
  3. Streaming (`TxPlayer-Stream` thread): prefetch queue (`PREFETCH_MAX=6`, `PREFETCH_BUDGET_BYTES=64 MiB`, pooled
     frame-sized direct buffers, `IN_FLIGHT_KEEP=2`), decode permits shared across players (`DECODE_PERMITS =
     Semaphore(max(2, baseThreads))`), hot `scale`/`lod`, seek by decoding forward from frame 0 (`reader.reset()` or
     reopen), frame stepping while paused, BUFFERING refills, repeat by reopening.
- Locks: `signals` (status writes, triggers, `prepareActive`), `clock` (passive clock pair; order `clock` → `signals`),
  `releaseLock`; `lifecycleSerial` makes stale passes unable to publish status.
- Overrides: `forward/rewind` 1 s, `canSeek()` = `playable && animated`, `fps()` from the current delay, `duration()` =
  known duration (0 for stills). `stop()` keeps the texture; `release()` throws from its own threads and after a 5 s
  wait for I/O ("release must be retried").

### 7.7 `NetworkCache` (package-private, lives in `api/player`)
- `start(Path dir)`: loads `index.dat` (`MAGIC=0x574D4943`, `VERSION=5`), one startup sweep deletes `*.part` and
  unindexed `wm_*.tmp` older than `SWEEP_GRACE_MS=60_000` (⚠ no periodic timer), publishes the `Store`.
- `read(uri, headers, accept, maxBytes, enabled)` → `CachedBytes(bytes, contentType, cached, expiresAt)`;
  `readFile(...)` → `CachedFile(path, cached, contentType)` or null for playlists (`mpegurl`, `dash+xml`), non-HTTP,
  uncacheable headers or a disabled cache; bounded fetch fails (IOException → caller streams directly) without
  `Content-Length`, over `maxBytes` or on ICY streams.
- Key = SHA-256 of URI + lowercased headers (+ accept); expiry from `Cache-Control`/`Pragma`/`Expires` else
  `DEFAULT_TTL_MS=7 days`; in-flight downloads shared per key (`fetchShared` on the cached pool `MediaCache`); access-ordered
  LRU under the store monitor evicted to `player.cacheBudget` MB after each write; index rewritten atomically on every
  mutation; store failures never fail the download (`writeWarned` once).

## 8. Engines (`api/player`)

### 8.1 `GFXEngine` (abstract sealed, permits `VKEngine`, `GLEngine`, `HeadlessGFXEngine`, `SWEngine`)
- State (volatile): `format`, `width`, `height`, `bits`, `color`, `released`, `framed`; `SENTINEL_TEXTURE=-1L` for
  handle-less backends. Public: `width()`, `height()`, `format()`, `bits()`, `released()`, `texture()`,
  `supports(PixelFormat[, int bits])` (default: raw formats at 8/10/12/16/32 bits; BCn declined by every backend),
  `preload()`, `release()` (final, test-and-set then `destroy()` once).
- Package-private driver API (single producer thread): `format(fmt, w, h[, bits[, Colorimetry]])` → `apply(...)`,
  `upload(ByteBuffer[] planes, int[] strides)` (direct buffers, strides in bytes, 0 = tight; planes stay valid until the
  second later upload returns) → `push`, `preload(ByteBuffer[])`, `frame(int)`, `alignment()`, `release(ByteBuffer)`.
  Statics `minBytes(stride, rowBytes, rows)`, `bitScale(bits)`. Order: `format` before the first `upload` and on every
  layout change; `preload(frames)` after `format`.
- ⚠ Sealed + package-private hooks: hosts cannot implement custom engines; all factories enforce the client side.

### 8.2 `GLEngine` (`PlayerAPI.glEngine(renderThread, renderEx)`)
- ⚠ The executor must run on `renderThread` and be pumped every frame, including once after `release()`; without a
  thread contract the calling thread must own the GL context. Programs belong to the context current at the first
  conversion layout; never move an engine between contexts.
- Upload path: producer `push` copies planes into a persistent mapped ring (`RING_SLOTS=4`, `ARB_buffer_storage`;
  `DROPPED` when all slots are in flight) or snapshots client buffers, stores the latest-wins `pending` and
  `Hub.schedule(this)` (one `Hub` per render thread posts a single `drainAll` task). Render thread: `Env.save` (captures
  and later restores texture bindings, PBO, unpack state, program, FBOs, VAO, viewport, blend/scissor/cull/... so host
  state caches stay truthful) → `renderSubmission` (texture sub-uploads through `nglTexSubImage2D` from ring offsets with fences, or legacy
  `NUM_PBOS=2` double buffering) → `convertToRGBA()` (fullscreen quad into the RGBA8 `managedTexture` through the
  per-context `Kit` program) → `Env.restore()` → `sweepOrphans()`.
- Conversion kinds and shaders (`opengl/`): GRAY → `gray.frag`, NV12/NV21 (P010/P016 as NV12 16-bit) → `nv.frag`,
  YUV420P/422P/444P → `yuv3.frag`, YUVA* → `yuva.frag`, YUYV/UYVY → `yuyv.frag` (`texelFetch` pairs); BGRA/RGBA/RGB
  upload directly. Uniform scheme `plane0..3`, `bitScale`, `uvSwap`, `outputWidth`, `colorimetry[4]`. A compile failure
  marks the kind in `Kit.failKinds`, `supports()` turns false and the producer pre-converts to BGRA.
- Frame-texture fast path: `preload(ByteBuffer[])` (direct BGRA/RGBA/RGB only) uploads one texture per frame
  progressively (one render task per frame), `frame(i)` selects, `texture()` returns it. The VRAM gate is in
  `TxMediaPlayer.shouldUseFrameTextures`.
- Deletion of exposed textures: `orphanTexture` zero-sizes the storage and queues the name in `ORPHANS`; `sweepOrphans`
  deletes names bound on no unit (first 32 units). `GL_CHECKS = -Dwatermedia.glchecks` enables hot-path `glGetError`.

### 8.3 `VKEngine` + `VKContext` (`PlayerAPI.vkEngine(VKContext)`)
- `VKContext` (public interface the host implements; `VulkanRenderBackend` is the reference): `vkInstance()`,
  `physicalDevice()`, `vkDevice()`, `queue()` + `queueFamily()` (⚠ must be the LAST queue created in that family),
  `queueLock()` (⚠ held by the engine around every submit/fence op; the host must hold it for its own submits and
  presents), `memoryProperties()`, `hostImportSupported()`, `minImportedHostPointerAlignment()`, `ycbcrSampler()`,
  `retire(Runnable)` (host runs destroys after its in-flight frames; must also drain them at device teardown),
  `deviceInfo()` (default null = no Vulkan Video) → `DeviceInfo(VkPhysicalDeviceFeatures2 features (chain must stay
  allocated), List<String> instanceExtensions, deviceExtensions, int[] queueCounts)`.
- Engine: `SLOTS=3` round-robin slots, each with fence, command buffer, descriptor set, RGBA8 output image and view;
  `texture()` (render thread, under `queueLock`) returns the newest completed slot's `VkImageView`; the old view stays
  exposed until a new layout completes; `WAIT_TIMEOUT_NS=5 s` wedged-GPU guard (`failed` parks the engine, release
  leaks objects with an ERROR).
- Upload routes chosen in `apply`: RGBA passthrough (BGRA/RGBA copy into the output image), compute conversion
  (`vulkan/convert.comp`, modes `MODE_GRAY=0, NV12=1, NV21=2, YUV3=3, YUVA=4, YUYV=5, UYVY=6, RGBA=7`, push block
  `PUSH_BYTES=96`), YCbCr sampler conversion (`vulkan/ycbcr.comp`, only 8-bit NV12/YUV420P/422P/444P SDR non-wide with
  the right format features). Planes arrive by zero-copy host-pointer import (`VK_EXT_external_memory_host`, buffer
  address/capacity multiple of `minAlign`, cached per base address; `release(ByteBuffer)` must precede freeing) or by
  per-slot persistent staging buffers. `supports`: `RGB` never, BGRA/RGBA/YUYV/UYVY 8-bit only, others need
  SAMPLED+FILTER_LINEAR+TRANSFER_DST on the R/RG plane format; `convertBroken` (shaderc failure) → only BGRA/RGBA
  process-wide.
- Hardware decode: `hwDevice(codecId)` (H264/HEVC/AV1) builds once per `Kit` an FFmpeg Vulkan device context: route A
  "this device" when the host lends spare queues (`Kit.queues`: own family minus its last queue, needs compute +
  transfer + decode operations, Vulkan 1.3, `timelineSemaphore` + `synchronization2`, `VK_KHR_video_decode_queue`);
  route B "twin" device on the same GPU (external memory/semaphore extensions win32 or fd, one queue per used family,
  `TwinFree` registered as `AVHWDeviceContext._free`); `placebo()` true when the decoding device has
  `hostQueryReset`, `runtimeDescriptorArray`, `shaderStorageImageArrayNonUniformIndexing`, `maintenance4` and a free
  graphics queue and the natives have `libplacebo`. `samples(AVFrame)` / `upload(AVFrame)` follow the
  `hwcontext_vulkan.h` protocol (`lock_frame`, timeline semaphore wait/signal, layout write-back, `unlock_frame`);
  twin frames are imported per decoder image (`importPicture`, opaque handles, CloseHandle by address on Windows) with a
  release/acquire semaphore dance; a failed twin step sets `twinBroken`. Frames the engine cannot sample are downloaded
  by the player.
- `Kit` (per `VkDevice`, refcounted): sampler, descriptor/pipeline layouts, lazily built compute pipelines, `ycbcrs`
  map, `formatFeatures` cache, `hwDevice`/`decodeOps`/`twin`. SPIR-V compiled once per process with shaderc from
  `IOTool.shader(name)`. ⚠ Not implemented: `preload`/`frame` fast path, BCn.
- Host rules: release every player before destroying device/instance (twin included), never touch FFmpeg's queues,
  never `vkDeviceWaitIdle` while players exist (use `vkQueueWaitIdle(queue)` under `queueLock`).

### 8.4 Software and headless engines
- `SWEngine` (sealed, permits `AWTEngine`, `JFXEngine`): accepts only BGRA/RGBA 8-bit (everything else is pre-converted
  by the producer), one direct BGRA surface reallocated per `apply`, `push` copies rows (RGBA swizzled) then `present()`
  and `onFrame` on the producer thread. `AWTEngine.image()` → `BufferedImage TYPE_INT_ARGB` (replaced on resize; ⚠
  unsynchronized with the EDT). `JFXEngine.image()` → `Image` (a `WritableImage` over a `PixelBuffer`; premultiplied in place with
  `DataTool.premultiply`, `Platform.runLater(updateBuffer)`), `repaint()`.
- `HeadlessGFXEngine` (`PlayerAPI.headlessEngine([preload])`): records `uploadCount()`, `activeFrame()`,
  `preloadedFrames()`, `lastUpload()` (aliases the producer buffer); the inherited `texture()` returns the sentinel after
  the first upload.
- `Colorimetry(Matrix matrix, boolean full, boolean cositedX, boolean cositedY, Transfer transfer, boolean wide)`
  (package-private record): `Matrix {BT601, BT709, BT2020}` with `kr/kb`, `Transfer {SDR, PQ, HLG}`; `guess(format,
  w, h)` (BT.709 when `w ≥ 1280 || h > 576`), `constants(format, w, h, bits)` → 16 floats (range, matrix, chroma
  siting, transfer/wide) consumed by `colorimetry.glsl` (`ycbcr()`, `display()`: PQ/HLG → linear with SDR white 203
  nits, BT.2020→709, highlight knee from 0.75 to a 1000-nit peak, BT.1886 re-encode).

### 8.5 Audio engines
- `SFXEngine` (abstract sealed, permits `ALEngine`, `JSEngine`): `MIN/MAX_SAMPLE_RATE_HZ 4_000/384_000`; records
  `ChannelSupport(channels, Set<SampleType>)` and `SpatialAudio(x, y, z, referenceDistance, maxDistance, rolloff,
  auxOnly, Environment)` with `Environment.process(source, audio)` / `reset(source)` (host sound thread, e.g. Sound
  Physics adapter). Public: `supportedChannels()`, `supportedTypes()`, `channelSupport(int)`,
  `closestChannelSupport(int)`, `source()`, `spatial()`, `spatialAudio([SpatialAudio])`, `sampleType()`, `channels()`,
  `sampleRate()`, `speed()`, `volume()`, `canSpeed()`, `released()`, `release()`. Driver API: `format(type, channels,
  rate)`, `pause()`, `play()`, `speed(float)`, `volume(float)`, `upload(ByteBuffer)` (direct, whole frames; false =
  queue full), `flush()`, `pendingMs()` (audible position = last pts + duration − pending).
- `ALEngine(buffers=DEFAULT_BUFFER_COUNT=8, spatial)`: needs the host's OpenAL context current on every mutating call
  (`ALC_EXT_thread_local_context` honoured); table mono/stereo U8/S16 (+FLT/DBL when the driver exposes them), 4/6/7/8
  channels with `AL_EXT_MCFORMATS` on a plain engine; buffer queue with duration FIFO, `AL_PITCH` speed, `pendingMs`
  via `AL_SOFT_source_latency` on the primary; AL errors throw `IllegalStateException`. No device reload or listener
  API. Every source starts dry and listener-relative (`clearSpatial`), plain ones included.
  - Sinks (spatial only): `ALEngine.Sink` (inner class: `source()`, `mix()`/`mix(Mix)`, `primary()`, `released()`,
    `spatialAudio([SpatialAudio])`, `release()`) and `ALEngine.Mix { MONO, LEFT, RIGHT }`; `sinks()` (immutable, primary
    first, empty when plain or released), `addSink(Mix)` (null when plain or released). The spatial table is mono +
    stereo: FFmpeg folds surround into stereo and `render(chunk, mix)` gives each sink one side or `(L + R) / 2` in the
    same sample type (mono decodes pass as they are). Each sink owns its source and buffer pool; the FIFO
    (`queuedDur`, `queuedPcm` copies of the chunks) is shared and popped by the primary's `reclaimProcessed`. `push`
    queues on every sink or none (a failure after the first flushes); pause/play/stop/rewind are batch `*v` calls.
    `align()` rebuilds every queue from `queuedPcm` at the primary's `AL_SAMPLE_OFFSET` (pause, drop/trim the played
    head, `clear()`, requeue; the caller resumes): used by `addSink`, `Sink.mix(Mix)` on a stereo decode, and `play()`
    when some sinks still play while others stopped. ⚠ `play()` reclaims processed buffers first (a STOPPED source
    replays its whole queue). The primary cannot be released alone; `destroy` resets every sink's environment, then
    deletes all sources.
- `JSEngine(bufferMs=DEFAULT_BUFFER_MS=300)`: Java Sound `SourceDataLine`, mono/stereo U8/S16 only, no speed, no
  native handle, non-blocking writes (`available() < len` → false), `MASTER_GAIN` volume. Kept as core API; the app uses
  OpenAL only.

## 9. Platforms API (`api/platform`)
- `Platform` interface: `name()`, `resolve(URI)` → `Resolution` | null (foreign host) | `PlatformException` (own host,
  unsupported shape via `PlatformException.unsupported(NAME, uri)` without network, or any failure incl. interrupts) |
  `MatureContentException` (when `platforms.allowMatureContent` is false); `default search(query, limit)` → empty.
  Records `Resolution(Instant expires (null = never), List<Source> sources)`, `Source(MediaType type, URI thumbnail,
  Metadata metadata, HttpHeaders headers (frozen), List<Quality> qualities, List<Track> audioTracks, List<Track>
  subtitleTracks)`, `Quality(URI uri, int width, int height, float fps)` (0/0 = unknown; ⚠ report raw sizes, `MRL`
  buckets). ⚠ `audioTracks` are separate audio inputs (one per language) listed with the original (or default) first:
  a player without a language preference plays index 0. `PlatformResult(platform, title, thumbnail, uri)` = one search
  hit (raw page URL).
- `PlatformAPI` (static registry, no bootstrap needed): `fetch(URI)` probes CUSTOM newest first, then BUILTIN newest
  first (so `YouTube` → `YtDlp` → `Newgrounds` → `DTube` → ... → `WaterPlatform`); `register(Platform)`/`unregister(Platform)`
  (permanent for the process, clears the search cache), `platforms()`. Search: `search(query[, limit][, reSearch])`
  returns at once a live read-only list filled by 4 `PlatformAPI-Search` workers (one task per platform,
  `SEARCH_TIMEOUT_MS=5_000`, per-platform limit `min(limit, platforms.searchLimit)`), typing debounce 300 ms when the
  query is ≥ 0.7 Jaro-Winkler similar to the previous one, LRU cache of 32 queries cleared every
  `platforms.searchCleanupInterval` minutes (0 = effectively no cache), `searching(query)`, `searchHistory()` (10).
  Built-in search only in YouTube, Twitch, Kick, Imgur.
- `internal/WaterPlatform` (`water://` scheme, spec `WATER.md`): `local/<path>` → `WaterMedia.cwd()` file URI (⚠ `..`
  escapes cwd), `remote/<id>` → `NetworkAPI.remoteUrl()/<id>`, `global/<id>` → `GLOBAL_SERVER`
  (`https://watermedia.srrapero720.me/`); `location(URI)` reusable translator.
- `web/WebRequests` (package-private): `fetchJson(NAME, uri|NetRequest, Class)` (status/body errors → `PlatformException`),
  `follow(URI)` (short links), `hls(playlist, headers)` (best-effort ladder via `M3UTool.qualities`),
  `qualities(playlist, variants)` (drops audio-only; fallback `Quality(playlist,0,0)`).
- Handlers (`public final class XPlatform implements Platform`, `NAME`, `IT`, hosts via `DataTool.equalsAnyIgnoreCase`
  or dot-boundary suffixes through `WebRequests.hostMatches(host, suffixes...)`, Gson-bound private records last;
  durations in seconds converted to ms unless noted):
  `YtDlpPlatform` (sealed base, hosts soundcloud/snd.sc/facebook/fb.watch/instagram; runs `yt-dlp -J` out of
  process, `PROCESS_TIMEOUT_SECONDS=120`, playlists skip bad items, age limit gate, expiry `DataTool.expiry` else 4 h /
  live 30 min; one audio `Track` per `language`, each its best `abr`/`tbr`, ranked by yt-dlp's `language_preference`
  (YouTube: 10 original, 5 default, -1 other, -10 descriptive) then bitrate, named from the head of `format_note`
  without `(default)`/`(original)`; untagged formats drop once any is tagged; audio-only sources take the top one)
  → `YouTubePlatform` (youtube.com/youtu.be, `--no-playlist` for video ids, bot-check retry with a
  BotGuard `po_token` from `rustypipe-botguard` under `botGuardLock`, search `ytsearchN:`); `TwitchPlatform` (GQL with
  the public web client id, Usher HLS, clips, VOD, live; mature classification first and fail-closed; TTL 30 min;
  search keeps live channels only, those with a `stream`, through package-private `hits(data, limit)`);
  `BiliBiliPlatform` (www/m./live./b23.tv: video BV/parts, bangumi ep/ss, live rooms; every API call
  carries a `buvid3`/`buvid4` device cookie from `x/frontend/finger/spi`, fetched once per process and dropped on a
  failed call (⚠ without it the risk control answers HTTP 412), plus `platforms.biliBiliCookie`; DASH video + first DASH
  audio as `audioTracks`; live FLV through `platform=web` (the h5 HLS edge hangs); no mature gate; live 10 min / video
  90 min); `TikTokPlatform` (page JSON, WAF proof-of-work solver, cookies per call); `BlueskyPlatform` (public XRPC,
  HLS video, images as album, labels gate, 30 min); `ImgurPlatform` (API v3 client id, albums/galleries, `nsfw` gate,
  search); `KickPlatform` (live/clips/search on `api/v2`, VODs only on `api/v1/video/<uuid>` from `/video/<uuid>` or
  `/<channel>/videos/<uuid>`; mature gates; search keeps live, unbanned channels only (`isLive`, `is_banned`)
  through package-private `hits(res, limit)`, so mature live channels still show up and block on resolve: the payload
  has no mature flag);
  `TwitterPlatform` (syndication endpoint, photos + progressive MP4s, age gate; title/description = the
  `display_text_range` slice with `&amp;`/`&lt;`/`&gt;` decoded); `DrivePlatform` (API key fallback to usercontent
  download + virus-scan form); `MedalPlatform`; `PornHubPlatform` (blocks entirely when mature content is off);
  `SendvidPlatform` (polls status up to 60 s); `OdyseePlatform` (JSON-RPC proxy `resolve` → `get` `streaming_url`,
  video/audio/image files, reposts follow `reposted_claim`, livestreams through `api.odysee.live` HLS 30 min, NSFW tag
  gate, paid claims fail; ⚠ the CDN needs the odysee.com Referer and answers transient 429s; package-private
  constructor takes the API bases for tests); `NewgroundsPlatform` (portal movies via the `/portal/video/<id>` XHR
  JSON, audio, art galleries; E/T/M/A rating gate blocks M and A; solves the site-wide NG Guard argon2id/SHA-256
  proof-of-work under `guardLock`, which clears the IP, no cookie; pure-Java argon2id + BLAKE2b);
  `DTubePlatform`; `StreamablePlatform`; `MediaFirePlatform`; `DropboxPlatform`; `VidLiiPlatform`; `LightshotPlatform`.
- ↪ expiry of signed CDN links: `DataTool.expiry(uri)`; default headers: `HttpHeaders.defaults(uri)`; safe URL parse:
  `DataTool.uri(String)`; query map: `NetRequest.query(URI)`.

## 10. Network API (`api/network`) and `NetRequest`
- `NetworkAPI`: constants `PROTOCOL_WATER="water"`, `X_WATERMEDIA_TOKEN`, `X_WATERMEDIA_FILENAME`, `UPLOAD_THREADS=4`.
  `upload(File...)`/`upload(File)` (validated up front, `IllegalStateException` before `loaded()`) → `UploadStatus`
  (`totalBytes()`, `uploadedBytes()`, `speed()`, `id()`, `uri()` = `water://remote/<id>`, `completed()`, `failed()`,
  `error()`, `percentage()`, `displaySpeed()`, `cancel()`; ⚠ a cancelled status reads as failed with "Cancelled").
  POST `<remoteUrl>/upload` with `network.token`, filename and content-type headers, fixed-length streaming in 8 KiB
  chunks; 503 → "server is busy". `remoteUrl()` validates `network.remoteUrl` (http(s), trailing slash stripped).
  `Module.start()`: upload pool, then `NetworkServer.start(port, cwd)` when `network.server` is `ALWAYS`, or `DEDICATED`
  on a non-client; failure logged, uploads keep working.
- `NetworkServer` (package-private entry points `start`, `address`, `stop`): JDK `HttpServer` bound to
  `network.serverBindAddress:serverPort` (`127.0.0.1:25572`; ⚠ a non-loopback bind with the default token refuses to
  start), storage `cwd/watermedia/files` with a `.server.lock` file lock and an `.upload-*` sweep, elastic
  `ThreadPoolExecutor` (core = `maxServerRequests`, exchange cap 3× slots → connection dropped), admission filter answers
  `503` past `maxServerRequests` active requests, per-exchange idle `Watchdog` (`serverTimeout`), `Connection: close`
  echoed for FFmpeg. `POST /upload`: 401 token, 400 filename, 411 no length, 413 over `maxUploadSize`, 507 over
  `maxStorageSize`, stores `files/<8 lowercase alnum id>/<filename>` atomically, answers the id. `GET|HEAD /<id>`: first
  regular file of the directory, `Accept-Ranges`, one `bytes=` range → 206 (416 when unsatisfiable, malformed ranges
  ignored), `Content-Disposition` on full answers. The module never calls `stop()`.
- `NetRequest` (`api/util`): one request, manual redirect walk (`network.maxRedirects`, http(s)-only hops, 303 → GET,
  cross-origin strips `Authorization`, `Proxy-Authorization`, `Cookie`, `Cookie2` and `X-WaterMedia-Token`; a `Referer`
  is derived per hop when the caller set none), timeouts from `network.timeout`, builder (`method`, `contentType`, `accept`, `referer`,
  `userAgent(WATERMEDIA|GENERIC)`, `header`, `headers`, `body(..., crossOrigin)`, `connectTimeout`, `readTimeout`,
  `maxRedirects`, `httpsOnly()`, `maxBytes(n)`, `deadline(ms)`), `send()`, response (`uri()`, `statusCode()` (200 for
  file/ftp), `contentType()`, `contentLength()`, `header`, `responseHeaders()`, `inputStream()`, `text()` (cap
  `network.maxTextBytes` or `maxBytes`, deadline), `json(Class)` (null unless a JSON content type), `download(Path,
  sha256)` (⚠ digest mandatory, `CREATE_NEW`, verified, deleted on failure)), `close()`. Statics `query(URI)`
  (decoded map, last value wins), `contentTypeOf(fileName)`, `ACCEPT_MEDIA`, `ACCEPT_JSON`, `MIMETYPE_*`.
- `HttpHeaders`: ordered, case-insensitive, multi-valued; `defaults(URI)`, `set`, `add`, `merge`, `removeAll`, `value`,
  `values`, `has`, `empty`, `count`, `entries`, `toRawString()` (FFmpeg `headers` option), `freeze()` (mutators then
  throw; `Platform.Source`/`MRL.Source` freeze theirs); `Entry.toString()` prints the name only.

## 11. Utilities (`api/util` + `tools`)

### `api/util` (public)
- `MediaType {IMAGE, VIDEO, AUDIO, SUBTITLES, UNKNOWN}`: `of(mime)` (HLS/DASH → VIDEO, subtitle mimes), `mime(ext)`,
  `ofExtension(path)` (right-to-left extensions). No byte sniffing here (that is `CodecsAPI.sniff`).
- `MediaQuality {UNKNOWN(0), LOWEST(144), LOWER(240), LOW(360), LOWISH(480), MEDIUM(720), HIGH(1080), HIGHER(1440),
  HIGHEST(2160), ULTRA_8K(4320)}`: `height()`, `of(resolution)`, `of(width, height)` (smallest known dimension),
  `closest(Set, preferred)`.
- `PixelFormat`: raw `GRAY, RGB, RGBA, BGRA, YUYV, UYVY, NV12, NV21, YUV420P, YUV422P, YUV444P, YUVA420P, YUVA422P,
  YUVA444P`, compressed `BC1(8), BC2, BC3, BC5, BC7(16 bytes/block)`; `planes()`, `blockBytes()`, `compressed()`,
  `planeWidth(plane, w)`, `planeHeight(plane, h)`, `texelBytes(plane, bits)`, `frameBytes(w, h, bits)` (exact math).
- `SampleType {U8(1), S16(2), S32(4), FLT(4), DBL(8)}` + `bytes()`; `Metadata(title, description, postedAt,
  durationMs, author)`; `Track(name, lang, uri)`.
- `MathUtil` (`minutesToMs`, `clamp` int/long/float/double, `clip255`, `displayBytes`) and `MathEases` (29 easing
  constants, `apply(start, end, t)`, 65 536-entry sine table): usable before WaterMedia starts.

### `tools` (internal, used by Binaries)
- `DataTool`: byte reads (`startsWith`, `u16/u24/u32`, `megabytes`), lenient parsing (`parseInt/Long/Double`, `uri`,
  `expiry`, `equalsIgnoreCase`, `equalsAnyIgnoreCase`, `similarity` Jaro-Winkler), `redact(String)` (links stay
  whole; masks URI passwords, JWTs, `--accessToken`, `Authorization`, cookies, secret key/values, `token:x:y`),
  `sha256(byte[])`, pixels (`yuvToBgra`, `bgraToBuffer`, `blendOver`, `premultiply`, `orient`), scaling (`scalable`,
  `scaleArea`).
- `FFTool`: `Pixels(format, bits)` record + `BGRA`, `pixels(avPixFmt)`, `avPixelFormat(PixelFormat)`, `sampleType(av)`,
  `avSampleFormat(SampleType)`, `error(code)`, `failure(op, code)` → `IOException`, `isNull`, `string`,
  `parseVersion`, `vulkanBindings()`, `bundled()` (natives from Binaries), TLS (`startTLS()` verifies the patched
  `tls_verify`/`ca_file`/`header_origin`/`protocol_opts` options and exports the JVM trust anchors to a PEM;
  `configureTLS(AVDictionary)` per input). ⚠ Touches JavaCPP classes: call only once natives exist.
- `IOTool`: `os()`, `platform()`, `platformClassifier()` (names the FFmpeg zips), `web(URI)`, `closeQuietly`,
  `readLimited(in, maxBytes, expected, timeoutMs)`, `move` (atomic), `deleteTree`, `makeExecutable`, generations
  (`publishGeneration`, `currentGeneration`), digests (`sha256(Path)`, `requireSha256`, `verifySha256`), resources
  (`jarVersion([Class])`, `jarRead`, `jarOpenFile`, `shader(name)` with recursive `#include "x"` against the shaders
  root, no cycle guard). `BUFFER_SIZE=64 KiB`.
- `JSONTool`: shared Gson, `parse(JsonElement|String, Class)`, `write`, never-throwing accessors `obj(root, keys...)`,
  `arr`, `str`, `num`, `bool`, `intOr`, `longOr`.
- `M3UTool`: `parse(raw, source)` → sealed `Playlist` (`Master(variants, renditions)`, `Media(segments, live, vod,
  ...)`, `Iptv(channels)`), `fetch(uri[, headers])`, `qualities(uri, headers)` (never throws), URL gate `resolve`
  (relative against the source, no `jar:`/`water:`, `file:` only from a `file:` playlist); records `Variant`, `Segment`,
  `Rendition`, `Channel`.
- `ThreadTool`: `thread(name, task)` (daemon, `name-N`), `factory(name, priority)`, `workerFactory(name, priority)`
  (marks owned workers; `workerThread()` lets `WaterMedia.start` refuse callbacks), `tryAcquire(Semaphore, timeout,
  unit)`, `join(Thread)` (uninterruptible, restores the flag), `sleep(ms)`, `processors()`, `baseThreads()`.

## 12. Standalone app (`bootstrap/app`, internal)

### 12.1 Runtime
- `WaterMediaApp.start(Runnable task)` (render thread): `AppConfig.register()` → `initWindow()` (GLFW, engine from
  `-Dwatermedia.engine` or `AppConfig.engine`, `RenderSystem.chooseEngine/attach`, window rebuilt for GL when Vulkan
  attach fails) → `TextRenderer` → `Assets.load` → `initShell()` (`RootScreen`, global error/exit dialogs) →
  `LoadingScreen` frames while thread `WaterMediaApp-Init` runs `WaterMedia.start("WaterMediaApp", null, cwd, true)` →
  `catalog.load()`, `initScreens()` (`home`, `mrl`, `regions`, `player`, `multimedia`, `settings`), extension SPI →
  `mainLoop()` (nested `WaterMediaApp.FramePlan {IDLE, DIRECT, CACHE, COMPOSITE}` and `WaterMediaApp.FrameLimiter`
  paced to the monitor refresh,
  `glfwWaitEventsTimeout` when idle, `GlowPulse` thread every 250 ms, `AppConfig.poll`) → `cleanup()` ends with
  `System.exit(0)`. Hot swap GL↔VK (`requestEngineSwap` → `performEngineSwap` at the top of the loop) releases every
  player, disposes text/assets, destroys the window and rebuilds everything on the new backend.
- `AppContext`: window/input state (logical `mouseX/Y` = physical / `uiScale`), services (`catalog`, `media`, `sound`,
  `assets`, `upload`, `cleanup`), `execute(Runnable)` (render-thread queue + wake), `requestRender()`,
  `releaseAsync(name, runnable)` + `awaitPlayerRelease()` (blocking teardown off the render thread), `showError(...)`.
- `AppConfig` (WaterConfig spec `app`, `config/app.properties`): `engine` (VULKAN default), `scale` (0 = auto, else 1..3
  step 0.25), `crt`, `sound`; `apply(ctx)` applies live and triggers an engine swap only when the preference changed.
- Services: `Assets` (icon, banner, duck frames via AWT ImageIO before codecs exist; glow textures), `WinFrame` (Win32
  custom frame: caption hit-test, snap, DPI reclamp), `LogUploader` (only when launched inside a `mods` folder: scans
  `latest.log`, crash reports, app log, `hs_err`; redacts with `DataTool.redact`; uploads to `api.mclo.gs`; copies a
  GitHub issue body with `SystemInfo.alerts()/summary()`), `SystemInfo`, `CacheCleaner` (`WaterMedia.tmp()/cache`),
  `ClickSound` (own OpenAL device, `duck.ogg`), `MediaSession` (selected group/MRL/source/quality/player,
  `releasePlayer()` async), `MediaCatalog` (`assets/watermedia/catalog/uris.json`, `iptv.json`; `debug` entries only when
  DEBUG logging), nested `AppContext.ErrorState`, `ScreenManager` (`register`, `navigate(name)` without a stack, mounting over an `under()` screen through
  `RootScreen.mountUnder`, `releaseMediaAll`).

### 12.2 Render stack (`app/render`)
- `RenderSystem` (static facade): `Engine {OPENGL, VULKAN}`, `chooseEngine`, `applyWindowHints`, `attach(window)`,
  `mediaEngineSupplier(Thread, Executor)` (GL or VK engine for players), `uiScale(float)`, `cleanup`, and 1:1 delegates
  to `RenderEngine` (`beginFrame/present/clear/createTexture/deleteTexture/setupOrtho/color/bindTexture/
  bindMediaTexture/clip/clearClip/lineWidth/fill*/rect*/line*/blit/glowRect/shadowRect`, tree cache
  `beginCache/raiseCache/endCache/dropCache/drawCache/cacheValid/releaseCache`). ↪ UI code never calls a backend.
- `RenderEngine`: backend-agnostic batcher (8 floats per vertex, `MAX_VERTICES=8192`, TRIANGLES/LINES batched, FAN/
  LINE_LOOP flush), logical px through `setupOrtho`, scissor and line width scaled by `uiScale`, SDF soft rects for
  glow/shadow when the backend's `supportsSoftRect()` is true, two offscreen layers for the cached tree (`LAYER_BASE`, `LAYER_TOP`),
  `bindMediaTexture(long)` sentinel for 64-bit handles.
- `RenderBackend` interface (`init`, `cleanup`, `clear`, `viewport`, `createTexture`, `deleteTexture`, `bindTexture`,
  `useProjection`, `draw(DrawMode, float[], count, textured)`, `lineWidth`, `clip`, `clearClip`, optional; its nested
  `RenderBackend.DrawMode` and `RenderBackend.TextureHandle` are the shared vocabulary of the backends and `RenderEngine`,
  `softRect`, `beginFrame/present`, layers, `bindMediaTexture`, `deviceName/Version`). `OpenGLRenderBackend` (GL 3.2
  core, `opengl/ui.*` + `sdf.*` shaders, stream VBO, FBO layers, `-Dwatermedia.gldebug`). `VulkanRenderBackend`
  (also the `VKContext`: instance 1.1..1.4, device scored by discrete GPU and host import, queue plan lending FFmpeg
  decode/compute/transfer queues and a second render-family queue, video decode extensions when available, swapchain
  FIFO, two frames in flight, shaderc-compiled `vulkan/ui.*`/`sdf.*`, push constants 68/104 bytes, `retire(Runnable)`
  deferred destruction, `queueLock` around every submit/present/wait; ⚠ no validation layers).

### 12.3 Element toolkit (`app/element`, `app/ui`)
- `Element<T>` (self-typed fluent base): sizes `MAX_PARENT=-1`/`WRAP_CONTENT=-2`, `margin`, `padding`, `gravity`,
  `weight`, decor (`background`, `border`, `radius`, `glow`, `shadow`), flags; final pipeline `measure` → `layout` →
  `draw`; hooks `onMeasure` (set `contentWidth/Height`), `onLayout`, `onDraw`, `onUpdate`, `onPress/onDrag/onRelease`;
  dispatch `dispatchClick/Hover/Press/Scroll/Key/Char`, `textInputActive()`, `clearFocus()`; `invalidate()` →
  `ctx.requestRender()`. ⚠ `ctx` is null until attached; guard text measuring.
- Containers: `Group<T>` (children, reverse-order dispatch), `Parent.column()/row()` (weights, `spacing`),
  `ParentFrame` (overlay by gravity), `ParentScroll` (+ package-private `ScrollState` momentum), `Box`.
- `Canvas` (the only draw surface): alpha/clip stacks, `fill`, `fillRound`, `stroke`, `glow`, `shadow`, gradients,
  `line`, `cube`, `image(textureId, ...)`, `mediaImage(long handle, ...)` (player frames), `icon(name, ...)`
  (`PixelIcon` masks), single-line `text*` through `TextRenderer` (AWT glyph atlas, JetBrains Mono, no SDF/wrapping),
  `live(Consumer)` + `raise()` for painters over a cached tree.
- Widgets: `Text`, `Button` (+ static `render`/`width` for hand layout), `IconButton`, `Icon`, `Image`, `Badge`,
  `KeyChip`, `StatusSquare`/`StatusText`, `SectionHead`, `ProgressBar`, `SeekBar`, `Switch`, `CheckBox`,
  `SegmentedControl`, `Stepper`, `TextField` (append-at-end editing only; paste lives in `OpenMultimediaScreen`),
  `Spinner`, `Dropdown` (ATTACH popup or DIALOG), `Dialog` (modal scrim + bands; ⚠ clicks in panel dead space reach the
  scrim unless `panel().consumeTouch(true)`), `ListView<D>` (virtualized when `rowHeight > 0`) + `ListRow`,
  `VideoSurface` (aspect-fits `ctx.media.player.texture()`). Tokens: `Theme` (spacing, heights, glow/shadow),
  `AppTheme` (colours, type ramp, `alpha(color, a)` cache), `Spacing`, `Gravity`.
- Conventions for a new widget: `public final class X extends Element<X>`, fluent setters returning `this`, never
  override `measure/layout/draw`, draw only through `Canvas`, animations `invalidate()` from `onUpdate` until done,
  act on key RELEASE for ENTER/ESC and swallow the paired release.

### 12.4 Screens (`app/screen`)
- `Screen extends Group<Screen>`: lazy `build()`, `overlay()` for dialogs (`showDialog/hideDialog`), hooks `onEnter`,
  `onExit`, `keybinds()` (⚠ return constant lists of the nested `Screen.Keybind(key, label)`), `continuous()` (⚠ true
  repaints every frame), `under()`,
  `releaseMedia()`; screens that override `dispatchKey` call `super` first and act on `GLFW_RELEASE`. `RootScreen` hosts `Background`,
  `TitleBar`, the center slot, `KeybindsBar`, global dialogs; renders/composites the tree and routes input.
- `HomeScreen` (`enum Action {OPEN_MULTIMEDIA, UPLOAD_LOGS, CLEANUP, SETTINGS, REGION_SELECTOR, MRL_SELECTOR, PLAYER,
  EXIT, BACK}` is the navigation vocabulary; catalog tiles create every group MRL with `PlayerAPI.mrl`),
  `OpenMultimediaScreen` (URL/path vs search by `loadable(text)`; metadata preview MRL; `PlatformAPI.search`
  dropdown with thumbnail players; 30 s load wait), `MRLSelectorScreen` (group list with status, filter, thumbnail
  players for visible rows via `Previews`, reload of regenerable MRLs), `RegionSelectorScreen` (IPTV regions →
  TELEVISION group), `PlayerScreen` (`VideoSurface` + `CrtOverlay` + HUD; `PlayerAPI.player(selectedMRL, index,
  mediaEngineSupplier(...), PlayerAPI::alEngine)`; keys ESC/ENTER/V/SPACE/arrows/Y/U/S/./,/N/B/L/digits; quality and
  video dialogs; ⚠ not restored after an engine swap), `SettingsScreen` + `SettingsModel` (edits every WaterConfig spec
  reflectively, autosave on `WaterMedia-ConfigSave`, `SaveMachine` states; only the App spec has a live applier),
  `LoadingScreen` (reads `WaterMedia.loading()/step()/steps()`), `TitleBar`, `Header`, `KeybindsBar`, `Background`,
  `CrtOverlay`, `Previews` (MRL status label/colour, thumbnail release through `ctx.releaseAsync`).
- Threading: all screen code on the render thread; background work via `ThreadTool.thread` returns through
  `ctx.execute`/`ctx.requestRender`; MRL callbacks may only request a render.

## 13. Threads and locks (cross-cutting)

| Owner | Thread / executor | Purpose |
|---|---|---|
| `MediaPlayer` | `MediaSync` single daemon (NORM-2), 50 ms | Follower correction of every armed player |
| `MRL.Session` | `MRL-Loader` fixed `min(4, cpus)` (NORM-1) | Async resolution |
| `NetworkCache` | `MediaCache` cached pool (NORM-1) | Shared downloads |
| `TxMediaPlayer` | `TxPlayer-SingleFrame` fixed `max(1, baseThreads)`; `TxPlayer-Stream` per animation | Image prepare; streaming decode |
| `FFMediaPlayer` | `FFThread-N` → `<group>-lifecycle`, `<group>-hls-probe` | Consumption loop; HLS live/VOD probe |
| `FFDecoder` | `<group>-demux`, `<group>-video`, `<group>-audio` | Demux and decode |
| `NetworkAPI` | `NetworkAPI-Upload` fixed 4 | Uploads |
| `NetworkServer` | `NetworkServer` elastic pool, `NetworkServer-Deadline` scheduler | Requests; idle watchdogs |
| `PlatformAPI` | `PlatformAPI-Search` scheduled pool of 4 | Search probes |
| `YtDlpPlatform` | `<tool>-stdout/-stderr` per process | Process output drains |
| App | `WaterMediaApp-Init`, `WaterMedia-GlowPulse`, `WaterMedia-PlayerRelease` (chained), `LogUploader-*`, `CacheCleaner-*`, `WaterMedia-CacheSize`, `*-LoadTimeout`, `WaterMedia-ConfigSave` | Boot, repaint, teardown, dialogs, saves |

Key locks: `MediaPlayer.tickLock` (armed/heard/TICKING) and the `statusEvents` monitor (ordered delivery);
`MasterClock` `ReentrantLock` + `Condition` (mutations; reads volatile); `FFMediaPlayer.lifecycleLock`/`releaseLock`;
`FFDecoder.decodeLock`; `FrameQueue.lock`/`PacketQueue.lock` (wait/notify); `MRL` Session monitor + `loadLock`;
`NetworkCache` store monitor; `GLEngine` `HUBS`, `KITS`, `ringLock`, hub monitor; `VKEngine` engine monitor,
`ctx.queueLock()` (shared with `VulkanRenderBackend`), `KITS`, `Kit.hwLock`, `VKEngine.class` (SPIR-V);
`TxMediaPlayer` `clock` → `signals` ordering (`signals` is taken inside `clock`, never the reverse), `releaseLock`,
`DECODE_PERMITS`; `ALEngine`/`JSEngine` engine monitor (`ALEngine.Sink` calls lock their engine);
`PlatformAPI.SEARCH_LOCK`; `YouTubePlatform.botGuardLock`; `NetworkServer.Running` monitor + storage `FileLock`;
`ExecutableBinary.class` + `.install.lock`. Atomics are limited to `AppContext.renderRequested` (CAS) and
`ServerMediaPlayer.revision` (counter), both allowed exceptions of the AGENTS.md atomics rule; everything else is
`volatile`.

Thread rules: engines are driven by one producer thread (FF lifecycle or Tx thread) and read on the render thread;
`texture()` is render-thread only; `release()` never from playback threads or listeners; OpenAL calls need the
original context current; GL needs the pumped render executor; `WaterMedia.start` never from an owned worker thread.

## 14. Configuration keys (`watermedia.toml`; defaults; where read)

| Key | Default (range) | Read by |
|---|---|---|
| `codecs.maxImageSourceSize` | 128 MB (≥ 1) | `TxMediaPlayer.openSource` (download cap) |
| `codecs.png.failOnCorruptedData` / `useBKGDChunk` | true / false | `PngReader` |
| `codecs.svg.maxSize` | 512 px (1..8192) | `SvgReader` |
| `codecs.vp8.brokenTokens` | false | `VP8LossyDecoder` |
| `codecs.ffmpeg.disable` / `customPath` | false / "" | `CodecsAPI.startFFmpeg` (startup only) |
| `codecs.ffmpeg.hardwareAccel` | true | `FFDecoder.initVideo` |
| `codecs.ffmpeg.trackPacketReads` | 3 (1..12) | `FFDecoder` demux loop |
| `codecs.ffmpeg.cache` / `cacheMaxResponse` | true / 10 MB | `FFMediaPlayer.Callbacks.cached` |
| `codecs.ffmpeg.analyzeDuration` / `probeSize` | 7000 ms (0..60000) / 10 MB | `FFDecoder.applyInputOptions` |
| `player.defaultQuality` | `HIGHER` | `MediaPlayer` field init |
| `player.mrlCleanupInterval` | 60.0 min (≥ 1) | `MRL` sweep |
| `player.cacheBudget` | 8192 MB | `NetworkCache` eviction |
| `player.watertx.cache` / `texturesBudget` | true / 32 MB (0..512; 0 = streaming) | `TxMediaPlayer` |
| `platforms.allowMatureContent` | false | mature gates in the web handlers |
| `platforms.searchCleanupInterval` / `searchLimit` | 15.0 min / 2 (1..50) | `PlatformAPI` |
| `platforms.biliBiliCookie` | "" (password) | `BiliBiliPlatform` |
| `network.server` | `OFF` (`DEDICATED`, `ALWAYS`) | `NetworkAPI.Module` (startup) |
| `network.serverBindAddress` / `serverPort` | `127.0.0.1` / 25572 | `NetworkServer.start` (startup) |
| `network.maxServerRequests` / `serverTimeout` | 8 (1..256) / 30000 ms | `NetworkServer` (startup) |
| `network.maxStorageSize` / `maxUploadSize` | 1024 MB (≥ 1) / 8 MB (0 = unlimited) | `NetworkServer` (startup) |
| `network.remoteUrl` | `http://localhost:25572/` | `NetworkAPI.remoteUrl` (per use) |
| `network.token` | `watermedia_default_token_change_it` | server start (refuses the default off loopback) and every upload |
| `network.timeout` / `maxRedirects` / `maxTextBytes` | 15000 ms / 10 (0..50) / 16 MiB | `NetRequest` defaults, uploads, yt-dlp socket timeout |

App (`config/app.properties`): `engine` (`vulkan` default), `scale` (0 auto), `crt` (true), `sound` (true).
System properties: `watermedia.engine`, `watermedia.app` (child JVM flag), `watermedia.glchecks`, `watermedia.gldebug`,
`watermedia.ytdlp.binary`, `watermedia.botguard.binary`, tests `watermedia.test.requireNatives`,
`watermedia.test.classpath`.

## 15. Logging, errors and hardening
- Redaction is universal (`WaterMedia.logger` → `DataTool.redact` on message, parameters and stack traces); tests
  `LoggerRedactionTest`. Links are never censored (owner decision 2026-11-01), only secrets inside them. Native FFmpeg logs are not routed (disabled block, PENDING H9).
- Error policy by layer: codecs throw `CodecException`/`UnsupportedFormatException` (279 + 16 throw sites), platforms
  `PlatformException` (112), players/engines `IllegalStateException` for lifecycle misuse and `IllegalArgumentException`
  for bad input; engines log and drop bad frames instead of throwing (except `ALEngine` on AL errors); status failures
  surface through `MediaPlayer.exception()` and ERROR status.
- Hardening: decoder budgets (`ImageReader.MAX_DECODED_BYTES` 512 MiB, `Limits`, per-reader caps), allocation after
  validation, bounded inflate, XXE closed, hostile corpus `src/test/resources/pentest/*` (`PentestTest`, generator
  `MaliciousImages`), bounded downloads with mandatory SHA-256 (`NetRequest.download`, provisioning), HTTPS-only
  provisioning, cross-origin header stripping (Java and the FFmpeg patch), file-server token/size/storage limits,
  symlink refusal (`NOFOLLOW_LINKS`), TLauncher refusal, `DEFAULT_NETWORK_TOKEN` guard off loopback.

## 16. Tests (`src/test`)
- Layout mirrors the main packages (112 files, 15 of them support helpers). Root: `ApiGuideExampleTest` (runs the public-only `ApiGuideExample`),
  `BootstrapProcessTest` and `ServiceLifecycleProcessTest` (child JVMs through `MediaBootstrap.runProbe`),
  `LoggerRedactionTest`. Probes and scenarios: `BootstrapProbe` (`server`, `client`, `native`, `native-failure`,
  `optional-failure`, `tls` via `TLSLifecycleProbe.run`, `tls-empty`), `WithoutBinariesProbe` (`server-no-binaries`,
  `client-no-binaries`), `ServiceLifecycleProbe` (`platform`, `uploads`, `evicted`, `failed-probe`), `AppConfigProbe`
  (`defaults`, `shared`, `malformed`, `override`, launched by `AppConfigTest`), `NetworkServerTest.main`
  (`storage-lock`). `PlatformSmokeTest` is a manual network harness, not a JUnit test.
- Test classes by area: codecs `CodecsApiContractTest`, `ImageDataTest`, `ImageMetadataTest`, `MediaTypeSniffTest`,
  `ScanTest`, `PentestTest` (hostile corpus), `BcReaderTest`, `DdsHeaderTest`, PNG/GIF `ChunkContractTest`,
  `FFDecoderTest`, `QueueLifecycleTest`, `FFEncoderTest`, `GifEncoderTest`, `PngEncoderTest`, readers `GifTest`,
  `JpegTest`, `NetpbmTest`, `PngTest`, `WebpTest`, `WebpMalformedTest`, `SvgTest`, `AlphaDecoderTest`,
  `HuffmanTableTest`, `VP8LosslessDecoderTest`, `FFmpegLogRoutingTest` (disabled); network `NetworkServerTest`,
  `NetworkServerGateTest`, `UploadStatusTest`; platform `PlatformApiTest`, `PlatformSearchTest`, `PlatformClaimTest`,
  `WaterPlatformTest`, offline handler tests `BiliBiliPlatformTest`, `KickPlatformTest`, `NewgroundsPlatformTest`,
  `OdyseePlatformTest` (loopback proxy through `LocalHttp`), `TwitchPlatformTest` (search hits), `TwitterPlatformTest`,
  `YtDlpPlatformTest` (dubbed JSON offline); player `MediaPlayerBaseTest`, `MasterClockTest`, `MrlTest`, `NetworkCacheTest`, `PlayerStateTest`,
  `ServerMediaPlayerTest`, `FollowerClockTest`, `FollowerPlayerStateTest`, `StatusDeliveryTest`, `FFMediaPlayerTest`,
  `FFControlContractTest`, `FFAudioTracksTest` (pick, language, hot switch), `ColorimetryTest`, `Tx*Test` (animated, control contract, display time, frame step, frame
  textures, release, scaling, static image), engines `GFXEngineTest`, `GLEngineTest`, `VKEngineTest`,
  `HeadlessGFXEngineTest`, `AWTEngineTest`, `JFXEngineTest`, `SFXEngineTest`, `ALCapabilitiesTest`, `ALSinkTest`, `SpatialAudioTest`;
  util `HttpHeadersTest`, `NetRequestTest`, `MediaTypeTest`, `MediaQualityTest`, `PixelFormatTest`, `MathUtilTest`,
  `MathEasesTest`; bootstrap `AppBootstrapDependenciesTest`, `DefensiveCheckTest`, app `AppConfigTest`,
  `FramePlanTest`, `CanvasLiveTest`, `ListRowTest`, `ListViewInteractionTest`, `SpinnerTest`, `TextFieldTest`,
  `RenderEngineBatchTest`, `RenderEngineCacheTest`, `VulkanRenderBackendTest`; tools `DataToolTest`, `IOToolTest`,
  `JSONToolTest`, `M3UToolTest`, `ThreadToolTest`.
- Support helpers (`org.watermedia.test.support`): `Fixtures` (resource paths and constants `MP4_H264`, `PNG_STATIC`,
  `GIF_ANIMATED`, ...; `readAll`, `fileUri`, `wav` (plain, or WAVE_FORMAT_EXTENSIBLE with a channel mask), `tone`,
  `level`, `dubbed` (tagged multi-stream Matroska), `segment` (HLS-ready Matroska), `webm`), `MediaBootstrap` (`client()`, `ffmpegAvailable()` one shared
  bootstrap in a temp dir, `runProbe(type, dir, scenario, omitBindings)`), `PlayerWait` (`awaitStatus`, `awaitLoaded`,
  `awaitCondition`), `LocalHttp` (loopback `HttpServer`, `respond`, `respondRange`, `expiresIn`), `Origin` (range/
  endless/held origins), `LogCapture` (TRACE capture of a named logger), `OpenALContext` (skips without OpenAL),
  `YuvFrames` (planes and expected colours incl. PQ/HLG), `ImageBytes` (PNG/RIFF builders, bit writer), `PamImage`,
  `PixelDiff`, `FrameReplay` (frame hashes across two `reset()`), `DdsFixture`, `RecordingBackend` (GPU-less
  `RenderBackend`), `MaliciousImages` (pentest corpus generator, not a test).
- Fixtures: `gif/`, `jpeg/` (+`raw/*.pam` references), `png/` (+`raw/`), `webp/{animated,lossless,lossy}` (+`raw/`),
  `netpbm/`, `svg/` (+`png/` renders), `mp4/fibonaccisongh264.mp4`, `m3u/iptv_sample.m3u8`, `pentest/{dds,gif,jpeg,
  netpbm,png,svg,webp}`, `log4j2.xml` (console, `watermedia` DEBUG). `src/test/tools/GeneratePngReference.java`
  regenerates PNG references with ImageIO.
- Gates: natives optional unless `watermedia.test.requireNatives`; GPU tests need a real GL/VK context (LWJGL stack
  `test_lwjgl_stack`); `VKEngineTest.Decoded.playerDecodesOnTheDevice` is skipped with the Khronos validation layer
  installed (run with `VK_LOADER_LAYERS_DISABLE=~all~`); symlink tests skip without the Windows privilege;
  `FFmpegLogRoutingTest` is `@Disabled`. CI native matrix filters are listed in §4.
- Clock-dependent cases that a slow host can stall retry up to 3 times with 5 s pauses and rethrow the last failure
  (owner rule): `PlatformSearchTest.testTypingDelay`, `FFControlContractTest.Loading.speedBeforeStart` (2x rate window,
  which failed once on the macOS Intel runner). Bracketed system-clock checks (`ServerMediaPlayerTest`,
  `FollowerClockTest`) need none.
- Binaries tests (`binaries/src/test/java`): `NativeProvisionTest` (checksums, generations, interrupted installs,
  overrides, BotGuard archives), `RepackToolTest` (runs `RepackFFmpeg.java` in a child JVM).

## 17. Resources and assets
- Shaders `assets/watermedia/shaders/`: `colorimetry.glsl` (shared library, included), `opengl/convert.vert` +
  `gray|nv|yuv3|yuva|yuyv.frag` (`GLEngine`, GLSL 150 core), `opengl/ui.*`, `opengl/sdf.*` (`OpenGLRenderBackend`),
  `vulkan/convert.comp`, `vulkan/ycbcr.comp` (`VKEngine`, GLSL 450, shaderc at runtime), `vulkan/ui.*`, `vulkan/sdf.*`
  (`VulkanRenderBackend`). No precompiled SPIR-V; `#include` resolves against the shaders root via `IOTool.shader`.
- `font/jetbrainsmono-{reg,bld}.ttf` (`TextRenderer`), `textures/banner.png`, `textures/duck/00..07.png`, `sounds/duck.ogg`
  (`Assets`, `ClickSound`), `catalog/uris.json` (groups of `{name, uris[]}`), `catalog/iptv.json` (`{generatedAt,
  channels[]}`) (`MediaCatalog`), root `icon.png`/`pack.png`, `META-INF/licenses/javacpp-LICENSE.txt`.
- Binaries resources: `libs/ffmpeg-{windows,linux,linux-arm64,macos,macos-arm64}.zip`, `META-INF/botguard-pins.properties`,
  `META-INF/ffmpeg-manifest.properties` (from `tools/`), 11 license texts under `META-INF/licenses/`, loader metadata.
- IntelliJ run configuration `WaterMedia App`: main `org.watermedia.bootstrap.AppBootstrap`, working dir `run/`,
  `--enable-native-access=ALL-UNNAMED`, alternative JRE path (GraalVM 25), shortened classpath.

## 18. Documentation map
- `docs/en-us.md` / `docs/es-mx.md`: API guide (startup and ownership, runtime requirements and configuration table,
  public/internal packages, MRL resolution, player creation and controls, HTTPS trust, engines, spatial audio + Sound
  Physics adapter, images and DDS, encoding, synchronized playback + snapshot format, examples). ⚠ es-mx mirrors en-us
  line for line (same sections, lists, tables and byte-identical code blocks); edit both together.
- `README.md`: landing page (projects, donations, basic usage, engines, Vulkan contract incl. Minecraft 26.x,
  available APIs, Binaries, license). `WATER.md`: `water://` spec. `docs/animated-picture-stream-spec.md`: APS example
  (never implement). `binaries/tools/NATIVE_REBUILD.md`: native rebuild, repack, reviewed CVEs.
- `TODO.md`: BCn uploads in GL/VK, HDR tone mapping from metadata and on software engines, real version in IDE runs,
  Vulkan frame-texture fast path. `PENDING.md`: host validation ledger (H1..H15, Spanish).
- Known doc drift: PENDING H5 names a `WaterMedia.stop()` that does not exist.

## 19. Known gaps and disabled code
- Disabled: native FFmpeg log routing (`CodecsAPI.startFFmpeg`, `FFmpegLogRoutingTest`), libplacebo deinterlace
  (`Placebo.OPTIONS`), `@SuppressWarnings("deprecation")` on `VKEngine.twinStep` (`lock_queue`).
- Not implemented (by design, tracked in `TODO.md`): BC texture sampling in any engine and DDS dispatch in
  `CodecsAPI.reader`; VK `preload`/`frame` fast path; HDR tone mapping on software engines.
- Codec limits: JPEG 12-bit/CMYK/arithmetic/lossless; SVG text/use/clip/mask/filter/radial; WebP ANIM background and
  VP8 scaling bits; PNG 16-bit output and ICC application; GIF LZW min code 1.
- Platform notes: mature gates only where the service exposes a flag; `WaterPlatform.local` allows `..`; Sendvid/yt-dlp
  resolves can hold a loader thread for 60/120 s; Twitch persisted-query hashes rotate.
- App notes: no Vulkan validation layers; `PlayerScreen` not restored after a hot swap; `TextField` has no caret
  movement/selection; `HomeScreen.Action.SETTINGS` navigates only with DEBUG logging (`WaterMediaApp.navigateAction`);
  stale `config/watermedia.toml` in the repo.
- Library notes: `NetworkCache` sweeps `.part` files once per start (no timer); `GLEngine.drainOne` clears `pending`
  without a lock (possible lost frame, unverified); `AppBootstrap` libs dir is shared per tmpdir; FFmpeg generations are
  never pruned.
