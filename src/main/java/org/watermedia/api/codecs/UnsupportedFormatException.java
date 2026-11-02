package org.watermedia.api.codecs;

import java.nio.ByteBuffer;

/**
 * Thrown when a stream is valid but cannot be decoded: {@link CodecsAPI#reader(ByteBuffer)} raises it when
 * the leading bytes match no supported format, and readers raise it for a feature they do not implement
 * (a JPEG coding process, a PAM tuple type, a WebP bitstream version). A malformed stream is a plain
 * {@link CodecException} instead.
 */
public final class UnsupportedFormatException extends CodecException {
    public UnsupportedFormatException(final String message) { super(message); }
    public UnsupportedFormatException(final String message, final Throwable cause) { super(message, cause); }
}
