package org.watermedia.api.codecs;

/** Sound compression a {@link Container} carries; see {@link Container#audio()} for where each one fits. */
public enum AudioCodec {
    /** Uncompressed signed 16-bit PCM. */
    PCM,
    /** Opus through FFmpeg at 8, 12, 16, 24 or 48 kHz; any other rate is resampled to the next one up. */
    OPUS,
    /** AAC (low complexity) through FFmpeg. */
    AAC,
    /** MP3 through FFmpeg, mono or stereo. */
    MP3,
    /** FLAC, lossless, through FFmpeg. */
    FLAC
}
