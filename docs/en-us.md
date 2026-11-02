# WATERMeDIA API guide

English · [Español (México)](es-mx.md)

WATERMeDIA resolves media links, decodes them and hands the frames to your graphics and audio engines. It needs Java 17 or newer. Minecraft loaders start it for you; an app embedding it starts it once, after setting up its environment.

## Startup and ownership

```java
import org.watermedia.WaterMedia;

WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
boolean ready = WaterMedia.loaded();
```

`start` loads every module once for the life of the process. Nothing ever unloads: there's no stop, and a second `start` throws. The last argument picks the client environment; client-only modules (Binaries, codecs, player) do nothing on a dedicated server.

- `WaterMedia.loaded()` is the only readiness check.
- For a loading screen, `WaterMedia.loading()` names the module starting and `step()`/`steps()` count them: configuration, Binaries (when installed), network, codecs and player, so `steps()` is 5 with Binaries and 4 without.
- Optional parts (the file server, _FFmpeg_) log their failure and stay off while the load finishes, so check the capability you need, such as `CodecsAPI.ffmpegLoaded()`.
- Any other failure (the configuration, an installed Binaries module) makes `start` throw `IllegalStateException` with that failure as its cause, and `loaded()` stays false.
- Pure Java image decoding through `CodecsAPI` needs no startup at all.

`PlayerAPI.mrl`, the media players and `NetworkAPI.upload` throw `IllegalStateException` until `loaded()` is true, and the media ones always do on dedicated servers. `NetworkAPI.upload` also rejects anything that isn't an existing file right away; an unreachable or misconfigured remote server fails the returned status instead.

`PlatformAPI` needs no startup: its registry is static and `register`, `fetch` and `search` work anytime. Handlers that need Binaries (YouTube, _yt-dlp_) fail with their own `PlatformException` until it loads, and always on dedicated servers.

Binaries needs WATERMeDIA and loads with it, provisioning the natives right after the configuration and before the network, codecs and player modules. Minecraft clients require it; dedicated servers can skip it, since they never load it. Apps embedding the Java API can skip it too and keep image decoding and the native-free platform resolvers. Disabling _FFmpeg_ skips the extraction. The native paths stay published for the life of the process, and there's no separate Binaries init or shutdown API.

Custom _FFmpeg_ builds may use GPL, LGPL or any other declared license and version: the loader checks integrity and the required native capabilities, not a version or license allowlist. They still need JNI bindings compatible with the Java API WATERMeDIA uses.

A player owns the engines its factory consumes. Create engines lazily through suppliers, so an unavailable source can't leak preallocated native resources. Your app owns its OpenGL, Vulkan and OpenAL contexts: release every player on the right host context before tearing those contexts down, and create new engines after a device or context reload. Native libraries never unload and can't switch versions inside a running JVM.

## Runtime requirements and configuration

The Java API targets Java 17. Your host supplies _WATERCoNFIG_, _Log4j_, _Gson_, _JOML_ and the graphics and audio libraries it uses.

- On Forge, NeoForge and Fabric, WATERMeDIA bundles _WATERCoNFIG_ as a nested JAR.
- The standalone launcher extracts that JAR to a temporary file, adds it to the relaunched JVM's classpath and downloads the other runtime libraries it lists as needed; a cached or downloaded library joins the classpath only when it matches the SHA-256 pinned at build time.
- An embedding app brings its own classpath. Development Gradle dependencies don't prove a Minecraft installation ships the other libraries.
- The Binaries JAR embeds and relocates its _XZ_ dependency.
- Forge, NeoForge and Fabric load WATERMeDIA on clients and dedicated servers, and reject Minecraft versions outside the published list.
- WATERMeDIA and Binaries accept each other from 3.0.0 up to (not including) 3.1.0; older 3.0.0 BETA builds are rejected. Fabric can't limit a dependency to clients, so its bootstrap requires Binaries on clients and its manifest rejects incompatible Binaries versions.

The Minecraft bootstraps refuse to start when _TLauncher_ launched the game. They spot it through its injected skin mod, classes, files on the class path, JVM options, threads and parent processes, and fail naming the evidence; leftovers of an old install in the game directory only log a warning. Embedding apps skip this check.

Configuration loads before the natives are provisioned, so check the generated configuration and the real host classpath before blaming an unavailable backend. These are the keys of the generated `watermedia.toml`; sizes use MiB (1,048,576 bytes), even where the configuration says MB.

| Setting | Default and related limits |
| --- | --- |
| `codecs.ffmpeg.disable` | `false`; `true` skips _FFmpeg_ provisioning and startup. Startup settings apply on the next launch. Images never depend on _FFmpeg_. |
| `codecs.ffmpeg.customPath` | Empty; a directory with compatible native libraries, not an `ffmpeg.exe` program. Patched TLS/origin capabilities are still required, and decoding on a twin Vulkan device needs _FFmpeg_'s export fix (upstream commit `63d0237033`), which the bundled natives carry. |
| `codecs.ffmpeg.hardwareAccel` | `true`; real use also depends on native and driver support, the codec and the output engine, and software decoding stays available. On a `VKEngine` whose `VKContext.deviceInfo()` describes a device created for decoding, H.264, H.265 and AV1 decode on the GPU without touching RAM (see `PlayerAPI.vkEngine`). Frames that can't be imported, and those of every other accelerator, are downloaded to RAM. |
| `codecs.ffmpeg.analyzeDuration` / `probeSize` | 7,000 ms / 10 MiB, used by the main and separate-audio inputs. Zero duration keeps _FFmpeg_'s default. Bigger values can slow startup. |
| `codecs.maxImageSourceSize` | 128 MiB of encoded image data, separate from the reader's 512 MiB decoded-data limit. |
| `player.watertx.texturesBudget` | 32 MiB. Preloading also needs a capable engine, a valid frame count, and both decoded frames and output textures fitting the budget. Zero forces animation streaming. |
| `codecs.ffmpeg.cache` / `cacheMaxResponse` | On / 10 MiB per eligible response; it doesn't cache every stream or HLS/DASH input. |
| `player.watertx.cache` / `player.cacheBudget` | Image cache on / 8,192 MiB shared disk budget; older entries are evicted after writes. Disk, encoded-image, decoded-image and texture limits are independent. |
| `platforms.allowMatureContent` | `false`; supported platform adapters can mark a resource `BLOCKED` instead of handing it to playback. |
| `platforms.searchCleanupInterval` | 15 minutes; zero clears cached searches on every search. |
| `platforms.searchLimit` | 2 results per platform; a search asking for more is capped here. |

The optional file server follows `network.server`: `OFF` (the default) never starts it, `DEDICATED` starts it on any host without a client (dedicated servers) and `ALWAYS` on clients too.

- It binds to `127.0.0.1:25572`. A remote listener needs an explicit address and a custom, non-blank `network.token`; a bad value fails the file server's start, which the network module logs while the load still finishes.
- `network.remoteUrl` is the full HTTP(S) base URL every uploader uses, clients included; it doesn't change the bind address.
- The built-in listener speaks HTTP; remote HTTPS needs TLS termination managed by the host.
- Its root only answers the product name, and file IDs use lowercase letters and digits so they name the same file on every file system.
- `GET` and `HEAD` honour a single satisfiable `Range`; a malformed or multipart one is ignored. A request asking to close the connection gets `Connection: close` back, so _FFmpeg_ opens a fresh connection for each seek.

By default it serves eight requests at once, with a 30-second idle timeout per request, 8 MiB uploads and 1,024 MiB of total storage.

- The timeout only cuts a request after that long without headers or transfer progress, so a long download on a slow link finishes while a stalled one is dropped.
- `maxUploadSize = 0` removes only the per-upload limit; the storage quota, concurrency and idle timeout still apply. A full storage refuses uploads with `507`, warning on the first refusal of each server run, until files are removed by hand.
- `NetworkAPI.upload` returns one `UploadStatus` per file: `uri()` is the `water://remote/<id>` address once the server accepts it, and `cancel()` aborts a transfer in flight, ending the status as failed.

Java network requests, playlist fetches and the _yt-dlp_ socket timeout share `network.timeout` (15 seconds), ten redirects and a 16 MiB text-body limit. Native playback inputs use their own timeout policy.

## Public and internal packages

The public API is the packages `org.watermedia`, `api.codecs`, `api.network`, `api.platform`, `api.player` and `api.util`, without their subpackages. Everything else is internal: codec readers, encoders and chunk structures, player queues and caches, the built-in platforms, the bootstrap and the bundled _FFmpeg_ bindings. Binaries also uses two internal packages: `org.watermedia.tools` for its download, digest and JSON utilities, and `org.watermedia.bootstrap` for the module lifecycle it implements.

The JAR declares no Java module, so no loader blocks internal packages; treat the boundary as the contract anyway and reach internal features through their entry points, such as `PlayerAPI.player` and `PlatformAPI.fetch`. Run `gradle javadocInventory` from the project root to list missing public-method docs with file names and line numbers; it only reports, and only checks the public packages listed in `public_packages` of `gradle.properties`.

> [!WARNING]
> `CodecsAPI` is experimental: `decode`, `reader`, `sniff` and `encoder`, plus the readers, encoders and filters they hand out, can change or break in any update, so don't use them in your mods yet. The media players already use the codecs for you, and the status checks (`ffmpegLoaded()`, `ffmpegError()`, `vulkanDecode()`) are safe to call.

## Resolving addresses

```java
MRL mrl = PlayerAPI.mrl("https://example.com/movie.mp4");
MRL[] playlist = PlayerAPI.preload(firstUri, secondUri);
```

An MRL is a cached media locator. One URI can resolve to several media sources, and each source can hold quality variants and separate audio tracks. Resolution runs in the background:

- Poll `mrl.status()` from a game tick. It ends in `LOADED`, `ERROR` or `BLOCKED`; check `mrl.exception()` on failure.
- `FETCHING` means it's still resolving, `EXPIRED` marks stale sources and `FORGOTTEN` an evicted resource.
- `mrl.await(timeoutMillis)` is for background or app code, never the game thread: `true` means resolution finished, failures included, not that the media is playable. It returns `false` if the wait expires or the thread is interrupted.
- Keep the returned handle when refreshing; a refresh asked during a running load is queued and runs once that load finishes.

```java
if (mrl.status() == MRL.Status.LOADED) {
    List<MRL.Source> sources = mrl.sources();
    int count = mrl.sourceCount();
    MRL.Source first = mrl.source(0);
    MRL.Source firstVideo = mrl.sourceByType(MediaType.VIDEO);
    List<MRL.Source> videos = mrl.sourcesByType(MediaType.VIDEO);
}
```

`source(index)` returns null for an invalid index or an unavailable source, and `sourceByType(...)` returns the first match or null; `sources()` and `sourcesByType(...)` return lists, empty when nothing matches. `MediaType` lives in `org.watermedia.api.util`, not in `MRL`. Reload with `mrl = mrl.reload()`: a forgotten cache entry returns a new handle and the old one stays forgotten.

`PlayerAPI.mrl(String)` reads a string naming an existing file as a path relative to the working directory WATERMeDIA started with, and parses anything else as a URI. Malformed, relative or empty inputs return an uncached MRL born in `ERROR` whose `reload()` returns itself. A direct link is rejected only when the server answers with a document type (HTML, CSS, JavaScript, JSON, XHTML); any other content type passes, whatever its casing or parameters, and ambiguous ones are sniffed from their first bytes. Each source keeps one URL per quality bucket (the widest rendition, then the tallest, then the highest frame rate, then the first the platform listed) and its headers are frozen.

Indices start at zero: a gallery can hold an image at 0, a video at 1 and an animation at 2, so pass 1 to `PlayerAPI.player` to play the video. Leaving the index out picks the first source.

Platform handlers share one contract:

- `Platform.resolve` returns `null` only for a foreign host or scheme. A URL of its own host it can't play (a profile, a folder, a bare host) fails with `PlatformException` ("Unsupported URL") without touching the network, and every other failure, interrupted waits included, is a `PlatformException` too, so `MRL` reports the handler's own message.
- Mature content blocks with `MatureContentException` wherever the platform exposes a flag or label (Imgur, Kick, Twitch, Bluesky, PornHub, Odysee tags, Newgrounds ratings, _yt-dlp_, and the Twitter and YouTube age gates), and `MRL` turns it into `BLOCKED`.
- An album or playlist skips a bad item with a warning and only fails when nothing is left.
- Handlers added with `PlatformAPI.register` are probed before the built-in ones, newest first, and need no startup: register once, before or after `WaterMedia.start`, and the override holds until `unregister`, which also drops a built-in handler for the rest of the process.

Searching works like this:

- `PlatformAPI.search(query)` returns right away the read-only list its results land in, up to `platforms.searchLimit` per platform in answer order (fewer with `search(query, limit, reSearch)`). `PlatformAPI.searching(query)` turns false once every platform answered; poll both from the UI thread.
- One search runs at a time on four worker threads, and every built-in search request gives up after 5 seconds.
- Twitch and Kick list only channels that are live right now, since an offline channel has nothing to play.
- A new query discards the one in flight. A query one keystroke away from the previous one (a letter more, less or fixed while the user types) waits 300 ms for the next keystroke before touching the network; any other query goes out at once.
- A query already searched with the same limit gets the same list instance from the cache, finished or still filling, unless `search(query, true)` asks the platforms again.
- Registering a handler drops cached searches, and a blank search returns an empty list and leaves a running search alone.

`Metadata` means the same everywhere: `title` names the content (a video, stream, post or clip, never the account), `description` holds its caption, body text or category, and `author` the channel, uploader or streamer. Signed CDN links expire when their `expire`, `expires`, `validto` or `x-expires` parameter says so; the rest have a per-platform lifetime or none.

## Creating and controlling a player

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

`player` returns null when the source is still fetching, the index is invalid, the needed backend is unavailable or construction throws an `Exception`; an `Error` propagates. Check the MRL status before deciding to retry.

- Suppliers run only when needed, and images never call the audio supplier.
- A supplier returning null omits that output, such as `() -> null` as the graphics engine for audio-only playback.
- Never share an engine between players.

`TxMediaPlayer` plays supported images and animations, `FFMediaPlayer` plays audio and video through _FFmpeg_, and `ServerMediaPlayer` is a sync clock with no decoding or native engines.

```java
player.pause(true);
player.pause(false);
player.seek(15_000);
player.volume(50);
player.mute(true);
player.speed(1.25f);
player.repeat(true);
player.scale(1280, 720);
```

`speed(value)` takes finite values in `(0, 4]` and reports whether it was accepted; Java Sound can't change the playback rate. `canSpeed()` is a pure query any thread can call every frame, while `speed(value)` must run on the owning audio context, and the player clock only follows a rate the audio engine accepted. `spatialAudio(...)` also reports whether the engine accepted the update; check `spatial()` before showing positional controls.

Times are in milliseconds and volume is a percentage from 0 to 100. Check `canSeek()` for streams that can't seek, and poll `status()`, `time()`, `duration()`, `buffered()` and `exception()` for diagnostics. Scaling and level of detail work on the supported pixel layouts, and the built-in graphics engines don't accept compressed BC textures yet. Call `player.release()` when the host removes the playback surface, failed playback included.

Every boolean control answers one question: did this call change the player's own state? `false` covers calls the media refused, calls already satisfied and calls after `release()`; `repeat(boolean)` follows the same rule. All three players share one contract:

- A pause while loading is latched and applied once the pipeline opens.
- `pause()` and `resume()` return `false` on an ended, stopped or failed player.
- `stop()` tears the pipeline down, lands in `STOPPED` with `time()` at 0 and returns `true` only when a transition happened.
- `seek(...)` on an ended or stopped player restarts it paused at the target, and `togglePlay()` there starts it again.
- `nextFrame()` and `previousFrame()` only work while paused.
- `canPlay()` is true from the first successful load until the player fails or is released; `canSeek()` and `live()` are false until the source opens; `fps()` is 0 until the rate is known; `status()` is `STOPPED` after `release()`.
- A still image shown through _FFmpeg_ honours `displayTime(ms)` like the image player and never loops; without a display time it stays up until stopped.

`onStatus(listener)` adds a `MediaPlayer.StatusListener` that gets the player plus the previous and current status on every real transition, including the ones a restart, `stop()` and `release()` cause.

- Listeners stack (adding one twice keeps it once), and the transition into `STOPPED`, `ENDED` or `ERROR` consumes them. A playlist consumer registers once per playback, and when it restarts the player from that terminal callback, registers again from the callback itself.
- Transitions arrive one at a time, in the order the statuses changed, even when several threads drive the player. They run on whichever thread drove the change: a playback thread, the shared sync ticker, or the caller of `status()`, `time()` or `texture()` on image players whose clock resolves the end lazily. Keep them short and never call `release()` from one.
- A listener never runs while the player holds one of its locks, nor on a playback thread `release()` is waiting for. `release()` delivers its `STOPPED`, plus anything published while tearing down, once it finishes and the engines are freed, so that callback may wait on another thread that uses the player; every other callback must stay short and never block.
- `release()` is idempotent.

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

`Status.allows(next)` tells whether a pause, resume, buffering, end, stop or failure can move a status straight to `next`. Starting, seeking and restarting take their own paths: a player leaves `LOADING` only when its pipeline opens (or it stops or fails), a seek on `ENDED` or `STOPPED` restarts it, and `STOPPED` allows no direct move.

### Audio tracks and languages

Dubbed videos and files with several audio streams play their original (or default) audio unless you ask for something else. Platforms list a source's separate audio tracks with the original first, and inside a file or an HLS stream the stream flagged as default wins.

```java
player.audioLanguage("es-MX"); // PREFERRED FROM THE NEXT START; null CLEARS IT
for (final MediaPlayer.AudioTrack track: player.audioTracks()) {
    if ("es".equals(track.lang())) player.audioTrack(track); // SWITCHES RIGHT AWAY ON A RUNNING PLAYER
}
```

- The pick order is: your `audioTrack(...)` pick, then `audioLanguage(...)`, then the original or default audio, then the first one. A language tag matches the same tag first, then the same language, so `es-MX` takes `es-419`, `es` or the `spa` a file carries. There's no automatic language: without `audioLanguage(...)` the original plays, never an automatic dub.
- `audioTracks()` lists one `AudioTrack(track, stream, name, lang)` per audio stream: the main input's first, then each separate track of `source().audioTracks()`. `track` is `AudioTrack.MAIN` or the separate track's index and `stream` is the _FFmpeg_ stream index inside it.
- A separate track that hasn't played yet shows as one `provisional()` entry (`stream` is -1) built from the platform's name and language, so listing never opens every dub up front. Once it plays, its real streams take its place. The list is an immutable snapshot any thread can read.
- `audioTrack(track)` returns `true` when the pick changed. A running player rebuilds its pipeline at the current time (a live stream restarts at its edge), and the pick survives quality switches and restarts. `audioTrack()` returns what plays now. Image and server players, and players without an audio engine, list nothing.

## HTTPS trust

HTTPS playback needs the native verification capabilities of the bundled build or a compatible custom one. _FFmpeg_ verifies certificate chains and DNS/IP identities against a snapshot of the JVM truststore taken at startup.

- Set the standard `javax.net.ssl.trustStore` properties before starting WATERMeDIA when you need private certificate authorities.
- An empty or invalid truststore stops _FFmpeg_ from initializing; verification is never silently turned off.
- An empty `customPath` doesn't add the working directory to native discovery.

The exported authorities also reach nested HLS/DASH requests. This shares trust anchors with Java; it doesn't replace _FFmpeg_'s _OpenSSL_ transport. When the codecs module starts _FFmpeg_ it checks the required native options exist and writes the temporary PEM; `FFMediaPlayer` applies them to the main and separate-audio inputs of every player it opens, and nothing in the public API touches that configuration. Custom JSSE socket factories, hostname-verifier callbacks and revocation policies don't carry over to _OpenSSL_, and protocol whitelists stay as they are. The temporary CA bundle lives for the whole process and is deleted when the JVM exits.

Playback credentials belong to the first request's origin: scheme, hostname and effective port. Redirects and HLS/DASH child requests send `Authorization`, `Proxy-Authorization`, `Cookie`, `Cookie2` and `X-WaterMedia-Token` only to that origin. A cross-origin request builds its own `Host` header, while ordinary headers such as `User-Agent`, `Accept`, `Referer` and `Origin` stay available to CDNs.

Generated cookies respect the same boundary: cookies from another origin's `Set-Cookie` are ignored, even when a redirect later returns to the original server. It's a single-origin credential policy, so a redirected server can't start its own cookie session inside that playback input. Native HTTP logs leave out full requests, header dumps and cookie values.

`NetRequest` applies the same boundary to request bodies: a redirect to another origin fails instead of resending the body, unless it was set with `body(body, true)`. A 303 response is re-requested as `GET` without a body. A request is sent once: configure it, `send()` it and read its response on one thread.

Redirects only move between http and https, either way. A `Location` pointing at a `file:`, `jar:` or `ftp:` resource fails the request, and only the first request may name a local file. URLs taken from platform JSON must be web links too. Playlist entries may use any streaming scheme _FFmpeg_ speaks (`rtsp`, `rtmp`, `udp`, `srt`) but never a local file or archive, unless the playlist itself is a local file.

More `NetRequest` rules:

- Headers the caller sets win over the defaults, `User-Agent` included, and the derived `Referer` keeps an explicit port.
- `inputStream()` and `text()` return the body of an error status instead of throwing, and the body can be read once.
- `json(type)` accepts every JSON content type (`application/json`, `text/json`, `+json` subtypes).
- A text body that trickles in stops after four read timeouts in total.
- Neither the builder nor the response is thread-safe.

## Choosing engines

Vulkan development comes first; OpenGL stays supported as the secondary backend. Backend decisions belong in your rendering layer.

The standalone app renders with Vulkan unless the user picks OpenGL; when Vulkan is unavailable it runs that launch on OpenGL and keeps the saved choice. `-Dwatermedia.engine=opengl` or `-Dwatermedia.engine=vulkan` overrides the engine for one launch without saving it. The app keeps its engine, interface scale (`0` follows the monitor, otherwise a factor from 1 to 3 in 0.25 steps, shown in Settings as `Auto`, `x1.0`, `x1.25` and so on), CRT effect and click sound in `config/app.properties` under its working directory.

| Factory | Host obligation |
| --- | --- |
| `PlayerAPI.glEngine(renderThread, executor)` | The executor runs tasks on the thread owning the OpenGL context and keeps being pumped while playback or release may wait on it. |
| `PlayerAPI.vkEngine(context)` | Supply a `VKContext` whose Vulkan objects and retirement mechanism outlive the engine. To decode video on the GPU, override `deviceInfo()` with the device's feature chain, extensions and queue counts (see the README). _FFmpeg_ decodes on that device when it has queues to spare (the host then never touches them nor calls `vkDeviceWaitIdle` while players exist), or on a twin device when your device enables the external memory and semaphore extensions (`win32` on Windows, `fd` elsewhere); release every player before destroying the device or its instance. `CodecsAPI.vulkanDecode()` tells whether the natives support it. _libplacebo_ converts every decoded layout to SDR RGBA (HDR tone-mapped) when the device enables `hostQueryReset`, `runtimeDescriptorArray`, `shaderStorageImageArrayNonUniformIndexing` and `maintenance4` and, on your own device, the family of `queue()` holds a second queue; a twin enables those features by itself when the GPU has them. Without _libplacebo_ only 4:2:0 streams decode on the GPU. |
| `PlayerAPI.awtEngine(onFrame)` | Paint the published image from the AWT/Swing UI; hop the optional callback to the UI thread as needed. |
| `PlayerAPI.jfxEngine(onFrame)` | Needs a JavaFX 13+ runtime; bind the engine's image to your UI. Without it the factory throws `IllegalStateException` instead of a linkage error. |
| `PlayerAPI.headlessEngine()` / `headlessEngine(preload)` | Captures uploaded frames without a graphics context, handy for tests and image processing. Like every engine and media player it only exists on a client; a dedicated server can only create `ServerMediaPlayer` clocks. |
| `PlayerAPI.alEngine()` | The OpenAL context is already current and stays valid on the threads doing audio work. |
| `PlayerAPI.alEngine(true)` | Same OpenAL contract, playing through positional mono sinks (stereo pairs and extra speakers). |
| `PlayerAPI.jsEngine()` | A Java Sound device and line are available; no positional audio. |

Spatial audio uses `SpatialAudio` and an optional environment processor; see [spatial audio and Sound Physics Remastered](#spatial-audio-and-sound-physics-remastered) for the adapter, source and context ownership, update cadence and feature gates.

Engine base classes are sealed: your integration supplies host contexts and executors, not custom engine implementations. Engines manage their own playback resources, including textures, audio sources and buffers.

Every engine follows one lifecycle:

- `release()` is idempotent, must never run at the same time as the player feeding the engine, and leaves it inert: `released()` is true and `texture()` returns 0.
- The player checks `supports(format, bits)` before uploading and falls back to 8-bit BGRA when the engine declines a layout.
- Uploads never throw. A driver failure only parks the engine with a logged error while the last texture stays exposed, and a Vulkan resolution change keeps the previous view until the first frame of the new layout completes.
- Software and headless engines expose a non-zero sentinel instead of a GPU handle, so only test the handle against 0.
- `PixelFormat.planeWidth`, `planeHeight`, `texelBytes` and `frameBytes` size each plane of an engine's `format()`, `width()`, `height()` and `bits()`, for example to budget its memory. Subsampled planes round up like _FFmpeg_, so odd sizes keep their last chroma column and row.

Color handling:

- The OpenGL and Vulkan engines convert YUV with the matrix (BT.601, BT.709 or BT.2020), range and chroma position each video declares. When it declares nothing they guess like _mpv_: BT.601 for SD frames (narrower than 1280 pixels and up to 576 lines tall), BT.709 otherwise.
- PQ and HLG HDR show as SDR, with BT.2020 colors mapped to BT.709 and highlights compressed so a 1000-nit peak reaches white.
- Engines on one OpenGL context or Vulkan device build that conversion once and share it. It goes away with the last engine's release work (the task `release()` queues on the OpenGL render executor, or the `VKContext.retire` callbacks), so release every engine and let that work run before destroying the context or device.
- Software engines get BGRA that _FFmpeg_ converts with the declared matrix and range, without HDR tone mapping.

OpenAL needs its original context current on every thread driving the player, the sync ticker included. Java Sound is thread-safe and reopens a wider line when an audio chunk exceeds the line buffer, dropping whatever the old line still held.

## Spatial audio and Sound Physics Remastered

Create an OpenAL engine with `PlayerAPI.alEngine(true)` before building the player. Spatial mode is fixed for the engine's lifetime: _FFmpeg_ folds stereo and surround sources into stereo (the center channel reaches both sides, mono sources stay mono) and the engine plays them through sinks, which are positional mono sources. The plain `alEngine()` factory plays every channel on one dry, listener-relative source, though layouts the device can't play may still be converted to the closest channel count. Java Sound returns `false` from spatial updates and exposes no native source.

The engine starts with one sink, the primary, playing dry, listener-relative `Mix.MONO` audio. Pass a `SpatialAudio` state to place it in the world, or `null` to go back to dry, listener-relative playback. Volume, mute, speed, pause and sync don't depend on spatial settings and reach every sink.

```java
import org.watermedia.api.player.SFXEngine.SpatialAudio;

final ALEngine audio = PlayerAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

Create the engine, update it and release it on the host's sound executor, with the original OpenAL context current. The host owns the device, context and listener, and they must outlive every player using them.

- `FFMediaPlayer` uses private playback threads and, like ordinary `ALEngine` playback, currently needs the source's context to be the process-current context on those threads. Hosts that expose their audio context only through thread-local binding aren't supported: binding it on the caller's sound thread alone isn't enough.
- Native engine operations reject a different or missing context; they never switch or recreate the host's context.
- Release players before a device reload, then create new engines in the new context.
- `FFMediaPlayer.release()` interrupts and joins its playback pipeline before deleting the audio source; run it before the context is destroyed, never as a callback from that pipeline.

`SpatialAudio` validates finite positions OpenAL floats can hold, `0 < referenceDistance < maxDistance` and a finite, non-negative rolloff; zero rolloff turns distance attenuation off. The host's distance model decides how these parameters attenuate audio, and the API never touches the global distance model, the listener or the source velocity, so `maxDistance` isn't a universal silence radius under every OpenAL model.

### Sinks: stereo pairs and extra speakers

One decode feeds every sink of a spatial engine, so a stereo picture or a speaker placed somewhere else costs no second download or decode. Each sink plays one `ALEngine.Mix` of the decoded audio: `LEFT`, `RIGHT` or `MONO` (the average of both). A mono source plays the same samples on every mix, so a stereo pair never goes silent on mono media.

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

- The primary sink owns `player.audioSource()`. It drives the media clock and lives as long as the engine; its `release()` returns `false`. `player.sinks()` lists the primary first, and both `sinks()` and `addSink(...)` give an empty list or `null` without a spatial OpenAL engine.
- Every sink queues the same samples, and play, pause, flush, speed and volume run as one batch on all of them, so a stereo pair stays sample aligned. Adding a sink or changing a mix during playback rebuilds every queue on the primary's sample, which pauses the sinks for about one mixer update.
- Each sink has its own `spatialAudio(...)`: the environment adapter gets that sink's source in `process` and `reset`, and `sink.release()` resets it before deleting the source. Releasing the player releases every sink.
- Add, change and release sinks on the host's sound executor with the engine's context current, like every other spatial update. Their queries (`source()`, `mix()`, `spatialAudio()`, `released()`) need no context.
- A host may adopt a sink's source into its own sound system and write its gain, pitch and position, but it must never play, queue, unqueue or delete it: the engine owns its transport and buffers. A new sink starts at the engine's current gain and pitch.

### Sound Physics Remastered adapter

The reference reviewed for this integration was `sound-physics-remastered-1.21.1.zip`, which holds source code rather than a compiled JAR. Its modern entry point is:

```java
SoundPhysics.processSound(source, x, y, z, category, soundId, auxOnly);
```

Write a small adapter in the consuming Minecraft mod: WATERMeDIA keeps Minecraft and _Sound Physics_ classes out of its API on purpose. The host must load the adapter only when _Sound Physics Remastered_ is present and initialized.

```java
final ResourceLocation soundId = ResourceLocation.fromNamespaceAndPath("waterframes", "media");
final SoundSource category = SoundSource.MASTER;
final SpatialAudio.Environment environment = new SpatialAudio.Environment() {
    @Override
    public void process(final int source, final SpatialAudio audio) {
        SoundPhysics.processSound(source, audio.x(), audio.y(), audio.z(),
                category, soundId, audio.auxOnly());
    }

    @Override
    public void reset(final int source) {
        SoundPhysics.setDefaultEnvironment(source, false);
    }
};

// INSIDE THE HOST SOUND EXECUTOR, AFTER ITS CONTEXT, SOUND PHYSICS AND MRL ARE READY.
final MRL.Source media = mrl.source(0);
if (media == null || (media.type() != MediaType.AUDIO && media.type() != MediaType.VIDEO)) {
    throw new IllegalStateException("A resolved audio or video source is required");
}
final MediaPlayer player = PlayerAPI.player(mrl, () -> null, () -> PlayerAPI.alEngine(true));
if (player == null) throw new IllegalStateException("The media backend could not create a player");
try {
    player.spatialAudio(new SpatialAudio(sourceX, sourceY, sourceZ, 8.0f, 64.0f, 1.0f, false, environment));
    if (!player.start()) throw new IllegalStateException("The player refused to start");
} catch (final RuntimeException | Error failure) {
    player.release();
    throw failure;
}

// SUBMIT FROM THE HOST'S TICK INTEGRATION; THE EXECUTOR MUST SERIALIZE SOUND PHYSICS CALLS.
soundExecutor.execute(() -> player.spatialAudio(new SpatialAudio(
        sourceX, sourceY, sourceZ, 8.0f, 64.0f, 1.0f, false, environment)));

// BEFORE THE CONTEXT IS DESTROYED, ON THE SAME SOUND EXECUTOR.
soundExecutor.execute(player::release);
```

The example's `soundExecutor`, resolved `mrl` and world coordinates come from the consuming mod. It plays only the audio track, so the null-returning video supplier is on purpose. The audio supplier creates an engine only if the factory needs one, and the factory releases it if building the player throws an `Exception`. To render video too, pass a video supplier that respects the rendering context's ownership, and keep ownership of any preallocated engine until the factory consumes it. The modern `processSound` call passes the category and identifier directly; the legacy `setLastSoundCategoryAndName` plus `onPlaySound` pair relies on shared mutable metadata and isn't needed here.

Keep one stable adapter instance between updates.

- Each update restores the original world position before calling it, because _Sound Physics_ can replace `AL_POSITION` with a reflected sound origin.
- It also clears previous source filters and auxiliary sends, so a disabled or replaced processor can't leave old occlusion behind. The processor must apply its full environment on every call.
- Throttle `spatialAudio(...)` calls in the host instead of returning early inside the processor.
- `reset` runs when replacing or removing an adapter and during release. The engine deletes only its own source and buffers, never the shared _Sound Physics_ filters or reverb slots.
- If processing or reset throws, source effects are cleared and the exception propagates; a failing reset can't stop the native source from being deleted during release.

Refresh long-running media as the listener, source or world changes, including stationary sources when the listener moves. A good starting point is the mod's configured `soundUpdateInterval` of five ticks, plus immediate updates for new sources and position changes. Spread multiple players across ticks and respect the sound rate limits; ray casting per decoded audio packet isn't needed. The API only calls the adapter during explicit host updates, never from the _FFmpeg_ decode or playback loop.

Verified _Sound Physics_ prerequisites and related settings in the reviewed sources:

| Requirement or gate | Effect on integration | Source inside the ZIP |
| --- | --- | --- |
| The host initializes _Sound Physics_ in the source's context | Its shared filter and reverb IDs must belong to the same context; never call `SoundPhysics.init()` per player | `SoundPhysics.java:66`, `:85`; `mixin/SoundSystemMixin.java:27` |
| EFX and four auxiliary sends | Request `ALC_MAX_AUXILIARY_SENDS = 4` when the host creates the context; fewer sends mean fewer reverb paths | `mixin/LibraryMixin.java:18`; `SoundPhysics.java:97`, `:631` |
| `enabled` | Processing and `setDefaultEnvironment` both do nothing when disabled, so WATERMeDIA clears its source's EFX attachments itself | `SoundPhysics.java:189`, `:625` |
| `updateMovingSounds = false` by default | Category `RECORDS` is excluded; pick the intended category and enable moving-sound processing if records need effects | `SoundPhysics.java:223`; `config/SoundPhysicsConfig.java:150` |
| World initialized, player and level available | Until they're ready the mod restores the default environment | `SoundPhysics.java:208`, `:230` |
| Exact position `(0, 0, 0)` | The mod treats it as a default environment; WATERMeDIA accepts it but can't force the mod to process it | `SoundPhysics.java:211` |
| `maxSoundProcessingDistance = 512` by default | Distant sources get the default environment | `SoundPhysics.java:216`; `config/SoundPhysicsConfig.java:158` |
| Per-identifier sound rate limits and ambient-sound settings | Calls may get a default environment even when the source and context are valid | `SoundPhysics.java:236`, `:242` |
| Attenuation factor | To match its Minecraft linear-attenuation mixin, divide the desired range by `attenuationFactor` while enabled and set the reference distance to half that range; the host must pick the matching linear source distance model | `mixin/SourceMixin.java:42` |

`auxOnly = true` asks the adapter for reflected-only output and needs an environment processor. It doesn't implement reverb or force direct-path silence by itself: a disabled or bypassed processor that returns without applying effects leaves dry audio, so mute the player when you need strict silence there. Basic mono positioning works without EFX; an EFX adapter needs a device that exposes EFX. Keep optional adapter code in the consumer's integration module so WATERMeDIA still loads without Minecraft or _Sound Physics_.

### Verification

`SpatialAudioTest` covers invalid state rejection, Java Sound refusal, real OpenAL source coordinates and attenuation, callback origin restore, effect clearing, exception handling, mismatched contexts and late calls after release. It also generates a stereo WAV and plays it through `FFMediaPlayer`, checking that the stereo decode reaches every sink as mono PCM in real OpenAL buffers. `ALSinkTest` covers the sink set, the mixes in every sample type, batched controls, backpressure, sinks joining on the primary's sample and underrun recovery. The device tests need an OpenAL output device, and the decode case also needs the _FFmpeg_ natives.

An in-game check is still pending for how occlusion and reflections sound, host sound-executor serialization, listener movement, category settings and device reloads: the reference sources were reviewed, but this checkout has no runnable Minecraft plus _Sound Physics_ environment.

## Image and DDS access

> [!WARNING]
> Experimental: don't use it in your mods yet (see [public and internal packages](#public-and-internal-packages)).

`CodecsAPI.reader` picks an internal reader from the leading bytes and returns it as an `ImageReader`. The PNG and GIF chunk structures in `api.codecs.common` are internal, and the PNG and GIF encoders serialize through them.

```java
try (ImageReader reader = CodecsAPI.reader(encodedBuffer)) {
    PixelFormat format = reader.pixelFormat();
    while (reader.hasNext()) {
        ByteBuffer pixels = reader.next();
        // CONSUME OR COPY BEFORE THE NEXT FRAME REUSES THE READER'S BUFFER.
    }
}
```

Every decoder delivers one tightly packed BGRA buffer per frame, except grayscale JPEGs, which deliver one luma byte per pixel (`GRAY`).

- `CodecsAPI.reader(buffer, format)` asks for `BGRA`, `RGBA` or `GRAY` and the reader converts when that isn't its own layout, so `pixelFormat()` and `ImageData.format()` always name what the bytes hold; any other layout is rejected with `IllegalArgumentException`.
- `hasNext()` and `next()` follow iterator rules: `next()` past the last frame throws `NoSuchElementException`. On a closed reader, `hasNext()`, `next()`, `reset()` and `readAll()` throw `IllegalStateException` while the descriptors stay readable. `close()` is idempotent and the only method safe to call from another thread.
- `delay()` is the delay of the frame `next()` just returned, `repeat()` counts total plays (`ImageData.REPEAT_FOREVER`, 0, loops forever), `variableFrameRate()` is false for a single frame and `metadata()` returns a frozen snapshot that never changes under you.
- JPEG and WebP frames come out already rotated to their EXIF orientation, with `width()` and `height()` swapped when needed.
- Metadata uses plain Java values under shared keys (`CodecsAPI.METAKEY_ICC_PROFILE`, `METAKEY_EXIF`, `METAKEY_XMP`, `METAKEY_ORIENTATION`, `METAKEY_LOOP_COUNT`) plus per-format keys such as `PNG_METAKEY_*`. A malformed or oversized ancillary chunk is skipped and logged once, never fatal.
- A valid feature the decoder lacks (an unknown critical PNG chunk, a lossless or CMYK JPEG, an unknown PAM tuple type) throws `UnsupportedFormatException`, a `CodecException` subtype, so a host can tell "fall back" from "broken file".
- Netpbm covers the plain `P1`..`P3` and binary `P4`..`P7` variants.
- `readAll()` keeps copies and enforces a total decoded-byte limit per image. `CodecsAPI.decode` also accepts a `ByteBuffer` or an `InputStream`, and `sniff` a `ByteBuffer`.
- The source buffer is shared with the reader, not copied, so don't change it until the reader closes; when a reader refuses its body, the buffer position stays where it was.
- Concurrent readers still use independent memory: media players coordinate their decoding and reuse decoded images through their caches, while code driving the decoders directly owns its concurrency.

`BcReader` reads already compressed BC1/BC3/BC7 blocks from a DX10 DDS texture array, and `pixelFormat()` names them `PixelFormat.BC1`, `BC3` or `BC7` (`PixelFormat.blockBytes()` gives the block size).

- Instantiate `org.watermedia.api.codecs.BcReader` directly for block access; `CodecsAPI.reader` doesn't dispatch DDS files.
- No native encoder is needed, but the built-in OpenGL, Vulkan and software engines don't accept BC textures, so reading DDS blocks doesn't mean the player supports them end to end.
- A WATERMeDIA animation footer supplies delays when present. Bytes after the texture that don't start with the footer magic (ASCII `WMTC`) are ignored, and plain DDS slices have zero delay; a footer that starts with the magic but is truncated or inconsistent is malformed.
- Mip chains, volumes and cube maps are rejected, and there's no BC encoder or codec-cache option.

## Encoding

> [!WARNING]
> Experimental: don't use it in your mods yet (see [public and internal packages](#public-and-internal-packages)).

`CodecsAPI.encoder(container, target, video, audio)` opens an `Encoder` for one `Container`. `Container.video()` and `Container.audio()` list the codecs each track accepts; a track that doesn't fit, or a decode-only container (WebP, JPEG, Netpbm, SVG), is refused with `IllegalArgumentException` before the target is touched. PNG (APNG when more than one frame arrives) and GIF are pure Java and need no _FFmpeg_; MP4, MOV, MKV, WebM and Ogg go through _FFmpeg_ and throw `IOException` without it.

```java
Encoder.Video video = new Encoder.Video(VideoCodec.H264, 1280, 720, PixelFormat.BGRA, 60);
Encoder.Audio audio = new Encoder.Audio(AudioCodec.AAC, 48_000, 2, SampleType.S16);
try (Encoder encoder = CodecsAPI.encoder(Container.MP4, Path.of("clip.mp4"), video, audio)) {
    encoder.video(frame, ptsUs); // ONE CALL PER FRAME, TIMESTAMPS IN MICROSECONDS THAT KEEP INCREASING
    encoder.audio(pcm);          // INTERLEAVED SAMPLES, CONTINUOUS FROM TIME ZERO
}
```

- **Frames:** exactly `Video.frameBytes()` bytes in `Video.format()`. A frame lasts until the next timestamp and the last one for `1 / fps`; an animation starts with its first frame whatever its timestamp. PNG and GIF take RGBA, BGRA, RGB or GRAY. _FFmpeg_ codecs take any raw `PixelFormat`, converted to the codec's layout and tagged BT.709 limited range (RGB goes through that matrix, YUV is taken as already in it), dropping alpha and growing an odd side by one pixel for 4:2:0 codecs.
- **Quality:** `quality` runs from 0 (smallest) to 100 (best) and maps onto each codec's constant-quality scale; PNG ignores it and GIF dithers its palette from 50 up. `repeat` counts an animation's plays, 0 for forever.
- **Codecs:** H.264 uses _OpenH264_ (_libx264_ in the GPL natives), H.265 _Kvazaar_ (_libx265_ in the GPL natives), AV1 _SVT-AV1_ or _libaom_, VP8 and VP9 _libvpx_, and ProRes `prores_ks`. Opus takes 8, 12, 16, 24 or 48 kHz and resamples any other rate to the next one up, MP3 takes mono or stereo, and every codec gets its own frame size, padding or shortening the last frame.
- **Audio:** the sound timeline starts at the video's time zero and advances with every sample, so the caller pads a late start with silence.
- **Targets:** with a `Path`, the _FFmpeg_ containers encode into `<file>.part` next to it and rename it over the target once `close()` finishes (MP4 and MOV also move their index to the front); with an `OutputStream`, they encode into a private temporary file copied into it. PNG writes the whole file on `close()` and GIF streams it frame by frame. Closing an encoder that got no frame and no sample throws `IllegalStateException` and writes nothing. The stream belongs to the encoder once it opens and stays with the caller when the open is refused. Encoders aren't thread-safe: feed each one from a single thread.

## Synchronized playback

`PlayerAPI.serverPlayer()` builds a `ServerMediaPlayer`, a clock with no media or engines that runs on either side. It owns the time math: start, pause, seek, speed, repeat and the loop wrap or `ENDED` transition run on the system clock, and every successful change bumps `revision()`. The host owns everything else: who may drive the clock, how its snapshots reach the clients and where its position is saved. Engines and players have no public constructors: every instance comes from `PlayerAPI`.

```java
ServerMediaPlayer clock = PlayerAPI.serverPlayer();
clock.syncDuration(durationMs);
clock.start();
// ON EVERY SERVER TICK: SEND clock.snapshot() WITH YOUR OWN PACKET WHEN clock.revision() MOVED
MediaPlayer player = PlayerAPI.player(mrl, gfxSupplier, sfxSupplier);
player.sync(snapshot); // ON THE CLIENT, FOR EVERY SNAPSHOT RECEIVED
```

A player fed a snapshot through `sync(PlayerState)` (or `sync(ByteBuffer)` for the encoded form) follows the clock from then on. Every 50 ms it:

- Mirrors the snapshot's status: start, pause and stop. A `BUFFERING` snapshot, which only a hand-built one carries, holds like a pause.
- Adopts its repeat and speed.
- Pulls playback back with a rate-limited `seekQuick` once `drift()` exceeds `tolerance(ms)` (one second by default).

The latest snapshot fed always wins, so send them in the order the clock produced them. `authority()` returns that snapshot, `authorityTime()` extrapolates where the clock is now and `following()` tells whether a player follows one. Correction never trims the playback speed; it pauses while the player loads or buffers and takes the short way around a loop boundary on repeating media.

A control called on a following player applies at once and is undone by the next tick that disagrees, so route user controls to the clock through your own packets and let the snapshot carry the result. Frame stepping is refused while following, and volume, mute, scaling and spatial audio always stay local.

The clock holds at zero until `syncDuration(ms)` or `live(true)` tells it which timeline it runs on. Report the duration once from a client that opened the source (the first positive value wins for the clock's lifetime and later different values are ignored), and restore a saved position with `syncTime(ms)` after starting the clock, since `start()` restarts from zero. `canSeek()` is false and `seek(...)` returns `false` until a duration is known, and always on a live session. `release()` freezes the position and leaves the clock `STOPPED` with a new revision, so save and send that last snapshot before dropping it, or the players keep extrapolating the previous one. Release each player when its host session ends.

### Snapshot format

`MediaPlayer.PlayerState` is a fixed 27-byte big-endian record (version, revision, status, time, duration, speed, flags). `PlayerState.read(ByteBuffer)` consumes only those bytes and leaves trailing data for your transport; the buffer's own byte order doesn't matter, and a rejected payload leaves the position untouched. Check who sent a snapshot in your transport: the library never identifies peers.

## Executable examples and diagnostics

The project loggers are `watermedia` and `watermedia_binaries`; set those names to DEBUG for diagnostics.

- Project log messages show media links in full, so paths and IDs stay useful for debugging. They hide header values and mask link passwords (`user:password@`), bare access tokens, `Authorization` and `Cookie` values, Minecraft session arguments and secret-looking `key=value` pairs (`DataTool.redact` applies the same filter to the app's log uploads). Exception traces carrying a secret keep their type, stack, causes and suppressed failures as sanitized text.
- Nothing is aggregated per time window: an anomaly that can repeat (a short frame buffer, a refused layout, a failing callback, a rejected upload) is logged on its first occurrence in each engine, player, server or playlist and then stays quiet, and slow playback iterations are counted and reported once when the run ends.
- _FFmpeg_ still writes its own native log lines to the process error output, outside the project loggers and their redaction; routing them through the project logger is ready but disabled until macOS and Linux are validated.

`src/test/java/org/watermedia/ApiGuideExample.java` compiles factory usage for every graphics and audio engine and both sides of synchronized playback, the clock and a follower. Its image decoding example, which filters every frame through a `DownscaleFilter`, runs in `ApiGuideExampleTest`; GUI, audio-device and Minecraft behavior needs the matching host integration. The regular playback and spatial test suites cover real _FFmpeg_ and OpenAL behavior separately.

Examples use host-provided variables: they're integration fragments, not complete apps. Jobs set with `require_natives=true` require the _FFmpeg_ binaries instead of skipping when they're missing.
