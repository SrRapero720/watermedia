package org.watermedia.api.codecs;

import org.watermedia.WaterMedia;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.api.util.SampleType;
import org.watermedia.tools.DataTool;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Streaming encoder of one {@link Container}, opened through {@link CodecsAPI#encoder}.
 *
 * <p>Video frames go in through {@link #video(ByteBuffer, long)} with increasing timestamps and, when the encode
 * has an audio track, interleaved PCM through {@link #audio(ByteBuffer)}; the sound timeline starts at the video's
 * time zero and advances with every sample, so a late start is padded with silence by the caller.
 * {@link #close()} finishes the file and closes the target. An encoder is not thread-safe: feed it from one
 * thread, or under the caller's own lock.
 */
public abstract class Encoder implements Closeable {
    /** Quality a track gets when it names none: a balanced size for lossy codecs. */
    public static final int DEFAULT_QUALITY = 50;

    protected final Container container;
    protected final Video video;
    protected final Audio audio;
    private long lastPtsUs = -1;
    private boolean closed;

    protected Encoder(final Container container, final Video video, final Audio audio) {
        check(container, video, audio);
        this.container = container;
        this.video = video;
        this.audio = audio;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Returns the container this encoder writes. */
    public final Container container() { return this.container; }
    /** Returns the video track, or null when the encode has none. */
    public final Video video() { return this.video; }
    /** Returns the audio track, or null when the encode has none. */
    public final Audio audio() { return this.audio; }

    /**
     * Encodes one frame shown from {@code ptsUs}; it lasts until the next frame, the last one for {@code 1 / fps}.
     * An animation starts with its first frame whatever that frame's timestamp.
     *
     * @param frame  exactly {@link Video#frameBytes()} bytes in {@link Video#format()}, read from its position
     * @param ptsUs  presentation time in microseconds, increasing from frame to frame
     * @throws IllegalArgumentException when the frame is null or of another size, or the timestamp does not increase
     * @throws IllegalStateException    when the encoder is closed or the encode has no video track
     * @throws IOException              when the codec or the target fails
     */
    public final void video(final ByteBuffer frame, final long ptsUs) throws IOException {
        if (this.closed) throw new IllegalStateException(DataTool.errCode(WaterMedia.ERR_ENCODER_FRAME_CLOSED) + "Failed to encode the frame, the encoder is closed");
        if (this.video == null) throw new IllegalStateException(DataTool.errCode(WaterMedia.ERR_ENCODER_FRAME_NO_VIDEO) + "Failed to encode the frame, the encode has no video track");
        if (frame == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_FRAME_NULL) + "Failed to encode the frame due to null frame");
        if (frame.remaining() != this.video.frameBytes())
            throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_FRAME_SIZE) + "Failed to encode the frame due to invalid size: " + frame.remaining() + " bytes, expected " + this.video.frameBytes()
                    + " for " + this.video.width() + "x" + this.video.height() + " " + this.video.format());
        if (ptsUs < 0 || ptsUs <= this.lastPtsUs)
            throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_FRAME_TIMESTAMP) + "Failed to encode the frame due to non-increasing timestamp: " + ptsUs + " after " + this.lastPtsUs);
        this.lastPtsUs = ptsUs;
        this.frame(frame, ptsUs);
    }

    /**
     * Encodes the next interleaved PCM samples of the audio track.
     *
     * @param samples whole sample frames ({@link Audio#frameBytes()} each) in {@link Audio#type()}, read from its position
     * @throws IllegalArgumentException when the buffer is null or splits a sample frame
     * @throws IllegalStateException    when the encoder is closed or the encode has no audio track
     * @throws IOException              when the codec or the target fails
     */
    public final void audio(final ByteBuffer samples) throws IOException {
        if (this.closed) throw new IllegalStateException(DataTool.errCode(WaterMedia.ERR_ENCODER_SAMPLES_CLOSED) + "Failed to encode the samples, the encoder is closed");
        if (this.audio == null) throw new IllegalStateException(DataTool.errCode(WaterMedia.ERR_ENCODER_SAMPLES_NO_AUDIO) + "Failed to encode the samples, the encode has no audio track");
        if (samples == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_SAMPLES_NULL) + "Failed to encode the samples due to null samples");
        if (samples.remaining() % this.audio.frameBytes() != 0)
            throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_SAMPLES_SPLIT) + "Failed to encode the samples due to a split sample frame of " + this.audio.frameBytes() + " bytes: " + samples.remaining());
        if (samples.hasRemaining()) this.samples(samples);
    }

    /**
     * Finishes the file and closes the target; a second call does nothing.
     *
     * @throws IllegalStateException when no frame and no sample was encoded; nothing is written
     * @throws IOException           when the codec or the target fails
     */
    @Override
    public final void close() throws IOException {
        if (this.closed) return;
        this.closed = true;
        this.finish();
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    /** Encodes a validated frame; the buffer is only valid during the call. */
    protected abstract void frame(ByteBuffer frame, long ptsUs) throws IOException;

    /** Encodes validated, non-empty PCM; only called when the encode has an audio track. */
    protected void samples(final ByteBuffer samples) throws IOException {
        throw new IllegalStateException(DataTool.errCode(WaterMedia.ERR_ENCODER_SAMPLES_UNSUPPORTED) + "Failed to encode the samples, " + this.container + " carries no audio");
    }

    /** Flushes the codecs, writes the trailer and closes the target, releasing everything even when it throws. */
    protected abstract void finish() throws IOException;

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // CHECKS THE TRACKS AGAINST THE CONTAINER, SO CodecsAPI REFUSES A MISMATCH BEFORE IT TOUCHES THE TARGET
    static void check(final Container container, final Video video, final Audio audio) {
        if (container == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_CHECK_NULL_CONTAINER) + "Failed to check the encode due to null container");
        if (container.video().isEmpty() && container.audio().isEmpty()) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_CHECK_DECODE_ONLY) + "Failed to check the encode, " + container + " can only be decoded");
        if (video == null && audio == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_CHECK_NO_TRACK) + "Failed to check the encode due to no video or audio track");
        if (video != null && !container.video().contains(video.codec()))
            throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_CHECK_VIDEO_CODEC) + "Failed to check the encode, " + container + " cannot carry " + video.codec() + " video, only " + container.video());
        if (audio != null && !container.audio().contains(audio.codec()))
            throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_CHECK_AUDIO_CODEC) + "Failed to check the encode, " + container + " cannot carry " + audio.codec() + " audio, only " + container.audio());
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /**
     * Video track of an encode.
     *
     * @param codec   picture compression, one of the container's {@link Container#video()}
     * @param width   picture width in pixels
     * @param height  picture height in pixels
     * @param format  layout of the frames given to {@link Encoder#video(ByteBuffer, long)}; block-compressed layouts are refused
     * @param fps     nominal frame rate: the rate control hint and the duration of the last frame
     * @param quality 0 (smallest) to 100 (best); lossless codecs ignore it
     * @param repeat  plays of an animation, 0 for forever; video containers ignore it
     */
    public record Video(VideoCodec codec, int width, int height, PixelFormat format, int fps, int quality, int repeat) {
        public Video {
            if (codec == null || format == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_VIDEO_NULL) + "Failed to create the video track due to null codec or pixel format");
            if (format.compressed()) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_VIDEO_COMPRESSED) + "Failed to create the video track due to compressed format, encoders take raw frames: " + format);
            if (width <= 0 || height <= 0) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_VIDEO_DIMENSIONS) + "Failed to create the video track due to invalid dimensions: " + width + "x" + height);
            if (format.frameBytes(width, height, 8) > Integer.MAX_VALUE)
                throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_VIDEO_BUFFER) + "Failed to create the video track, a " + width + "x" + height + " " + format + " frame does not fit a buffer");
            if (fps <= 0 || fps > 1_000) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_VIDEO_FPS) + "Failed to create the video track due to frame rate out of range [1,1000]: " + fps);
            if (quality < 0 || quality > 100) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_VIDEO_QUALITY) + "Failed to create the video track due to quality out of range [0,100]: " + quality);
            if (repeat < 0) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_VIDEO_REPEAT) + "Failed to create the video track due to negative repeat count: " + repeat);
        }

        /** Creates a track at the default quality that repeats forever. */
        public Video(final VideoCodec codec, final int width, final int height, final PixelFormat format, final int fps) {
            this(codec, width, height, format, fps, DEFAULT_QUALITY, 0);
        }

        /** Returns the bytes one frame takes in {@link #format()}. */
        public long frameBytes() { return this.format.frameBytes(this.width, this.height, 8); }
    }

    /**
     * Audio track of an encode.
     *
     * @param codec      sound compression, one of the container's {@link Container#audio()}
     * @param sampleRate rate of the given samples in Hz; the encoder resamples when its codec needs another
     * @param channels   interleaved channels of the given samples, 1 to 8
     * @param type       sample type of the PCM given to {@link Encoder#audio(ByteBuffer)}
     * @param bitrate    target bits per second for lossy codecs, 0 for the codec default
     */
    public record Audio(AudioCodec codec, int sampleRate, int channels, SampleType type, int bitrate) {
        public Audio {
            if (codec == null || type == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_AUDIO_NULL) + "Failed to create the audio track due to null codec or sample type");
            if (sampleRate < 4_000 || sampleRate > 384_000) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_AUDIO_RATE) + "Failed to create the audio track due to sample rate out of range [4000,384000]: " + sampleRate);
            if (channels < 1 || channels > 8) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_AUDIO_CHANNELS) + "Failed to create the audio track due to channel count out of range [1,8]: " + channels);
            if (bitrate < 0) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_ENCODER_AUDIO_BITRATE) + "Failed to create the audio track due to negative bitrate: " + bitrate);
        }

        /** Creates a track at the codec's default bitrate. */
        public Audio(final AudioCodec codec, final int sampleRate, final int channels, final SampleType type) {
            this(codec, sampleRate, channels, type, 0);
        }

        /** Returns the bytes one interleaved sample frame takes. */
        public int frameBytes() { return this.channels * this.type.bytes(); }
    }
}
