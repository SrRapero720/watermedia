package me.srrapero720.watermedia.tools.exceptions;

import me.srrapero720.watermedia.WaterMedia;

import static me.srrapero720.watermedia.WaterMedia.IT;
import static me.srrapero720.watermedia.WaterMedia.LOGGER;

public class IllegalEnvironmentException extends Exception {
    public IllegalEnvironmentException() {
        super(WaterMedia.NAME + " CANNOT be installed on SERVER-SIDE. Please remove " + WaterMedia.NAME + " from the server, and keep it on client");
        LOGGER.fatal(IT, "##############################  ILLEGAL ENVIRONMENT  ######################################");
        LOGGER.fatal(IT, "{} is not designed to work on server-side, please remove it from server and keep it on client", WaterMedia.NAME);
        LOGGER.fatal(IT, "Dependent mods can work without {} ON SERVERS, remember keep the mod ONLY ON CLIENT-SIDE", WaterMedia.NAME);
        LOGGER.fatal(IT, "if dependent mods throws exceptions ON SERVER asking for WATERMeDIA, report it to the creators");
        LOGGER.fatal(IT, "##############################  ILLEGAL ENVIRONMENT  ######################################");
    }
}