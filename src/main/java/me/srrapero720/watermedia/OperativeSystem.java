package me.srrapero720.watermedia;

import com.sun.jna.Platform;
import me.srrapero720.watermedia.core.exceptions.UnsupportedArchitechtureException;

import static me.srrapero720.watermedia.WaterMedia.IT;
import static me.srrapero720.watermedia.WaterMedia.LOGGER;

/**
 * Contains the current OS, architecture and the binary-wrap state
 * @deprecated see
 */
@Deprecated
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
        if (Platform.is64Bit()) {
            if (Platform.isARM()) {
                if (Platform.isWindows()) return WIN_ARM64;
                if (Platform.isMac()) return MAC_ARM64;
                if (Platform.isLinux()) return NIX_ARM64;
            } else {
                if (Platform.isWindows()) return WIN_X64;
                if (Platform.isMac()) return MAC_X64;
                if (Platform.isLinux()) return NIX_X64;
            }
            return DUMMY;
        } else {
            throw new UnsupportedArchitechtureException();
        }
    }
}