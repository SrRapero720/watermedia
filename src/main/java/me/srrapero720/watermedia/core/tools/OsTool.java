package me.srrapero720.watermedia.core.tools;

import com.sun.jna.Platform;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import static me.srrapero720.watermedia.WaterMedia.LOGGER;

@Deprecated(forRemoval = true)
public enum OsTool {
    WIN_X32("win", "x32", ".dll", false),
    WIN_X64("win", "x64", ".dll", true),
    MAC_X64("mac", "all", ".dylib", true),
    NIX_X64("nix", "x64", ".os", false),

    WIN_ARM64("win", "arm64", ".dll", false),
    MAC_ARM64("mac", "all", ".dylib", true),
    NIX_ARM64("nix", "arm64", ".os", false),

    WIN_ARM("win", "arm", ".dll", false),
    MAC_ARM("mac", "all", ".dylib", true),
    NIX_ARM("nix", "arm", ".os", false),

    DUMMY("dummy", "dummy", ".dummy", false);


    public final String os, arch, ext;
    public final boolean wrapped;
    OsTool(String os, String arch, String ext, boolean wrapped) {
        this.os = os;
        this.arch = arch;
        this.ext = ext;
        this.wrapped = wrapped;
    }
    @Override
    public String toString() { return os + "-" + arch; }

    // STATIC
    private static final Marker IT = MarkerManager.getMarker("Tools");
    private static final OsTool ARCH;
    static {
        ARCH = OsTool.getArch();
        if (!ARCH.wrapped) {
            LOGGER.warn(IT, "################### THIS IS NOT AN ERROR - VLC IS NOT PRE-INSTALLED  ###########################");
            LOGGER.warn(IT, "WATERMeDIA doesn't contain VLC binaries for your OS. You need to manually download and install it");
            LOGGER.warn(IT, "Find out VLC 3 for your operative system and architecture here: https://www.videolan.org/vlc/");
            LOGGER.warn(IT, "################### THIS IS NOT AN ERROR - VLC IS NOT PRE-INSTALLED  ###########################");
        }
    }

    public static OsTool getArch() {
        if (ARCH != null) return ARCH;

        switch (Platform.ARCH) {
            case "x86-64":
            case "amd64":
                if (RuntimeUtil.isWindows()) return WIN_X64;
                if (RuntimeUtil.isMac()) return MAC_X64;
                if (RuntimeUtil.isNix()) return NIX_X64;
            case "arm64":
                if (RuntimeUtil.isWindows()) return WIN_ARM64;
                if (RuntimeUtil.isMac()) return MAC_ARM64;
                if (RuntimeUtil.isNix()) return NIX_ARM64;
            case "armel":
            case "arm":
                if (RuntimeUtil.isWindows()) return WIN_ARM;
                if (RuntimeUtil.isMac()) return MAC_ARM;
                if (RuntimeUtil.isNix()) return NIX_ARM;
            case "x86":
                if (RuntimeUtil.isWindows()) return WIN_X32;
                throw new IllegalStateException("Detected x86 but begin non windows");
            default:
                return DUMMY;
        }
    }
}