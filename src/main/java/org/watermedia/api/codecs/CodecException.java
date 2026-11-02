package org.watermedia.api.codecs;

import java.io.IOException;

/** Sealed root of the codec exception hierarchy, thrown on malformed or unsupported image data. */
public sealed class CodecException extends IOException permits UnsupportedFormatException {
    public CodecException() { super(); }
    public CodecException(final String message) { super(message); }
    public CodecException(final String message, final Throwable cause) { super(message, cause); }
    public CodecException(final Throwable cause) { super(cause); }
}
