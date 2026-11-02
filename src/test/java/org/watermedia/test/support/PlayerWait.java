package org.watermedia.test.support;

import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.MediaPlayer.Status;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded spin-waits that observe player transitions on their own threads instead of sleeping an arbitrary time. */
public final class PlayerWait {
    private static final long POLL_INTERVAL_MS = 5L;

    private PlayerWait() {}

    /** Waits until {@code player.status()} matches one of {@code anyOf}, or {@code timeoutMs} elapses. */
    public static boolean awaitStatus(final MediaPlayer player, final long timeoutMs, final Status... anyOf) {
        final Set<Status> targets = EnumSet.copyOf(Arrays.asList(anyOf));
        return awaitCondition(() -> targets.contains(player.status()), timeoutMs);
    }

    /** Waits until {@code player.canPlay()} returns true, or {@code timeoutMs} elapses. */
    public static boolean awaitLoaded(final MediaPlayer player, final long timeoutMs) {
        return awaitCondition(player::canPlay, timeoutMs);
    }

    /** Returns whether the condition holds within {@code timeoutMs}, polling every 5 ms. */
    public static boolean awaitCondition(final BooleanSupplier condition, final long timeoutMs) {
        // MONOTONIC: A WALL-CLOCK ADJUSTMENT MUST NEVER STRETCH OR CUT A WAIT
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() - deadline < 0) {
            if (condition.getAsBoolean()) return true;
            try { Thread.sleep(POLL_INTERVAL_MS); }
            catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
