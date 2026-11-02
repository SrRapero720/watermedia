package org.watermedia.api.codecs;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Media container: the file format that carries a video track, an audio track or both.
 *
 * <p>{@link #video()} and {@link #audio()} list the codecs {@link CodecsAPI#encoder} accepts for each track; a
 * container with neither is decoded only, and an empty audio set means it carries no sound. Image and animation
 * containers are house-made pure Java; audio and video containers go through FFmpeg.
 */
public enum Container {
    // HOUSE-MADE
    /** PNG, and APNG when it holds more than one frame. */
    PNG(EnumSet.of(VideoCodec.PNG), EnumSet.noneOf(AudioCodec.class)),
    /** GIF, still or animated. */
    GIF(EnumSet.of(VideoCodec.GIF), EnumSet.noneOf(AudioCodec.class)),
    /** WebP, still or animated; decoding only. */
    WEBP(EnumSet.noneOf(VideoCodec.class), EnumSet.noneOf(AudioCodec.class)),
    /** JPEG; decoding only. */
    JPEG(EnumSet.noneOf(VideoCodec.class), EnumSet.noneOf(AudioCodec.class)),
    /** Netpbm (PBM, PGM, PPM, PAM); decoding only. */
    NETPBM(EnumSet.noneOf(VideoCodec.class), EnumSet.noneOf(AudioCodec.class)),
    /** SVG, rasterized; decoding only. */
    SVG(EnumSet.noneOf(VideoCodec.class), EnumSet.noneOf(AudioCodec.class)),

    // FFMPEG
    /** MP4 (ISO BMFF). */
    MP4(EnumSet.of(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.VP9),
            EnumSet.of(AudioCodec.AAC, AudioCodec.OPUS, AudioCodec.MP3, AudioCodec.FLAC)),
    /** QuickTime MOV. */
    MOV(EnumSet.of(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.PRORES), EnumSet.of(AudioCodec.AAC, AudioCodec.PCM)),
    /** Matroska, which carries every codec. */
    MKV(EnumSet.of(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.VP8, VideoCodec.VP9, VideoCodec.PRORES),
            EnumSet.of(AudioCodec.AAC, AudioCodec.OPUS, AudioCodec.MP3, AudioCodec.FLAC, AudioCodec.PCM)),
    /** WebM, the Matroska profile for the web. */
    WEBM(EnumSet.of(VideoCodec.VP8, VideoCodec.VP9, VideoCodec.AV1), EnumSet.of(AudioCodec.OPUS)),
    /** Ogg, audio only. */
    OGG(EnumSet.noneOf(VideoCodec.class), EnumSet.of(AudioCodec.OPUS, AudioCodec.FLAC));

    private final Set<VideoCodec> video;
    private final Set<AudioCodec> audio;

    Container(final EnumSet<VideoCodec> video, final EnumSet<AudioCodec> audio) {
        this.video = Collections.unmodifiableSet(video);
        this.audio = Collections.unmodifiableSet(audio);
    }

    /** Returns the video codecs the container accepts when encoding. */
    public Set<VideoCodec> video() { return this.video; }
    /** Returns the audio codecs the container accepts when encoding, empty when it carries no sound. */
    public Set<AudioCodec> audio() { return this.audio; }
}
