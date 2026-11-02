package org.watermedia.api.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMediaConfig.Network.Server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the {@code network.server} gate that decides whether the file server starts on a host. */
@DisplayName("network.server gate")
public class NetworkServerGateTest {

    @Test
    @DisplayName("OFF never starts the server")
    void offNeverStarts() {
        assertFalse(NetworkAPI.serverEnabled(true, Server.OFF));
        assertFalse(NetworkAPI.serverEnabled(false, Server.OFF));
    }

    @Test
    @DisplayName("DEDICATED starts only on hosts without a client")
    void dedicatedNeedsNoClient() {
        assertFalse(NetworkAPI.serverEnabled(true, Server.DEDICATED));
        assertTrue(NetworkAPI.serverEnabled(false, Server.DEDICATED));
    }

    @Test
    @DisplayName("ALWAYS starts on clients too")
    void alwaysStartsEverywhere() {
        assertTrue(NetworkAPI.serverEnabled(true, Server.ALWAYS));
        assertTrue(NetworkAPI.serverEnabled(false, Server.ALWAYS));
    }
}
