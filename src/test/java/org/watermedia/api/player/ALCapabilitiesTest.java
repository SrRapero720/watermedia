package org.watermedia.api.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.SampleType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the {@link ALEngine} channel table lists only the native formats of each OpenAL extension mix, without a device. */
@DisplayName("ALEngine capability table")
public class ALCapabilitiesTest {

    @Test
    @DisplayName("Extension-limited contexts advertise only their native formats")
    void extensionLimitedContextsAdvertiseOnlyTheirNativeFormats() throws Exception {
        final var table = ALEngine.class.getDeclaredMethod("channelTable", boolean.class, boolean.class, boolean.class, boolean.class);
        table.setAccessible(true);
        for (int mask = 0; mask < 8; mask++) {
            final boolean floating = (mask & 1) != 0;
            final boolean doubles = (mask & 2) != 0;
            final boolean multichannel = (mask & 4) != 0;
            for (final boolean spatial: new boolean[] { false, true }) {
                final var channels = (SFXEngine.ChannelSupport[]) table.invoke(null, floating, doubles, multichannel, spatial);
                // SPATIAL SINKS SPLIT A MONO OR STEREO DECODE, NEVER A SURROUND ONE
                assertEquals(multichannel && !spatial ? 6 : 2, channels.length);
                for (final var entry: channels) {
                    assertTrue(entry.supports(SampleType.U8));
                    assertTrue(entry.supports(SampleType.S16));
                    assertFalse(entry.supports(SampleType.S32));
                    assertEquals(entry.channels() > 2 || floating, entry.supports(SampleType.FLT));
                    assertEquals(entry.channels() <= 2 && doubles, entry.supports(SampleType.DBL));
                }
            }
        }
    }
}
