[![CurseForge downloads](https://cf.way2muchnoise.eu/watermedia.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/watermedia)
[![CurseForge](https://img.shields.io/curseforge/v/869524?style=for-the-badge&label=curseforge&labelColor=%232d2d2d&color=%23e04e14&link=https%3A%2F%2Fwww.curseforge.com%2Fminecraft%2Fmc-mods%2Fwatermedia%2Ffiles)](https://www.curseforge.com/minecraft/mc-mods/watermedia/files)
[![Minecraft versions supported](https://cf.way2muchnoise.eu/versions/Supports_watermedia_all.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/watermedia/files)
[![JitPack](https://img.shields.io/jitpack/version/com.github.SrRapero720/watermedia?style=for-the-badge&label=JITPACK&color=34495e&link=https%3A%2F%2Fjitpack.io%2F%23SrRapero720%2Fwatermedia)](https://jitpack.io/#SrRapero720/watermedia)
[![Github issues](https://img.shields.io/github/issues/SrRapero720/watermedia?style=for-the-badge&logo=github)](https://github.com/SrRapero720/watermedia)
[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/SrRapero720/watermedia/gradle.yml?branch=master&event=push&style=for-the-badge&logo=github)](https://github.com/SrRapero720/watermedia)

# WATERMeDIA | MULTIMEDIA API
[![](https://dcbadge.vercel.app/api/server/cuYAzzZ)](https://discord.gg/cuYAzzZ)
[![](https://dcbadge.vercel.app/api/server/453QZ749U4)](https://discord.gg/453QZ749U4)

API-Library providing multimedia support using LibVLC, used by Minecraft mods like VideoPlayer.
Designed to be used in pure Java and Minecraft environments using MinecraftForge (NeoForge) and Fabric (Quilt).

our API provides an extra layer of VLCJ MediaPlayers making easier player management,
async task and concurrency.
Custom ``URLFixers`` to provide support of platforms like YouTube, Twitch,
Kick.com, Google Drive, OneDrive, Dropbox, Imgur, Twitter and PornHub.

## VERSION STATUS
WATERMeDIA can be loaded in any MC version in range of 1.12.2 ~ 1.20.x and
outside Minecraft using the proper dependencies.

IMPORTANT: we only support the most popular versions of Minecraft.

| Version | Forge | Fabric | Quilt | NeoForge |
|:-------:|:-----:|:------:|:-----:|:--------:|
| 1.16.5  |  🚨   |   🚨   |  🚨   |    🚫    |
| 1.18.2  |   ✅   |   ✅    |   ✅   |    🚫    |
| 1.19.2  |   ✅   |   ✅    |   ✅   |    🚫    |
| 1.20.1  |   ✅   |   ✅    |   ✅   |    ✅     |
| 1.21.x  |   !   |   !    |   !   |    ⚠     |

✅ - **SUPPORTED** ||| ⚠ - **PLANNED** ||| ⛔ - **NOT SUPPORT** ||| 🚫 - **N/A** ||| 🚨 - **DROPPING**

# INSTALLATION
You can use two ways to install this mod in your project

## CURSEMAVEN
CurseMaven uses CurseForge network to download 
any released version even if it wasn't approved.
This option doesn't include JavaDoc or Sources, 
so all stacktrace doesn't going to match with your decompiled bytecode
checkout what is the latest version file ID here: https://www.curseforge.com/minecraft/mc-mods/watermedia

**SETUP IN YOUR GRADLE**
```gradle
repositories {
    maven {
        url "https://www.cursemaven.com"
        content { includeGroup "curse.maven" }
    }
}

dependencies {
    // doesn't require fg.debof() or any debofuscation tool
    implementation "curse.maven:watermedia-869524:latest-version-file-id"
}
```

## JITPACK.IO
JitPack.io is a third-party tool to build and distribute artifacts
using direct GitHub tags or branches
This option includes JavaDoc and Sources <br>
Check all available versions [here](https://github.com/SrRapero720/watermedia/tags)

**SETUP IN YOUR GRADLE**
```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    // doesn't require fg.debof() or any debofuscation tool
    implementation 'com.github.SrRapero720.watermedia:build:<version>'
}
```

# VIDEOLAN OS SUPPORT
Our API is based in pure JAVA, except video decoding; we use VideoLAN for that.
It requires VideoLan (libVLC) binaries to load and use video features.

We feature on Windows x64 a pre-installation of VLC in our JAR, 
so they don't have to download it manually. 
But some OS+Arch requires manual installation of VLC, 
and others aren't supported by VLC limitations.

Ensure your OS is supported in this list.<br>
Linux users: check https://www.videolan.org/vlc/#download

|   Operative System    | Support status |
|:---------------------:|:--------------:|
| Windows 7 ~ 11 (x64)  |       ✅        |
| Windows 7 ~ 11 (x32)  |       ❎        |
| Windows 10 ~ 11 (ARM) |       🚫       |
|      MacOS (x64)      |       ❎        |
| MacOS (Apple Silicon) |       ❎        |
|      Linux (x64)      |       ❎        |
|      Linux (ARM)      |       ⛔        |

- ✅ **SUPPORTED**
- ❎ **LIMITED:** Needs manually VLC 3 installation
- 🚫 **NOT COMPATIBLE:** Only in VLC 4 (support planned in 3.x)
- ⛔ **UNSUPPORTED:** we do not provide support

# Projects using WATERMeDIA
- [WATERFrAMES](https://www.curseforge.com/minecraft/mc-mods/waterframes) - Version using: 2.0.x
- [VideoPlayer](https://www.curseforge.com/minecraft/mc-mods/video-player) - Version using: 2.0.x
- [LittlePictureFrames](https://www.curseforge.com/minecraft/mc-mods/littleframes) - Version using: 2.0.x
- [NightmareCraft](https://www.curseforge.com/minecraft/modpacks/nightmare-craft-chapter-1) - Version using: 2.0.x

# MAINTAINERS
- [SrRapero720](https://github.com/SrRapero720) - Mastermind
- [NGoedix](https://github.com/NGoedix) - Mastermind

# CONTRIBUTORS
- [zenoarrows](https://github.com/ZenoArrows) - Buffer Eater
- [cyyynthia](https://github.com/cyyynthia) - Deadlock hunter

# SHADED LIBRARIES
**Important:** All libraries are relocated and wrapped using shadowJar. You can skip our API and directly use
all native libraries... highly no recommended. Find it all on``me.lib720.libraryname`` package
- [VLCJ-natives (forked)](https://github.com/caprica/vlcj-natives/tree/vlcj-4.x)
- ~~[Java-Youtube-Downloader (forked)](https://github.com/sealedtx/java-youtube-downloader)~~

# DEPENDENCIES
**note: those are not shaded**
- LWJGL
- commons-io
- jna
- jna-platform
- gson
- log4j-api
- log4j-core
- commons-lang3