package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * iCCP (embedded ICC profile) chunk: a compressed ICC color profile that defines the image color space.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11iCCP">PNG Specification - iCCP</a>
 */
public record ICCP(String profileName, int compressionMethod, byte[] compressedProfile) implements IChunk {
    public static final int SIGNATURE = 0x69_43_43_50; // "iCCP"
    // CAP DECOMPRESSED PROFILE: A TINY iCCP CHUNK CAN INFLATE TO GIGABYTES (ZLIB BOMB); LEGIT ICC PROFILES ARE WELL UNDER THIS
    private static final int MAX_DECOMPRESSED_BYTES = 16 * 1_024 * 1_024;

    /** Reads a complete iCCP chunk (length, type, data and CRC) from the buffer. */
    public static ICCP read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to iCCP. */
    public static ICCP convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for iCCP: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        // THE PROFILE NAME TAKES 1-79 BYTES AND ENDS AT A NUL
        int nullIndex = -1;
        for (int i = 0; i < Math.min(80, data.length); i++) {
            if (data[i] == 0) {
                nullIndex = i;
                break;
            }
        }
        if (nullIndex < 1) throw new CodecException("Invalid iCCP: missing or empty profile name");
        // NEED THE COMPRESSION-METHOD BYTE AFTER THE NAME NUL; A TRUNCATED PAYLOAD OTHERWISE READS PAST THE ARRAY
        if (data.length < nullIndex + 2) throw new CodecException("Truncated iCCP: missing compression method");

        final String profileName = new String(data, 0, nullIndex, StandardCharsets.ISO_8859_1);
        final int compressionMethod = data[nullIndex + 1] & 0xFF;
        if (compressionMethod != 0) throw new CodecException("Unknown iCCP compression method: " + compressionMethod);

        final int profileStart = nullIndex + 2;
        final byte[] compressedProfile = new byte[data.length - profileStart];
        System.arraycopy(data, profileStart, compressedProfile, 0, compressedProfile.length);
        return new ICCP(profileName, compressionMethod, compressedProfile);
    }

    /** Decompresses and returns the ICC profile data; a truncated profile is an error, a partial profile is useless. */
    public byte[] profile() throws CodecException {
        return Zlib.inflate(this.compressedProfile, MAX_DECOMPRESSED_BYTES, "iCCP", false);
    }

    @Override
    public byte[] toBytes() {
        final byte[] nameBytes = this.profileName.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] data = new byte[nameBytes.length + 1 + 1 + this.compressedProfile.length];
        System.arraycopy(nameBytes, 0, data, 0, nameBytes.length);
        data[nameBytes.length] = 0;
        data[nameBytes.length + 1] = (byte) this.compressionMethod;
        System.arraycopy(this.compressedProfile, 0, data, nameBytes.length + 2, this.compressedProfile.length);
        return data;
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
