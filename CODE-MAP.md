<!-- CODE-MAP V2 -->
# CODE-MAP: WaterMedia

> NOTE: WATERMEDIA IS NOW ON RELEASE CHANNEL, WHICH MEANS NO BREAKING CHANGES WOULD BE ALLOWED ON PUBLIC API, EXCEPT EXPERIMENTAL APIS
> 
> CURRENT EXPERIMENTAL APIS: CodecsAPI

## NOTEPAD

Quick notes, pending tasks and reminders of the owner and the agents. The owner manages it.
- 2026-11-27: `INFORME.md` (untracked, Spanish) holds the seek and buffering investigation with the rework of
  `FFDecoder`, `FFMediaPlayer` and the follower of `MediaPlayer`. Phases F0 to F4 are in (track seek flag, input
  options, follower landing and aim, then generations, floors, a reader per input, landings, buffering by the least
  buffered stream, the follower's guide and catch-up). F5, the FFmpeg patch that keeps an HLS segment's connection
  across googlevideo's intermittent redirect, is in the natives recipe (`binaries/tools/ffmpeg-http-keepalive.patch`)
  but the natives are not rebuilt yet. It carries the backport of upstream b66d2aca0f (a connection reused for another
  host sent it the credentials), a fix n9.0.2 lacks.

## SCRAPPED IDEAS

Ideas that were discarded, with the reason.
- Trimming the playback rate so a follower converges on its session: it reads as the video randomly running slow, so a
  jump past `MediaPlayer.tolerance()` corrects it instead.

Reference map of this repository for agents. Read it before every task and keep it current: when a task changes
behavior, API, layout or rules, update the matching section (never leave it describing the old code).
Mapped on 2026-10-30 against version `3.0.0` (`RELEASE`); counts (files, latches, throw sites) are as of that date.
Homologation wave of 2026-11-01 (dividers, member order, nested single-owner types, no dashes), the app upload-logs
revamp of 2026-11-16 (issue title and description, `TextArea`, clipboard helpers, config copies) and the error codes
and file server port check wave of 2026-11-16 (`wm_err` registry, startup check of a fixed `serverPort`) and the site
lists wave of 2026-11-17 (`player.whitelist`, `player.blacklist`, `WhitelistException`, `BlacklistException`) and the
download wave of 2026-11-22 (`FFTranscoder`, `MediaDownloader`, `DownloadDialog`, `ChoiceGrid`, `Container.MP3`/`M4A`)
and the working directory fix of 2026-11-22 (`AppEnvironment`: a jar in `mods` runs from the instance root, the app
points WaterConfig at its home) and the demux fix of 2026-11-23 (no blocking packet put) and the seek rework of
2026-11-27 (seek generations and floors, one reader per input, landings, buffering by the least buffered stream)
folded in.
Symbol names are exact; line numbers are omitted on purpose.
Legend: ⚠ gotcha or hard rule, ↪ reuse pointer ("to do X use Y").

Where to look (task → section → entry point):

| Task | Section | Start at |
|---|---|---|
| Start the library or add a lifecycle module | §5 | `WaterMedia.start`, `X extends WaterMedia.Module` |
| Add or read a setting | §14, §5 | `WaterMediaConfig` nested classes (`@Spec.Field`) |
| Decode an image or animation | §6.1 | `CodecsAPI.decode` / `CodecsAPI.reader` + `ImageReader.next()` |
| Classify unknown bytes or a MIME type | §6.1, §11 | `CodecsAPI.sniff`, `MediaType.of` / `ofExtension` |
| Write PNG/APNG/GIF or an FFmpeg container | §6.5 | `CodecsAPI.encoder` + `Encoder.Video` / `Encoder.Audio` |
| Add an image format or a chunk type | §6.2, §6.3 | `ImageReader` subclass + `CodecsAPI.reader` dispatch; `common/png` records |
| Open media and play it | §7.2, §7.1 | `PlayerAPI.mrl` → poll `MRL.status()` → `PlayerAPI.player` → `MediaPlayer` controls |
| Give a player a GPU or audio sink | §8 | `PlayerAPI.glEngine` / `vkEngine` / `alEngine`; the host implements `VKContext` |
| Synchronize players across hosts | §7.4 | `PlayerAPI.serverPlayer()`, `MediaPlayer.PlayerState.toBytes`, `MediaPlayer.sync` |
| Resolve a site URL or add a platform | §9 | `PlatformAPI.fetch` / `register`, implement `Platform`, `WebRequests.fetchJson` |
| Restrict which sites play | §7.1, §14 | `MediaPlayer.checkSite`, `player.whitelist` / `player.blacklist` |
| HTTP request, download, redirects | §10 | `NetRequest`, `HttpHeaders.defaults` |
| Upload a file to the WaterMedia file server | §10 | `NetworkAPI.upload` → `UploadStatus.uri()` |
| Parse M3U, HLS or IPTV playlists | §11 | `M3UTool.parse` / `fetch` / `qualities` |
| Pixel buffers: blend, scale, orient, premultiply | §11 | `DataTool.blendOver` / `scaleArea` / `orient` / `premultiply` |
| FFmpeg ↔ engine formats and native errors | §11 | `FFTool.pixels` / `avPixelFormat` / `sampleType` / `failure` |
| Threads, digests, bounded reads, shader includes | §11 | `ThreadTool.workerFactory`, `IOTool.sha256` / `readLimited` / `shader` |
| Change the desktop app | §12 | `BaseScreen` subclasses, `Element` widgets, draw through `Canvas` only |
| Build, natives, CI, release | §4 | `build.gradle`, `gradle/common.gradle`, `binaries/tools` |
| Copy or re-encode a media into a file | §6.5, §12.1 | `FFTranscoder.probe` + `new FFTranscoder(Job, listener).run()`; the app's `MediaDownloader` |
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
  - Documentation is `docs/DOCS-EN-US.md` (the source) plus `DOCS-ES-MX.md`, `DOCS-ES-AR.md`, `DOCS-PT-BR.md`,
    `DOCS-RU-RU.md` and `DOCS-ZH-CN.md`, mirrored block for block; every doc change touches all six. ⚠ The GitHub
    wiki is a copy of these files and never gets edited on its own: sync it in the same task (procedure in §18).
  - Docs prose wraps at 120 columns (CJK characters count as two) so it reads in an editor: paragraphs, list items
    (continuation indented to the item text) and quotes (continuation keeps `> `). Headings, table rows and code
    blocks stay one line each; links and code spans never split. Chinese breaks at a space, or after full-width
    punctuation (，。；：、) only when a line has no space to break at. `check-translations.ps1` warns about longer
    lines; `scripts/ai/wrap-docs.ps1 -Out <scratch> [-Wiki]` reflows every paragraph holding one into the scratch
    folder (copy the results with Write), and leaves paragraphs that already fit alone. ⚠ The owner's hand-wrapped
    paragraph under `0.2 CONFIGURATION` in `DOCS-EN-US.md` (121 and 124 columns) stays as written (owner 2026-11-05):
    its warning is expected, and a wrap-docs reflow of it is never copied.
  - The standalone app (`bootstrap/app`) is a debugging utility, not API; its UI draws only through `Canvas`.
  - Every logger comes from `WaterMedia.logger(id)` (the core uses the shared `WaterMedia.LOGGER`, Binaries its own
    `WaterMedia.logger("watermedia_binaries")`), so every message is redacted by `DataTool.redact`.
  - Natives are optional in tests unless `-Prequire_natives=true` (the CI native matrix sets it); GPU and OpenAL tests
    skip without a device. ⚠ Except the child-JVM `BootstrapProcessTest` scenarios `native`, `tls` and `tls-empty`:
    they always need the host's bundled FFmpeg zip and fail on a platform without one (e.g. windows-arm64).

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
    IMAGE`, `PUBLIC API: FFMPEG`) ordered inside that sequence instead of a `HELPERS` box. No one-line
    `// ==== X ====` banners and no plain `// UTILITY` comments remain. ⚠ No em or en dashes anywhere in the sources,
    comments and log messages included (0 left on 2026-11-01).
    ⚠ `PUBLIC API` boxes belong only to the `public_packages` (owner rule, 2026-11-05). `api.codecs` is experimental
    public API (breaking changes skip the version schema) through `CodecsAPI`, `ImageReader` (the decoder) and `Encoder`
    (the encoder); its subpackages are internal. Internal code (`tools`, `bootstrap/**`, `api.platform.web`,
    `api.codecs.*`, Binaries) and tests drop the plain box, so public members follow the header directly; topic boxes
    keep only the topic (`PIXELS`, `NATIVE TLS`, `WRITER SIDE (DECODE THREAD)`), and a private section repeating a
    public topic becomes `HELPERS: X` (`OpenGLRenderBackend`, `VulkanRenderBackend`).
  - A marker is shared across a package only for decoder families: `VP8LosslessDecoder.IT` (used by
    `HuffmanDecoder`) and `VP8LossyDecoder.IT` (used by `VP8BoolDecoder`).
  - Modules are the classes themselves: `WaterMediaConfig`, `WaterMediaBinaries`, `NetworkAPI`, `CodecsAPI` and
    `PlayerAPI` extend `WaterMedia.Module`, guard a public constructor with `private static final AtomicBoolean
    LOADED` (`compareAndSet(false, true)` or a coded ISE "Failed to load X, it is already loaded"; Binaries keeps an
    uncoded "X is already loaded") and override `protected start(...)` in their `INTERNAL API` box. Utility classes are
    `final` with `private X() {}` (e.g. `DataTool`, `PlatformAPI`, `RenderSystem`).
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
- Error messages (owner rule, 2026-11-16): every exception or error built, `LOGGER.error` and `ThreadTool.safeCall`
  message of `src/main/java` reads `DataTool.errCode(WaterMedia.ERR_X) + "Failed to <verb> <object>"`, then
  optionally ` due to <cause>`, `: <values>` and `, <hint>` (`ERR_SERVER_STOP_BUSY` prints `(wm_err:0720) Failed to
  stop the file server due to active file uploads, try again!`). Inside `WaterMedia.java` the constant goes
  unqualified, inside `DataTool` the call is `errCode(WaterMedia.ERR_X)`; never a numeric literal.
  - One constant per site, never shared: a new site declares a new `ERR_*` in the block of its class (§15).
  - Names are `ERR_` + the class prefix + the cause in a few words (`ERR_SERVER_NO_TOKEN`). Prefixes are fixed per
    class; read them from the class block (`ERR_CORE_` `WaterMedia`, `ERR_DATA_` `DataTool`, `ERR_REQUEST_`
    `NetRequest`, `ERR_NETWORK_` `NetworkAPI`, `ERR_SERVER_` `NetworkServer`, `ERR_PLAYER_` `MediaPlayer`,
    `ERR_<PLATFORM>_` per web handler with shared shapes `_RESOLVE`, `_PAGE_STATUS`, `_MATURE`, `ERR_PNG_<CHUNK>_`
    for the chunk classes with `_TYPE`, `_LENGTH`, `ERR_LAUNCHER_` `AppBootstrap`, `ERR_APP_<AREA>_` for the app).
    A `_LOG` suffix marks some `LOGGER.error` constants, such as the log beside a throw of the same event
    (`ERR_CORE_LOAD_MODULE_LOG` next to `ERR_CORE_LOAD_MODULE`); most log constants carry none.
  - `PlatformException(NAME, msg)` keeps its platform prefix, so the code follows it; `PlatformException.unsupported`
    is one site inside the factory; `safeCall` only forwards its caller's coded message; `LOGGER.warn/info/debug`
    carry no code.
- Trivially similar one-line members are grouped without blank lines; comments go inside the method body or trailing a
  constant, never between another comment and a Javadoc.
- Javadoc: one line (`/** Returns|Creates|Reads ... */`) by default; multi-line `@param`/`@return`/`@throws` only for
  complex contracts such as the `MediaPlayer` control surface; never `/** @return ... */` one-liners; none on private
  or package-private members (plain comments instead). `gradle javadocInventory` checks only `public_packages`.
- Tests: test methods carry no `test` prefix and `@Nested` groups no `Tests` suffix; a class with a single topic
  stays flat; boxes are `INTERNAL API` (hooks and probe `main`s), `UTILITY` and `NESTED TYPES`.
  `public class XTest` with a class Javadoc starting with "Verifies", `@DisplayName` on class and methods,
  explicit static `Assertions` imports, `@Nested` groups; shared helpers in `src/test/java/org/watermedia/test/support`;
  timing assertions bracket the measured call with `System.nanoTime()` instead of fixed tolerances.
- Gradle: tasks registered with `group` and `description`, values captured outside `doLast`, `.jar` suffix
  concatenated instead of braces; constants in `gradle.properties`; `$var` not `${var}` unless `object.field` needs it.

## 3. Repository layout

| Path | Content |
|---|---|
| `src/main/java/org/watermedia/` | `WaterMedia` (entry), `WaterMediaConfig` (TOML spec), `api/` (public API), `bootstrap/` (loader entry points, launcher, app), `tools/` (internal utilities) |
| `src/main/resources/` | `META-INF/mods.toml`, `META-INF/neoforge.mods.toml`, `fabric.mod.json`, `pack.mcmeta`, `icon.png`, `pack.png`, `assets/watermedia/{shaders,font,textures,sounds,catalog}`, `META-INF/licenses/{javacpp,jetbrainsmono}-LICENSE.txt` |
| `src/test/java/org/watermedia/` | Tests mirroring the main packages, child-JVM probes (`*Probe.java`, started by the `*ProcessTest` classes and `AppConfigTest`, plus the `main` of `NetworkServerTest` and `NetworkAPITest`), `test/support` helpers; `src/test/resources` fixtures (`pentest/` hostile corpus); `src/test/tools/GeneratePngReference.java` and `GenerateJpegReference.java` |
| `binaries/` | Included build of "WaterMedia: Binaries": `src/main/java/org/watermedia/binaries` (module + provisioning), `src/main/resources/libs/ffmpeg-*.zip`, `libs/ffmpeg-<ver>.jar`, `tools/` (rebuild and verification scripts), own `gradle.properties`, `CHANGELOG.md`, `.github/workflows`, `docs/assets` (OS icons of its README) |
| `modloaders/` | Compile-only stubs of Forge/NeoForge/Fabric loader classes (bodies throw "Stub!"), never shipped |
| `gradle/common.gradle` | Shared Java/JUnit setup, loader range math, `processResources` expansion, jar manifest, `publishMods` validation, store description sync |
| `docs/` | `DOCS-EN-US.md` (API guide) and its five translations `DOCS-<LANG>.md` (see §18), `BLUEPRINTS.md` (the guide's outline), `animated-picture-stream-spec.md` (APS example spec), `assets/` (README icons, see §18) |
| `scripts/ai/` | Agent check scripts (PowerShell 7, exit code 1 on failure): `check-translations.ps1` (every `DOCS-<LANG>.md` against `DOCS-EN-US.md`), `check-wiki.ps1` (the `/documentation/` wiki clone against the docs), `wrap-docs.ps1` (reflows docs prose at 120 columns into a scratch folder, `-Wiki` gives the pages the same breaks), see §0 and §18 |
| `config/` | Gitignored runtime files of the app (`watermedia.toml`, `waterconfig.toml`, `app.properties`); the repo copy of `watermedia.toml` is stale (old key names), the Java spec is the authority |
| `run/` | Working directory of the IntelliJ run configuration `WaterMedia App` (`run/config`, `run/logs` gitignored) |
| Root files | `CODE-MAP.md`, `CHANGELOG.md`, `TODO.md`, `PENDING.md` (gitignored), `WATER.md` (`water://` spec), `README.md`, `LICENSE.md` (DPS NC v1.0.1), `jitpack.yml` (JDK 25, custom `install`) |

Package map (`src/main/java/org/watermedia`):
- `api/codecs`: `CodecsAPI`, `ImageReader`, `ImageData`, `ImageMetadata`, `Encoder`, `Container`, `VideoCodec`,
  `AudioCodec`, `BcReader`, `ReaderFilter`, `DownscaleFilter`, `CodecException`, `UnsupportedFormatException`;
  `common/` (`Canvas`, `Limits`, `Tiff`, `Xmp`, `dds/DdsHeader`, `png/*` 25 chunk records + `PngChunk`, `IChunk`,
  `ColorType`, `Zlib`; `gif/*` 4 block records + `IChunk`);
  `readers/` (`PngReader`, `GifReader`, `JpegReader`, `WebPReader`, `NetpbmReader`, `svg/*`, `webp/*`);
  `decoders/` (`FFDecoder`, `FrameQueue`, `PacketQueue`, `Placebo`); `encoders/` (`AnimationEncoder`, `PngEncoder`,
  `GifEncoder`, `FFEncoder`, `FFTranscoder`).
- `api/player`: `PlayerAPI`, `MediaPlayer`, `FFMediaPlayer`, `TxMediaPlayer`, `ServerMediaPlayer`, `MRL`,
  `MasterClock`, `NetworkCache`, `GFXEngine`, `GLEngine`, `VKEngine`, `VKContext`, `HeadlessGFXEngine`, `SWEngine`,
  `AWTEngine`, `JFXEngine`, `SFXEngine`, `ALEngine`, `JSEngine`, `Colorimetry`, `WhitelistException`,
  `BlacklistException`.
- `api/platform`: `PlatformAPI`, `Platform`, `PlatformResult`, `PlatformException`, `MatureContentException`,
  `internal/WaterPlatform`, `web/*` (21 handlers + `WebRequests`).
- `api/network`: `NetworkAPI`, `NetworkServer`. `api/util`: `NetRequest`, `HttpHeaders`, `MediaType`, `MediaQuality`,
  `PixelFormat`, `SampleType`, `Metadata`, `Track`, `MathUtil`, `MathEases`.
- `bootstrap`: `AppBootstrap`, `DefensiveCheck`, `ForgeBootstrap`, `NeoBootstrap`, `FabricBootstrap`; `bootstrap/app` (runtime), `app/render` (+`opengl`, `vulkan`), `app/element`, `app/ui`,
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
  `<name>depends` and `<name>breaks` (Fabric JSON predicates). Examples: `forgerange=[40.3.10,41),...,[64,67)`,
  `neorange=[21.1.0,21.2),...`, `neofmlrange=[4,5),[10,13)`, `javarange=[17,26)`,
  `minecraftrange=[1.18.2],[1.19.2],...` (exact), `binariesrange=[3.0.1],[3.0.2,3.1)`. Forge 66 and NeoForge 26.3.0.x
  (FML 12) are MC 26.3. The Binaries build computes `watermediarange` the same way.
  ⚠ Both companion minimums are 3.0.1: that version moved the base class Binaries extends (`WaterMedia.Module`), so a
  3.0.0 half fails to link (`NoClassDefFoundError`, watermedia#278). Raise both `binariesversions` and the Binaries
  `watermediaversions` whenever a release changes a member the other half links (list in §5 "Binaries module").
- `openranges=true` (Binaries only) turns every game, loader and Java interval into one `[low, null)` span from its
  oldest minimum: Maven `[40.3.10,)`, Fabric `>=1.18.2`, no gap above. Its loader lists keep only that minimum;
  `minecraftversions` and `javaversions` stay full because the stores publish them. The companion `watermediarange`
  stays closed. ⚠ The core keeps exact Minecraft matches and closed loader lines.
- `publishMods`: CurseForge (`curseforgeid`, `CURSEFORGE_TOKEN`, Minecraft + Java versions, client and server required,
  `requires('watermedia-binaries')`), Modrinth (`modrinthid`, `MODRINTH_TOKEN`) and GitHub (`publishGithub`: release of
  tag `version` in the `github` repository, `GITHUB_TOKEN`, `GITHUB_SHA` as commitish, the jar plus the sources jar,
  pre-release unless `RELEASE`); type `ALPHA`/`BETA`/`STABLE` from `versiontype`; validation: `RELEASE` needs `x.y.z`,
  pre-releases `x.y.z.w`.
- Store descriptions (`gradle/common.gradle`, group `publishing`): `syncModrinthDescription` PATCHes
  `/v2/project/<modrinthid>` with `{body}`; `syncCurseforgeDescription` PUTs `{description, descriptionType: 5}` (5 is
  Markdown, 1 HTML) to `authors.curseforge.com/_api/projects/description/<curseforgeid>` with
  `Cookie: CobaltSession=<CURSEFORGE_SESSION>; AuthorsUser=<CURSEFORGE_AUTHORS_USER>`, both from the same browser
  session (401 without the first, 403 without the second, 500 when the second is malformed). Each store declares its `headers` and `cookies` as
  name → secret; a missing secret fails the task. Both send `README.md` (CRLF to LF) and fail on any answer from 300 up;
  401/403 names the secrets to renew. Binaries runs the same tasks over
  `binaries/README.md`. CurseForge rewrites inline HTML (drops `align`, adds `rel="nofollow"`, compacts `style`) and
  reads back the old text for a few seconds after the PUT.
  ⚠ CurseForge has no public description API: that call is the authors site editor's own (unofficial, may change),
  the upload token gets 401 there, and the session cookie expires and opens the whole account.
- `maven-publish` publication `watermedia`: only `jar` + `sourcesJar`, no POM dependencies and no Gradle module
  metadata (⚠ `components.java` would leak `:modloaders` stubs and host libs to mods). JitPack serves it (see CI).
- `gradle.properties` groups: runtime (`org.gradle.*`), identity (`id`, `libname`, `group`, `version`, `versiontype`,
  `mainclass`, `github`, `modrinthid`, `curseforgeid`), loader ranges, info (`license`, `authors*`, `credits`,
  `description`), publishing (`modloaders`), plugin/toolchain pins (`osdetector_version`, `publish_version`,
  `gradle_version`, `java_test_version`, `java_compat_version`, `java_release`), library versions (`ffmpeg_version`,
  `xz_version`, `waterconfig_version/_range`, `junit_version`, `log4j_version`, `gson_version`, `lwjgl_version`,
  `joml_version`, `javafx_version`), test runtime (`test_max_heap`, `test_max_direct_memory`, `test_lwjgl_stack`,
  `require_natives`), launcher (`lwjgl_platforms`).

### Binaries build (`binaries/build.gradle`)
- Plugins `java`, `maven-publish`, `com.gradleup.shadow`, mod-publish. `main.java.srcDirs = []` (the core compiles the sources);
  `tools.java.srcDirs = ['tools']`; test classpath = `library` (`org.watermedia:watermedia`, substituted by the
  included build) + `../build/classes/java/main`.
- `shadowJar` (the only distributed jar): the core's `org/watermedia/binaries/**` classes + `org.tukaani:xz` relocated to
  `org.watermedia.libs.xz`; `jar` disabled. `processResources` adds `tools/ffmpeg-manifest.properties` under `META-INF`.
  Publication `binaries`: the `shadowJar` alone, artifactId `slug` (`watermedia-binaries`, the repository name).
- `binaries/gradle.properties`: id `watermedia_binaries`, `watermediaversions`, FFmpeg provenance (`ffmpeg_version`
  `9.0.2-1.5.14`, `ffmpeg_variant=lgpl`, `ffmpeg_source_*` of the fork `SrRapero720/javacpp-presets` branch
  `ffmpeg-watermedia-custom`, SHA pins of FFmpeg, libxml2, OpenSSL, Vulkan headers, Maven, CMake, Meson, Microsoft JDK).

### Native rebuild pipeline (`binaries/tools`, workflow `binaries/.github/workflows/ffmpeg-rebuild.yml`)
1. `SetupJava.ps1 -Platform <p>` (pinned Microsoft JDK), `SetupNativeTools.ps1` (Maven, CMake, Meson on Linux),
   `SetupLinuxNative.ps1` (Raspberry Pi userland, linux-arm64 only).
2. `RebuildFFmpeg.ps1 -Platform <p>`: downloads and verifies sources into `build/nr`, patches `cppbuild.sh`/`pom.xml`
   (LGPL/GPL switch, no OpenCL, `ffmpeg-headers.patch`, then `ffmpeg-http-keepalive.patch`), `mvn package` of the preset, import-closure check, smoke DASH
   fixture, `VerifyFFmpeg.java` (versions, Vulkan bindings, `libplacebo`, `libkvazaar`, licenses) and `VerifyTLS.java`
   (~35 HTTPS/HLS/DASH trust and header-isolation cases) in clean JVMs → `build/rebuilt/<platform>/`.
3. Job `repack`: `java tools/RepackFFmpeg.java . build/rebuilt` verifies the five candidates and writes the zips, the
   wrapper jar and `ffmpeg-manifest.properties` (DEFLATE 9, SHA-256 per native).
- `ffmpeg-headers.patch` makes libavformat send `Authorization`, `Proxy-Authorization`, `Cookie`, `Cookie2`,
  `X-WaterMedia-Token` and a custom `Host` only to the original origin across redirects and nested HLS/DASH reads.
- `ffmpeg-http-keepalive.patch` (`ffmpeg_keepalive_patch_sha256`, manifest `keepalive.patch.sha256`, five commits of
  `http.c`): the backport of FFmpeg b66d2aca0f (a connection is reused only for its own host, a credential leak 9.0.2
  carries), a resend once when a kept connection dies before any reply, no reuse of a half-read reply, a second kept
  connection for the other host of a redirect and the 302's own connection kept when its body is complete. An HLS
  segment googlevideo redirects costs one handshake instead of two (it closes on the 302, so not zero).
  ⚠ Added to the recipe on 2026-11-27, the installed natives predate it until the next rebuild.

### CI (`.github/workflows`)
- `build-test.yml` (push/PR on `master`, `3.0.x`): job `build` on ubuntu-22.04 (`gradle build --warning-mode fail`
  after `SetupJava.ps1` and the Linux media runtime packages); job `native` matrix (linux x64/arm64, windows, macOS
  x64/arm64, plus a Java 17 "compatibility" leg) running `gradle test -Prequire_natives=true` with explicit `--tests`
  filters (FF players, decoders, encoders, `FFmpegLogRoutingTest`; the compat leg: bootstrap, network,
  AL/AWT/SFX/spatial, NetRequest, `FFmpegLogRoutingTest`). Every leg plays with FFmpeg's log routed through its native
  callback; a failed leg keeps its `hs_err_pid*.log` and test results as artifact `native-failure-<platform>`.
  Both jobs set `ALSOFT_DRIVERS=null`, so OpenAL opens its null device and the `OpenALContext` cases run.
  ⚠ No filter reaches the FFmpeg cases of `ColorimetryTest` (`Tags`) and `StatusDeliveryTest`
  (`LockFreeRelease`): only the `build` job runs them, where natives are optional and a failed load skips them.
- `build-release.yml` (tags), one job per step over a single build: `build` (fails unless the tag is `version`,
  `gradle build`, keeps `build/libs` as artifact `libs`) → `release` (`publishGithub`) → `curseforge` and `modrinth`
  (`publish<Store>` over the artifact, `-x jar -x sourcesJar`) → `<store>-description` after its own store's upload.
  A store that fails never holds the other back, and re-running a red description job never uploads again.
  `workflow_dispatch` (input `store`: `all`, `curseforge`, `modrinth`) skips the build and uploads and only syncs the
  descriptions from the README of the selected ref. Jobs share `.github/actions/setup-build` (Gradle version,
  `SetupJava.ps1`, setup-gradle). Secrets: `CURSEFORGE_TOKEN`, `MODRINTH_TOKEN` (needs "Write projects"),
  `CURSEFORGE_SESSION` and `CURSEFORGE_AUTHORS_USER` (the authors site cookies; renew both together).
- Binaries CI (`binaries/.github/workflows/building-*.yml`, push/PR on `master`, `3.0.x` and tags): checks out the
  core `SrRapero720/watermedia@master` and the Binaries revision into its `binaries/` folder, then builds Binaries as root.
  `building-release.yml` mirrors the core release jobs (`-x shadowJar`, its own `setup-build` under `watermedia/binaries`).
- JitPack (`jitpack.yml` in each repository, JDK 25): ⚠ a custom `install` runs `publishToMavenLocal`, because the
  default command passes `-Pversion=<branch>-<hash>-1` and the `versiontype` check rejects it; JitPack serves the
  `org.watermedia` artifacts as `com.github.SrRapero720:<repository>:<ref>`. Binaries' `install` clones the core master
  into `/tmp/watermedia`, copies itself into its `binaries/` folder and publishes from there (it never builds alone).
  ⚠ A tag whose commit pins an unpushed Binaries gitlink fails as a JitPack `gitError`.
- ⚠ `neoforge.mods.toml` declares `iconFile` and `logoFile` together in both jars: NeoForge 26.2+ draws the mod list
  icon only from `iconFile` and, in development runs, opens on a loading warnings screen for a `logoFile` without
  `iconFile` or `bannerFile`; older NeoForge lines only read `logoFile`. The core also sets `bannerFile` to the asset
  `watermedia:textures/banner.png` (the info panel of the mod list; a value with `:` is a resource location, a plain one
  a file at the jar root), while Binaries has no banner and falls back to `logoFile`. Forge and Fabric metadata have no
  such keys.
- Loader metadata: `mods.toml` (Forge, `@Mod("watermedia")` scan), `neoforge.mods.toml`, `fabric.mod.json` (entry
  `org.watermedia.bootstrap.FabricBootstrap`, `jars` = nested libs, `breaks.watermedia_binaries`), `pack.mcmeta`; the
  Binaries jar carries twin files with mod id `watermedia_binaries` requiring `watermedia`; both jars mark the companion
  dependency `side="CLIENT"`.

## 5. Lifecycle, configuration and bootstrap

### `WaterMedia` (`org/watermedia/WaterMedia.java`): static process state and module walker (no instances)
- Constants `ID="watermedia"`, `NAME`, `VERSION=IOTool.jarVersion()` ("unknown" from class folders), `USER_AGENT`,
  `BROWSER_AGENT` (Chrome UA whose major grows every two weeks), `LOGGER=logger(ID)`.
- Error codes: `ERR_UNKNOWN = 0` and one `public static final int ERR_*` per error site of the core, declared after
  the default paths (registry and layout in §15). They are compile-time constants, so javac inlines them: a site
  never loads or initializes `WaterMedia`, and `AppBootstrap`, `IOTool` and `DataTool` keep no reference to it (the
  launcher runs before Log4j exists, see `AppBootstrap` below).
- `start(String name, Path tmp, Path cwd, boolean clientSide)` (static synchronized): throws `IllegalStateException`
  if called from an owned worker thread (`ThreadTool.workerThread()`) or twice, `IllegalArgumentException` on a blank
  name. Defaults: `tmp` → `<java.io.tmpdir>/watermedia`, `cwd` → current directory. Constructs the hardcoded module
  list `new WaterMediaConfig()` → `new WaterMediaBinaries()` (only when `org/watermedia/binaries/WaterMediaBinaries.class`
  is visible) → `new NetworkAPI()` → `new CodecsAPI()` → `new PlayerAPI()` (all constructed before the first start;
  a refused construction throws raw, before `started`), writes the static environment (`name`, `tmp`, `cwd`,
  `clientSide`, `steps`), sets the volatile `started` flag, then runs every `module.start(name, tmp, cwd,
  clientSide)` on the calling thread; `loading()` is the class simple name. `Exception | LinkageError` → ERROR log
  (`ERR_CORE_LOAD_MODULE_LOG`) + `IllegalStateException` "Failed to load WaterMedia module X" (`ERR_CORE_LOAD_MODULE`).
  ⚠ No stop/unload exists; a failed start cannot be retried in-process.
- Memory model: the environment and `steps` are plain write-once statics published by the volatile `started` write;
  every reader checks `started` first (`requireStarted()`, `steps()`, `loaded()`), ⚠ keep that order. `loading`
  (module name) and `done` stay volatile because the app splash polls them while `start` holds the class lock.
- `loaded()` (`started && done == steps`), `loading()` (module name or null), `step()`, `steps()` (4 or 5): safe from
  any thread. `name()`, `cwd()`, `tmp()`, `clientSide()` throw before start. `checkIsClientSideOrThrow(Class)` guards
  client-only ctors. `toId(path)` → `watermedia:path` (deprecated). `logger(name)` → redacting `ExtendedLoggerWrapper`.
- Gates are self-aware, never `WaterMedia.loaded()`: `PlayerAPI.mrl/preload` throw until `MRL.session` exists (the
  player module started on a client); `NetworkAPI.upload` throws until its volatile `uploads` exists. Players need an
  MRL with sources, which only a live session yields, so `MediaPlayer.arm()` has no gate. `ServerMediaPlayer` has no MRL
  and works before the load and on servers.

### `WaterMedia.Module` (nested in `WaterMedia.java`)
- `public abstract static class Module` with only `protected abstract void start(String name, Path tmp, Path cwd,
  boolean clientSide) throws Exception` (once, on the starting thread, with the normalized environment). Subclasses
  own their loaded state: a `LOADED` `AtomicBoolean` flipped by the first construction; a second one throws, whoever
  calls it. ⚠ Their constructors are public (WaterMedia sits in another package) but only `WaterMedia.start` may call
  them; one called earlier makes `start` throw.
- Modules decide per side themselves; a part that can be missing (file server, FFmpeg, disk cache) logs and continues;
  only escaping exceptions abort the load. What a module writes in `start` is visible to any thread that saw
  `WaterMedia.loaded()` true (the `done` volatile write follows it). ↪ new module: extend `WaterMedia.Module` with the
  `LOADED` constructor guard, add `new X()` to the list in `WaterMedia.start`. `PlatformAPI` is not a module (nothing
  to load).

### `WaterMediaConfig` (`org/watermedia/WaterMediaConfig.java`): WaterConfig TOML spec `watermedia`
- Static tree `codecs`, `player`, `platforms`, `network` (instances of nested `@Spec(disableStatic=true)` classes);
  key path = TOML tables (`codecs.ffmpeg.disable`, `player.watertx.texturesBudget`). Annotations `@Spec.Field`,
  `@Comment`, `@NumberConditions`, `@StringConditions`, controls (SEEKBAR, DROPDOWN, PASSWORD, INPUT_FOLDER).
- `start(...)`: `WaterConfig.init()`; if no spec `watermedia` exists → `registerBlocking(WaterMediaConfig.class)`;
  else waits on the existing spec monitor until loaded or FAILED, then `refresh()` (one writer per process).
  ⚠ The host owns the WaterConfig folder (owner rule 2026-11-23): `start` never moves it, whatever `cwd` it gets.
  The default relative `config` resolves against the process directory, and a host that may run from another one sets
  it before the load, as the app does (§12 `WaterMediaApp.start`). Never anchor it to `cwd` inside the library.
- File `config/watermedia.toml` under the WaterConfig path (CWD `config/`); library `me.srrapero720.waterconfig`
  (nested jar, `requires static waterconfig`, `opens org.watermedia to waterconfig`). Full key table in §14.

### Loader entry points (`bootstrap/`)
- `ForgeBootstrap` / `NeoBootstrap` (`@Mod("watermedia")` constructors): `DefensiveCheck.run(null)` then
  `WaterMedia.start("Forge"|"NeoForge", null, null, dist.isClient())`; NeoForge resolves the dist through
  `FMLLoader.getCurrent().getDist()` (FML 10+) with a reflective fallback to the static `getDist()` (FML ≤ 9).
- `FabricBootstrap.onInitialize()`: `DefensiveCheck.run(gameDir)`; a client without mod `watermedia_binaries` throws
  `IllegalStateException` "Failed to start WaterMedia, WaterMedia: Binaries is required on Minecraft clients"
  (`ERR_FABRIC_BINARIES_MISSING`); `WaterMedia.start("Fabric", null, gameDir, client)`.
- A failure of `WaterMedia.start` is wrapped in a `RuntimeException` "Failed to start WaterMedia for <loader>: <cause
  message>" (`ERR_FABRIC_START`, `ERR_FORGE_START`, `ERR_NEOFORGE_START`); the TLauncher check and the Fabric Binaries
  check run before it and throw their own `IllegalStateException`. Binaries ships empty twin bootstraps so loaders
  recognize its jar as a mod.
- `DefensiveCheck.run(Path gameDir)`: TLauncher refusal on hard evidence only (mod ids `tlskincape`/
  `tlauncher_custom_cape_skin`, `org.tlauncher.*` classes across all class loaders, mixin resources, thread names and
  stack frames, JVM arguments/properties matched by `SIGNATURE`, up to `PARENT_DEPTH=5` parent processes) →
  `IllegalStateException` listing the first `SHOWN_SIZE=4` items; leftovers in the game dir only WARN. ⚠ Not run by
  `AppBootstrap` or `WaterMedia.start`.

### `AppBootstrap` (`bootstrap/AppBootstrap.java`): standalone launcher (`Main-Class`)
- `AppEnvironment` (`bootstrap/app`, record `type`, `home`, `app`; JDK only, so the launcher loads it): the one place
  that decides where the app runs. `of(cwd, app)` is pure and `CURRENT` resolves it for the process from its working
  directory and the jar or classes directory it was loaded from. ⚠ Owner rule 2026-11-22, `Type.MINECRAFT`: a jar
  inside a `mods` folder (any letter case) always runs from the instance root (`mods/..`), whatever directory the
  process started in, because the game's `config/watermedia.toml` lives there and the app's Settings screen edits it
  (`logs/watermedia-app.log` joins the game's `logs/`). `Type.DEVELOPMENT` (classes from a directory) keeps the
  working directory. `Type.STANDALONE` (any other jar) keeps it too, or takes the folder of the jar when the working
  directory is read-only (`system32` after a shortcut or a search launch; neither writable → the working directory).
  The answer is the same from the home, so the launcher and the app it starts there agree without passing anything.
  ↪ Read `AppEnvironment.CURRENT` for the app's folder or type (`WaterMedia.start` cwd, `LogUploader.AVAILABLE` and
  its scan root); never `Path.of("")` or a check of your own.
- Launcher JVM: reads `AppEnvironment.CURRENT`; `prepare(home, app)` extracts the `nested` jars that
  `bootstrap.properties` lists from `META-INF/jarjar/` to
  `<tmpdir>/watermedia/libs`, downloads the `Dependency` list coded in `dependencies(Properties)` (versions and SHA-256
  pins from `bootstrap.properties`; Maven Central, `User-Agent: WaterMedia`, ≤ `MAX_DOWNLOAD_BYTES=256 MiB`, exact
  length, atomic move; optional `lwjgl-vulkan`/`lwjgl-shaderc`), adds extension jars from the home and from the folder
  of the jar (the `mods` folder of an instance) named `watermedia_*`, `wm_*`, `waterm_*`, `wmedia_*`, then
  `relaunch(...)` a child `java --enable-native-access=ALL-UNNAMED
  -Dwatermedia.app=true -Dwatermedia.engine=<opengl|vulkan> ... AppBootstrap` started in the home
  (`ProcessBuilder.directory`, classpath entries made absolute), so `config/`, `logs/` and every relative path of the
  app live there. Exit code `RELAUNCH_EXIT=42` relaunches; errors show the Swing `BootstrapWindow` (Relaunch/Close)
  unless headless.
- Child JVM (`-Dwatermedia.app` set): `WaterMediaApp.start(...)` with the `ServiceLoader<AppBootstrap.Extension>`
  loader (`interface Extension { default String name(); void load(); }`).
- Engine choice: `-Dwatermedia.engine` > `<home>/config/app.properties` key `engine` > `vulkan`.
- ⚠ Runs before Log4j/Gson exist (plain `HttpURLConnection`, `System.out`); shares `<tmpdir>/watermedia/libs`.

### Binaries module (`binaries/src/main/java/org/watermedia/binaries`)
- `WaterMediaBinaries extends WaterMedia.Module`: `ID="watermedia_binaries"`, `FFMPEG_ID="ffmpeg"`, `YTDLP_ID="yt-dlp"`,
  `BOTGUARD_ID="botguard"`. `start()`: dedicated server → nothing; client → registers `yt-dlp` and `botguard`
  directories under `tmp`, and unless `codecs.ffmpeg.disable` runs `FFmpegBinaries.start(<tmp>/ffmpeg)` eagerly; an
  `IOException` aborts the WaterMedia load. `pathOf(id)` → directory or null; `binaryDir(id)` throws when not started.
- `FFmpegBinaries` (package-private): reads `/META-INF/ffmpeg-manifest.properties` (per-platform native SHA-256,
  archive hash and size), extracts `/libs/ffmpeg-<IOTool.platformClassifier()>.zip` into a staged `install-*`
  generation under a `.install.lock` file lock, verifies CRC/size/SHA-256 per entry, publishes with
  `IOTool.publishGeneration` (atomic `current` pointer); a valid current generation is reused (re-hashed every start).
  Old generations are never deleted. `windows-arm64` and unknown platforms → `IOException`.
- `ExecutableBinary` (sealed, permits `YtDlpBinary`, `BotGuardBinary`): `executable()` (synchronized, blocking) resolves
  a system-property override (`watermedia.ytdlp.binary`, `watermedia.botguard.binary`), else the executable it already
  returned (re-hashed), else verifies the cached release (`installed.properties`) and always asks `latest()`: the cache
  stays when it equals `latest()` (version, origin, archive SHA-256) or the lookup fails, otherwise it downloads
  `latest()` with `NetRequest.httpsOnly().maxBytes(512 MiB).deadline(180 s)` + `download(path, sha256)`, extracts,
  `makeExecutable`, publishes a generation. yt-dlp: GitHub latest release + `SHA2-256SUMS`, so the first resolve of each
  resolver instance asks GitHub. BotGuard (`rustypipe-botguard`): local pin manifest
  `/META-INF/botguard-pins.properties` (fails closed, even with a cache), ZIP or `.tar.xz` extraction, `snapshot()`
  file.
- ⚠ Binaries links core members that must keep their semantics: `IOTool.{platform, platformClassifier,
  currentGeneration, publishGeneration, deleteTree, makeExecutable, sha256, requireSha256, verifySha256}`,
  `JSONTool.parse(String, Class)`, `NetRequest` (`httpsOnly`, `maxBytes`, `deadline`, `maxRedirects`, timeouts,
  `send`, `download`, `text`, `statusCode`, `close`), `WaterMedia`, `WaterMediaConfig`, `WaterMedia.Module`.

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
  - `start(...)`: server → return; no Binaries class → WARN; else `startFFmpeg()`: honours `codecs.ffmpeg.disable`,
    builds the preload path (`WaterMediaBinaries.pathOf("ffmpeg")` then `codecs.ffmpeg.customPath`), sets JavaCPP
    properties, logs component versions, probes hwdevice types, `FFTool.startTLS()`, publishes `ffmpegLoaded`. Failure
    → `ffmpegFailure` (boolean accessor only). FFmpeg's log goes to the WaterMedia logger under the `FFmpeg` marker
    (nested `NativeLog`, installed once by `startFFmpeg`): the preset's C trampoline `setLogCallback` formats each line
    and filters by `av_log_get_level` natively, so no `va_list` crosses into Java, and the callback only joins a
    thread's fragments and queues the line (`CAPACITY` 4096, then dropped and warned once). One `FFmpeg-Log` daemon
    thread logs it: FFmpeg ERROR and worse as ERROR, WARNING as WARN, INFO as DEBUG (its INFO lines name every URL it
    opens, a viewer's address included on YouTube). ⚠ Log4j and the redaction never run on a native FFmpeg thread (a
    small stack on macOS). ⚠ The level is QUIET while the callback swaps (the trampoline is set before its callback) and
    from the JVM shutdown hook `FFmpeg-Log-Quiet` on, so a decoder thread never enters a dying VM. Users reported JVM
    crashes on macOS ARM and Linux with an earlier routing (outputs lost), hence `FFmpegLogRoutingTest` in the CI native
    matrix of every platform.
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
  `WEBM{VP8,VP9,AV1 / OPUS}`, `OGG{- / OPUS,FLAC}`, `MP3{- / MP3}`, `M4A{- / AAC}`.
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
  `parseUntilFirstFrame()`, skips the default image of an animated stream (an `IDAT` ahead of the first `fcTL`; an
  `fcTL` ahead of the `IDAT` makes it frame 0), allocates the ARGB canvas. `decode()`
  collects `IDAT`/`fdAT` until the next `fcTL`/`IEND`, inflates with a reused `Inflater` (expected size gate →
  `ERR_PNGREADER_INFLATE_OVERSIZE` "Failed to inflate the PNG image data due to size over the expected (N bytes)"),
  unfilters (None/Sub/Up/Average/Paeth, Adam7), converts depths 1/2/4/8/16 (16 → 8), applies gamma LUT
  (`gAMA`/`sRGB`/`cICP`, only when it differs from 2.2), optional bKGD flatten (`codecs.png.useBKGDChunk`), APNG
  dispose (NONE/BACKGROUND/PREVIOUS) and blend (SOURCE/OVER via `DataTool.blendOver`), writes BGRA with `Canvas.write`.
  CRC mismatch → `CodecException` when `codecs.png.failOnCorruptedData`. Caps: `MAX_ANCILLARY_CHUNKS=64`,
  `MAX_ANCILLARY_BYTES=4 MiB`, `MAX_TEXT_ENTRIES=256`,
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
- `svg/SvgReader`: StAX parse (`SvgParser`: external entities disabled, empty resolver for DTDs, every JDK entity,
  depth and attribute limit pinned because Java 24 changed the defaults (`maxElementDepth=0` leaves nesting to
  `MAX_DEPTH`), `MAX_ELEMENTS=100_000`, `MAX_ATTR_CHARS=1_000_000`, `MAX_DOC_CHARS=16_000_000`), `SvgDocument` (viewBox, intrinsic size,
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
- `FFDecoder(String group, URI uri, HttpHeaders headers, boolean video, AudioChoice audio, Host host)` (null `audio` =
  no audio): one run per instance, used only by `FFMediaPlayer`, which drives it (no clock inside). Nested `Host`
  (implemented by `FFMediaPlayer.Callbacks`: `cached(URI)`, `vulkan(codecId)` → `Device(AVBufferRef ref, boolean
  filters)`, `audio(type, channels, rate)` → `Pcm`, `duration(ms)`, `opened(decoder)`, `failed(cause)`); records
  `Device`, `Pcm`, `AudioChoice(List<Track> tracks, int track, int stream, String language)` (`MAIN=-1`, `AUTO=-2`)
  and `AudioStream(track, stream, name, lang)`.
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
  - Threads (`ThreadTool.workerFactory("FFThread")`, named `<group>-demux|track|video|audio`): one reader per input
    (nested `Reader {IntPointer interrupt, Thread thread, boolean live}`, the format context's interrupt flag) and one
    decode thread per stream, all alive until `stop()`. The main reader (`demuxLoop`) opens the inputs and the codecs,
    reports `opened`, starts the decode threads and the track reader, then reads; `stop()` aborts the queues and joins
    it first, then the others. Queues: `PacketQueue` per stream (no cap, the reader asks first), `FrameQueue` video 3
    / audio 9 slots. One public static `INTERRUPT` callback (JavaCPP caps live callback instances at 10), which
    `FFTranscoder` shares.
  - Separate audio `Track` → second format context with its own reader (`<group>-track`); a track that fails to open
    falls back to the main audio stream. ⚠ `codecs.ffmpeg.trackPacketReads` has no effect since 3.0.4 (the key stays so
    config files keep loading).
  - Input options (public static `inputOptions(options, input, headers)`, shared by the main input, the separate audio
    track and the inputs of `FFTranscoder`):
    `FFTool.configureTLS`, `analyzeduration`/`probesize` from config, `reconnect`/`reconnect_streamed`/
    `reconnect_delay_max=5`, `rtsp_transport=tcp`; `buffer_size` (32 MiB) on every input: only `udp` and `rtp` read
    it (and `rtsp` for UDP transport, which `rtsp_transport=tcp` never opens), every other protocol leaves it unread,
    so a scheme check would only be noise. ⚠ `rtbufsize` (capture devices), `max_delay` (RTP reordering) and `http_persistent` (the
    HLS default) were dropped on 2026-11-27: nothing these inputs open read them;
    `headers` (`HttpHeaders.toRawString`) only when the source has any (an empty value makes FFmpeg end the request
    head early); `timeout` and `rw_timeout` are both 10 s: ⚠ `timeout` stays on the input's own connections and is
    left out of `rtmp*` inputs, where it means the seconds to wait for an incoming connection and turns the input into
    a server (no RTMP link played in 3.0.0 and 3.0.1); HLS/DASH copy only
    `rw_timeout` to their segment requests (overwriting anything seeded through `protocol_opts`); `protocol_opts`
    gets `:reconnect=1:reconnect_delay_max=5` appended to the TLS policy, because the top-level `reconnect` never
    reaches segment connections and a segment whose download was cut (server reset or a silent router drop while
    paused) was skipped without any error (watermedia#275). ⚠ No `reconnect_streamed` there: it would restart a
    segment served without ranges at byte 0, so that reconnect resumes only the segments served with ranges.
    `seg_max_retry=2`: an HLS segment that fails to open is asked for twice more before the demuxer skips it (the
    open of a segment nothing fetched ahead; hls.c asks for the next segment on every read of the current one).
    `request_size` = `YOUTUBE_REQUEST_BYTES` (10 MiB) only when the host is `YOUTUBE_CDN` (`googlevideo.com` and its
    subdomains): YouTube paces a longer or open-ended range to
    about twice the bitrate (waterframes#313, long videos froze while their audio played). Every other host keeps one
    open request, because each bounded range is a new request, which a file server out of slots answers with 503.
    `multiple_requests=1` rides with `request_size`: the next range goes out on the connection of the last one
    instead of a new TCP and TLS handshake every 10 MiB. ⚠ A seek still opens a connection (FFmpeg reuses one only
    within `short_seek_size` of the range's end).
  - Control, from any thread under the `control` monitor: `seek(targetMs, precise)` opens generation + 1 and returns
    it (floor = target when precise, else none) and raises the `interrupt` flag of every `live` reader, so a read
    blocked on the network is cut short; ⚠ never during an open or a `reopenFormat()` (`live=false`), which close
    the input first. A seek before `start()` is where the run opens. `floor(generation, ms)` raises the floor of the
    current generation, `hold(boolean)` parks the readers (a paused player), `generation()` is 0 before any seek.
  - Reader loop `read(Reader)` (a park lasts `PARK_MS=50` at most): a new generation is served first (flush its
    queues; clear `eof_reached` and `error` of the input's `AVIOContext`, which a read or seek the interrupt cut short
    left set, so a short seek forward would fail as the end of the input; the track seeks `BACKWARD | ANY`, the main
    input `BACKWARD` → flags 0 → `reopenFormat()`), then a park
    while held, at its EOF or when `fed`, else `av_read_frame` → `put(packet, generation, tsMs, durationUs)`. Read
    ahead: a queue has `enough` with `READ_AHEAD_MS=20 s` in more than `READ_AHEAD_PACKETS=25` packets, or at its byte
    cap (`VIDEO_PACKET_QUEUE_BYTES` 16 MiB / `AUDIO_PACKET_QUEUE_BYTES` 8 MiB). Of two streams of one input, `fed`
    stops at a byte cap (`capped`), or when one has enough and the other has too or stays empty. ⚠ `xHolds` also
    accept `capped`, or a rebuffer whose sibling queue hit its cap first would wait for a cushion that never comes.
    Timestamps become milliseconds through `ms(sec)`, clamped to `TIME_LIMIT_MS` (a quarter of the `long` range), so
    no crafted pts wraps the sums the player takes. ⚠ Each input has its own
    reader, so a slow or blocked one never keeps the other from reading: one shared thread starved the video behind
    a paced track (waterframes#313) and the sound behind a main input waiting for its HLS segment after a seek.
    ⚠ `sparse`: the stream of a shared input whose queue stays empty while its sibling has enough (cover art, a late
    or badly interleaved track) is not coming, so nobody waits for it. A cover picture (`AV_DISPOSITION_ATTACHED_PIC`)
    finishes its queue right after its packet (every seek sends it again). EOF → `finish(generation)`, an in-band end
    mark, and the reader parks until a seek (repeat is driven by the player). ⚠ A read an abort or a newer seek cut
    short is never EOF. An exception → `ERR_FFDECODER_READ_EXCEPTION` and `host.failed`.
    ⚠ The separate track seeks with `AVSEEK_FLAG_BACKWARD | AVSEEK_FLAG_ANY`: every packet of sound is an entry
    point, so it lands on the target. Without `ANY` a WebM lands on the cue before it (one per 10 s on YouTube). A
    track seek that fails warns once (`trackSeekWarned`) and the sound keeps its old position. The main input lands
    where its demuxer says (an HLS video on its segment start) and the floor drops what plays before the target.
  - Decode threads (persistent): an entry of an older generation is dropped, a generation change flushes the codec
    (and the resampler), and frames a newer seek overtook mid-packet are left to that flush. Video packets more than
    `SKIP_MARGIN_MS=1 s` before the floor decode with `skip_frame=AVDISCARD_NONREF` (H.264, HEVC, VP8; VP9 and AV1
    decode every frame anyway), never while `shifted` (packet times then name other frames). A frame that ends before the floor is dropped before the GPU transfer or a queue
    slot. The end mark drains the codec and sets `videoDone`/`audioDone` to its generation.
  - Video decode: `receive_frame` → GPU→RAM transfer when a non-Vulkan hwaccel (`HW_TRANSFER_FAIL_LIMIT=30`, perf window
    120 frames at 0.75 of the frame budget → software fallback `reinitVideoCodecSoftware()`) → optional `Placebo` →
    `queueVideo` (best-effort pts, 10 s discontinuity offset `PTS_DISCONTINUITY_SEC`, which sets `shifted` until the
    next seek). No `sws_scale` here.
  - Audio decode: `swr` conversion to the engine's `Pcm` in `engineLayout(layout, channels)` order (FFmpeg's default,
    but quad for 4 channels: OpenAL's quad is FL FR BL BR, the default 4.0 is FL FR FC BC); passthrough when interleaved,
    same type/channels and already in that order (an unspecified order counts as it); a mid-stream format change
    rebuilds the resampler, `AUDIO_SAMPLES=2048` output slots, resampler flush when the delay exceeds half a slot.
  - Codec selection: fps from `avg_frame_rate` (plausible 1..1000) else `r_frame_rate`; hardware order: Vulkan on the
    engine's device/twin (only when `Device.filters()` or the format is YUV420P/YUVJ420P/YUV420P10LE), then CUDA, QSV,
    AMF, OHCODEC, D3D11VA, D3D12VA, VIDEOTOOLBOX, VAAPI, VDPAU, DRM, MEDIACODEC, VULKAN (own device, downloads), then
    software (public static `softwareVideoDecoder(codecId, fallback)`: `libdav1d` > `libaom-av1` for AV1; threads
    2/4/6/MAX by pixel count capped by `MAX_DECODE_THREADS`).
  - Public: `start`, `seek`, `floor`, `hold`, `abort`, `stop`, `close`, `owns(Thread)`, `videoFrames()`,
    `audioFrames()`, `video()`, `audio()`, `generation()`, `videoBuffered()`/`audioBuffered()` (ms queued, at least 1
    while a packet waits), `videoHolds(ms)`/`audioHolds(ms)` (that much queued, or all it can be: EOF or byte cap),
    `buffered()` (the least among the coming streams, `Long.MAX_VALUE` without one), `videoComing()`/`audioComing()`
    (not done in this generation and not sparse), `ended()`, `videoDecoded()`, `covers(targetMs)` (every coming
    stream queued past target + `COVER_MARGIN_MS=500`, saturated so a target near `Long.MAX_VALUE` never passes, false
    once `shifted`), `demuxDied()`, `hardware()`, `vulkan()`,
    `filters()`, `videoContext()`, `uri()`, `fps()`, `hls()`.
- `FrameQueue(capacity)`: pre-allocated `Slot {AVFrame frame; ptsMs; durationMs; generation; width; height; format}`
  ring, single writer (`peekWritable()` blocks while full, `push()`), single reader (`peek()`, `peekNext()`, `next()`
  unrefs), `flush()`, `abort()`, `reset()`, `free()`. ⚠ A slot is invalid after `next()`.
- `PacketQueue()` (package-private): `Entry(AVPacket packet, int generation, long tsMs, long durationUs)`; `put`
  takes the packet's reference (`av_packet_move_ref`, the reader's packet is left blank for the next read), never
  waits nor refuses (only once aborted), `finish(generation)` queues an end mark (null packet),
  `get()` blocks while empty (null once aborted, the caller frees the packet), `flush()`, `holds(bytes, packets, ms)`,
  `empty()` (no packet, end marks aside), `closed()` (an end mark is last), `byteSize()` (payloads plus `ENTRY_BYTES`
  = 256 per packet), `durationMs()` (durations, each capped at `PACKET_LIMIT_US` = 10 s, else the span between first
  and last timestamp, at least 1 while a packet waits), `lastMs()`, `abort()`, `free()`. ⚠ The per-packet weight is
  what bounds a flood of empty or 1-byte packets without timestamps: without it the caps let millions of entries in
  (several GB of native memory from a small file).
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
- `FFEncoder(Container, Path file, OutputStream out, Video, Audio)`: MP4/MOV/MKV/WEBM/OGG/MP3/M4A; writes `<file>.part`
  (or a temp file for streams) and moves on `finish()`; `+faststart` for MP4/MOV/M4A; video encoders by preference H264
  `libx264`→`libopenh264`, HEVC `libx265`→`libkvazaar`, AV1 `libsvtav1`→`libaom-av1`, VP8 `libvpx`, VP9 `libvpx-vp9`,
  PRORES `prores_ks`; `quality` 0..100 mapped to each encoder's CRF/QP/preset; pixel conversion with `sws_scale` tagged
  BT.709 limited; time base 1/90_000, GOP 2 s; audio `pcm_s16le`, `libopus`, `aac`, `libmp3lame`, `flac` with
  `swresample` + `AVAudioFifo` chunking. Requires `CodecsAPI.ffmpegLoaded()`. ↪ Package-private statics shared with
  `FFTranscoder`: `muxer(Container)` (FFmpeg muxer name, null for house-made containers; M4A is `ipod`),
  `faststart(Container)`, `software(VideoCodec|AudioCodec)` (encoder names by preference), `encoder(codec, names)`,
  `rate(rates, wanted)` and `supported(context, codec, config)`.
- `FFTranscoder(Job, Runnable progressed)` (2026-11-22, internal, used by the app's `MediaDownloader`): one media into
  one file, each track copied as it comes or decoded and encoded again at a target bitrate. One instance runs one job
  on the calling thread.
  - Records `Job(input, track, headers, container, file, video, audio, title, author)` (`track` = separate audio input;
    an audio job with a track never opens `input`), `Video(codec, bitrate, fps, effort, hardware)` and
    `Audio(codec, bitrate)` (null codec = copy), `enum Effort {FAST, BALANCED, BEST}`, and the probe results
    `Info(durationMs, video, audio)`, `VideoInfo(codec, name, width, height, fps, bitrate)`,
    `AudioInfo(codec, name, sampleRate, channels, bitrate)` (null codec = one no `Container` encodes).
  - The constructor only checks the job against its container (IAE) and `ffmpegLoaded()` (IOException).
  - `static probe(URI, HttpHeaders)`: length (0 = unknown or live), picked streams and bitrates. A bitrate comes from
    the stream, else from what the file size leaves (never for `hls`/`dash`, whose size is the playlist's), else it is
    measured: `measure` reads `MEASURE_WINDOWS=3` windows of `MEASURE_WINDOW_US=4 s` at even steps of the media (one
    window when it is short or cannot seek). ⚠ A measure reads some seconds of media over the network.
  - `run()` → true when written, false after `cancel()`: `openInputs` (same `FFDecoder.inputOptions` and `INTERRUPT`
    as the players, unused streams `AVDISCARD_ALL`), `openOutput` (`<file>.part`, tags `title`/`artist`, `hvc1` tag for
    HEVC in MP4/MOV), `pump` (reads the input that is behind in time, so two inputs interleave), flush, trailer, move.
    ⚠ A read error other than EOF fails the run instead of ending it, so a cut connection never leaves a short file
    that looks whole (only an HLS segment the demuxer gives up on passes without an error and leaves a hole in both
    tracks); a packet a decoder refuses is skipped (warned once per track).
  - Timestamps: `pump` rewrites every packet to count from the start of its input before any decoder or the muxer
    sees it. On a format flagged `AVFMT_TS_DISCONT` (HLS, MPEG-TS) a packet that lands over `JUMP_BACK_US` (1 s) before
    or `JUMP_AHEAD_US` (10 s) after where its track should go on continues that track instead, as the `ffmpeg` tool
    does, so a clock that restarts mid-way never freezes the picture.
  - Stream picks (`stream`): the largest video that is no attached picture (cover art), the default audio else the first.
  - Video encode: software decoder (`FFDecoder.softwareVideoDecoder`), candidates = GPU encoders that take system
    memory frames when `hardware` (`<codec>_nvenc`, `_qsv`, `_amf`, `_videotoolbox`, `_mf`), then
    `FFEncoder.software`; the first that opens wins (`encoder()` names it). Pixel format 4:2:0 10-bit for a deep
    source (never H264/VP8), else 8-bit, else NV12; source size (odd sides grow by a pixel), source colour tags (RGB
    turns into BT.709 limited), clock 1/90_000, GOP 2 s, `tune` (bitrate + 1.5x peak + 2x buffer, effort mapped to each
    encoder's preset; ⚠ no peak for `libsvtav1`, which refuses to open with one next to a bitrate), frame rate limit
    by output slots (first frame of each slot stays). The display matrix (rotation) and the HDR mastering and light
    level side data of the input stream are copied onto the output stream, which an encode would otherwise drop.
  - Audio encode: resampler built from the first decoded frame (rebuilt on a mid-stream change), FIFO chunks, pts by
    sample count from where the first frame starts; a frame that comes more than two encoder frames later than that
    count gets the hole written as silence first, so the sound after it stays in sync; channel layout = the codec's
    own of the source's count, else its widest below it (a downmix).
  - `cancel()` sets a flag and raises the abort pointer the interrupt callback polls (checked again before the
    trailer and before the final move, so a late cancel still writes nothing); `free()` deletes the `.part`.
    `progress()` (0..1, volatile, 0 for an unknown length) and the listener (at most every `PROGRESS_STEP_NS` 200 ms,
    on the run's thread).

## 7. Player API (`api/player`)

### 7.1 `MediaPlayer` (abstract sealed, permits `ServerMediaPlayer`, `FFMediaPlayer`, `TxMediaPlayer`)
- Constructors are package-private: create only through `PlayerAPI`. The MRL ctor checks
  `WaterMedia.checkIsClientSideOrThrow`, `mrl.source(index)` non-null, at least one engine; the headless ctor of
  `ServerMediaPlayer` checks nothing. ⚠ Every concrete constructor ends with `arm()` (no gate: it only publishes the
  player to the ticker and backdates the seek cooldown; the before-load refusal lives in `PlayerAPI.mrl`, §5); the
  shared 50 ms `MediaSync` ticker (`TICK_MS=50`, single daemon thread) only ticks armed players.
- Site lists (owner, 2026-11-17): `static checkSite(URI)` (box `UTILITY: SITE LISTS`, helpers `host`, `listed`,
  `local`) judges a link against `player.blacklist` and `player.whitelist` by the host it was written with, never by
  the hosts a platform, playlist or redirect leads to. Callers: `MRL.doLoad` before `PlatformAPI.fetch` (no request
  reaches a refused site) and the constructor again (the config can change after an MRL resolved), where a refusal
  calls `MRL.block` and throws `IllegalStateException` (`ERR_PLAYER_SITE_BLOCKED`, cause = the refusal), so
  `PlayerAPI.player` returns null.
  - A list is on with `enable` and something to judge by: the whitelist needs `sites` or `trustLocal`, the blacklist
    `sites`. The blacklist wins: `BlacklistException` (`ERR_PLAYER_SITE_FORBIDDEN`, "the site is forbidden"), then
    `WhitelistException` (`ERR_PLAYER_SITE_NOT_ALLOWED`, "the site is not allowed"); both `extends IOException`,
    package-private constructors.
  - `water://global` always passes. `trustLocal` covers the rest of `water://`, `file:` without a host, `localhost`,
    `*.localhost`, `*.local` and loopback, private, link-local and any-local addresses (IPv6 `fc00::/7` too).
  - `host(URI)` is the compared form: lowercase ASCII (`IDN.toASCII`), no port, user, brackets or trailing dot, IPv6
    in full; when `URI.getHost()` is null it is cut from the raw authority after the first `@`. An entry is read like
    a link (`x://` + entry, a bare IPv6 gets its brackets, leading `*.` and dots dropped), so a pasted URL or a port
    still names its site. A site covers its subdomains at a dot boundary, an address only itself.
  - `jar:` links are unwrapped at the first `!/` (where `JarURLConnection` cuts, repeatedly) and judged by the link
    they wrap, which is the one Java fetches; an inner text that is no URI throws `IllegalArgumentException` (the MRL
    ends in ERROR).
  - ⚠ A `file:` path starting with two separators (`file:////server/share`, `file://localhost//server/share`, the
    `%5C` spellings) is a Windows share: when the link host is missing, empty, `localhost` or `~` (Java reads those
    as no host) its server is the host, so it is never "local" by itself; any other link host is the one judged.
    `host` returns null only for a link that writes no host at all, which is what makes a `file:` link local.
  - ⚠ Disguised hosts (`DISGUISED` and not `IPV4`) are forbidden by an active blacklist, never allowed by the
    whitelist and never local, because the parsers and network stacks disagree on where they lead: an IPv4 as one
    number, hex, shortened or with leading zeros (`2130706433`, `0x7f.1`, `127.1`; any last label that is a number),
    a host keeping `@`, `%` or `?` (a second user mark, an escape, the device prefix `\\?\`), a host that was written
    but names nothing (`.`, an empty share server, the device prefix `\\.\`; `host` returns it as `?`) and
    `*.ipv6-literal.net`. ⚠ Only bracketed literals and validated dotted quads reach `InetAddress`, so the check
    never asks the DNS.
- Status enum `WAITING, LOADING, BUFFERING, PLAYING, PAUSED, STOPPED, ENDED, ERROR` (the `PlayerState` status byte is the
  ordinal: a reorder bumps `PlayerState.VERSION`); `allows(next)` is the one transition map (pause, resume, buffering, end, stop, failure; no
  self-moves, STOPPED allows nothing; starts, seeks and restarts take their own paths); terminal for listeners: STOPPED,
  ENDED, ERROR. `onStatus(StatusListener)` (`onStatus(player, previous, current)`): duplicates ignored; ⚠ listeners are
  consumed (cleared) on a terminal transition, re-register from the callback; delivery is ordered, outside player locks,
  on the pipeline/caller thread; never call `release()` from a listener.
- Control surface (boolean controls answer "did this call change this player's own state"): `start()`,
  `startPaused()` (final, → `launch(paused, -1)`; legal from any status, restarts ENDED/STOPPED/ERROR), `pause()`,
  `resume()`, `pause(boolean)` (latched while LOADING), `stop()` (lands STOPPED, time 0; false on a never-started
  player, which stays WAITING), `togglePlay()`, `seek(ms)` (clamped; from ENDED/STOPPED restarts PAUSED at the target,
  except that Tx frame textures turn an ENDED player PAUSED in place), `seekQuick(ms)`, `skipTime(delta)`,
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
  startPaused/pause/resume/stop), adopts `repeat` and `speed`, and corrects time with `seekQuick` when
  `|drift| > tolerance` (`catchUp` when behind); no rate trimming; ERROR players are left alone.
  - Landing: the ticker sees the pipeline play again (PLAYING or PAUSED) after it saw it BUFFERING (`flying`) or
    after a new `seekNanos` stamp. `seekNanos` (package-private, volatile) is written by `FFMediaPlayer` when a
    public `seek`/`seekQuick` is taken, the host's as much as the follower's. ⚠ The ticker reads the stamp before
    the status, so a seek taken in between shows as BUFFERING; a stamp seen while LOADING is never timed.
  - Cooldown: `SEEK_COOLDOWN_NS` (1 s) counts from the landing (`landedNanos`), or from the request until it lands.
    It used to count from the follower's own request only, so a host's seek and a slow landing left none.
  - Aim: behind the session (`drift > 0`) the correction is `catchUp(authorityTime(heard, 0))`; ahead of it,
    `seekQuick(authorityTime(heard, latencyMs))`, where the session will be when it lands (`latencyMs` is the flight
    of the last timed seek, capped at `LATENCY_MAX_MS`, 5 s). Aimed at where the session is, every network seek landed
    its own flight behind and a flight over the tolerance asked for the next one forever.
  - Hooks (package-private, base = `seekQuick` and nothing): `catchUp(ms)`, which `FFMediaPlayer` serves as an `ahead`
    seek, a floor over what its readers already read when it covers `ms`, without a request; `guide(ms)`, called by
    the ticker while a stamped seek flies (BUFFERING, session PLAYING) with where the session is, which `FFMediaPlayer`
    turns into its landing floor, so a seek lands on the session instead of a flight behind it.
  - Chain: corrections less than `CHAIN_NS` (10 s) apart count up, and the third in a row warns once per player
    (`chainWarned`, "Sync keeps correcting"). DEBUG "Sync: seek landed in Xms, Yms away from the session" per landing.
  A BUFFERING session holds the follower like a pause; a dormant (WAITING/STOPPED/ENDED) follower is started once per
  dormancy, an ENDED one only while the session sits before its own media's end; a STOPPED session stops only a
  running follower, never an ENDED one; live or durationless sessions get no time correction.
- Subclass helpers: `launch(paused, seekMs)` (abstract), `holdStatus()`, `tick()`, `queueStatus(prev, next)` +
  `deliverStatus()`, `exception(Throwable)`, `resolveQuality(w, h)` (relabels the MRL's UNKNOWN bucket via
  `MRL.moveQuality`), static `scaled(native, max, percent)`, `mergeFailure`, `nowMs()` (monotonic ms).

### 7.2 `PlayerAPI` (final utility)
- `mrl(String)` (existing file relative to `WaterMedia.cwd()` → file URI, else `URI.create`; malformed → uncached
  `MRL.error`), `mrl(URI)`, `preload(URI...)`: throw `IllegalStateException` before `loaded()` or on a server. A
  relative URI (`mrl(URI)`, or a string naming no file) yields an uncached MRL born in ERROR (`ERR_MRL_RELATIVE_URI`).
  ⚠ A string resolving to a Windows share (`\\server\share\x`) is never probed with the file system (that already
  connects to its server): it becomes the link `file://server/share/x` for the loader, where the site lists judge it.
- `player(MRL, [int sourceIndex,] Supplier<GFXEngine>, Supplier<SFXEngine>)`: null when the source is missing (FETCHING
  logs DEBUG), `MediaType.IMAGE` → `TxMediaPlayer` (sfx supplier never called), else `FFMediaPlayer` when
  `CodecsAPI.ffmpegLoaded()`, else null (ERROR `ERR_PLAYERAPI_NO_BACKEND` "Failed to create the player due to no
  media backend available"); an `Exception` in construction releases
  the engines and returns null, `Error`s propagate. `serverPlayer()` → `ServerMediaPlayer` (works on servers).
- Engine factories (client only): `glEngine(Thread renderThread, Executor renderEx)` (both null = calling-thread GL),
  `vkEngine(VKContext)`, `jfxEngine(Runnable onFrame)` (throws without JavaFX 13+), `awtEngine(Runnable)`,
  `headlessEngine([boolean preload])`, `alEngine([int buffers][, boolean spatial])`, `jsEngine([int bufferMs])`.
- `start(...)` (client only): `MRL.start()` then `NetworkCache.start(tmp.resolve("cache"))` (failure logged).

### 7.3 `MRL`: cached async resolution
- Statuses `FETCHING, LOADED, EXPIRED, ERROR, BLOCKED, FORGOTTEN` (`loaded()`, `failed()`, `regenerable()`,
  `disposable()`); `status()` flips LOADED → EXPIRED lazily after `Platform.Resolution.expires()`.
- Session: `ConcurrentHashMap<URI, MRL>`, fixed pool `MRL-Loader` (`min(4, cpus)`); a sweep inside `doLoad()` forgets
  disposable entries once `player.mrlCleanupInterval` minutes passed (no timer: it runs on the next load, never while
  idle). `reload()` returns the active handle (a FORGOTTEN one
  is replaced; a reload during a fetch is deferred). `subscribe(Consumer<MRL>)` fires once on completion or failure
  (dropped afterwards); `await(timeoutMs)` polls.
- `doLoad()`: `MediaPlayer.checkSite(uri)` first (site lists, §7.1), then `PlatformAPI.fetch(uri)`; null → direct
  probe with `NetRequest.accept(ACCEPT_MEDIA)`: HTTP ≥ 400 fails,
  document content types are rejected (`text/html`, `text/css`, `text/javascript`, `application/xhtml+xml`,
  `application/(x-)javascript`, `application/ecmascript`, `application/json`, `*+json`), M3U shapes parsed with
  `M3UTool` (IPTV → one source per channel, HLS → one VIDEO source), otherwise one source typed by `sniffType`
  (`MediaType.of(contentType)` → `CodecsAPI.sniff(stream)` → `MediaType.ofExtension`). A single UNKNOWN platform source
  is re-sniffed with its headers. Qualities are bucketed per `MediaQuality.of(width, height)`, one URL per bucket
  (widest, tallest, highest fps, first). `MatureContentException`, `WhitelistException` and `BlacklistException` →
  BLOCKED (WARN without trace), any other Throwable → ERROR. `block(IOException)` (package-private, called by the
  `MediaPlayer` constructor) turns a resolved MRL BLOCKED and drops its sources; a no-op without sources.
- `MRL.Source(MediaType type, URI thumbnail, Metadata metadata, HttpHeaders headers (frozen), Map<MediaQuality,URI>
  qualities, List<Track> audioTracks, List<Track> subtitleTracks)`: `uri(MediaQuality)` (closest bucket),
  `qualityOf(URI)`. Accessors `uri()`, `status()`, `exception()`, `sources()`, `sourceCount()`, `source(int)`,
  `sourceByType(MediaType)`, `sourcesByType(MediaType)`.

### 7.4 Clocks and synchronization
- `MasterClock` (package-private, one per `FFMediaPlayer`): ffplay-style pts drift in seconds; `time()`/`timeMs()`
  lock-free. ⚠ It never starts on its own: a start, a seek or a rebuffer leaves it BUFFERING (`unsettled()`), frozen
  where it resumes, until the player calls `land(ptsSec, epoch)` with the frames every stream has (BUFFERING →
  PLAYING, or PAUSED when the pause intent is latched; a PAUSED clock just moves there; false for an older epoch).
  `update(ptsSec, epoch)` only corrects a PLAYING clock (drift > `DRIFT_RECALIBRATION_SEC=0.050`) with a frame of the
  current epoch. `transition`/`exchange` accept only `Status.allows(next)` moves (no table of its own), while `start()`
  (LOADING or WAITING → BUFFERING, keeping the position a seek while loading set), `reload()` (running → LOADING),
  `requestSeek`, `land`, `halt()` and `reset()` own the rest (STOPPED terminal until `reset()`).
  `requestSeek(ms, precise, ahead)` opens a new epoch, freezes at the target and enters BUFFERING (stays LOADING
  while loading); `consumeSeek()` → nested `Seek(targetMs, precise, ahead, epoch)`, `epoch()`. `paused(boolean)`: a
  resume while BUFFERING only clears the intent, and a clock paused before it landed resumes into BUFFERING, never
  into PLAYING without frames. `holding()`, `awaitChange`, `awaitNotStatus`, `speed(double)`, `fps(float)`
  (`frameDurationSec`, `skipThresholdMs` ≈ 5 frames), events drained in order to one listener after the lock is
  released. `heldMs()` (time since the clock entered PAUSED by request or by a landing; 0 when not holding) and
  `reread()` (a holding clock clears its pause intent and requests a precise seek to its frozen position under one
  lock hold, PAUSED → BUFFERING without a PLAYING in between, so the parked readers wake with the seek pending and
  never read first from a connection the origin cut).
- `ServerMediaPlayer` (`PlayerAPI.serverPlayer()`): pure `System.nanoTime` clock, no engines, allowed on servers;
  `syncDuration(ms)` (the first non-zero report wins for the lifetime; later reports are ignored and a divergence >
  `DURATION_DIVERGENCE_MS=500` warns once), `syncTime(ms)`,
  `live(boolean)`, `revision()` (AtomicInteger seeded from `(int) System.currentTimeMillis()`, bumped by every clock
  mutation, the loop wrap and `release()`; `volume`/`mute` never bump it), `snapshot()` → `PlayerState`; held while
  `duration <= 0 && !live`; non-repeat → ENDED at the duration, repeat wraps with carried overshoot. A live session
  never ends or wraps, even with a latched duration; `live(false)` without a duration keeps the counted time and
  holds. Fed snapshots (`sync`), it adopts the session duration (while its own is unknown) and the live flag in
  `tick()`.
- `MediaPlayer.PlayerState(int revision, Status status, long time, long duration, float speed, boolean repeat, boolean
  live)` nested record (was the top-level `Sync`): `BYTES=27` big-endian wire form (`VERSION=3`), `toBytes()`,
  `read(ByteBuffer)` (IAE on malformed, position advanced only after a full parse). Transport is the host's; feed with
  `MediaPlayer.sync(...)`.

### 7.5 `FFMediaPlayer`: audio/video/live through `FFDecoder`
- `launch` (under `lifecycleLock`) starts one `FFThread-<n>-lifecycle` run: `clock.reset()`, LOADING, `open()` (quality
  URL, an `FFDecoder.AudioChoice` when an sfx exists: the host's `audioPick` with its language, else `AUTO` with
  `audioLanguage()`; `new FFDecoder(...)`; the seek pending so far is the decoder's first generation, so a restart or a
  quality switch never reads from the beginning first; `decoder.start()`), then the consumption loop. The run keeps
  `generation`, `epoch`, `settled` and `floorMs` (lifecycle thread only).
  - Seeks: `consumeSeek()` → `decoder.seek` (new generation, floor at the target when precise), or, for an `ahead`
    seek (a follower `catchUp`, `nextFrame()`) of a landed run, past where it is and within `CATCH_UP_MS=3 s`, that
    `decoder.covers(target)`, only `decoder.floor(target)` with no request (VP9 and AV1 decode every frame they drop,
    hence the reach); either way `sfx.flush()` and the run is unsettled. `decoder.hold(settled && clock.holding())` parks the readers
    of a landed pause. Frames of an older generation or that end before the floor are dropped wherever they are met.
  - Landing (while unsettled): a follower `guide` past the seek target, within `GUIDE_RANGE_MS=10 s` of it, raises
    the floor to where the session is. Then it waits until every stream still coming has a frame and its cushion
    (`videoHolds`/`audioHolds(cushionMs)`). Start = the later of the first picture and the first audible sound (audio
    head minus `sfx.pendingMs()`, so the sound a rebuffer left in the engine plays first); what a stream has before it
    goes through the floor. ⚠ From the beginning (`fromStart`: a run opened at 0, a seek to 0, a repeat) the start is
    the earlier one and nothing is dropped, so a stream that starts late (a delayed audio track) joins when it starts. `clock.land(start, epoch)`, the landing frame goes up even when it resolves into PAUSED
    (a seek on a paused player shows its target), and the sfx plays. ⚠ The stamp of the seek is read before `land`,
    so a seek stamped right after a landing never takes its report.
  - Playing: `drainAudio` (drop stale frames, stop when more than `AUDIO_MAX_LEAD_SEC=0.5` ahead, `sfx.upload`, clock =
    audible position `pts + duration - sfx.pendingMs()`), one video frame per iteration (wait if `diff > 0.040 s`; a
    frame later than `skipThresholdMs` raises the floor to the clock, so the decoder drops the rest before they leave
    the GPU; adaptive render-debt drops), `drainAudio` again, 1 ms sleep.
- Video upload: Vulkan frames sampled in place when `VKEngine.samples(frame)` (else `av_hwframe_transfer_data`),
  native planes via `gfx.upload(planes, strides)` with a 4-set rotating plane pool (aligned when `gfx.alignment() > 0`),
  or `sws_scale` (native output if supported else BGRA, `SWS_AREA` when downscaling); `gfx.format(...)` re-issued only
  on format/bits/size/colour change (refusal latches `uploadRefused`); `colorimetry(...)` maps FFmpeg tags to
  `Colorimetry` (matrix BT709/BT601/BT2020, range, siting, PQ/HLG transfer, BT.2020 primaries) with `Colorimetry.guess`
  fallback. Video drives the clock only without audio.
- Seek landing report: a public `seek`/`seekQuick` (and a follower `catchUp`) the clock took stamps `seekNanos` and
  `seekTargetMs`, and the landing logs one DEBUG line ("Seek to Xms landed at Yms after Zms (picture at Pms, sound at
  Sms, -1 without one)"): where the clock started and where each stream had its first frame.
- Buffering, decided by the least buffered stream (never a seek, both streams wait): a stream with no frame and no
  packet while more is due for `STARVED_MS=300` rebuffers with a cushion of `CUSHION_MS=1 s` (2 s when it starves
  again within `CUSHION_WINDOW_MS=30 s`); a picture with packets but no frame for `BEHIND_MS=3 s` rebuffers with no
  cushion. The rebuffer pauses the sfx, raises the floor to the clock and lands again. ⚠ The sound never runs on over a
  frozen picture: that alternated PLAYING and BUFFERING after a seek (the reported bug). `buffered()` is the least
  buffered stream against the cushion; rate monitor warns once outside 0.9..1.1;
  EOF: zero frames decoded → ERROR (`ERR_FFPLAYER_NO_FRAMES` "Failed to decode video due to no frames emitted, the
  stream is unsupported or broken"), stills honour `displayTime`, `repeat()` → `requestSeek(0)` once the engine
  holds no more than `TAIL_MS=50` of sound (the seek flushes it); `decoder.ended()` is read before the frame heads,
  because a decoder pushes its last frames before it says it is done,
  else ENDED. `quality(q)` or `audioTrack(AudioTrack)` on a live run sets `switchRequest` → `switchSource()` (rebuild
  decoder, VOD re-seeks, LIVE restarts at the edge).
- `pause(false)` after a hold of `REREAD_HOLD_MS` or more (10 s) on an
  `hls()` decoder that `canSeek()` and has not `ended()` resumes through `clock.reread()`: the HLS seek closes
  the cut segment connection and the one of the next segment FFmpeg fetched ahead, reopens the segment from byte 0 and
  the floor lands on the paused frame (measured: about 0.1 to 0.6 s to the first new frame). A resume starts the sfx
  only when the clock plays; one that still buffers (seek, reread) leaves it to the landing. ⚠ Without the reread a segment served like YouTube's (200
  chunked, no ranges, which FFmpeg marks streamed and never reconnects) and cut while paused lost its rest plus the
  next segment, freezing the picture with no BUFFERING (watermedia#275); a lost span over `PTS_DISCONTINUITY_SEC`
  shows as a content cut instead. A cut in the middle of playback is still lost (PENDING D15).
- Audio tracks: the ctor lists one provisional entry (`stream=-1`) per separate track (none without an sfx); `opened`
  rebuilds `audioTracks` from `decoder.audioStreams()` (main input's entries replaced on every open, an opened track's
  streams replace its provisional entry for the player's life) and sets `audioTrack`. `audioPick` (volatile) survives
  quality switches and restarts; a provisional pick equals the stream its origin resolved to.
- `Callbacks` (`FFDecoder.Host`): `cached(uri)` → `NetworkCache.readFile` (skips `.m3u8`/`.mpd`, gated by
  `codecs.ffmpeg.cache` and `cacheMaxResponse`), `vulkan(codecId)` → `new FFDecoder.Device(vk.hwDevice(codecId),
  vk.placebo())` when `gfx instanceof VKEngine`, `audio(...)` negotiates channels/type with `SFXEngine`
  (exact → closest; type kept if supported, else S16, else first), `duration(ms)` → `mediaDurationMs` (on every
  open and reopen, 0 when unknown), `opened` (HLS probe thread, fps, source size, `resolveQuality`, pre-`gfx.format`,
  then `clock.start()`, which buffers until the landing),
  `failed` → ERROR.
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
     `gfx.preload(frames)`, passive clock `texTime()` read from the wall clock by `time()` and `status()` (ENDED lands
     without a draw); `texture()` picks the frame (`frameAt` binary search → `gfx.frame(i)`), ⚠ so the engine's frame
     and `fps()` (`currentDelayMs`) follow only drawn frames.
  3. Streaming (`TxPlayer-Stream` thread): prefetch queue (`PREFETCH_MAX=6`, `PREFETCH_BUDGET_BYTES=64 MiB`, pooled
     frame-sized direct buffers, `IN_FLIGHT_KEEP=2`), decode permits shared across players (`DECODE_PERMITS =
     Semaphore(max(2, baseThreads))`), hot `scale`/`lod`, seek by decoding forward from frame 0 (`reader.reset()` or
     reopen), frame stepping while paused, BUFFERING refills, repeat by reopening.
- Locks: `signals` (status writes, triggers, `prepareActive`), `clock` (passive clock pair; order `clock` → `signals`),
  `releaseLock`; `lifecycleSerial` makes stale passes unable to publish status.
- Overrides: `forward/rewind` 1 s, `canSeek()` = `playable && animated`, `fps()` from the current delay, `duration()` =
  known duration (a timed still reports its `displayTime`, an untimed one 0). `stop()` keeps the texture; `release()`
  throws from its own threads (`ERR_TXPLAYER_RELEASE_CALLBACK`) and after a 5 s wait for image preparation
  (`ERR_TXPLAYER_RELEASE_BUSY` "..., retry after I/O finishes").

### 7.7 `NetworkCache` (package-private, lives in `api/player`)
- `start(Path dir)`: loads `index.dat` (`MAGIC=0x574D4943`, `VERSION=5`), one startup sweep deletes `*.part` and
  unindexed `wm_*.tmp` older than `SWEEP_GRACE_MS=60_000` (⚠ no periodic timer), publishes the `Store`.
- `read(uri, headers, accept, maxBytes, enabled)` → `CachedBytes(bytes, contentType, cached, expiresAt)`;
  `readFile(...)` → `CachedFile(path, cached, contentType)` or null for playlists (`mpegurl`, `dash+xml`), non-HTTP,
  uncacheable headers or a disabled cache; bounded fetch fails (IOException → caller streams directly) without
  `Content-Length`, over `maxBytes` or on ICY streams.
- Key = SHA-256 of URI + header lines (names lowercased, values verbatim) (+ `accept:` when the headers carry no
  `Accept`); expiry from `Cache-Control`/`Pragma`/`Expires` else
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
  per-context `Kit` program) → `Env.restore()` → `sweepOrphans()`. Without a thread contract (both nulls), or with the
  producer on `renderThread`, `push` renders at once (`Env.save` → `renderSubmission` of the client buffers through
  the `NUM_PBOS` PBOs; no `pending`, no `Hub`), and an engine built with both nulls never maps a ring.
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
  the first upload or an accepted `preload(frames)` (only with `preload` true).
- `Colorimetry(Matrix matrix, boolean full, boolean cositedX, boolean cositedY, Transfer transfer, boolean wide)`
  (package-private record): `Matrix {BT601, BT709, BT2020}` with `kr/kb`, `Transfer {SDR, PQ, HLG}`; `guess(format,
  w, h)` (BT.709 when `w ≥ 1280 || h > 576`), `constants(format, w, h, bits)` → 16 floats (range, matrix, chroma
  siting, transfer/wide) consumed by `colorimetry.glsl` (`ycbcr()`, `display()`: PQ/HLG → linear with SDR white 203
  nits, BT.2020→709, highlight knee from 0.75 to a 1000-nit peak, BT.1886 re-encode).

### 8.5 Audio engines
- `SFXEngine` (abstract sealed, permits `ALEngine`, `JSEngine`): `MIN/MAX_SAMPLE_RATE_HZ 4_000/384_000`; records
  `ChannelSupport(channels, Set<SampleType>)` and `SpatialAudio(x, y, z, referenceDistance, maxDistance, rolloff,
  auxOnly, Environment)` with `Environment.process(source, audio)` / `reset(source)` (host sound thread, e.g. a
  spatial sound mod adapter; owner 2026-11-05: docs name no specific mod). Public: `supportedChannels()`, `supportedTypes()`, `channelSupport(int)`,
  `closestChannelSupport(int)`, `source()`, `spatial()`, `spatialAudio([SpatialAudio])`, `sampleType()`, `channels()`,
  `sampleRate()`, `speed()`, `volume()`, `canSpeed()`, `released()`, `release()`. Driver API: `format(type, channels,
  rate)`, `pause()`, `play()`, `speed(float)`, `volume(float)`, `upload(ByteBuffer)` (direct, nonempty, whole frames;
  false when the queue is full, unconfigured or released, checked before the IAE `ERR_SFX_AUDIO_DATA` of a bad chunk),
  `flush()`, `pendingMs()` (audible position = last pts + duration − pending).
- `ALEngine(buffers=DEFAULT_BUFFER_COUNT=8, spatial)`: needs the host's OpenAL context current on every mutating call
  (`ALC_EXT_thread_local_context` honoured); table mono/stereo U8/S16 (+FLT/DBL when the driver exposes them), 4/6/7/8
  channels U8/S16/FLT with `AL_EXT_MCFORMATS` on a plain engine (FLT even without `AL_EXT_FLOAT32`, never DBL); buffer
  queue with duration FIFO, `AL_PITCH` speed, `pendingMs` via `AL_SOFT_source_latency` on the primary; AL errors
  throw `IllegalStateException`. No device reload or listener API. Every source starts dry and listener-relative
  (`clearSpatial`), plain ones included.
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
  native handle, non-blocking writes (`available() < len` → false), `MASTER_GAIN` volume. A chunk wider than the line
  buffer reopens the line at 2×len (dropping what it queued, resuming if it ran); without a wider line the chunk is
  dropped (`dropWarned`). Kept as core API; the app uses OpenAL only.

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
  (permanent for the process, clears the search cache), `platforms()`. Search: `search(query[[, limit], reSearch])`
  returns at once a live read-only list filled by 4 `PlatformAPI-Search` workers (one task per platform,
  `SEARCH_TIMEOUT_MS=5_000`, per-platform limit `min(limit, platforms.searchLimit)`), typing debounce 300 ms when the
  query is ≥ 0.7 Jaro-Winkler similar to the previous one, LRU cache of 32 queries cleared every
  `platforms.searchCleanupInterval` minutes (0 = effectively no cache), `searching(query)`, `searchHistory()` (10).
  Built-in search only in YouTube, Twitch, Kick, Imgur.
- `internal/WaterPlatform` (`water://` scheme, spec `WATER.md`): `local/<path>` → `WaterMedia.cwd()` file URI (⚠ `..`
  escapes cwd; a path that resolves to a Windows share throws, `ERR_WATER_LOCAL_SHARE`), `remote/<id>` → `NetworkAPI.remoteUrl()/<id>`, `global/<id>` → `GLOBAL_SERVER`
  (`https://watermedia.srrapero720.me/`); `location(URI)` reusable translator.
- `web/WebRequests` (package-private): `fetchJson(NAME, uri|NetRequest, Class)` (status/body errors → `PlatformException`),
  `follow(URI)` (short links), `hls(playlist, headers)` (best-effort ladder via `M3UTool.qualities`),
  `qualities(playlist, variants)` (drops audio-only; fallback `Quality(playlist,0,0)`).
- Handlers (`public final class XPlatform implements Platform`, `NAME`, `IT`, hosts via `DataTool.equalsAnyIgnoreCase`
  or dot-boundary suffixes through `WebRequests.hostMatches(host, suffixes...)`, Gson-bound private records last;
  durations in seconds converted to ms unless noted):
  `YtDlpPlatform` (sealed base, hosts soundcloud/snd.sc/facebook/fb.watch/instagram; runs `yt-dlp -J` out of
  process (about 3 requests to the site per run; without a JS runtime yt-dlp 2026.10.26 answers YouTube through its
  VISIONOS client: HLS video plus https DASH audio), `PROCESS_TIMEOUT_SECONDS=120`, playlists skip bad items, age limit gate, expiry `DataTool.expiry` else 4 h /
  live 30 min; one audio `Track` per `language`, each its best `abr`/`tbr`, ranked by yt-dlp's `language_preference`
  (YouTube: 10 original, 5 default, -1 other, -10 descriptive) then bitrate, named from the head of `format_note`
  without `(default)`/`(original)`; untagged formats drop once any is tagged; audio-only sources take the top one)
  → `YouTubePlatform` (youtube.com/youtu.be, `--no-playlist` for video ids, bot-check retry with a
  BotGuard `po_token` from `rustypipe-botguard` under `botGuardLock`; a gate that survives the token is retried with
  `--force-ipv4` (`tokenInfo`), which then stays on for every later run and search while it gets links through
  (`ipv4`), is dropped when the gate reaches it too and is not tried again once it gated a first attempt
  (`ipv4Gated`); any other failure over IPv4 is reported with the gate suppressed and leaves it for the next link.
  YouTube flags whole IPv6 blocks; ⚠ FFmpeg still picks the family of the media request (one link resolved over IPv4
  answered 206 over IPv6 on the test host; not verified on a flagged network); a package-private
  constructor takes the visitor_id endpoint for tests; search `ytsearchN:`); `TwitchPlatform` (GQL with
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
  `upload(File...)`/`upload(File)` (validated up front, `IllegalStateException` `ERR_NETWORK_UPLOAD_NOT_LOADED` until
  `NetworkAPI.start` created the upload pool) → `UploadStatus`
  (`totalBytes()`, `uploadedBytes()`, `speed()`, `id()`, `uri()` = `water://remote/<id>`, `completed()`, `failed()`,
  `error()`, `percentage()`, `displaySpeed()`, `cancel()`; ⚠ a cancelled status reads as failed with "Cancelled").
  `error()` is text for the user: a server that is off or unreachable reads `(wm_err:0714) Failed to upload, the file
  server at <remoteUrl> is off or unreachable` (`ERR_NETWORK_UPLOAD_UNREACHABLE`: refused, no route, unresolved or
  a connect timeout), one that took the connection and went silent `(wm_err:0715) ... did not answer in time`
  (`ERR_NETWORK_UPLOAD_NO_ANSWER`), a wrong `remoteUrl` its own coded message; only unexpected failures keep the
  exception's `toString()`.
  POST `<remoteUrl>/upload` with `network.token`, filename and content-type headers, fixed-length streaming in 8 KiB
  chunks; 503 → "busy server" failure (`ERR_NETWORK_UPLOAD_SERVER_BUSY`). `remoteUrl()` validates `network.remoteUrl`
  (http(s), trailing slash stripped; else ISE `ERR_NETWORK_REMOTE_URL`). `start(...)`: upload pool, then
  `NetworkServer.start(network.serverPort, cwd)` when the package-private gate `enable(client, server)` passes
  (`network.server` is `ALWAYS`, or `DEDICATED` on a non-client); a failure logs ERROR `ERR_NETWORK_SERVER_START` and
  uploads keep working; a start that succeeds latches `serverUp`.
  - `serverEnabled()` (public): true once `serverUp` is latched, by the local start above or by a remote answer; it is
    never cleared. Otherwise it returns false and queues one request on the upload pool, `answers(remoteUrl())`, when
    the module started (`uploads` set), no request is in flight (`PROBING` CAS) and the pause is over (`probeAt`,
    `PROBE_RETRY_NS` 5 s after each request). A reached remote logs INFO; a silent, foreign or misconfigured one logs
    WARN once per process (`probeWarned`). ⚠ The first call always returns false: callers poll it.
  - `answers(remote)` (package-private, shared with the port check): `NetRequest` GET `<remote>/` with
    `PROBE_TIMEOUT_MS` 5 s connect and read and `PROBE_MAX_BYTES` 64; true only on HTTP 200 with the body
    `WaterMedia.NAME` (what `GET /` serves), false for any other answer, `IOException` when nothing answers.
- `NetworkServer` (package-private entry points `start`, `address`, `stop`): JDK `HttpServer` bound to
  `network.serverBindAddress:serverPort` (`127.0.0.1:25572`; ⚠ a non-loopback bind with the default token refuses to
  start), storage `cwd/watermedia/files` with a `.server.lock` file lock and an `.upload-*` sweep, elastic
  `ThreadPoolExecutor` (core = `maxServerRequests`, exchange cap 3× slots → connection dropped), admission filter answers
  `503` past `maxServerRequests` active requests, per-exchange idle `Watchdog` (`serverTimeout`), `Connection: close`
  echoed for FFmpeg. `POST /upload`: 401 token, 400 filename, 411 no length, 413 over `maxUploadSize`, 507 over
  `maxStorageSize`, stores `files/<8 lowercase alnum id>/<filename>` atomically, answers the id. `GET|HEAD /<id>`: first
  regular file of the directory, `Accept-Ranges`, one `bytes=` range → 206 (416 when unsatisfiable, malformed ranges
  ignored), `Content-Disposition` on full answers. The module never calls `stop()`. ⚠ The JDK dispatcher is not a
  daemon thread: a JVM whose `main` returns stays alive while the server runs (child-JVM probes call `stop()`).
  - Port check (a fixed port only; port 0 is ephemeral, as in tests, and skips both steps): before the storage is
    created, locked or swept, a probe `ServerSocket` binds and closes `serverBindAddress:port` (IOException
    `ERR_SERVER_PORT_BUSY` when busy or blocked); after `server.start()`,
    `NetworkAPI.answers(NetworkAPI.remoteUrl())` must be true. Otherwise the server, both pools and the storage lock are
    released and it throws IOException `ERR_SERVER_PORT_CLOSED` (an invalid `remoteUrl` throws its ISE
    `ERR_NETWORK_REMOTE_URL` the same way). ⚠ A router without NAT loopback fails the second step.
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
- `MediaType {IMAGE, VIDEO, AUDIO, SUBTITLES, UNKNOWN}`: `of(mime)` (HLS/DASH/RealMedia → VIDEO, subtitle mimes incl.
  `application/x-subrip`), `mime(ext)`, `ofExtension(path)` (right-to-left extensions). No byte sniffing here (that
  is `CodecsAPI.sniff`).
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
  whole; masks URI passwords, JWTs, `--accessToken`/`--session`, `Authorization`, cookies (any `...cookie` key, so
  `biliBiliCookie` too), the rest of an `X-WaterMedia-Token` line, secret key/values, a bare `token` key of a config
  file (`token = "x"`, `network.token=x`, a quoted value whole; possessive, so a long value never overflows the
  stack) while `?token=` link parameters and compound keys (`nextPageToken`, `token_type`) stay, and `token:x:y`),
  `sha256(byte[])`, pixels (`yuvToBgra`, `bgraToBuffer`, `blendOver`, `premultiply`, `orient`), scaling (`scalable`,
  `scaleArea`), `errCode(int)` (takes a `WaterMedia.ERR_*` constant and returns the `"(wm_err:0720) "` prefix of an
  error message, the code padded to four digits and a wider one kept whole; registry in §15). ⚠ A new secret shape
  goes into `redact` only: the loggers and the app's log upload (config copies included) both mask through it.
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
  unit)`, `join(Thread)` (uninterruptible, restores the flag), `sleep(ms)`, `processors()`, `baseThreads()`,
  `silentCall(SilentRunnable)` (swallows), `safeCall(SilentRunnable, Marker, log)` (logs ERROR with the given message).

## 12. Standalone app (`bootstrap/app`, internal)

### 12.1 Runtime
- `WaterMediaApp.start(Runnable task)` (render thread): `WaterConfig.setPath(<home>/config)` (the app owns the folder
  of its config files, ⚠ `setPath` takes the config folder itself, not the home) → `AppConfig.register()` →
  `initWindow()` (GLFW, engine from
  `-Dwatermedia.engine` or `AppConfig.engine`, `RenderSystem.chooseEngine/attach`, window rebuilt for GL when Vulkan
  attach fails) → `TextRenderer` → `Assets.load` → `initShell()` (`RootScreen`, global error/exit dialogs) →
  `LoadingScreen` frames while thread `WaterMediaApp-Init` runs `WaterMedia.start("WaterMediaApp", null, AppEnvironment.CURRENT.home(), true)` →
  `catalog.load()`, `initScreens()` (`home`, `mrl`, `regions`, `player`, `multimedia`, `settings`), extension SPI →
  `mainLoop()` (nested `WaterMediaApp.FramePlan {IDLE, DIRECT, CACHE, COMPOSITE}` and `WaterMediaApp.FrameLimiter`
  paced to the monitor refresh,
  `glfwWaitEventsTimeout` when idle, `GlowPulse` thread every 250 ms, `AppConfig.poll`) → `cleanup()` ends with
  `System.exit(0)`. Hot swap GL↔VK (`requestEngineSwap` → `performEngineSwap` at the top of the loop) releases every
  player, disposes text/assets, destroys the window and rebuilds everything on the new backend.
- `AppContext`: window/input state (logical `mouseX/Y` = physical / `uiScale`), services (`catalog`, `media`, `sound`,
  `assets`, `upload`, `cleanup`, `download`), `execute(Runnable)` (render-thread queue + wake), `requestRender()`,
  `releaseAsync(name, runnable)` + `awaitPlayerRelease()` (blocking teardown off the render thread), `showError(...)`,
  static `clipboard()` (the text, or "" when empty, busy or headless) and `clipboard(String)` (true once copied).
  ↪ They are the app's only clipboard door (pastes in `OpenMultimediaScreen` and `TextArea`; copies in
  `MRLSelectorScreen` and `LogUploader`); only the launcher's Swing `BootstrapWindow` keeps its own AWT copy.
- `AppConfig` (WaterConfig spec `app`, `config/app.properties`): `engine` (VULKAN default), `scale` (0 = auto, else 1..3
  step 0.25), `crt`, `sound`; `apply(ctx)` applies live and triggers an engine swap only when the preference changed.
- Services: `Assets` (icon, banner, duck frames via AWT ImageIO before codecs exist; glow textures), `WinFrame` (Win32
  custom frame: caption hit-test, snap, DPI reclamp), the dialog services `LogUploader` (+ `SystemInfo`) and
  `CacheCleaner` (below), `ClickSound` (own OpenAL device, `duck.ogg`), `MediaSession` (selected group/MRL/source/quality/player,
  `releasePlayer()` async), `MediaCatalog` (`assets/watermedia/catalog/uris.json`, `iptv.json`; `debug` entries only when
  DEBUG logging), nested `AppContext.ErrorState`, `ScreenManager` (`register`, `navigate(name)` without a stack, mounting over an `under()` screen through
  `RootScreen.mountUnder`, `releaseMediaAll`).
- Dialog services `ctx.upload` (`LogUploader`) and `ctx.cleanup` (`CacheCleaner`, scan and wipe of
  `WaterMedia.tmp()/cache`): public volatile state (`visible`, `working`, `done`, `error`, `stage`, own fields) that
  `HomeScreen.onUpdate` binds every frame; `step()` (from `WaterMediaApp.navigateAction`) runs the next stage on a
  `ThreadTool.thread` worker that ends with the repaint callback. ⚠ `step()` checks `working` before `visible`, so a
  dialog reopened mid-stage shows that stage instead of resetting it under a second worker.
- `MediaDownloader` (`ctx.download`, 2026-11-22): the download dialog's picks, probes and transcode; the element tree
  is `DownloadDialog` (§12.4). Picks and probe results belong to the render thread: workers (`MediaDownloader-Probe`,
  `-Transcode`, `-Reveal`) hand results back through the `Executor` it was built with (`AppContext.execute`).
  - `open(source, title, page, preferred)` resets the picks to the `SOURCE` preset (a running download just shows
    again); `title` is the source's own or null, and the file is then named after the last part of `page`.
  - Runtime retention (owner, 2026-11-22): `start()` keeps its picks in the private `last`, a `Picks(container,
    quality, video, audio, fps, effort, hardware, videoRate, audioRate, preset)` record, and every later `open()`
    restores them (the quality as the closest one the new source has, ahead of `preferred`) before `normalize` fits
    them to that source. It lives only while the app runs, and picks that started no download are never kept.
  - Public picks the dialog writes: `container`, `quality`, `video`/`audio` (null = copy), `fps` (0 = source),
    `videoRate`/`audioRate` (slider 0..1), then `changed()` (leaves the preset, runs the rules); `effort` and
    `hardware` need no `changed()`. `preset(Preset)` keeps the preset on, so a probe that lands later completes it.
  - Rules (`normalize`): `SOURCE` copies both tracks into WEBM when both codecs were born in it, else MP4, else MKV
    (an audio-only source gets the audio container of its codec); `COMPATIBILITY` is MP4 + H264/AAC (a track already
    in them is copied) at the best resolution up to 1080p; `AUDIO` is the audio container of the source's codec. A
    codec the container refuses moves to the copy when it fits (`allowed(null)`; MKV takes anything), else to
    `VIDEO_FALLBACK` (H264, VP9, HEVC, AV1) or the first of `AUDIO_CODECS`; an fps at or above the source's becomes 0.
    A source read whole that has no sound never stays on an audio container (it goes back to the one of its video)
    and turns the `AUDIO` preset into `SOURCE`.
  - Probes: one `FFTranscoder.probe` per picked resolution URI and one for the first `audioTracks()` entry, cached
    per URI (`info()`, `picture()`, `sound()`, `probing()`, `failure()`). `open()` keeps the cache for an equal
    source and only drops the failures, so a dialog opened again reads nothing twice. ⚠ A running probe cannot be
    cancelled: it ends by itself and its result is dropped when the source changed.
  - Bitrates: `videoMax()`/`audioMax()` are the source's (guessed when unknown: 0.07 bits per pixel, 64 kbps per
    channel; lossy audio capped at 320 kbps); `videoBitrate()`/`audioBitrate()` map the slider between a floor and
    that maximum, never above it; `estimate()` = bitrates over `durationMs()`.
  - `start()` only when `ready()` (probes landed, no failure, a known length, a track to write): the file goes to
    `<user.home>/Downloads`, else `<cwd>/downloads`, named `fileName(name)` (no separators, refused characters, lone
    surrogates, trailing dots or Windows device names; 120 chars) plus ` (n)` while taken. The worker reserves that
    name with an empty file (`Files.createFile`) which the finished download replaces, so a file saved under it
    meanwhile is never overwritten; a download that wrote nothing deletes it. `cancel()` stops a running download or
    hides the dialog; `back()` returns to the options; `reveal()` opens the folder; `shutdown()` (from
    `WaterMediaApp.cleanup`) cancels and waits up to 3 s so the `.part` is deleted before exit.
  - ⚠ Pictures are never offered (`MediaType.IMAGE`), a live media (length 0) never becomes `ready()`, and only the
    first audio track (the original language) is downloaded.
- `LogUploader` (dialog stages SCAN/UPLOAD/ISSUE = `stage` 1..3; workers `LogUploader-Scan`, `-Upload`, `-Report`,
  `-Submit`):
  - Gate: `AVAILABLE` opens the dialog (else the home tile reads `LOCKED`) where `AppEnvironment.CURRENT.type()` is
    `MINECRAFT` or `DEVELOPMENT`, the two types whose home is an instance to read (§5 `AppEnvironment`). ⚠ Nothing is
    simulated in a dev run: uploads reach mclo.gs and the issue page opens in the real browser.
  - Sessions: a hidden dialog at `stage == 3 && !issueOpened` reopens as it was (uploads, report, texts) instead of
    rescanning, which would paste the same files again; any other hidden dialog starts a fresh session (scan).
  - `scan(cwd)` (called with `AppEnvironment.CURRENT.home()`): one layout, since the home is the instance root (a
    Minecraft instance, or `run/` in a dev run): the game and the app share `config/` and `logs/`, and the jars come
    from `<cwd>/mods`. Rows are
    `FileEntry(name, label, path)` (the label names the report line): `latest.log`, newest `crash-reports/*.txt`,
    newest `hs_err_pid*` of the CWD, `logs/watermedia-app.log`, `<cwd>/<WaterConfig.getPath()>/watermedia.toml`
    ("Config") and `app.properties`. ⚠ Releases up to 3.0.2 ran the app from `mods`, so an old `mods/config` and
    `mods/logs` may remain there; nothing reads or migrates them. A found row reads `READY`, or `TOO LARGE` past 10 MiB or 25 000 lines; a missing one `NOT FOUND`.
    It also keeps the lowercased `*.jar` names (`jars`) for the suspected mods and the extension alerts.
  - `upload`: each uploadable row is read with a lenient UTF-8 decoder, masked by `DataTool.redact` and `maskUser`
    (`readContent`) and POSTed as a form to `https://api.mclo.gs/1/log` through `NetRequest.httpsOnly()`.
  - `maskUser(text, home)` hides only the user folder of home paths before anything is published (uploads and
    `body()`): `USER_PATH` (a drive path under `Users`, `/home/x` and `/Users/x` outside a link), `HOME_PATH`
    (`homePath(user.home)`: the real home with any separator, for names with spaces or other folders; `null` without
    a parent folder) and the `USERNAME=` line of an `hs_err` environment. Local logs keep their paths whole.
  - ISSUE: CONTINUE starts `report()` (`SystemInfo.read(jars)`, seconds of PowerShell queries) while the user types
    `title` (`TITLE_LIMIT_CHARS` 80) and `description` (`DESCRIPTION_LIMIT_CHARS` 2000); `ready()` turns true when it
    lands. A failed read sets `error`, and the stage 3 `step()` reads the report again while it is missing (the RETRY
    button); with a report it submits only under a non-blank title. `submit()` composes `body(description, info, jars,
    files, codes)` (description, `## Alerts`, `## Suspected mods` from the `SUSPECT_MODS` ids inside jar names,
    `## Error codes` (`- None detected`, or one line of the codes as `wm_err:NNNN` in backticks), `## Files` as
    `label: url` or `N/A`, `## Environment` with the SW codecs folded in `<details>`, `## System Properties`), copies it
    with `AppContext.clipboard(body)` and browses `issueUrl(title, body)`: the body rides in the link only while the
    encoded link fits `URL_LIMIT_CHARS` (8000), else `PASTE_NOTE` ("Paste the report here."); a refused long link
    retries once with the short one while the clipboard holds the report. A failure sets `error`, plus `browserFailed`
    when it came from the browser (the clipboard alone fails before the browser is tried). `title`/`description`
    outlive a closed dialog and clear on the first session after `issueOpened`.
  - Error codes: `readContent` adds every `ERROR_CODE` match (`\bwm_err:(\d{4})\b`, so an unpadded or five-digit
    code is skipped) of the masked text of each file it reads for the upload, config copies included, to `codes`
    (package-private sorted `ConcurrentSkipListSet`). A fresh session clears it; a resumed stage 3 keeps it. A row
    that is `TOO LARGE`, missing or fails to read adds none.
- `SystemInfo`: package-private record `(alerts, environment, codecs, system)` built by `read(jars)` on the report
  worker, once per gathered report. Alerts: NVIDIA Threaded Optimization (Windows, GL), FFmpeg load failure, and
  `extensionAlerts(jars)` for `watermedia-youtube-extension`/`watermedia-platform-extension` jars (case, `-` and `_`
  ignored). `environment`/`system` are Markdown list lines; `codecs` is the sorted FFmpeg SW decoders, or null while
  FFmpeg is not loaded; the "Extensions:" line of `environment` lists the `AppBootstrap.Extension` SPI providers,
  not jars.
  ↪ Tests build the record by hand to compose bodies.

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
- `RenderBackend` interface (`init`, `cleanup`, `configureFrameState`, `clear`, `viewport`, `createTexture`,
  `deleteTexture`, `bindTexture`, `useProjection`, `draw(DrawMode, float[], count, textured)`, `lineWidth`, `clip`,
  `clearClip`, `mediaEngineSupplier(Thread, Executor)`; optional `supportsSoftRect`/`softRect`, `beginFrame/present`,
  layers, `bindMediaTexture`, `deviceName/Version`); its nested `RenderBackend.DrawMode` and
  `RenderBackend.TextureHandle` are the shared vocabulary of the backends and `RenderEngine`.
  `OpenGLRenderBackend` (GL 3.2 core, `opengl/ui.*` + `sdf.*` shaders, stream VBO, FBO layers, `-Dwatermedia.gldebug`).
  `VulkanRenderBackend` (also the `VKContext`: instance 1.1..1.4, device scored by discrete GPU and host import, queue
  plan lending FFmpeg decode/compute/transfer queues and a second render-family queue, video decode extensions when
  available, swapchain FIFO, two frames in flight, shaderc-compiled `vulkan/ui.*`/`sdf.*`, push constants 68/104
  bytes, `retire(Runnable)` deferred destruction, `queueLock` around every submit/present/wait; ⚠ no validation
  layers).

### 12.3 Element toolkit (`app/element`, `app/ui`)
- `Element<T>` (self-typed fluent base): sizes `MAX_PARENT=-1`/`WRAP_CONTENT=-2`, `margin`, `padding`, `gravity`,
  `weight`, decor (`background`, `border`, `radius`, `glow`, `shadow`), flags; final pipeline `measure` → `layout` →
  `draw`; hooks `onMeasure` (set `contentWidth/Height`), `onLayout`, `onDraw`, `onUpdate`, `onPress/onDrag/onRelease`;
  dispatch `dispatchClick/Hover/Press/Scroll/Key/Char`, `textInputActive()`, `clearFocus()`; `invalidate()` →
  `ctx.requestRender()`. ⚠ `ctx` is null until attached; guard text measuring. ⚠ `Group` routes keys and chars, and
  answers `textInputActive()`, through hidden children too: a pane that hides blurs its inputs (`clearFocus()`, as
  `HomeScreen.ReportPane` does on its visibility edges).
- Containers: `Group<T>` (children, reverse-order dispatch), `Parent.column()/row()` (weights, `spacing`),
  `ParentFrame` (overlay by gravity), `ParentScroll` (+ package-private `ScrollState` momentum; clicks, hovers and
  presses route only inside its viewport, so a scrolled-out slider never captures the pointer), `Box`.
- `Canvas` (the only draw surface): alpha/clip stacks, `fill`, `fillRound`, `stroke`, `glow`, `shadow`, gradients,
  `line`, `cube`, `image(textureId, ...)`, `mediaImage(long handle, ...)` (player frames), `icon(name, ...)`
  (`PixelIcon` masks), single-line `text*` through `TextRenderer` (AWT glyph atlas; JetBrains Mono SemiBold as plain
  and ExtraBold as bold from `AppTheme.FONT_PLAIN`/`FONT_BOLD`, Consolas fallback; one glyph per `char`, so surrogate
  pairs cannot show; no SDF/wrapping), `live(Consumer)` + `raise()` for painters over a cached tree.
- Widgets: `Text`, `Button` (+ static `render`/`width` for hand layout), `IconButton`, `Icon`, `Image`, `Badge`,
  `KeyChip`, `StatusSquare`/`StatusText`, `SectionHead`, `ProgressBar`, `SeekBar`, `Switch`, `CheckBox`,
  `SegmentedControl`, `Stepper`, `ChoiceGrid` (rows of `columns` choice buttons drawn by hand through `Button.box`:
  `cells(labels)`, `locked(index, value)` (dim, swallows its click), `picked(index)` bound by the owner, `onPick`; it
  never picks by itself), `TextField` (single line, append-at-end editing only; it binds no CTRL+V:
  `OpenMultimediaScreen` binds CTRL+V and a PASTE button that replace its value), `TextArea` (multi-line: soft wrap,
  caret by click, arrows, HOME/END and CTRL word moves, ENTER breaks the line, own CTRL+V through `insert`, scrolls to
  the caret; CTRL+ENTER (decided on the press), TAB and ESC reach the screen; `singleLine(true)` keeps one paragraph,
  every break set, pasted or held turning into a space, and never consumes ENTER (the issue title); ⚠ no selection,
  copy, cut or undo, BMP only), `Spinner`, `Dropdown` (ATTACH popup or DIALOG), `Dialog` (modal scrim + bands; an
  ENTER/ESC release that no child took, press included, runs `onPrimary`/`onDismiss`; `Panel.onMeasure` measures the
  title, header and footer bands first and offers the content only the height they leave, minus `PANEL_AIR` over and
  under the panel; ⚠ clicks in panel dead space reach the scrim unless `panel().consumeTouch(true)`), `ListView<D>`
  (virtualized when `rowHeight > 0`; an overflowing list reserves a scrollbar channel of the bar width plus
  `Theme.SPACE_XS` of air (less a right padding), so its rows end clear of the bar: `rowWidth`, natural rows measured
  again by `measureNaturalRows`) + `ListRow`, `VideoSurface` (aspect-fits
  `ctx.media.player.texture()`). ↪ Content taller than a dialog can offer must scroll or shrink by itself: a `ListView`
  at `WRAP_CONTENT` height clips to the offer and scrolls, while fixed-height content is never squeezed and leaves the
  window. ⚠ `value(v)` of `TextField`/`TextArea` never fires `onChange` (typing and `TextArea.insert` do). Tokens:
  `Theme` (spacing, heights, glow/shadow), `AppTheme` (colours, `FONT_PLAIN`/`FONT_BOLD` font resources, type ramp,
  `alpha(color, a)` cache), `Spacing`, `Gravity`.
- Conventions for a new widget: `public final class X extends Element<X>`, fluent setters returning `this`, never
  override `measure/layout/draw`, draw only through `Canvas`, animations `invalidate()` from `onUpdate` until done.
  An ENTER/ESC that leaves the widget acts on RELEASE (`TextField` `onSubmit`/`onEscape`, `Dialog`); an in-place edit
  acts on PRESS (`Spinner` commit/cancel; `TextArea` on PRESS and REPEAT) and swallows the paired release, which
  `Dialog.childConsumedKey` also mutes.

### 12.4 Screens (`app/screen`)
- `abstract BaseScreen extends Group<BaseScreen>`: lazy `build()`, `overlay()` for dialogs (`showDialog/hideDialog`),
  hooks `onEnter`, `onExit`, `keybinds()` (⚠ return constant lists of the nested `BaseScreen.Keybind(key, label)`),
  `continuous()` (⚠ true repaints every frame; a screen holding text inputs ORs `textInputActive()` into it, or the
  caret stops blinking), `under()`, `releaseMedia()`; screens that override `dispatchKey` call `super` first and act
  on `GLFW_RELEASE`. `RootScreen` hosts `Background`, `TitleBar`, the center slot, `KeybindsBar`, global dialogs;
  renders/composites the tree and routes input.
- `HomeScreen` (`enum Action {OPEN_MULTIMEDIA, UPLOAD_LOGS, CLEANUP, SETTINGS, REGION_SELECTOR, MRL_SELECTOR, PLAYER,
  EXIT, BACK}` is the navigation vocabulary; catalog tiles create every group MRL with `PlayerAPI.mrl`; dialogs below),
  `OpenMultimediaScreen` (URL/path vs search by `loadable(text)`; metadata preview MRL; `PlatformAPI.search`
  dropdown with thumbnail players; 30 s load wait; DOWNLOAD button and `D` while the field is blurred, enabled once
  the previewed MRL resolved to a source that is no picture, `downloadable()`), `MRLSelectorScreen` (group list with status, filter, thumbnail
  players for visible rows via `Previews`, reload of regenerable MRLs), `RegionSelectorScreen` (IPTV regions →
  TELEVISION group), `PlayerScreen` (`VideoSurface` + `CrtOverlay` + HUD; `PlayerAPI.player(selectedMRL, index,
  mediaEngineSupplier(...), PlayerAPI::alEngine)`; keys ESC/ENTER/V/D/SPACE/arrows/Y/U/S/./,/N/B/L/digits; quality and
  video dialogs; DOWNLOAD button in the top right corner and `D` open the download dialog on the playing source and
  quality (hidden for pictures); ⚠ not restored after an engine swap), `SettingsScreen` + `SettingsModel` (edits every WaterConfig spec
  reflectively, autosave on `WaterMedia-ConfigSave`, `SaveMachine` states; only the App spec has a live applier),
  `LoadingScreen` (reads `WaterMedia.loading()/step()/steps()`), `TitleBar`, `Header`, `KeybindsBar`, `Background`,
  `CrtOverlay`, `Previews` (MRL status label/colour, `blocked(mrl)` reason text by exception type, thumbnail release
  through `ctx.releaseAsync`).
- `DownloadDialog` (2026-11-22; a `Group` holding one `Dialog`, mounted by `WaterMediaApp.initShell` on the root
  overlay under the error and exit dialogs, so every screen shares it): it only binds `ctx.download` in `onUpdate`.
  Owner layout: one narrow panel (`PANEL_W` 460) of stacked sections inside a `ParentScroll`, each a compact
  `SectionHead` over a `ChoiceGrid` of three cells per row: Container, Resolution (the source's qualities), Video
  codec (`SRC` = copy; ⚠ `AV2` is shown locked, `AV2_CELL`, until the natives bring an encoder, see `TODO.md`), Audio
  codec, Audio bitrate and Video bitrate (`SeekBar`, only for a track encoded again; never above the source's), Frame
  rate (`SRC`, 60, 50, 30, 25, 24; rates at or above the source's locked), Compression (effort), Encoder
  (software/hardware), Presets (two per row; the first reads FROM YOUTUBE on a YouTube link, FROM SOURCE elsewhere)
  and the size line (estimate and length, or why nothing can start: a failed probe, READING SOURCE, LIVE MEDIA HAS NO
  END, NO TRACK TO DOWNLOAD). Video sections hide for an audio container and lock for a copy; the audio containers
  and BEST AUDIO lock for a source without sound. While a download runs the content turns into the percent, a `ProgressBar` and the encoder name;
  then the file name and its folder, or the failure. ESC cancels the download, goes back from a failure or closes;
  ENTER starts or closes; the left footer button becomes OPEN FOLDER once done. `keybinds(download)` feeds
  `WaterMediaApp.footerKeybinds`.
- `HomeScreen` dialogs: the upload and cleanup `Dialog`s stay mounted on the overlay and only follow `ctx.upload`/
  `ctx.cleanup` in `onUpdate`; the primary buttons reach `step()` through `navigator.accept(Action.UPLOAD_LOGS|CLEANUP)`
  (the cleanup CLOSE hides the dialog itself). Upload (`Stepper` SCAN/UPLOAD/ISSUE, one `UPLOAD_PANEL_W` width):
  stages 1..2 show the file `ListView` of `FileRow`s at one `UPLOAD_ROW_H` (every row on SCAN, only candidates and
  failures on UPLOAD), which clips to what the dialog offers and scrolls; a plain ENTER uploads or continues there.
  Stage 3 shows the nested `ReportPane`: title (a `singleLine` `TextArea`, `TITLE_LIMIT_CHARS`), description
  `TextArea` (`DESCRIPTION_LIMIT_CHARS`), live `n/limit` counters (amber at the limit) and the `ReportStrip` line from
  `HomeScreen.ReportStatus.of(working, ready, titled, opened, copied, browserFailed, error)` (`REPORT_FAILED` while
  the report is missing after a failed read, `ALL_FAILED` when clipboard and browser both failed). `ReportPane` focuses
  the title when it shows and blurs both boxes when it hides, and owns every ENTER of the stage so the dialog never
  submits on a plain one: only CTRL+ENTER (decided on the press, opened on the release) or the button submits; a
  plain ENTER breaks a description line, moves from the title to the description, or with nothing focused focuses the
  title; TAB swaps the boxes on press. The primary reads "OPEN ISSUE" (needs a title) or "RETRY" after a failed
  report (needs nothing). `ReportPane.onMeasure` gives the title one line and the description 8 lines down to 2 from
  the height the dialog offers.
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
| `FFDecoder` | `<group>-demux`, `<group>-track`, `<group>-video`, `<group>-audio` | Read each input, decode each stream |
| `NetworkAPI` | `NetworkAPI-Upload` fixed 4 | Uploads |
| `NetworkServer` | `NetworkServer` elastic pool, `NetworkServer-Deadline` scheduler | Requests; idle watchdogs |
| `PlatformAPI` | `PlatformAPI-Search` scheduled pool of 4 | Search probes |
| `YtDlpPlatform` | `<tool>-stdout/-stderr` per process | Process output drains |
| App | `WaterMediaApp-Init`, `WaterMedia-GlowPulse`, `WaterMedia-PlayerRelease` (chained), `LogUploader-*`, `CacheCleaner-*`, `WaterMedia-CacheSize`, `*-LoadTimeout`, `WaterMedia-ConfigSave` | Boot, repaint, teardown, dialogs, saves |

Key locks: `MediaPlayer.tickLock` (armed/heard/TICKING) and the `statusEvents` monitor (ordered delivery);
`MasterClock` `ReentrantLock` + `Condition` (mutations; reads volatile); `FFMediaPlayer.lifecycleLock`/`releaseLock`;
`FFDecoder.control` (generation, floor, hold, reader flags; wait/notify); `FrameQueue.lock`/`PacketQueue.lock`
(wait/notify); `MRL` Session monitor + `loadLock`;
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
| `codecs.ffmpeg.trackPacketReads` | 3 (1..12) | Nothing since 3.0.4 (a separate track has its own reader); kept so config files load |
| `codecs.ffmpeg.cache` / `cacheMaxResponse` | true / 10 MB | `FFMediaPlayer.Callbacks.cached` |
| `codecs.ffmpeg.analyzeDuration` / `probeSize` | 7000 ms (0..60000) / 10 MB | `FFDecoder.inputOptions` |
| `player.defaultQuality` | `HIGHER` | `MediaPlayer` field init |
| `player.mrlCleanupInterval` | 60.0 min (≥ 1) | `MRL` sweep |
| `player.cacheBudget` | 8192 MB | `NetworkCache` eviction |
| `player.watertx.cache` / `texturesBudget` | true / 32 MB (0..512; 0 = streaming) | `TxMediaPlayer` |
| `player.whitelist.enable` / `sites` / `trustLocal` | false / `youtube.com`, `youtu.be`, `twitch.tv`, `imgur.com`, `kick.com` / true | `MediaPlayer.checkSite` (per MRL load and per player) |
| `player.blacklist.enable` / `sites` | false / empty | `MediaPlayer.checkSite` (per MRL load and per player) |
| `platforms.allowMatureContent` | false | mature gates in the web handlers |
| `platforms.searchCleanupInterval` / `searchLimit` | 15.0 min / 2 (1..50) | `PlatformAPI` |
| `platforms.biliBiliCookie` | "" (password) | `BiliBiliPlatform` |
| `network.server` | `OFF` (`DEDICATED`, `ALWAYS`) | `NetworkAPI.start` (startup) |
| `network.serverBindAddress` / `serverPort` | `127.0.0.1` / 25572 | `NetworkServer.start` (startup; a fixed port must bind and answer through `remoteUrl`, §10) |
| `network.maxServerRequests` / `serverTimeout` | 8 (1..256) / 30000 ms | `NetworkServer` (startup) |
| `network.maxStorageSize` / `maxUploadSize` | 1024 MB (≥ 1) / 8 MB (0 = unlimited) | `NetworkServer` (startup) |
| `network.remoteUrl` | `http://localhost:25572/` | `NetworkAPI.remoteUrl` (per use; the file server's startup check asks it, so it must lead back to this server) |
| `network.token` | `watermedia_default_token_change_it` | server start (refuses the default off loopback) and every upload |
| `network.timeout` / `maxRedirects` / `maxTextBytes` | 15000 ms / 10 (0..50) / 16 MiB | `NetRequest` defaults, uploads, yt-dlp socket timeout |

App (`config/app.properties`): `engine` (`vulkan` default), `scale` (0 auto), `crt` (true), `sound` (true).
System properties: `watermedia.engine`, `watermedia.app` (child JVM flag), `watermedia.glchecks`, `watermedia.gldebug`,
`watermedia.ytdlp.binary`, `watermedia.botguard.binary`, tests `watermedia.test.requireNatives`,
`watermedia.test.classpath`.

## 15. Logging, errors and hardening
- Redaction is universal (`WaterMedia.logger` → `DataTool.redact` on message, parameters and stack traces); tests
  `LoggerRedactionTest`. Links are never censored (owner decision 2026-11-01), only secrets inside them. Native FFmpeg
  logs are routed under the `FFmpeg` marker (§ CodecsAPI, `NativeLog`), redacted like any other line.
- Error policy by layer: codecs throw `CodecException`/`UnsupportedFormatException` (279 + 16 throw sites), platforms
  `PlatformException` (112), players/engines `IllegalStateException` for lifecycle misuse and `IllegalArgumentException`
  for bad input; engines log and drop bad frames instead of throwing (except `ALEngine` on AL errors); status failures
  surface through `MediaPlayer.exception()` and ERROR status.
- Error codes (`wm_err`; owner rule 2026-11-16, message form and naming in §2): `DataTool.errCode(WaterMedia.ERR_X)`
  writes the prefix and the app's log upload lists the codes it finds (§12.1). The constants live in `WaterMedia.java`
  right after the default paths, under a header comment: `public static final int ERR_UNKNOWN = 0` (reserved for
  unknown, no site), then one `// <PACKAGE> <first>-<last>` comment per range (`// ROOT PACKAGE 1-99`,
  `// TOOLS 100-399`, `// UTIL 400-699`, `// NETWORK 700-999`, `// PLAYER 1000-1999`, `// PLATFORM 2000-3999`,
  `// CODECS 4000-7999`, `// BOOTSTRAP AND APP 8000-8999`) holding one `// Class first-last` comment per block
  (`// NetworkServer 720-769`) over its constants. 9000-9999 belong to Binaries and extensions (Binaries has no codes
  yet). Each source file owns one block of its package range (nested types share it). Ranges, blocks and next free
  block:
  - Root 1-99 (next 30): `WaterMedia` 1-19, `WaterMediaConfig` 20-29.
  - `tools` 100-399 (next 180): `DataTool` 100-119, `FFTool` 120-139, `IOTool` 140-169, `M3UTool` 170-179.
    `ThreadTool` owns no block: `safeCall` forwards its caller's code.
  - `api/util` 400-699 (next 490): `HttpHeaders` 400-409, `MediaQuality` 410-419, `Metadata` 420-429, `NetRequest`
    430-469, `PixelFormat` 470-479, `Track` 480-489.
  - `api/network` 700-999 (next 770): `NetworkAPI` 700-719, `NetworkServer` 720-769.
  - `api/player` 1000-1999 (next 1300): `ALEngine` 1000-1019, `FFMediaPlayer` 1020-1049, `GFXEngine` 1050-1059,
    `GLEngine` 1060-1079, `JSEngine` 1080-1089, `MRL` 1090-1129, `MediaPlayer` 1130-1169, `NetworkCache` 1170-1189,
    `PlayerAPI` 1190-1209, `SFXEngine` 1210-1229, `SWEngine` 1230-1239, `TxMediaPlayer` 1240-1269, `VKEngine`
    1270-1299.
  - `api/platform` 2000-3999 (next 2460): `Platform` 2000-2009, `PlatformAPI` 2010-2019, `PlatformException`
    2020-2029, `PlatformResult` 2030-2039; subfolders:
    - `internal`: `WaterPlatform` 2040-2049.
    - `web`: `BiliBiliPlatform` 2050-2079, `BlueskyPlatform` 2080-2089, `DTubePlatform` 2090-2099, `DrivePlatform`
      2100-2109, `DropboxPlatform` 2110-2119, `ImgurPlatform` 2120-2139, `KickPlatform` 2140-2159,
      `LightshotPlatform` 2160-2169, `MedalPlatform` 2170-2179, `MediaFirePlatform` 2180-2189, `NewgroundsPlatform`
      2190-2239, `OdyseePlatform` 2240-2269, `PornHubPlatform` 2270-2289, `SendvidPlatform` 2290-2309,
      `StreamablePlatform` 2310-2319, `TikTokPlatform` 2320-2349, `TwitchPlatform` 2350-2379, `TwitterPlatform`
      2380-2389, `VidLiiPlatform` 2390-2399, `WebRequests` 2400-2409, `YouTubePlatform` 2410-2429, `YtDlpPlatform`
      2430-2459.
  - `api/codecs` 4000-7999 (next 5290): `BcReader` 4000-4009, `CodecsAPI` 4010-4039, `DownscaleFilter` 4040-4049,
    `Encoder` 4050-4109, `ImageData` 4110-4139, `ImageMetadata` 4140-4149, `ImageReader` 4150-4169; subfolders:
    - `common/dds`: `DdsHeader` 4170-4209.
    - `common/gif`: `ColorTable` 4210-4219, `GraphicExtension` 4220-4229, `ImageDescriptor` 4230-4239,
      `ScreenDescriptor` 4240-4249.
    - `common/png`: `ACTL` 4250-4259, `BKGD` 4260-4269, `CHRM` 4270-4279, `CICP` 4280-4289, `CLLI` 4290-4299,
      `ColorType` 4300-4309, `EXIF` 4310-4319, `FCTL` 4320-4339, `FDAT` 4340-4349, `GAMA` 4350-4359, `HIST` 4360-4369,
      `ICCP` 4370-4379, `IHDR` 4380-4389, `ITXT` 4390-4409, `MDCV` 4410-4419, `PHYS` 4420-4429, `PLTE` 4430-4439,
      `PngChunk` 4440-4459, `SBIT` 4460-4479, `SPLT` 4480-4489, `SRGB` 4490-4499, `TEXT` 4500-4509, `TIME` 4510-4519,
      `TRNS` 4520-4529, `ZTXT` 4530-4539, `Zlib` 4540-4549.
    - `decoders`: `FFDecoder` 4550-4619, `FrameQueue` 4620-4629, `Placebo` 4640-4649 (4630-4639 was `PacketQueue`'s,
      empty since 3.0.4 dropped its byte limit).
    - `encoders`: `AnimationEncoder` 4650-4659, `FFEncoder` 4660-4699, `GifEncoder` 4700-4709, `PngEncoder` 4710-4719,
      `FFTranscoder` 5230-5289 (added later, so its block follows the last one of the range).
    - `readers`: `GifReader` 4720-4749, `JpegReader` 4750-4869, `NetpbmReader` 4870-4919, `PngReader` 4920-4999,
      `WebPReader` 5000-5039.
    - `readers/svg`: `Path` 5040-5049, `PathParser` 5050-5059, `SvgColor` 5060-5069, `SvgDocument` 5070-5079,
      `SvgParser` 5080-5089, `SvgRasterizer` 5090-5099, `SvgReader` 5100-5109.
    - `readers/webp`: `AlphaDecoder` 5110-5119.
    - `readers/webp/lossless`: `BitReader` 5120-5129, `ColorCache` 5130-5139, `HuffmanDecoder` 5140-5159,
      `HuffmanTable` 5160-5169, `VP8LosslessDecoder` 5170-5189.
    - `readers/webp/lossy`: `VP8BoolDecoder` 5190-5199, `VP8LossyDecoder` 5200-5229.
  - `bootstrap` 8000-8999 (next 8220): `AppBootstrap` 8000-8019, `DefensiveCheck` 8020-8029, `FabricBootstrap`
    8030-8039, `ForgeBootstrap` 8040-8049, `NeoBootstrap` 8050-8059; subfolders:
    - `app`: `ClickSound` 8060-8069, `LogUploader` 8070-8089, `MediaCatalog` 8090-8099, `WaterMediaApp` 8100-8119.
    - `app/element`: `Spinner` 8120-8129.
    - `app/render`: `RenderEngine` 8130-8139, `RenderSystem` 8140-8149.
    - `app/render/opengl`: `OpenGLRenderBackend` 8150-8159.
    - `app/render/vulkan`: `VulkanRenderBackend` 8160-8189.
    - `app/screen`: `SettingsModel` 8190-8199, `SettingsScreen` 8200-8209.
    - `app` (added later): `MediaDownloader` 8210-8219.
  - New codes: a new site declares a new constant with the next unused value of its class block (the last constant
    under its `// Class first-last` comment) and the class prefix; a new class adds a `// Class first-last` comment
    with the next free block of its package (blocks so far hold about twice their sites, rounded up to a ten) and
    moves that package's "next" above; a folder outside these prefixes (a new `api/x`) needs a `RANGES` entry.
  - `ErrorCodeInventoryTest` parses the `public static final int ERR_X = <digits>;` declarations of `WaterMedia.java`
    and scans `src/main/java/org/watermedia` with comments and string contents blanked: every site
    (`new ...Exception(`, `new ...Error(`, `LOGGER.error(`, `ThreadTool.safeCall(`) carries an `errCode(` (only
    exemption: the forwarding `LOGGER.error(it, log, e)` of `safeCall`); every `errCode(...)` argument is a declared
    constant (bare or `WaterMedia.`), used by one site only, whose value sits in the range of the package that uses it
    (the test's `RANGES` map) and is followed by `+ "Failed to`; no value belongs to two constants; every constant but
    `ERR_UNKNOWN` (asserted 0) has its site. A numeric literal, an undeclared name or a declaration the pattern cannot
    read (`4_750`) fails it.
- Hardening: decoder budgets (`ImageReader.MAX_DECODED_BYTES` 512 MiB, `Limits`, per-reader caps), allocation after
  validation, bounded inflate, XXE closed, hostile corpus `src/test/resources/pentest/*` (`PentestTest`, generator
  `MaliciousImages`), bounded downloads with mandatory SHA-256 (`NetRequest.download`, provisioning), HTTPS-only
  provisioning, cross-origin header stripping (Java and the FFmpeg patch), file-server token/size/storage limits,
  symlink refusal (`NOFOLLOW_LINKS`), TLauncher refusal, `DEFAULT_NETWORK_TOKEN` guard off loopback.

## 16. Tests (`src/test`)
- Layout mirrors the main packages (133 files, 17 of them support helpers). Root: `ApiGuideExampleTest` (runs the
  public-only `ApiGuideExample` and checks the sizes its `DownscaleFilter` yields),
  `BootstrapProcessTest` and `ServiceLifecycleProcessTest` (child JVMs through `MediaBootstrap.runProbe`),
  `LoggerRedactionTest`, `ErrorCodeInventoryTest` (parses the `ERR_*` declarations of `WaterMedia.java`; every site
  carries a declared constant used once, inside its package range, and every constant but `ERR_UNKNOWN` has a site;
  details in §15). Probes and scenarios:
  `BootstrapProbe` (`server`, `client`, `native`, `native-failure`, `optional-failure`, `tls` via
  `TLSLifecycleProbe.run`, `tls-empty`, `config`: the files go to the folder the host set on WaterConfig, never to
  the `cwd` of the load), `WithoutBinariesProbe` (`server-no-binaries`, `client-no-binaries`),
  `ServiceLifecycleProbe` (`platform`, `uploads`, `evicted`, `failed-probe`), `AppConfigProbe` (`defaults`, `shared`,
  `malformed`, `override`, launched by `AppConfigTest`), `NetworkServerTest.main` (`storage-lock`),
  `NetworkAPITest.main` (`host`: a dedicated server that starts the file server reports `serverEnabled()` at once).
  `PlatformSmokeTest` is a manual network harness with a `main`, not a JUnit test: it resolves the links it is given
  through `PlatformAPI.fetch` and prints their sources.
- Test classes by area: codecs `CodecsApiContractTest`, `ImageDataTest`, `ImageMetadataTest`, `MediaTypeSniffTest`,
  `ScanTest`, `PentestTest` (hostile corpus; every refusal must start with the `(wm_err:NNNN)` of the guard its
  fixture targets, and an accepted file must decode or lose only the chunk under attack), `BcReaderTest`,
  `DdsHeaderTest`, PNG/GIF `ChunkContractTest`,
  `FFDecoderTest` (`InputOptions`: transport and probe policy, headers only when present, `request_size` and
  `multiple_requests` for YouTube's CDN alone, the options of a CDN link reading a loopback file past 10 MiB through
  bounded requests that keep their connection (raw `avformat`, so a fork that drops `request_size` fails), open
  requests for any other host, the socket buffer for `udp` and `rtp` alone, RTMP without `timeout`
  and an RTMP input that connects; `IdleConnections`: a segment download reset or left silent in the middle of its
  body while the demuxer is held is asked for again and every frame decodes, the silent one after the real 10 s
  timeout; `SegmentRetries`: a first segment refused twice still decodes; `Abort`, `EmbeddedAudio`,
  `SeparateTracks` (track choice and fallback, plus the video beside a track: every frame decodes while nobody takes
  the sound and its queue stays full, and a track paced at twice its bitrate still feeds the video faster than it
  plays; a seek between two cues of an Opus WebM track lands on its target; an HLS main input whose origin holds the
  segment after a seek never keeps the track from being read; tests drive the decoder through `hold`, `seek` and
  `ended()`), `OutputOrder`, `LanguageMatch`),
  `QueueLifecycleTest` (frame queue abort and reset; packet queue puts that never wait, generations, measures, in-band
  end marks and abort), `FFEncoderTest` (each container is probed for the codec ids and the audio length it wrote),
  `FFTranscoderTest` (`Arguments`: jobs refused before anything opens; `Media`: probe of files and of a file-based HLS
  playlist whose bitrate it measures, copies, audio-only containers, encodes, mixed copy and encode, a separate audio
  input, bitrate, frame rate limit, every codec on its first available encoder, rotation kept by copy and encode, a
  hole in the sound filled with silence, a playlist whose segments restart their clock, hardware fallback, tags and
  progress, cancels and failures that leave no file),
  `GifEncoderTest`, `PngEncoderTest`, readers `GifTest`,
  `JpegTest` (progressive fixtures plus the baseline `7.jpg`, also read as extended sequential), `NetpbmTest` (whole
  rasters against the samples read straight from the file), `PngTest`, `WebpTest`, `WebpMalformedTest`, `SvgTest`,
  `AlphaDecoderTest`,
  `HuffmanTableTest`, `VP8LosslessDecoderTest`, `FFmpegLogRoutingTest` (fragments joined, levels, redaction, 8 threads at once, a broken H.264 decoded on FFmpeg's
  own threads, and a child JVM that exits while they log with no `hs_err`); network `NetworkServerTest`
  (snapshots and restores `remoteUrl` with the other `network.*` keys; `Port`: a busy port fails before the storage
  exists, a port `remoteUrl` reaches starts, an unreachable, a foreign or an invalid `remoteUrl` fails and gives the
  port and the storage lock back), `NetworkAPITest`
  (`enable`, `answers`, `serverEnabled` and the coded message of an upload to a server that is off, silent or behind
  a wrong `remoteUrl`; it reads `PROBING` and resets `serverUp`, `probeWarned` and `probeAt` by reflection, `probeAt`
  also in the middle of a case to end the pause between probes), `UploadStatusTest`;
  platform `PlatformApiTest`, `PlatformSearchTest`, `PlatformClaimTest`,
  `WaterPlatformTest`, offline handler tests `BiliBiliPlatformTest`, `KickPlatformTest`, `NewgroundsPlatformTest`,
  `OdyseePlatformTest` (loopback proxy through `LocalHttp`), `TwitchPlatformTest` (search hits), `TwitterPlatformTest`,
  `YtDlpPlatformTest` (dubbed JSON offline), `YouTubePlatformTest` (the bot gate ladder with scripts standing in for
  yt-dlp and rustypipe-botguard through `watermedia.ytdlp.binary` / `watermedia.botguard.binary`);
  player `MediaPlayerBaseTest`, `MasterClockTest` (state machine through landings, epochs and stale frames, a pause
  before the landing, `Hold`: the hold time and `reread()`), `MrlTest`,
  `SiteListsTest` (the `checkSite` matrix with disguised hosts, `file:` shares and `jar:` links, BLOCKED MRLs
  without a request, player revalidation, Windows share strings; it snapshots and restores the
  `player.whitelist`/`player.blacklist` fields and gives every case its own URI), `NetworkCacheTest`, `PlayerStateTest`,
  `ServerMediaPlayerTest`, `FollowerClockTest`, `FollowerSyncTest` (end-to-end mirrors over
  `MediaPlayer.sync(ByteBuffer)`), `StatusDeliveryTest`, `FFMediaPlayerTest`
  (upload size, native and scaled), `FFHoldResumeTest` (an HLS VOD of MPEG-TS segments served like YouTube's by an
  `IdleOrigin` that resets idle connections; a pause in the middle of a segment and a paused launch held the real
  10 s keep playing from the paused frame, a short pause resumes without BUFFERING or a second request of the
  segment), `FFFollowerTest` (a video-only player following a `ServerMediaPlayer` over an origin that answers every
  request 800 ms late, with an all-keyframe ProRes MOV so only the flight is left to miss by: the start aligns, a
  host's seek lands on the session without a correction, a session that runs 2 s ahead is caught up without a
  request, and with a 1 ms tolerance the chain warns once), `FFLandingTest` (a precise seek between two keyframes of
  an H.264 + Opus MKV starts picture, sound and clock together on the target, read from the landing report; a WebM
  cut on an endless origin beside a whole WAV track buffers once and holds the clock instead of playing the sound
  on; both on a Java Sound engine, aborted without a line), `FFControlContractTest`, `FFAudioTracksTest` (pick, language, hot switch), `ColorimetryTest`,
  `TxStaticImageTest` (stills, timed or not, and bodies typed by their bytes), `TxAnimatedImageTest` (streaming and
  frame steps in both modes), `TxScalingTest` (+ `FrameTextures`), `TxControlContractTest`, `TxReleaseTest`,
  engines `GFXEngineTest`, `GLEngineTest`, `VKEngineTest` (a DEVICE upload must import its planes in place),
  `HeadlessGFXEngineTest`, `AWTEngineTest`, `JFXEngineTest`, `SFXEngineTest` (`JavaSound`, `OpenAL` and the
  device-free `ChannelTable`), `ALSinkTest`, `SpatialAudioTest`;
  util `HttpHeadersTest`, `NetRequestTest`, `MediaTypeTest`, `MediaQualityTest`, `PixelFormatTest`, `MathUtilTest`,
  `MathEasesTest`; bootstrap `AppBootstrapDependenciesTest`, `DefensiveCheckTest`, app `AppConfigTest`,
  `AppEnvironmentTest` (the three types, a read-only working directory through a deny ACL on Windows and permission
  bits elsewhere, the same answer from the home),
  `FramePlanTest`, `LogUploaderTest` (stages with the stage 3 resume and the report retry, the instance scan in a
  `@TempDir`, masked reads and user names, collected `wm_err` codes, body with `## Error codes`, link limit),
  `MediaDownloaderTest` (`Names`: file names and bitrate labels; `Dialog`: picks, container rules, presets, a source
  without sound, sliders, a download, the name taken from the link, the picks kept for the next dialog, a cancel and
  a failed probe, on a `MediaDownloader` built with the test
  seam folder and a queue `Executor` the test thread drains while it waits),
  `SystemInfoTest` (extension alerts), `HomeScreenTest` (`IssueKeys`, `EarlierKeys`, `Status`: a windowless
  `HomeScreen` whose navigator only records the fired `Action`s, so nothing is uploaded or opened; ⚠ `onEnter`
  still starts the `WaterMedia-CacheSize` walk of the cache folder on its own thread), `CanvasLiveTest`,
  `DialogTest` (panel height against the bands), `ListRowTest`,
  `ListViewInteractionTest` (+ `ScrollbarChannel`), `SpinnerTest`, `TextFieldTest`, `TextAreaTest` (+ `SingleLine`;
  headless through the package-private `TextArea.metrics` hook), `RenderEngineBatchTest`, `RenderEngineCacheTest`,
  `VulkanRenderBackendTest` (⚠ it derives "the GPU offers decoding" from the physical device with the same rule as
  `VulkanRenderBackend.createDeviceAndQueue`, so a change of that queue plan changes the test); tools `DataToolTest` (incl. `errCode` padding), `IOToolTest`, `JSONToolTest`,
  `M3UToolTest`, `ThreadToolTest`. ⚠ Many tests that match an error message match its code too
  (`assertEquals("(wm_err:0410) Failed to pick the quality ...", ...)`), so renumbering a site breaks them.
  ⚠ `LogUploaderTest` expects a test JVM that loads classes from a directory (`LogUploader.DEV`; its `Stages` group
  asserts it before every case); `Stages.startsOver` scans the real working directory and
  `Stages.retriesFailedReport` reads the real system (`SystemInfo.read`).
- ⚠ Test seams: main code never gets a member, a mutable static or a looser constant so a test can reach it. The one
  accepted seam is a package-private constructor that points a class at a local server (`// TEST SEAM: ...`, as
  `OdyseePlatform` and `YouTubePlatform`); a test waits a constant out (the 10 s I/O timeout, `REREAD_HOLD_MS`) or
  drives FFmpeg with the options `inputOptions` built. ⚠ `TextArea.metrics`, `TextArea.Metrics` and
  `TextArea.scrollOffset()` are older hooks that only `TextAreaTest` uses.
- ⚠ Reflection by name: these tests read or reset existing private state through `getDeclaredField` /
  `getDeclaredMethod`, so a rename compiles and fails at run time. `NetworkAPI` (`PROBING`, `serverUp`,
  `probeWarned`, `probeAt`), `NetworkServer` (`running`, `Running.executor`), `PlatformAPI` (`searchRemaining`,
  `searchQuery`, `searchGeneration`, `nextCacheClean`, `HISTORY`, `SEARCH_CACHE`, `searchProbes`, `SEARCH_LOCK`),
  `MRL` (`owner`, `nextClean`, `loadLock`, `loading`), `FFDecoder` (`init`, `reopenFormat`), `FFMediaPlayer` (`colorimetry`,
  `planeAlign`, `planePool`), `TxMediaPlayer.SINGLE_FRAME_POOL`, `ALEngine` (`alFormat`, `primary`,
  `Sink.freeCount`, `totalQueuedUs`, `channelTable`, `render`), `GLEngine` (`ringAddr`, `ringBufferId`, `kit`,
  `Kit.programs`), `VKEngine` (`ycbcrFmt`, `ycbcrFilter`, `ycbcr`, `importCache`, `ctx`, `kit`, `decodedFrames`,
  `importedFrames`), `RenderSystem.engine` (set by `RecordingBackend`), `AppBootstrap` (`PREFERENCES`, `engine`,
  `download`, `dependencies`, `versions`, `extract`, `Dependency`), `WaterMediaBinaries.paths`, and WaterConfig's
  `ConfigSpec.save`.
- Support helpers (`org.watermedia.test.support`): `Fixtures` (resource paths and constants `MP4_H264`, `PNG_STATIC`,
  `GIF_ANIMATED`, `JPEG_GRAY`, `M3U_IPTV`, ...; `readAll`, `fileUri`, `wav` (plain, or WAVE_FORMAT_EXTENSIBLE with a channel mask), `tone`,
  `level`, `dubbed` (tagged multi-stream Matroska), `segment` (HLS-ready Matroska), `transportSegments` (intra-only
  MPEG-2 noise cut into `segment<i>.ts` with continuous timestamps and continuity counters, PAT/PMT per segment),
  `webm`, `clip` (moving gradient plus a 440 Hz tone through `CodecsAPI.encoder`, either track optional), `holed` (a
  `segment` whose timestamps skip some seconds), `rotated` (the video of a file copied into an MP4 with a display
  matrix)), `MediaProbe` (`read(file)`: length, title tag and every stream with its codec id, layout, packets, end
  and rotation, read back with libavformat; `track(type)`), `MediaBootstrap` (`client()`, `ffmpegAvailable()` one shared
  bootstrap in a temp dir, `runProbe(type, dir, scenario, omitBindings)`, whose failures carry the log of the child,
  a probe killed at its 45 s limit included), `PlayerWait` (`awaitStatus`,
  `awaitCondition`), `LocalHttp` (loopback `HttpServer`, one context with any handler, `respond`, `respondRange`
  (announces `Connection: close` unless `persistent`), `expiresIn`, `freePort` (a loopback port nobody listens on)),
  `IdleOrigin`
  (keep-alive HTTP/1.1 with bodies per path and byte ranges that resets, `resetting`, or goes silent, `silent`, on an
  idle connection; `serve`, `stream` (a body served like YouTube's segments: 200 chunked, no ranges, paced), `uri`,
  `requests(path)`, `cuts`, `cuts(path)` (downloads of a path cut in the middle of their body), `connections`,
  `strays`), `Origin` (one body under every path: ranges with `of`, a feed
  that never ends with `endless`, `hold`/`awaitEntered`/`release` to park requests before their headers,
  `requests()`, a fresh path per `uri(ext)`), `LogCapture` (TRACE capture of a named logger), `OpenALContext`
  (skips without OpenAL),
  `YuvFrames` (planes and expected colours incl. PQ/HLG), `ImageBytes` (PNG/RIFF builders, bit writer, `exif`
  payload of an orientation), `PamImage` (`read`, `diff` against a BGRA frame, `argb` pixels of a decoded frame),
  `PixelDiff`, `FrameReplay` (frame hashes across two `reset()`), `DdsFixture`, `RecordingBackend` (GPU-less
  `RenderBackend` that records layers, binds, clips, projections and line widths), `MaliciousImages` (pentest corpus
  generator, not a test: its `main` writes the committed corpus, or a folder given as argument, and must reproduce
  every file byte for byte).
- Fixtures: `gif/`, `jpeg/` (`1.jpg` to `6.jpg` progressive, `7.jpg` baseline 4:2:0; +`raw/*.pam` references and a
  `README.md` of provenance), `png/` (+`raw/` with a `README.md` of provenance and
  SHA-256), `webp/{lossless,lossy}` (+`raw/`), `webp/animated`, `netpbm/`, `svg/` (+`png/` renders),
  `mp4/fibonaccisongh264.mp4`, `m3u/iptv_sample.m3u8`, `pentest/{dds,gif,jpeg,netpbm,png,svg,webp}`, `log4j2.xml`
  (console, `watermedia` and `watermedia_binaries` DEBUG). `src/test/tools/GeneratePngReference.java`
  regenerates PNG references with ImageIO and `GenerateJpegReference.java` writes the baseline JPEG with its
  reference (both run with the Java source launcher). `.gitattributes` marks `pentest/**` and every `*.pam` under
  `src/test/resources` `-text`: ⚠ a byte-exact fixture without a zero byte in its first 8000 bytes is sniffed as text
  and rewritten to CRLF by a checkout with `core.autocrlf=true` (it broke `PngTest [1.png]` with `png/raw/1.pam`).
- Gates: natives optional unless `watermedia.test.requireNatives`; GPU tests need a real GL/VK context (LWJGL stack
  `test_lwjgl_stack`); `VKEngineTest.Decoded.playerDecodesOnTheDevice` and `twinDecodesForADeviceWithoutQueues` are
  skipped with the Khronos validation layer installed (run with `VK_LOADER_LAYERS_DISABLE=~all~`; ⚠ no CI leg has a
  Vulkan device, so they only run on a dev machine without the layer); symlink tests skip without the Windows
  privilege; `SiteListsTest.ShareStrings` and the share cases of `WaterPlatformTest` are Windows only;
  `FFAudioTracksTest` aborts its `Embedded` and `Separate` cases when no Java Sound line plays mono S16 at 48 kHz;
  CI native matrix filters are listed in §4.
- Clock-dependent cases that a slow host can stall retry up to 3 times with 5 s pauses and rethrow the last failure
  (owner rule): `PlatformSearchTest.Discard.typedQueryWaitsForTheNextKey`,
  `FFControlContractTest.Loading.speedBeforeStart` (2x rate window,
  which failed once on the macOS Intel runner) and the rendering check of `FFHoldResumeTest.LongHold` (both cases).
  Bracketed system-clock checks (`ServerMediaPlayerTest`, `FollowerClockTest`) need none.
- Binaries tests (`binaries/src/test/java`): `NativeProvisionTest` (checksums, generations, interrupted installs,
  overrides, BotGuard archives), `RepackToolTest` (runs `RepackFFmpeg.java` in a child JVM).

## 17. Resources and assets
- Shaders `assets/watermedia/shaders/`: `colorimetry.glsl` (shared library, included), `opengl/convert.vert` +
  `gray|nv|yuv3|yuva|yuyv.frag` (`GLEngine`, GLSL 150 core), `opengl/ui.*`, `opengl/sdf.*` (`OpenGLRenderBackend`),
  `vulkan/convert.comp`, `vulkan/ycbcr.comp` (`VKEngine`, GLSL 450, shaderc at runtime), `vulkan/ui.*`, `vulkan/sdf.*`
  (`VulkanRenderBackend`). No precompiled SPIR-V; `#include` resolves against the shaders root via `IOTool.shader`.
- `font/jetbrainsmono-{semibold,extrabold}.ttf` (`TextRenderer` through `AppTheme.FONT_PLAIN`/`FONT_BOLD`),
  `textures/banner.png`, `textures/duck/00..07.png`, `sounds/duck.ogg` (`Assets`, `ClickSound`), `catalog/uris.json`
  (groups of `{name, uris[]}`), `catalog/iptv.json` (`{generatedAt, channels[]}`) (`MediaCatalog`), root
  `icon.png`/`pack.png`, `META-INF/licenses/javacpp-LICENSE.txt` and `jetbrainsmono-LICENSE.txt` (SIL OFL 1.1, the
  bundled font).
- Binaries resources: `libs/ffmpeg-{windows,linux,linux-arm64,macos,macos-arm64}.zip`, `META-INF/botguard-pins.properties`,
  `META-INF/ffmpeg-manifest.properties` (from `tools/`), 11 license texts under `META-INF/licenses/`, loader metadata.
- IntelliJ run configuration `WaterMedia App`: main `org.watermedia.bootstrap.AppBootstrap`, working dir `run/`,
  `--enable-native-access=ALL-UNNAMED`, alternative JRE path (GraalVM 25), shortened classpath.

## 18. Documentation map
- `docs/DOCS-EN-US.md` (2026-11-05): API guide on the `docs/BLUEPRINTS.md` outline (introduction, version schema,
  use-case indexes incl. hard rules for AI agents, 0 setup with Gradle deps and a config overview, 1 startup +
  restrictions + packages + diagnostics, 2 links, 3 playback and engines, 4 file server and `NetRequest`, 5 codecs).
  Owner rules 2026-11-05: config fields aren't explained there; each area lists its fields in a `> [!NOTE]` pointing
  at their `watermedia.toml` comments, hardware needs go in `> [!IMPORTANT]`, and under a heading the order is
  warning, note, text. Translations: `DOCS-ES-MX.md`, `DOCS-ES-AR.md` (voseo + porteño idioms), `DOCS-PT-BR.md`,
  `DOCS-RU-RU.md`, `DOCS-ZH-CN.md`, block for block with byte-identical code blocks and anchors rewritten to the
  translated headings (GitHub rule: lowercase, keep letters, digits, `-` and `_`, spaces become `-`).
- GitHub wiki (`SrRapero720/watermedia.wiki`, branch `master`, cloned at the gitignored `/documentation/`): root
  `Home.md` language picker + `_Sidebar.md`, one folder per language (`en-US/`, `es-MX/`, `es-AR/`, `pt-BR/`,
  `ru-RU/`, `zh-CN/`) with its own `_Sidebar.md` (section and subsection links) and seven pages `<LANG>-Home` (text
  before `# 0`), `<LANG>-0-Setup`, `-1-Startup`, `-2-Managing-links`, `-3-Playing-media`,
  `-4-Uploading-and-downloading-files`, `-5-Codecs`, one per `#` section. ⚠ Wiki sync (owner rule 2026-11-05): the
  docs are the source, so any `DOCS-<LANG>.md` change ships to the wiki in the same task:
  1. Clone the wiki into `/documentation/` when missing, otherwise `git pull` it.
  2. Re-cut every changed language into its pages, keeping the doc's line breaks: same-page links stay `(#anchor)`,
     links into another page become `(<LANG>-<page>#anchor)`. A renamed heading updates its sidebar entry; a new `#` section adds a page in every
     language plus its sidebar entries.
  3. Run `pwsh scripts/ai/check-translations.ps1` (byte-identical code blocks, heading levels, badges, links, no
     dashes or NUL bytes) and `pwsh scripts/ai/check-wiki.ps1` (no broken link or anchor, sidebars and the language
     picker present, each language's pages joined in order equal to its doc, no stray pages); both must pass.
  4. Commit the wiki in the git format and push it right after the docs commit.
- `README.md`: landing page (projects, donations, basic usage, engines, Vulkan contract incl. Minecraft 26.x,
  available APIs, Binaries, license). Supported platforms and supported OSes are centered icon rows
  (`<p align="center" style="text-align: center;">` of `<img>` with `width`, `height`, `alt` and `title`): 128x128
  pixel art from `docs/assets/` (platforms at 48 px, OSes at 64 px), and `binaries/README.md` reads its own copies in
  `binaries/docs/assets/`. ⚠ Icons go by absolute `raw.githubusercontent.com/.../master/docs/assets/` links, since
  the stores receive the README as is: a new icon shows only after its push. ↪ A new platform adds its icon to the
  row; Linux ARM64 has no icon yet and stays as text. `WATER.md`: `water://` spec. `docs/animated-picture-stream-spec.md`: APS example
  (never implement). `binaries/tools/NATIVE_REBUILD.md`: native rebuild, repack, reviewed CVEs.
- `TODO.md`: AV2 downloads in the app, BCn uploads in GL/VK, HDR tone mapping from metadata and on software engines, real version in IDE runs,
  Vulkan frame-texture fast path. `PENDING.md`: host validation ledger (H1..H15, Spanish).
- Known doc drift: PENDING H5 names a `WaterMedia.stop()` that does not exist.

## 19. Known gaps and disabled code
- Disabled: libplacebo deinterlace
  (`Placebo.OPTIONS`), `@SuppressWarnings("deprecation")` on `VKEngine.twinStep` (`lock_queue`).
- Not implemented (by design, tracked in `TODO.md`): BC texture sampling in any engine and DDS dispatch in
  `CodecsAPI.reader`; VK `preload`/`frame` fast path; HDR tone mapping on software engines.
- Codec limits: JPEG 12-bit/CMYK/arithmetic/lossless; SVG text/use/clip/mask/filter/radial; WebP ANIM background and
  VP8 scaling bits; PNG 16-bit output and ICC application; GIF LZW min code 1.
- Platform notes: the site lists judge only the link of an MRL, so `PlatformAPI.fetch`/`search` called directly,
  thumbnails and resolved sources are never checked; mature gates only where the service exposes a flag;
  `WaterPlatform.local` allows `..`; Sendvid/yt-dlp
  resolves can hold a loader thread for 60/120 s; Twitch persisted-query hashes rotate.
- App notes: downloads skip pictures and live media, take only the first audio track and write no subtitles; a GPU
  encoder is only tried when the download starts (no check when the dialog opens); no Vulkan validation layers;
  `PlayerScreen` not restored after a hot swap; `TextField` has no caret
  movement/selection; `TextArea` has no selection, copy, cut or undo; `HomeScreen.Action.SETTINGS` navigates only with
  DEBUG logging (`WaterMediaApp.navigateAction`); stale `config/watermedia.toml` in the repo.
- Library notes: `NetworkCache` sweeps `.part` files once per start (no timer); `GLEngine.drainOne` clears `pending`
  without a lock (possible lost frame, unverified); `AppBootstrap` libs dir is shared per tmpdir; FFmpeg generations are
  never pruned.
