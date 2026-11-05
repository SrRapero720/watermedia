![WATERMeDIA banner](https://raw.githubusercontent.com/SrRapero720/watermedia/master/src/main/resources/assets/watermedia/textures/banner.png)

<p align="center" style="text-align: center;">
<a href="https://www.curseforge.com/minecraft/mc-mods/watermedia"><img src="https://cf.way2muchnoise.eu/watermedia.svg?badge_style=for_the_badge" alt="CurseForge downloads"></a>
<a href="https://www.curseforge.com/minecraft/mc-mods/watermedia/files"><img src="https://img.shields.io/curseforge/v/869524?style=for-the-badge&label=CURSEFORGE&labelColor=2d2d2d&color=e04e14" alt="CurseForge"></a>
<a href="https://modrinth.com/mod/watermedia"><img src="https://img.shields.io/modrinth/v/watermedia?style=for-the-badge&logo=modrinth&label=MODRINTH&color=1bd96a" alt="Modrinth"></a>
<br><br>
<a href="https://jitpack.io/#SrRapero720/watermedia"><img src="https://img.shields.io/jitpack/version/com.github.SrRapero720/watermedia?style=for-the-badge&label=JITPACK&color=34495e" alt="JitPack"></a>
<a href="https://discord.gg/VsVZPUWWYr"><img src="https://img.shields.io/discord/486853064284831744?style=for-the-badge&logo=discord&logoColor=white&label=DISCORD&color=7289DA" alt="Discord"></a>
</p>

<h1 style="color: #EB622B;"><strong>📕 OVERVIEW</strong></h1>

WATERMeDIA is a multimedia library with a well designed API: it plays videos, music, live streams, pictures and
animations, and decodes and encodes them, rendering with OpenGL and Vulkan and playing sound through OpenAL.
Pictures go through our own pure-Java decoders and everything else through _FFmpeg_, bundled in the companion mod
[_WATERMeDIA: Binaries_](https://www.curseforge.com/minecraft/mc-mods/watermedia-binaries). Nothing to compile or
install, just plug and play.

Supports Forge, NeoForge and Fabric on every Minecraft version from 1.18.2 to 26.2 that runs on Java 17 or newer,
on clients and dedicated servers. It also runs outside Minecraft: opening the jar launches a full media player, and
any Java app can embed the API.

<h1 style="color: #EB622B;"><strong>🛠️ FEATURES</strong></h1>

\* Depends on the mod you're using and only works when its developer wires it in. Ask its author.

- 🎬 Videos and music play with _FFmpeg_ 9, bundled in _WATERMeDIA: Binaries_ for Windows (x64), Linux (x64,
  ARM64) and macOS (Intel, Apple Silicon)
- 🖼️ Our own pure-Java picture decoders: PNG, APNG, JPEG, GIF, WebP (animated too), SVG and Netpbm. Safe by
  design, they use far less CPU and VRAM, and many animations can play at once without stuttering the game
- ⚡ Hardware video decoding on NVIDIA, AMD, Intel and macOS, falling back to software when the GPU struggles
- 🌋 Vulkan rendering next to OpenGL, ready for Minecraft 26.x. On Vulkan, H.264, H.265 and AV1 videos decode and
  draw right on the GPU, never touching RAM
- 🌈 HDR and 10, 12 and 16-bit videos look natural on regular screens, and every video keeps its real colors, from
  old SD clips to wide-color videos and phone or webcam recordings
- 💾 Disk cache for videos and pictures: fast and reliable loads that save bandwidth
- 📡 Live streams (HLS and DASH) and IPTV playlists, which open as a channel list
- 🌐 Support for popular online platforms (YouTube, Twitch, Kick, BiliBili, TikTok, Twitter (X) and more), with
  safeguards for mature content where the platform exposes it
- 🖥️ Runs on dedicated servers. Install it there only when the mod using WATERMeDIA requires it server-side; it
  brings an optional file server that shares uploaded media with players through `water://remote/` links
- 🗣️ Videos with a separate audio track play in sync, unlocking higher qualities on platforms like YouTube. Dubbed
  videos play their original language, or the one you prefer, and you can switch languages while watching\*
- 🎚️ Pick the quality, change the speed, seek precisely, loop, and lower the resolution of distant screens to save
  VRAM\*
- 🔊 Positional audio that fades with distance. Stereo screens can play each side from its own edge, and extra
  speakers can play a screen's sound anywhere without loading it twice\*
- ⏱️ Synchronized playback through a server clock, so everyone watches the same moment\*
- 🔍 Search videos by keyword on YouTube, Twitch, Kick and Imgur\*
- 🎥 Record videos with sound to MP4, MOV, MKV, WebM and Ogg, and save pictures and animations as PNG, APNG and GIF (experimental)\*
- 🔒 HTTPS media checks certificates, and tokens, passwords and login data never reach the logs

<h1 style="color: #EB622B;"><strong>🌐 SUPPORTED PLATFORMS</strong></h1>

- 📺 YouTube, Twitch, Kick, BiliBili, Odysee, D.Tube, VidLii
- 🐦 Twitter (X), Bluesky, TikTok, Facebook, Instagram
- 🎵 SoundCloud, Newgrounds (movies, music and art)
- 🖼️ Imgur, Lightshot, Medal, Streamable, Sendvid
- 📁 Google Drive, Dropbox, MediaFire
- 🔞 PornHub (only with mature content allowed in the settings)
- 🔗 Direct links to any video, music, picture or playlist, local files and `water://` resources

YouTube, SoundCloud, Facebook and Instagram play through _yt-dlp_, which requires _WATERMeDIA: Binaries_. BiliBili
plays up to 1080p (8K with VIP) when you add your session cookie in the settings; without it, up to 480p.

<h1 style="color: #EB622B;"><strong>🧩 MODS USING WATERMeDIA</strong></h1>

<table width="100%" style="width: 100%">
  <tr><th>Mod</th><th>Author</th><th>Link</th></tr>
  <tr><td>🖼️ WaterFrames</td><td>SrRapero720</td><td><a href="https://mods.cf/834607">CurseForge</a></td></tr>
  <tr><td>📺 WaterVision</td><td>SrRapero720</td><td><a href="https://mods.cf/1167473">CurseForge</a></td></tr>
  <tr><td>🧱 FancyMenu</td><td>Keksuccino</td><td><a href="https://mods.cf/367706">CurseForge</a></td></tr>
  <tr><td>🖼️ LittlePictureFrames</td><td>CreativeMD</td><td><a href="https://mods.cf/435557">CurseForge</a></td></tr>
  <tr><td>🏺 Vista</td><td>MehVahdJukaar</td><td><a href="https://mods.cf/1368607">CurseForge</a></td></tr>
  <tr><td>⏪ WaterFrames: Backported Edition</td><td>Toshayo</td><td><a href="https://mods.cf/1178892">CurseForge</a></td></tr>
  <tr><td>📽️ Holographic Renderers</td><td>Mysticpasta1</td><td><a href="https://mods.cf/532110">CurseForge</a></td></tr>
  <tr><td>📽️ BBS CML Edition</td><td>McHorse</td><td><a href="https://mods.cf/1392794">CurseForge</a></td></tr>
  <tr><td>💻 Conditional Videos</td><td>MateoF024</td><td><a href="https://mods.cf/1521845">CurseForge</a></td></tr>
  <tr><td>⏯️ Owleaf PlayBack</td><td>Owleaf_Studio</td><td><a href="https://mods.cf/1224054">CurseForge</a></td></tr>
</table>

<h3 style="color: #EB622B;"><strong>🏅 Special mentions</strong></h3>

- VideoPlayer (not maintained)

<h1 style="color: #EB622B;"><strong>📦 WATERMeDIA: BINARIES</strong></h1>

_FFmpeg_ natives and a few extra shared libraries ship in the companion mod
[_WATERMeDIA: Binaries_](https://www.curseforge.com/minecraft/mc-mods/watermedia-binaries). Its sources live in
this repository under [`binaries/`](https://github.com/SrRapero720/watermedia/tree/master/binaries).

- Minecraft clients require Binaries, and both mods must be on 3.0
- Dedicated servers can skip it. CurseForge and Modrinth list it as required because their dependencies can't
  express client/server conditions, so installers may add it to servers too
- Apps embedding the Java API can run without it, keeping picture support but losing video decoding and the
  platforms backed by _yt-dlp_

<table width="100%" style="width: 100%">
  <tr><th>Architecture</th><th align="center">Windows</th><th align="center">Linux</th><th align="center">macOS</th><th align="center">Android</th></tr>
  <tr><td>x86_64</td><td align="center">✅</td><td align="center">✅</td><td align="center">✅</td><td align="center">⛔</td></tr>
  <tr><td>aarch64</td><td align="center">⛔</td><td align="center">✅</td><td align="center">✅</td><td align="center">⛔</td></tr>
</table>

<h1 style="color: #EB622B;"><strong>🎮 CREATE YOUR SERVER AT LOW PRICES</strong></h1>

KineticHosting, our partners in hosting solutions, are great to make a server and start sharing multimedia with
your friends with all the mods listed above.

![KineticHosting sponsor](https://i.imgur.com/GPwmO5S.png)

<h1 style="color: #EB622B;"><strong>⁉️ FAQ</strong></h1>

- **¿Does WATERMeDIA run on servers?**
  - *Yes, since 3.0 it loads on dedicated servers. WATERMeDIA: Binaries is optional there*
- **¿Do I still need VLC?**
  - *No. VLC was replaced by FFmpeg, bundled in WATERMeDIA: Binaries, and uninstalling VLC is safe*
- **¿Why do videos stutter?**
  - *Turn hardware decoding off in `config/watermedia.toml`*
- **¿Can I use my own FFmpeg?**
  - *Yes, FFmpeg can be turned off or swapped for your own compatible build in the settings*
- **¿Why are some videos blocked?**
  - *Mature content is blocked by default, and the site whitelist or blacklist can leave a site out; see FOR USERS*
- **¿Why does the game stop at startup with TLauncher?**
  - *TLauncher is not supported, and the game stops when it's detected*
- **¿Where do I report bugs?**
  - *On [GitHub Issues](https://github.com/SrRapero720/watermedia/issues) or [Discord](https://discord.gg/VsVZPUWWYr)*

<h1 style="color: #EB622B;"><strong>🙋 FOR USERS</strong></h1>

WATERMeDIA keeps its settings in `config/watermedia.toml`, inside your game folder (or your server folder on a
dedicated server). It's created on the first launch with a comment above every field. Edit it while the game is
closed, and your changes apply the next time it starts. Each name below is a `[section]` plus a field:
`player.cacheBudget` is `cacheBudget` under `[player]`.

<h2 style="color: #EB622B;"><strong>📤 ENABLE THE FILE SERVER</strong></h2>

The file server lets players upload their own videos, music and pictures to your Minecraft server and share them
with everyone through `water://remote/` links, with no outside hosting. It needs WATERMeDIA on the server and a mod
that offers uploads.

1. On the server, set these fields in `config/watermedia.toml`, with your server's public IP or domain in
   `remoteUrl`:
   ```toml
   [network]
     server = "DEDICATED"
     serverBindAddress = "0.0.0.0"
     serverPort = 25572
     remoteUrl = "http://play.example.com:25572/"
     token = "a-long-secret-only-you-know"
   ```
2. Open that port (TCP) in your firewall or your host's panel; WATERMeDIA doesn't open it for you.
3. Copy the same `remoteUrl` and `token` into the `config/watermedia.toml` of every player. Modpacks can ship it.

- The server won't start for outside players while `token` keeps its default value. Change it on every server
  update to keep strangers from uploading.
- Each upload can weigh up to 8 MB (`maxUploadSize`, 0 means no limit) and all of them together up to 1024 MB
  (`maxStorageSize`).
- Uploaded files land in `watermedia/files` inside the server folder; delete them there to free space.
- It speaks plain HTTP, so put it behind your own HTTPS proxy when it faces the internet.
- `server = "ALWAYS"` also runs it from your own game, for worlds you host yourself.

<h2 style="color: #EB622B;"><strong>🖥️ STANDALONE APP</strong></h2>

The WATERMeDIA jar is also a desktop media player. With Java 17 or newer installed, double click it, or run
`java -jar watermedia-<version>.jar` from a terminal.

- **Play media** opens any link or file path, or searches by keyword. The sample tiles try links from every
  supported platform, and **Television** opens live TV channels by region.
- Keep _WATERMeDIA: Binaries_ in the same folder to play videos and music; without it, only pictures and animations
  play.
- The first launch downloads a few libraries, so it needs internet that time.
- It draws with Vulkan and falls back to OpenGL when Vulkan fails. Write `engine=OPENGL` in `config/app.properties`
  to always use OpenGL.
- **Cleanup cache** empties the media cache, the same one your game uses.

<h3 style="color: #EB622B;"><strong>🐞 BUG REPORTS AND LOGS</strong></h3>

Something broke in your game? Open the app from your Minecraft `mods` folder and pick **Upload Logs** (`U`):

1. It finds your `latest.log`, the newest crash report and the newest JVM crash file (`hs_err_pid`).
2. It hides tokens, passwords and login data, then uploads the files to [mclo.gs](https://mclo.gs).
3. It copies a ready report to your clipboard. Choose where to send it, WATERMeDIA or the mod you suspect
   (FancyMenu, WaterFrames, WaterVision or LittleFrames, when installed), and paste it into the issue page that
   opens.

Files over 10 MB or 25,000 lines don't upload, so share those by hand. You can always send `logs/latest.log` and the
`crash-reports` folder from your game folder on [GitHub Issues](https://github.com/SrRapero720/watermedia/issues) or
[Discord](https://discord.gg/VsVZPUWWYr).

<h2 style="color: #EB622B;"><strong>💾 CONFIGURE CACHE SIZE</strong></h2>

Downloaded videos and pictures stay on disk, so they load faster next time and save bandwidth. They live in
`watermedia/cache` inside your system's temporary folder (`%TEMP%` on Windows, `/tmp` on Linux), and deleting it
while the game is closed is safe.

- `player.cacheBudget`: disk space for the whole cache, in MB (8192 by default). Once it's full, the oldest files
  leave first.
- `codecs.ffmpeg.cacheMaxResponse`: the biggest video or music file it keeps, in MB (10 by default). Bigger files
  stream every time.
- `player.watertx.cache` and `codecs.ffmpeg.cache`: set them to `false` to stop caching pictures or videos.
- `player.watertx.texturesBudget`: video memory for smooth animations, in MB (32 by default). Set it to 0 when your
  GPU runs short on VRAM.

<h2 style="color: #EB622B;"><strong>🚦 WHITELIST, BLACKLIST AND MATURE CONTENT</strong></h2>

Choose which sites can play under `[platforms]`:

```toml
[platforms]
  allowMatureContent = false
  whitelist = ["youtube.com", "imgur.com"]
  blacklist = ["example.com"]
```

- `whitelist`: when it lists any site, only links from those sites play. Empty, the default, lets every site play.
- `blacklist`: links from these sites never play.
- Both take hostnames only, like `website.com`: no `https://`, paths or `*`.
- `website.com` covers the whole site, subdomains included (`some.website.com`, `another.website.com`), while
  `some.website.com` covers only that subdomain and never `website.com` itself.
- `allowMatureContent`: mature videos and streams stay blocked until you set it to `true`. It works where the
  platform marks them, such as Kick, Twitch or Twitter (X), and PornHub needs it to play at all.

<h1 style="color: #EB622B;"><strong>💻 FOR DEVELOPERS</strong></h1>

Two classes do the work: `MRL` (Multimedia Resource Location) resolves a link into its sources, qualities and
metadata, and `MediaPlayer` plays it through the engines you pick (`GLEngine`, `VKEngine`, `ALEngine` and more).

```java
MRL mrl = PlayerAPI.mrl(URI.create("https://imgur.com/gallery/snow-ducks-YcDd9x"));

// IN A TICK-LOOP METHOD
if (mrl.status() == MRL.Status.LOADED) {
    MediaPlayer player = PlayerAPI.player(mrl,
            () -> PlayerAPI.glEngine(renderThread, renderExecutor),
            () -> PlayerAPI.alEngine());
}
```

- `PlayerAPI`: media playback and display
- `PlatformAPI`: web platform support for loading and searching media
- `NetworkAPI`: host and remote storage for private media
- `CodecsAPI` (**experimental**, don't use it in your mods yet): our own picture and animation codecs, plus
  _FFmpeg_-backed audio and video

The full API guide lives on the [wiki](https://github.com/SrRapero720/watermedia/wiki) in
[English](https://github.com/SrRapero720/watermedia/wiki/EN-US-Home),
[Español (México)](https://github.com/SrRapero720/watermedia/wiki/ES-MX-Home),
[Español (Argentina)](https://github.com/SrRapero720/watermedia/wiki/ES-AR-Home),
[Português (Brasil)](https://github.com/SrRapero720/watermedia/wiki/PT-BR-Home),
[Русский](https://github.com/SrRapero720/watermedia/wiki/RU-RU-Home) and
[简体中文](https://github.com/SrRapero720/watermedia/wiki/ZH-CN-Home). Artifacts are on
[JitPack](https://jitpack.io/#SrRapero720/watermedia).

<h1 style="color: #EB622B;"><strong>💰 DONATIONS</strong></h1>

Thank you for using WATERMeDIA! 🫶 Every donation, big or small, helps us keep it free, updated and growing with
every new Minecraft version.

[![Support me on Patreon](https://img.shields.io/badge/Patreon-F96854?style=for-the-badge&logo=patreon&logoColor=white)](https://patreon.com/SrRapero720)
[![Support me via Paypal](https://img.shields.io/badge/PayPal-00457C?style=for-the-badge&logo=paypal&logoColor=white)](https://paypal.me/SrRapero720)
[![Support me on Ko-Fi](https://img.shields.io/badge/Ko--fi-F16061?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/srrapero720)
[![Support me via Revolut](https://img.shields.io/badge/REVOLUT-DONATE%20DIRECTLY-191C1F?style=for-the-badge&logo=revolut&logoColor=black&labelColor=ffffff)](https://revolut.me/srrap720)

> Please consider donating to the [_FFmpeg_ developers](https://www.ffmpeg.org/donations.html) too. Every video
> WATERMeDIA plays goes through their work, and this project wouldn't exist without them.

<h1 style="color: #EB622B;"><strong>⚖️ LICENSE</strong></h1>

WATERMeDIA and _WATERMeDIA: Binaries_ are under the
[_Defensive Public Source (Non-Commercial) License v1.0.1_](https://github.com/SrRapero720/watermedia/blob/master/LICENSE.md).
Copyright SrRapero720 (https://github.com/SrRapero720).

Permitted non-commercial usage (included and not limited to modpacks or server packs). Commercial use is forbidden;
contact us to use WATERMeDIA for commercial purposes.

The third-party libraries both mods ship keep their own licenses; read them in the
[WATERMeDIA](https://github.com/SrRapero720/watermedia) and
[_WATERMeDIA: Binaries_](https://github.com/SrRapero720/watermedia-binaries) repositories before redistributing.
