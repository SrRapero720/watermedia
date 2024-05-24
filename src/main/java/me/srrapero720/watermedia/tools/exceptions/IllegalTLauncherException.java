package me.srrapero720.watermedia.tools.exceptions;

import me.srrapero720.watermedia.WaterMedia;

import static me.srrapero720.watermedia.WaterMedia.IT;
import static me.srrapero720.watermedia.WaterMedia.LOGGER;

public class IllegalTLauncherException extends Exception {
    public IllegalTLauncherException() {
        super("TLauncher is NOT supported by " + WaterMedia.NAME + ", please stop using it (and consider safe alternatives like SKLauncher or MultiMC)");
        LOGGER.fatal(IT, "##############################  ILLEGAL LAUNCHER DETECTED ######################################");
        LOGGER.fatal(IT, "{} refuses to load sensitive modules in a INFECTED launcher, please stop using TLauncher", WaterMedia.NAME);
        LOGGER.fatal(IT, "Because TLauncher infects sensitive files (which {} includes) and we prefer avoid any risk", WaterMedia.NAME);
        LOGGER.fatal(IT, "Consider use safe alternative like SKLauncher, MultiMC or Buy the game and use original launcher");
        LOGGER.fatal(IT, "And please avoid Feather Launcher, TLauncher Legacy or any CRACKED LAUNCHER (except SKLauncher)");
        LOGGER.fatal(IT, "##############################  ILLEGAL LAUNCHER DETECTED  ######################################");
    }
}