package org.watermedia.api.util;

import org.watermedia.api.player.MRL;

import java.time.Instant;

/**
 * Metadata a platform reports for a media {@link MRL.Source}. Every component is optional:
 * {@code null} text or instant and a {@code durationMs} of zero mean the platform did not say.
 * A field means the same thing on every platform: {@code title} names the content (a video, stream,
 * post or clip, never the account that published it), {@code description} carries its caption, body
 * text or category, and {@code author} names the channel, uploader or streamer.
 *
 * @param title       display title of the content
 * @param description longer description, caption or category
 * @param postedAt    publication time
 * @param durationMs  running time in milliseconds, or zero when unknown or live
 * @param author      uploader, channel or streamer name
 */
public record Metadata(String title, String description, Instant postedAt, long durationMs, String author) {
    public Metadata {
        if (durationMs < 0) throw new IllegalArgumentException("Duration cannot be negative");
    }
}
