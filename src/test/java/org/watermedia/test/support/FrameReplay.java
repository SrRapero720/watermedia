package org.watermedia.test.support;

import org.watermedia.api.codecs.ImageReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks that {@link ImageReader#reset()} replays every frame byte for byte. */
public final class FrameReplay {
    private FrameReplay() {}

    /** Decodes every frame, replays the reader twice through reset() and returns the first pass's SHA-256 frame hashes. */
    public static List<byte[]> verify(final ImageReader reader, final String name) throws IOException {
        final long[] delays = reader.delays();
        final List<byte[]> first = hashes(reader, name);
        assertFalse(first.isEmpty(), "No frames decoded for " + name);
        for (int pass = 1; pass <= 2; pass++) {
            assertTrue(reader.reset(), "reset() pass " + pass + " must be supported for " + name);
            assertArrayEquals(delays, reader.delays(), "Delays changed after reset for " + name);
            final List<byte[]> replay = hashes(reader, name);
            assertEquals(first.size(), replay.size(), "Frame count changed after reset for " + name);
            for (int i = 0; i < first.size(); i++)
                assertArrayEquals(first.get(i), replay.get(i), "Frame " + i + " not byte-identical after reset for " + name);
        }
        return first;
    }

    // HASHES INSTEAD OF COPIES: LARGE FIXTURES TIMES THREE PASSES WOULD EXHAUST THE TEST JVM'S DIRECT MEMORY,
    // AND SHA-256 EQUALITY IS BYTE IDENTITY. EVERY FRAME MUST ALSO BE TIGHTLY PACKED IN ITS DECLARED LAYOUT
    private static List<byte[]> hashes(final ImageReader reader, final String name) throws IOException {
        final MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); }
        catch (final NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
        final List<byte[]> out = new ArrayList<>();
        while (reader.hasNext()) {
            final ByteBuffer frame = reader.next();
            assertEquals(reader.pixelFormat().frameBytes(reader.frameWidth(), reader.frameHeight(), 8), frame.remaining(),
                    "Frame " + out.size() + " size of " + name);
            sha.update(frame.duplicate());
            out.add(sha.digest());
        }
        return out;
    }
}
