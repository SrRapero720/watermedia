package org.watermedia.api.platform;

/** Rejection of mature content while {@code platforms.allowMatureContent} is off; thrown before any media is fetched, once the platform's metadata says so. */
public class MatureContentException extends PlatformException {
    /** Creates a rejection by the platform called {@code platform} described by {@code message}. */
    public MatureContentException(final String platform, final String message) {
        super(platform, message);
    }

    /** Creates a rejection by the platform called {@code platform} described by {@code message} and caused by {@code cause}. */
    public MatureContentException(final String platform, final String message, final Throwable cause) {
        super(platform, message, cause);
    }
}
