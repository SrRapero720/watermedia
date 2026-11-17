package org.watermedia.api.player;

import java.io.IOException;

/** Refusal of a link whose site {@code player.blacklist} forbids; thrown before any platform or request touches it. */
public final class BlacklistException extends IOException {
    // ONLY THE SITE LISTS CHECK OF MediaPlayer REFUSES LINKS
    BlacklistException(final String message) {
        super(message);
    }
}
