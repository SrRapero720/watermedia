package org.watermedia.api.player;

import java.io.IOException;

/** Refusal of a link whose site {@code player.whitelist} does not allow; thrown before any platform or request touches it. */
public final class WhitelistException extends IOException {
    // ONLY THE SITE LISTS CHECK OF MediaPlayer REFUSES LINKS
    WhitelistException(final String message) {
        super(message);
    }
}
