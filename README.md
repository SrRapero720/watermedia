![WATERMeDIA banner](https://raw.githubusercontent.com/SrRapero720/watermedia/master/src/main/resources/assets/watermedia/textures/banner.png)

<p align="center" style="text-align: center;">
<a href="https://www.curseforge.com/minecraft/mc-mods/watermedia"><img src="https://cf.way2muchnoise.eu/watermedia.svg?badge_style=for_the_badge" alt="CurseForge downloads"></a>
<a href="https://www.curseforge.com/minecraft/mc-mods/watermedia/files"><img src="https://img.shields.io/curseforge/v/869524?style=for-the-badge&label=CURSEFORGE&labelColor=2d2d2d&color=e04e14" alt="CurseForge"></a>
<a href="https://modrinth.com/mod/watermedia"><img src="https://img.shields.io/modrinth/v/watermedia?style=for-the-badge&logo=modrinth&label=MODRINTH&color=1bd96a" alt="Modrinth"></a>
<br>
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
  <tr><td>🖼️ WaterFrames</td><td>SrRapero720</td><td><a href="https://www.curseforge.com/minecraft/mc-mods/waterframes">CurseForge</a></td></tr>
  <tr><td>📺 WaterVision</td><td>SrRapero720</td><td><a href="https://www.curseforge.com/minecraft/mc-mods/watervision">CurseForge</a></td></tr>
  <tr><td>🧱 FancyMenu</td><td>Keksuccino</td><td><a href="https://www.curseforge.com/minecraft/mc-mods/fancymenu">CurseForge</a></td></tr>
  <tr><td>📽️ Holographic Renderers</td><td>Mysticpasta1</td><td><a href="https://www.curseforge.com/minecraft/mc-mods/holographic-renderers">CurseForge</a></td></tr>
  <tr><td>📽️ BBS CML Edition</td><td>McHorse</td><td><a href="https://mods.cf/1392794">CurseForge</a></td></tr>
  <tr><td>🖼️ LittlePictureFrames</td><td>CreativeMD</td><td><a href="https://www.curseforge.com/minecraft/mc-mods/littleframes">CurseForge</a></td></tr>
  <tr><td>⏪ WaterFrames: Backported Edition</td><td>Toshayo</td><td><a href="https://www.curseforge.com/minecraft/mc-mods/waterframesbackported">CurseForge</a></td></tr>
  <tr><td>💻 Conditional Videos</td><td>MateoF024</td><td><a href="https://mods.cf/1521845">CurseForge</a></td></tr>
  <tr><td>⏯️ Owleaf PlayBack</td><td>Owleaf_Studio</td><td><a href="https://www.curseforge.com/minecraft/mc-mods/owleaf-playback">CurseForge</a></td></tr>
</table>

<h3 style="color: #EB622B;"><strong>Special mentions</strong></h3>

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
  - *Mature content is blocked by default; you can allow it in the settings*
- **¿Why does the game stop at startup with TLauncher?**
  - *TLauncher is not supported, and the game stops when it's detected*
- **¿Where do I report bugs?**
  - *On [GitHub Issues](https://github.com/SrRapero720/watermedia/issues) or [Discord](https://discord.gg/VsVZPUWWYr)*

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

The full API guide, including the Vulkan contract and the Minecraft 26.x bridge, is in
[English](https://github.com/SrRapero720/watermedia/blob/master/docs/en-us.md) and
[Español (México)](https://github.com/SrRapero720/watermedia/blob/master/docs/es-mx.md). Artifacts are on
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
[_WATERMeDIA: Binaries_](https://github.com/WaterMediaTeam/binaries) repositories before redistributing.
