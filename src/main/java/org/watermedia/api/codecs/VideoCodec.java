package org.watermedia.api.codecs;

/** Picture compression a {@link Container} carries; see {@link Container#video()} for where each one fits. */
public enum VideoCodec {
    /** PNG: filtered deflate, lossless with alpha; house-made. */
    PNG,
    /** GIF: LZW over a palette of up to 256 colors with one-bit transparency; house-made. */
    GIF,
    /** H.264 (AVC) through FFmpeg. */
    H264,
    /** H.265 (HEVC) through FFmpeg. */
    HEVC,
    /** AV1 through FFmpeg. */
    AV1,
    /** VP8 through FFmpeg. */
    VP8,
    /** VP9 through FFmpeg. */
    VP9,
    /** Apple ProRes 422, a 10-bit intermediate codec for editing, through FFmpeg. */
    PRORES
}
