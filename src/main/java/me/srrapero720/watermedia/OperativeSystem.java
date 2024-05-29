package me.srrapero720.watermedia;

import com.sun.jna.Platform;
import me.srrapero720.watermedia.runtime.UnsupportedArchitechtureException;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;

import static me.srrapero720.watermedia.WaterMedia.IT;
import static me.srrapero720.watermedia.WaterMedia.LOGGER;

public enum OperativeSystem {
    WIN_X64("win", "x64", true, false),
    MAC_X64("mac", "x64", true, true),
    NIX_X64("nix", "x64", false, false),
    WIN_ARM64("win", "arm64", false, false),
    MAC_ARM64("mac", "arm64", true, true),
    NIX_ARM64("nix", "arm64", false, false),
    WIN_ARM("win", "arm", false, false),
    MAC_ARM("mac", "arm", true, true),
    NIX_ARM("nix", "arm", false, false),

    DUMMY("dummy", "dummy", false, false);

    private final String name, arch;
    private final boolean wrapped, merged;
    OperativeSystem(String name, String arch, boolean wrapped, boolean merged) {
        this.name = name;
        this.arch = arch;
        this.wrapped = wrapped;
        this.merged = merged;
    }

    @Override
    public String toString() { return name + "-" + arch; }

    // STATIC
    public static final OperativeSystem OS = getOs();
    static {
        if (!OS.wrapped) {
            LOGGER.warn(IT, "[NOT A BUG] {} doesn't contains VLC binaries for your operative system and architecture, you had to manually download it from 'https://www.videolan.org/vlc/'", WaterMedia.NAME);
        }
    }

    public static boolean isWrapped() { return OS.wrapped; }
    public static boolean isMerged() { return OS.merged; }
    public static String getFile() { return (isMerged() ? getName() + "-all" : getName() + "-" + getArch()) + ".7z"; }
    public static String getName() { return OS.name; }
    public static String getArch() { return OS.arch; }

    private static OperativeSystem getOs() {
        if (!Platform.is64Bit()) throw new UnsupportedArchitechtureException();
        switch (Platform.ARCH) {
            case "x86-64":
            case "amd64":
                if (Platform.isWindows()) return WIN_X64;
                if (Platform.isMac()) return MAC_X64;
                if (Platform.isLinux()) return NIX_X64;
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
                throw new IllegalStateException("Detected unsupported arch x86");
            default:
                return DUMMY;
        }
    }
}