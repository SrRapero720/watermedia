package org.watermedia.api.codecs.encoders;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avcodec.AVPacketSideData;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVAudioFifo;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVPixFmtDescriptor;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.watermedia.WaterMedia;
import org.watermedia.api.codecs.AudioCodec;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.codecs.decoders.FFDecoder;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MathUtil;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.FFTool;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * FFmpeg transcoder of one media into one file. Each track is copied as it comes or decoded and encoded again at a
 * target bitrate, and both end in one container; the video and the audio may come from two inputs, as platforms
 * serve them. One instance runs one job on the calling thread, and {@link #probe} reads what an input holds first.
 */
public final class FFTranscoder {
    private static final Marker IT = MarkerManager.getMarker(FFTranscoder.class.getSimpleName());

    // TIMING
    private static final int VIDEO_CLOCK_HZ = 90_000;          // MPEG CLOCK, THE ONE FFEncoder STAMPS WITH
    private static final int GOP_SECONDS = 2;                  // A KEYFRAME EVERY TWO SECONDS KEEPS SEEKING CHEAP
    private static final int PCM_CHUNK_SAMPLES = 1_024;        // FRAME SIZE OF CODECS THAT TAKE ANY, SUCH AS PCM
    private static final float DEFAULT_FPS = 30f;              // RATE OF A VIDEO THAT STATES NONE
    private static final long PROGRESS_STEP_NS = 200_000_000L; // AT MOST FIVE PROGRESS CALLS A SECOND

    // BITRATE MEASURE OF AN INPUT THAT STATES NONE (HLS): A FEW WINDOWS SPREAD OVER THE MEDIA
    private static final int MEASURE_WINDOWS = 3;
    private static final long MEASURE_WINDOW_US = 4_000_000L;
    private static final long MEASURE_WINDOW_BYTES = 12L * 1_024 * 1_024;
    private static final long MEASURE_MIN_US = 500_000L;       // A SHORTER SPAN SAYS NOTHING ABOUT THE AVERAGE

    // CLOCK JUMPS OF A FORMAT THAT MAY RESTART ITS TIMESTAMPS: BEYOND THESE A PACKET CONTINUES ITS TRACK INSTEAD OF MOVING IN TIME
    private static final long JUMP_BACK_US = 1_000_000L;
    private static final long JUMP_AHEAD_US = 10_000_000L;

    // GPU ENCODERS THAT TAKE FRAMES FROM SYSTEM MEMORY: NVIDIA, INTEL, AMD, APPLE AND WINDOWS MEDIA FOUNDATION
    private static final String[] HARDWARE = { "_nvenc", "_qsv", "_amf", "_videotoolbox", "_mf" };

    private final Job job;
    private final Runnable progressed;
    private final IntPointer abort;    // RAISED BY cancel() AND POLLED BY THE INTERRUPT CALLBACK OF EVERY INPUT
    private final AVRational micros;
    private volatile boolean cancelled;
    private volatile float progress;
    private volatile String encoder;
    private boolean ran;

    // INPUTS
    private AVFormatContext main;      // CARRIES THE VIDEO, AND THE AUDIO WHEN NO SEPARATE TRACK DOES
    private AVFormatContext side;      // SEPARATE AUDIO TRACK
    private AVStream videoIn;
    private AVStream audioIn;
    private long mainStartUs;
    private long sideStartUs;
    private long durationUs;

    // OUTPUT
    private Path tempFile;
    private AVFormatContext muxer;
    private AVPacket packet;           // READ FROM AN INPUT
    private AVPacket encoded;          // WRITTEN BY AN ENCODER
    private AVFrame frame;             // DECODED PICTURE OR SOUND
    private AVStream videoOut;
    private AVStream audioOut;
    private long videoDts = Long.MIN_VALUE;
    private long audioDts = Long.MIN_VALUE;
    private long packets;
    private long notifiedNs;

    // VIDEO RECODE
    private AVCodecContext videoDecoder;
    private AVCodecContext videoEncoder;
    private SwsContext scaler;
    private int scalerWidth;
    private int scalerHeight;
    private int scalerFormat = avutil.AV_PIX_FMT_NONE;
    private AVFrame converted;
    private long frameTicks;
    private long lastTicks = -1;
    private long lastSlot = -1;
    private int limitFps;
    private boolean videoWarned;

    // AUDIO RECODE
    private AVCodecContext audioDecoder;
    private AVCodecContext audioEncoder;
    private SwrContext resampler;
    private int resamplerFormat;
    private int resamplerRate;
    private int resamplerChannels;
    private AVAudioFifo fifo;
    private AVFrame resampled;         // RESAMPLER OUTPUT, GROWN ON DEMAND
    private AVFrame chunk;             // ONE ENCODER FRAME READ FROM THE FIFO
    private int chunkSamples;
    private boolean shortLast;         // THE CODEC TAKES A SHORTER LAST FRAME, ELSE IT IS PADDED WITH SILENCE
    private long samplesSent;
    private boolean audioStarted;
    private boolean audioWarned;

    /** Checks the job against its container, so a refused one never touches the network or the disk; {@code progressed} may be null. */
    public FFTranscoder(final Job job, final Runnable progressed) throws IOException {
        if (job == null || job.input() == null || job.headers() == null || job.container() == null || job.file() == null)
            throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_JOB_NULL) + "Failed to create the transcode due to null job, input, headers, container or file");

        final Video video = job.video();
        final Audio audio = job.audio();
        final Container container = job.container();

        // VALIDATION
        if (video == null && audio == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_NO_TRACK) + "Failed to create the transcode due to no video or audio track");
        if (FFEncoder.muxer(container) == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_CONTAINER) + "Failed to create the transcode due to non-FFmpeg container: " + container);
        if (video != null && (container.video().isEmpty() || (video.codec() != null && !container.video().contains(video.codec()))))
            throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_VIDEO_CODEC) + "Failed to create the transcode, " + container + " cannot carry " + (video.codec() == null ? "copied" : video.codec()) + " video, only " + container.video());
        if (audio != null && audio.codec() != null && !container.audio().contains(audio.codec()))
            throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_AUDIO_CODEC) + "Failed to create the transcode, " + container + " cannot carry " + audio.codec() + " audio, only " + container.audio());
        if ((video != null && (video.bitrate() < 0 || video.fps() < 0 || video.effort() == null)) || (audio != null && audio.bitrate() < 0))
            throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_RATES) + "Failed to create the transcode due to negative bitrate or frame rate, or null effort");
        if (!CodecsAPI.ffmpegLoaded()) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_NO_FFMPEG) + "Failed to create the transcode due to unavailable FFmpeg");

        this.job = job;
        this.progressed = progressed;
        this.abort = new IntPointer(new int[1]);
        this.micros = avutil.av_make_q(1, 1_000_000);
    }

    /** Returns the written share of the media, 0 to 1; it stays 0 for a media of unknown length. */
    public float progress() { return this.progress; }
    /** Returns the name of the video encoder the run opened, or null while it has none. */
    public String encoder() { return this.encoder; }

    /** Ends the run at its next packet, waking a read blocked on the network; the unfinished file is deleted. */
    public void cancel() {
        this.cancelled = true;
        this.abort.put(1);
    }

    /** Reads what an input holds: its length and, per track, the codec, the layout and the bitrate, measured from the media when nothing states it. */
    public static Info probe(final URI input, final HttpHeaders headers) throws IOException {
        if (!CodecsAPI.ffmpegLoaded()) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_PROBE_NO_FFMPEG) + "Failed to probe the media due to unavailable FFmpeg");
        // NEVER RAISED: A PROBE ENDS BY ITS OWN TIMEOUTS
        final IntPointer abort = new IntPointer(new int[1]);
        final AVFormatContext context = open(input, headers, abort);
        final AVPacket packet = avcodec.av_packet_alloc();
        try {
            final long length = context.duration();
            final long durationUs = length == avutil.AV_NOPTS_VALUE || length < 0 ? 0 : length;
            final int video = stream(context, avutil.AVMEDIA_TYPE_VIDEO);
            final int audio = stream(context, avutil.AVMEDIA_TYPE_AUDIO);
            for (int i = 0; i < context.nb_streams(); i++) if (i != video && i != audio) context.streams(i).discard(avcodec.AVDISCARD_ALL);

            // BITRATES: THE STREAM'S OWN, ELSE WHAT THE FILE SIZE LEAVES (A PLAYLIST HAS NO SIZE OF ITS OWN), ELSE MEASURED
            long videoRate = video < 0 ? 0 : context.streams(video).codecpar().bit_rate();
            long audioRate = audio < 0 ? 0 : context.streams(audio).codecpar().bit_rate();
            final String format = FFTool.isNull(context.iformat()) ? "" : FFTool.string(context.iformat().name(), "");
            final long total = format.contains("hls") || format.contains("dash") ? 0 : context.bit_rate();
            if (video >= 0 && videoRate <= 0 && (audio < 0 || audioRate > 0)) videoRate = Math.max(0, total - audioRate);
            if (audio >= 0 && audioRate <= 0 && (video < 0 || videoRate > 0)) audioRate = Math.max(0, total - videoRate);
            if (!FFTool.isNull(packet) && ((video >= 0 && videoRate <= 0) || (audio >= 0 && audioRate <= 0))) {
                final long[] measured = measure(context, packet, durationUs);
                if (video >= 0 && videoRate <= 0) videoRate = measured[video];
                if (audio >= 0 && audioRate <= 0) audioRate = measured[audio];
            }

            VideoInfo picture = null;
            if (video >= 0) {
                final AVStream stream = context.streams(video);
                final AVCodecParameters parameters = stream.codecpar();
                // avg_frame_rate, ELSE r_frame_rate, EACH ONLY WHEN PLAUSIBLE: MPEG-TS REPORTS ITS 90 KHZ CLOCK AS r_frame_rate
                final double average = avutil.av_q2d(stream.avg_frame_rate());
                final double real = avutil.av_q2d(stream.r_frame_rate());
                final float fps = average >= 1.0 && average <= 1_000.0 ? (float) average : real >= 1.0 && real <= 1_000.0 ? (float) real : 0f;
                final VideoCodec codec = switch (parameters.codec_id()) {
                    case avcodec.AV_CODEC_ID_H264 -> VideoCodec.H264;
                    case avcodec.AV_CODEC_ID_HEVC -> VideoCodec.HEVC;
                    case avcodec.AV_CODEC_ID_AV1 -> VideoCodec.AV1;
                    case avcodec.AV_CODEC_ID_VP8 -> VideoCodec.VP8;
                    case avcodec.AV_CODEC_ID_VP9 -> VideoCodec.VP9;
                    case avcodec.AV_CODEC_ID_PRORES -> VideoCodec.PRORES;
                    default -> null;
                };
                picture = new VideoInfo(codec, FFTool.string(avcodec.avcodec_get_name(parameters.codec_id()), "unknown"),
                        parameters.width(), parameters.height(), fps, videoRate);
            }
            AudioInfo sound = null;
            if (audio >= 0) {
                final AVCodecParameters parameters = context.streams(audio).codecpar();
                final AudioCodec codec = switch (parameters.codec_id()) {
                    case avcodec.AV_CODEC_ID_AAC -> AudioCodec.AAC;
                    case avcodec.AV_CODEC_ID_OPUS -> AudioCodec.OPUS;
                    case avcodec.AV_CODEC_ID_MP3 -> AudioCodec.MP3;
                    case avcodec.AV_CODEC_ID_FLAC -> AudioCodec.FLAC;
                    case avcodec.AV_CODEC_ID_PCM_S16LE -> AudioCodec.PCM;
                    default -> null;
                };
                sound = new AudioInfo(codec, FFTool.string(avcodec.avcodec_get_name(parameters.codec_id()), "unknown"),
                        parameters.sample_rate(), parameters.ch_layout().nb_channels(), audioRate);
            }
            return new Info(durationUs / 1_000, picture, sound);
        } finally {
            if (!FFTool.isNull(packet)) avcodec.av_packet_free(packet);
            avformat.avformat_close_input(context);
            // FREED ONLY NOW: THE INTERRUPT CALLBACK READ IT UNTIL THE INPUT CLOSED
            abort.close();
        }
    }

    /**
     * Runs the job on the calling thread. The file appears only once finished: it is written next to its target
     * as {@code <file>.part} and replaces it at the end.
     *
     * @return whether the file was written; false after {@link #cancel()}, which leaves no file behind
     * @throws IllegalStateException on a second run
     * @throws IOException           when an input, a codec or the target fails
     */
    public boolean run() throws IOException {
        if (this.ran) throw new IllegalStateException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_RERUN) + "Failed to run the transcode, it already ran");
        this.ran = true;
        try {
            this.openInputs();
            if (this.cancelled) return false;
            this.openOutput();
            LOGGER.debug(IT, "Transcoding {} into {}: video {}, audio {}", this.job.input(), this.job.file(),
                    this.videoOut == null ? "none" : this.videoEncoder == null ? "copied" : this.encoder,
                    this.audioOut == null ? "none" : this.audioEncoder == null ? "copied" : FFTool.string(this.audioEncoder.codec().name(), "?"));
            this.pump();
            if (this.cancelled) return false;

            // THE RESAMPLER TAIL AND THE LAST SHORT CHUNK, THEN EVERY ENCODER'S LOOKAHEAD
            if (this.audioEncoder != null) {
                if (this.resampler != null) {
                    this.resample(null);
                    this.sendChunks(true);
                }
                this.send(this.audioEncoder, this.audioOut, null);
            }
            if (this.videoEncoder != null) this.send(this.videoEncoder, this.videoOut, null);
            if (this.packets == 0) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_EMPTY) + "Failed to transcode due to no packets, the media is empty or its codec is unsupported");
            // THE TRAILER MAY REWRITE THE WHOLE FILE (FASTSTART), SO A CANCEL THAT ARRIVED DURING THE FLUSH STOPS HERE
            if (this.cancelled) return false;
            final int trailer = avformat.av_write_trailer(this.muxer);
            if (trailer < 0) throw FFTool.failure("av_write_trailer", trailer);

            // CLOSE THE NATIVE FILE SO EVERY PATCHED BYTE IS ON DISK BEFORE IT BECOMES THE TARGET
            final AVIOContext pb = this.muxer.pb();
            this.muxer.pb(null);
            final int closed = avformat.avio_close(pb);
            if (closed < 0) throw FFTool.failure("avio_close", closed);
            if (this.cancelled) return false;
            Files.move(this.tempFile, this.job.file(), StandardCopyOption.REPLACE_EXISTING);
            this.tempFile = null;
            this.progress = 1f;
            return true;
        } catch (final IOException | RuntimeException e) {
            // A CANCEL THAT CUT A NATIVE CALL SHORT IS NO FAILURE
            if (this.cancelled) return false;
            throw e;
        } finally {
            this.free();
        }
    }

    // ==========================================================================
    // SETUP
    // ==========================================================================
    // OPENS THE INPUTS THE JOB READS AND PICKS ITS STREAMS, DISCARDING THE REST SO NOTHING FETCHES THEM
    private void openInputs() throws IOException {
        final boolean separate = this.job.audio() != null && this.job.track() != null;
        if (this.job.video() != null || !separate) this.main = open(this.job.input(), this.job.headers(), this.abort);
        if (separate) this.side = open(this.job.track(), this.job.headers(), this.abort);

        final int video = this.job.video() == null ? -1 : stream(this.main, avutil.AVMEDIA_TYPE_VIDEO);
        if (this.job.video() != null && video < 0) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_NO_VIDEO) + "Failed to transcode due to no video track in " + this.job.input());
        final AVFormatContext sound = separate ? this.side : this.main;
        final int audio = this.job.audio() == null ? -1 : stream(sound, avutil.AVMEDIA_TYPE_AUDIO);
        if (this.job.audio() != null && audio < 0) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_NO_AUDIO) + "Failed to transcode due to no audio track in " + (separate ? this.job.track() : this.job.input()));
        if (video >= 0) this.videoIn = this.main.streams(video);
        if (audio >= 0) this.audioIn = sound.streams(audio);

        for (final AVFormatContext input: new AVFormatContext[] { this.main, this.side }) {
            if (input == null) continue;
            for (int i = 0; i < input.nb_streams(); i++) {
                final boolean used = (input == this.main && i == video) || (input == sound && i == audio);
                if (!used) input.streams(i).discard(avcodec.AVDISCARD_ALL);
            }
            // EACH INPUT COUNTS FROM ITS OWN START, SO BOTH TRACKS BEGIN AT ZERO IN THE FILE
            final long start = input.start_time() == avutil.AV_NOPTS_VALUE ? 0 : input.start_time();
            if (input == this.main) this.mainStartUs = start;
            else this.sideStartUs = start;
            if (input.duration() != avutil.AV_NOPTS_VALUE) this.durationUs = Math.max(this.durationUs, input.duration());
        }
    }

    // OPENS THE MUXER ON <file>.part, ONE OUTPUT STREAM PER TRACK (COPIED PARAMETERS OR AN ENCODER) AND WRITES THE HEADER
    private void openOutput() throws IOException {
        final Container container = this.job.container();
        final Path file = this.job.file().toAbsolutePath();
        // ABSOLUTE, SO FFMPEG NEVER READS A "scheme:" PREFIX AS A PROTOCOL
        this.tempFile = file.resolveSibling(file.getFileName() + ".part");
        this.packet = avcodec.av_packet_alloc();
        this.encoded = avcodec.av_packet_alloc();
        this.frame = avutil.av_frame_alloc();
        if (FFTool.isNull(this.packet) || FFTool.isNull(this.encoded) || FFTool.isNull(this.frame))
            throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_ALLOC) + "Failed to allocate the packets and the frame");

        try (final BytePointer name = new BytePointer(FFEncoder.muxer(container), StandardCharsets.US_ASCII);
             final BytePointer path = new BytePointer(this.tempFile.toString(), StandardCharsets.UTF_8)) {
            this.muxer = new AVFormatContext(null);
            if (avformat.avformat_alloc_output_context2(this.muxer, null, name, path) < 0 || this.muxer.isNull())
                throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_MUXER_ALLOC) + "Failed to allocate the " + container + " muxer");
            final boolean globalHeader = (this.muxer.oformat().flags() & avformat.AVFMT_GLOBALHEADER) != 0;

            // TRACKS OPEN BEFORE THE FILE, SO A CODEC THAT REFUSES ITS TRACK CREATES NOTHING
            if (this.videoIn != null) {
                final VideoCodec codec = this.job.video().codec();
                if (codec == null) {
                    this.videoOut = this.copied(this.videoIn);
                    this.videoOut.avg_frame_rate(this.videoIn.avg_frame_rate());
                    this.videoOut.sample_aspect_ratio(this.videoIn.sample_aspect_ratio());
                } else {
                    this.openVideo(globalHeader);
                }
                // APPLE PLAYERS ONLY OPEN HEVC TAGGED hvc1, WHILE FFMPEG TAGS IT hev1 IN MP4 AND MOV
                if (this.videoOut.codecpar().codec_id() == avcodec.AV_CODEC_ID_HEVC && (container == Container.MP4 || container == Container.MOV))
                    this.videoOut.codecpar().codec_tag('h' | 'v' << 8 | 'c' << 16 | '1' << 24);
            }
            if (this.audioIn != null) {
                if (this.job.audio().codec() == null) this.audioOut = this.copied(this.audioIn);
                else this.openAudio(globalHeader);
            }

            final AVIOContext pb = new AVIOContext(null);
            final int opened = avformat.avio_open(pb, path, avformat.AVIO_FLAG_WRITE);
            if (opened < 0) throw FFTool.failure("avio_open", opened);
            this.muxer.pb(pb);
        }

        // THE TITLE AND THE AUTHOR TRAVEL AS TAGS OF THE FILE
        final AVDictionary tags = new AVDictionary(null);
        if (this.job.title() != null && !this.job.title().isBlank()) avutil.av_dict_set(tags, "title", this.job.title(), 0);
        if (this.job.author() != null && !this.job.author().isBlank()) avutil.av_dict_set(tags, "artist", this.job.author(), 0);
        this.muxer.metadata(tags);

        final AVDictionary options = new AVDictionary(null);
        try {
            // THE MP4 FAMILY MOVES ITS INDEX TO THE FRONT, SO PLAYERS START BEFORE THE WHOLE FILE ARRIVES
            if (FFEncoder.faststart(container)) avutil.av_dict_set(options, "movflags", "+faststart", 0);
            final int header = avformat.avformat_write_header(this.muxer, options);
            if (header < 0) throw FFTool.failure("avformat_write_header", header);
        } finally {
            avutil.av_dict_free(options);
        }
    }

    // OUTPUT STREAM THAT CARRIES THE INPUT'S PACKETS AS THEY COME, UNDER THE CODEC TAG THE MUXER PICKS
    private AVStream copied(final AVStream input) throws IOException {
        final AVStream stream = avformat.avformat_new_stream(this.muxer, null);
        if (FFTool.isNull(stream)) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_STREAM_CREATE) + "Failed to create a stream");
        final int copied = avcodec.avcodec_parameters_copy(stream.codecpar(), input.codecpar());
        if (copied < 0) throw FFTool.failure("avcodec_parameters_copy", copied);
        stream.codecpar().codec_tag(0);
        stream.time_base(input.time_base());
        return stream;
    }

    // OUTPUT STREAM FED BY AN ENCODER
    private AVStream encoded(final AVCodecContext context) throws IOException {
        final AVStream stream = avformat.avformat_new_stream(this.muxer, null);
        if (FFTool.isNull(stream)) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_STREAM_CREATE_ENCODED) + "Failed to create an encoded stream");
        stream.time_base(context.time_base());
        final int parameters = avcodec.avcodec_parameters_from_context(stream.codecpar(), context);
        if (parameters < 0) throw FFTool.failure("avcodec_parameters_from_context", parameters);
        return stream;
    }

    // OPENS A SOFTWARE DECODER OF THE STREAM WITH THE FRAME THREADS IT PICKS BY ITSELF
    private AVCodecContext decoder(final AVStream stream, final boolean video) throws IOException {
        final int id = stream.codecpar().codec_id();
        final AVCodec found = avcodec.avcodec_find_decoder(id);
        final AVCodec codec = video ? FFDecoder.softwareVideoDecoder(id, found) : found;
        if (FFTool.isNull(codec)) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_DECODER_MISSING) + "Failed to find a decoder for " + FFTool.string(avcodec.avcodec_get_name(id), "the track"));
        final AVCodecContext context = avcodec.avcodec_alloc_context3(codec);
        if (FFTool.isNull(context)) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_DECODER_ALLOC) + "Failed to allocate the " + FFTool.string(codec.name(), "?") + " decoder");
        int opened = avcodec.avcodec_parameters_to_context(context, stream.codecpar());
        if (opened >= 0) {
            context.pkt_timebase(stream.time_base());
            context.thread_count(0);
            opened = avcodec.avcodec_open2(context, codec, (PointerPointer<?>) null);
        }
        if (opened < 0) {
            avcodec.avcodec_free_context(context);
            throw FFTool.failure("avcodec_open2 (" + FFTool.string(codec.name(), "decoder") + ")", opened);
        }
        return context;
    }

    private void openVideo(final boolean globalHeader) throws IOException {
        final Video video = this.job.video();
        this.videoDecoder = this.decoder(this.videoIn, true);
        final int source = this.videoDecoder.pix_fmt();
        final AVPixFmtDescriptor sourceLayout = avutil.av_pix_fmt_desc_get(source);
        if (FFTool.isNull(sourceLayout)) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_PIXEL_FORMAT) + "Failed to read the video due to unknown pixel format, the stream is unsupported or broken");
        final boolean rgb = (sourceLayout.flags() & avutil.AV_PIX_FMT_FLAG_RGB) != 0;
        // A DEEPER SOURCE STAYS AT 10 BITS WHERE DECODERS EXPECT IT, WHILE H.264 AND VP8 STAY AT THE 8 BITS EVERY DEVICE PLAYS
        final boolean deep = sourceLayout.comp(0).depth() > 8 && video.codec() != VideoCodec.H264 && video.codec() != VideoCodec.VP8;

        // FRAME RATE: THE SOURCE'S, OR THE LIMIT WHEN IT IS LOWER
        final AVRational guessed = avformat.av_guess_frame_rate(this.main, this.videoIn, null);
        final double guess = guessed.den() == 0 ? 0 : avutil.av_q2d(guessed);
        final float sourceFps = guess >= 1.0 && guess <= 1_000.0 ? (float) guess : DEFAULT_FPS;
        this.limitFps = video.fps() > 0 && video.fps() < sourceFps ? video.fps() : 0;
        final float fps = this.limitFps > 0 ? this.limitFps : sourceFps;
        final AVRational frameRate = this.limitFps > 0 || guess < 1.0 || guess > 1_000.0 ? avutil.av_make_q(Math.round(fps), 1) : guessed;
        this.frameTicks = Math.max(1, Math.round(VIDEO_CLOCK_HZ / fps));

        // CANDIDATES: THE GPU ENCODERS WHEN ASKED FOR, THEN THE SOFTWARE ONES, AND THE FIRST THAT OPENS WINS
        final List<AVCodec> candidates = new ArrayList<>();
        final List<String> tried = new ArrayList<>();
        if (video.hardware()) for (final String vendor: HARDWARE) tried.add(video.codec().name().toLowerCase(Locale.ROOT) + vendor);
        tried.addAll(Arrays.asList(FFEncoder.software(video.codec())));
        for (final String name: tried) {
            final AVCodec found = avcodec.avcodec_find_encoder_by_name(name);
            if (!FFTool.isNull(found)) candidates.add(found);
        }
        if (candidates.isEmpty()) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_VIDEO_ENC_MISSING) + "Failed to find the " + video.codec() + " encoder due to missing natives, tried " + String.join(", ", tried));

        IOException refused = null;
        for (final AVCodec codec: candidates) {
            final String name = FFTool.string(codec.name(), "");
            final AVCodecContext context = avcodec.avcodec_alloc_context3(codec);
            if (FFTool.isNull(context)) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_VIDEO_ENC_ALLOC) + "Failed to allocate the " + name + " encoder");

            // PIXEL FORMAT: 10-BIT 4:2:0 FOR A DEEP SOURCE, ELSE 8-BIT 4:2:0, ELSE NV12, ELSE THE CODEC'S FIRST ONE IN SYSTEM MEMORY
            final int[] formats = FFEncoder.supported(context, codec, avcodec.AV_CODEC_CONFIG_PIX_FORMAT);
            int target = formats.length == 0 ? avutil.AV_PIX_FMT_YUV420P : avutil.AV_PIX_FMT_NONE;
            for (final int wanted: new int[] { avutil.AV_PIX_FMT_NV12, avutil.AV_PIX_FMT_YUV420P, deep ? avutil.AV_PIX_FMT_YUV420P10LE : avutil.AV_PIX_FMT_NONE })
                for (final int candidate: formats) if (candidate == wanted && wanted != avutil.AV_PIX_FMT_NONE) target = candidate;
            for (int i = 0; target == avutil.AV_PIX_FMT_NONE && i < formats.length; i++)
                if ((avutil.av_pix_fmt_desc_get(formats[i]).flags() & avutil.AV_PIX_FMT_FLAG_HWACCEL) == 0) target = formats[i];
            if (target == avutil.AV_PIX_FMT_NONE) {
                avcodec.avcodec_free_context(context);
                continue;
            }

            // SUBSAMPLED CHROMA NEEDS EVEN SIDES, SO AN ODD ONE GROWS BY A PIXEL THAT THE SCALER STRETCHES INTO
            final AVPixFmtDescriptor layout = avutil.av_pix_fmt_desc_get(target);
            final int width = this.videoDecoder.width() + (layout.log2_chroma_w() > 0 ? this.videoDecoder.width() & 1 : 0);
            final int height = this.videoDecoder.height() + (layout.log2_chroma_h() > 0 ? this.videoDecoder.height() & 1 : 0);

            context.width(width);
            context.height(height);
            context.pix_fmt(target);
            context.time_base(avutil.av_make_q(1, VIDEO_CLOCK_HZ));
            context.framerate(frameRate);
            context.sample_aspect_ratio(this.videoDecoder.sample_aspect_ratio());
            context.gop_size(Math.max(1, Math.round(fps * GOP_SECONDS)));
            context.thread_count(0); // EVERY SOFTWARE ENCODER RUNS ONE THREAD UNLESS TOLD TO PICK ITS OWN COUNT
            // THE SOURCE'S OWN COLOR TAGS, SO HDR AND BT.601 STAY WHAT THEY ARE, WHILE RGB TURNS INTO BT.709 LIMITED RANGE
            context.colorspace(rgb ? avutil.AVCOL_SPC_BT709 : this.videoDecoder.colorspace());
            context.color_primaries(rgb ? avutil.AVCOL_PRI_BT709 : this.videoDecoder.color_primaries());
            context.color_trc(rgb ? avutil.AVCOL_TRC_BT709 : this.videoDecoder.color_trc());
            context.color_range(rgb ? avutil.AVCOL_RANGE_MPEG : this.videoDecoder.color_range());
            if (globalHeader) context.flags(context.flags() | avcodec.AV_CODEC_FLAG_GLOBAL_HEADER);
            tune(context, name, video);

            final int opened = avcodec.avcodec_open2(context, codec, (PointerPointer<?>) null);
            if (opened >= 0) {
                this.videoEncoder = context;
                this.encoder = name;
                break;
            }
            avcodec.avcodec_free_context(context);
            refused = FFTool.failure("avcodec_open2 (" + name + ")", opened);
            LOGGER.warn(IT, "Encoder {} refused the video ({}), trying the next one", name, FFTool.error(opened));
        }
        if (this.videoEncoder == null) throw refused != null ? refused : new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_VIDEO_ENC_FORMAT) + "Failed to open the " + video.codec() + " encoder due to no pixel format in system memory");

        this.videoOut = this.encoded(this.videoEncoder);
        // PACKETS CARRY NO DURATION, SO THE MUXER TAKES IT FROM THE RATE AND THE LAST FRAME LASTS 1 / fps INSTEAD OF 0
        this.videoOut.avg_frame_rate(frameRate);
        this.videoOut.sample_aspect_ratio(this.videoEncoder.sample_aspect_ratio());

        // THE ROTATION AND THE HDR MASTERING LEVELS TRAVEL NEXT TO THE STREAM, NOT INSIDE IT: AN ENCODE THAT DROPPED THEM WOULD
        // TURN A PHONE VIDEO SIDEWAYS AND LEAVE HDR WITHOUT ITS LEVELS
        final AVCodecParameters from = this.videoIn.codecpar();
        final AVCodecParameters to = this.videoOut.codecpar();
        for (final int type: new int[] { avcodec.AV_PKT_DATA_DISPLAYMATRIX, avcodec.AV_PKT_DATA_MASTERING_DISPLAY_METADATA, avcodec.AV_PKT_DATA_CONTENT_LIGHT_LEVEL }) {
            final AVPacketSideData found = from.nb_coded_side_data() <= 0 ? null : avcodec.av_packet_side_data_get(from.coded_side_data(), from.nb_coded_side_data(), type);
            if (FFTool.isNull(found)) continue;
            // THE LIST MAY MOVE WHEN IT GROWS, SO ITS NEW ADDRESS AND COUNT GO BACK INTO THE STREAM, WHICH OWNS THE COPY ONCE ADDED
            final AVPacketSideData list = FFTool.isNull(to.coded_side_data()) ? new AVPacketSideData(null) : to.coded_side_data();
            final Pointer copy = avutil.av_memdup(found.data(), found.size());
            try (final IntPointer count = new IntPointer(1).put(to.nb_coded_side_data())) {
                if (FFTool.isNull(copy) || FFTool.isNull(avcodec.av_packet_side_data_add(list, count, type, copy, found.size(), 0))) {
                    avutil.av_free(copy);
                    throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_SIDE_DATA) + "Failed to carry the rotation or the HDR levels of the video over");
                }
                to.coded_side_data(list);
                to.nb_coded_side_data(count.get());
            }
        }

        this.converted = avutil.av_frame_alloc();
        if (FFTool.isNull(this.converted)) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_VIDEO_FRAME_ALLOC) + "Failed to allocate the converted video frame");
        this.converted.format(this.videoEncoder.pix_fmt());
        this.converted.width(this.videoEncoder.width());
        this.converted.height(this.videoEncoder.height());
        final int buffer = avutil.av_frame_get_buffer(this.converted, 32);
        if (buffer < 0) throw FFTool.failure("av_frame_get_buffer", buffer);
    }

    private void openAudio(final boolean globalHeader) throws IOException {
        final Audio audio = this.job.audio();
        this.audioDecoder = this.decoder(this.audioIn, false);
        final AVCodec codec = FFEncoder.encoder(audio.codec().name(), FFEncoder.software(audio.codec()));
        this.audioEncoder = avcodec.avcodec_alloc_context3(codec);
        if (FFTool.isNull(this.audioEncoder)) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_AUDIO_ENC_ALLOC) + "Failed to allocate the " + audio.codec() + " encoder");

        // SAMPLE FORMAT: THE SOURCE'S OWN WHEN THE CODEC TAKES IT, ITS FIRST ONE OTHERWISE
        final int[] formats = FFEncoder.supported(this.audioEncoder, codec, avcodec.AV_CODEC_CONFIG_SAMPLE_FORMAT);
        int format = formats.length == 0 ? this.audioDecoder.sample_fmt() : formats[0];
        for (final int candidate: formats) if (candidate == this.audioDecoder.sample_fmt()) format = candidate;
        final int rate = FFEncoder.rate(FFEncoder.supported(this.audioEncoder, codec, avcodec.AV_CODEC_CONFIG_SAMPLE_RATE), this.audioDecoder.sample_rate());

        // CHANNEL LAYOUT: THE CODEC'S OWN OF THE SOURCE'S CHANNEL COUNT, ELSE ITS WIDEST BELOW IT (A DOWNMIX), ELSE ITS FIRST;
        // A CODEC THAT TAKES ANY GETS THE DEFAULT ONE OF THAT COUNT
        final int channels = Math.max(1, this.audioDecoder.ch_layout().nb_channels());
        try (final PointerPointer<Pointer> list = new PointerPointer<>(1); final IntPointer count = new IntPointer(1)) {
            final int found = avcodec.avcodec_get_supported_config(this.audioEncoder, codec, avcodec.AV_CODEC_CONFIG_CHANNEL_LAYOUT, 0, list, count);
            final Pointer entries = list.get(0);
            if (found < 0 || FFTool.isNull(entries) || count.get() <= 0) {
                avutil.av_channel_layout_default(this.audioEncoder.ch_layout(), channels);
            } else {
                final AVChannelLayout layouts = new AVChannelLayout(entries);
                int pick = 0;
                int best = -1;
                for (int i = 0; i < count.get(); i++) {
                    final int offered = layouts.position(i).nb_channels();
                    if (offered > channels || offered <= best) continue;
                    best = offered;
                    pick = i;
                }
                avutil.av_channel_layout_copy(this.audioEncoder.ch_layout(), layouts.position(pick));
            }
        }
        this.audioEncoder.sample_fmt(format);
        this.audioEncoder.sample_rate(rate);
        this.audioEncoder.time_base(avutil.av_make_q(1, rate));
        if (audio.bitrate() > 0 && audio.codec() != AudioCodec.PCM && audio.codec() != AudioCodec.FLAC) this.audioEncoder.bit_rate(audio.bitrate());
        if (globalHeader) this.audioEncoder.flags(this.audioEncoder.flags() | avcodec.AV_CODEC_FLAG_GLOBAL_HEADER);
        final int opened = avcodec.avcodec_open2(this.audioEncoder, codec, (PointerPointer<?>) null);
        if (opened < 0) throw FFTool.failure("avcodec_open2 (" + FFTool.string(codec.name(), audio.codec().name()) + ")", opened);
        this.audioOut = this.encoded(this.audioEncoder);

        // CODECS WITH A FIXED FRAME SIZE TAKE EXACTLY THAT MANY SAMPLES PER FRAME, SO THE FIFO CUTS THEM
        final int capabilities = codec.capabilities();
        final boolean variable = (capabilities & avcodec.AV_CODEC_CAP_VARIABLE_FRAME_SIZE) != 0 || this.audioEncoder.frame_size() <= 0;
        this.chunkSamples = variable ? PCM_CHUNK_SAMPLES : this.audioEncoder.frame_size();
        this.shortLast = variable || (capabilities & avcodec.AV_CODEC_CAP_SMALL_LAST_FRAME) != 0;
        this.fifo = avutil.av_audio_fifo_alloc(format, this.audioEncoder.ch_layout().nb_channels(), this.chunkSamples * 2);
        if (FFTool.isNull(this.fifo)) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_FIFO_ALLOC) + "Failed to allocate the audio FIFO");
        this.chunk = this.audioFrame(this.chunkSamples);
        this.resampled = this.audioFrame(this.chunkSamples);
    }

    private AVFrame audioFrame(final int samples) throws IOException {
        final AVFrame created = avutil.av_frame_alloc();
        if (FFTool.isNull(created)) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_AUDIO_FRAME_ALLOC) + "Failed to allocate an audio frame");
        created.format(this.audioEncoder.sample_fmt());
        created.sample_rate(this.audioEncoder.sample_rate());
        created.nb_samples(samples);
        final int copied = avutil.av_channel_layout_copy(created.ch_layout(), this.audioEncoder.ch_layout());
        final int buffer = copied < 0 ? copied : avutil.av_frame_get_buffer(created, 0);
        if (buffer < 0) {
            avutil.av_frame_free(created);
            throw FFTool.failure("av_frame_get_buffer", buffer);
        }
        return created;
    }

    // ==========================================================================
    // PUMP
    // ==========================================================================
    // READS BOTH INPUTS TO THEIR END. THE ONE BEHIND IN TIME IS READ NEXT, SO THE MUXER INTERLEAVES A FEW PACKETS AND NEVER
    // HOLDS A WHOLE TRACK IN MEMORY
    private void pump() throws IOException {
        final AVFormatContext[] inputs = { this.main, this.side };
        // WHAT THE TIMESTAMPS OF EACH INPUT LOSE TO COUNT FROM ZERO, MOVED WHENEVER ITS CLOCK JUMPS
        final long[] starts = { this.mainStartUs, this.sideStartUs };
        final long[] clocks = { Long.MIN_VALUE, Long.MIN_VALUE };
        // WHERE THE NEXT VIDEO AND THE NEXT AUDIO PACKET SHOULD START
        final long[] expected = { Long.MIN_VALUE, Long.MIN_VALUE };
        final boolean[] ended = { this.main == null, this.side == null };
        final int sound = this.side != null ? 1 : 0;
        final int again = avutil.AVERROR_EAGAIN();
        while (!ended[0] || !ended[1]) {
            if (this.cancelled) return;
            final int n = ended[0] || (!ended[1] && clocks[1] <= clocks[0]) ? 1 : 0;
            final int read = avformat.av_read_frame(inputs[n], this.packet);
            if (read == again) continue;
            if (read < 0) {
                if (this.cancelled) return;
                // A CUT CONNECTION MUST FAIL THE DOWNLOAD: READ AS THE END IT WOULD LEAVE A SHORT FILE THAT LOOKS WHOLE.
                // ONLY AN HLS SEGMENT THE DEMUXER GIVES UP ON PASSES WITHOUT AN ERROR, AND LEAVES A HOLE IN BOTH TRACKS
                if (read != avutil.AVERROR_EOF) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_READ) + "Failed to read the media: " + FFTool.error(read) + " (" + read + ")");
                ended[n] = true;
                // THE END OF AN INPUT: ITS DECODERS GIVE THE FRAMES THEY STILL HOLD
                if (n == 0 && this.videoIn != null) this.video(true);
                if (n == sound && this.audioIn != null) this.audio(true);
                continue;
            }
            try {
                final int index = this.packet.stream_index();
                final boolean video = n == 0 && this.videoIn != null && index == this.videoIn.index();
                final boolean audio = !video && n == sound && this.audioIn != null && index == this.audioIn.index();
                if (!video && !audio) continue;
                final AVRational base = inputs[n].streams(index).time_base();
                final long pts = this.packet.pts();
                final long dts = this.packet.dts();
                final long stamp = dts != avutil.AV_NOPTS_VALUE ? dts : pts;
                if (stamp != avutil.AV_NOPTS_VALUE) {
                    final int track = video ? 0 : 1;
                    long us = avutil.av_rescale_q(stamp, base, this.micros) - starts[n];
                    // A FORMAT THAT RESTARTS ITS CLOCK MID-WAY (HLS AFTER A DISCONTINUITY, MPEG-TS) GOES ON WHERE THE TRACK WAS,
                    // AS THE FFMPEG TOOL DOES: A JUMP BACK OVER A SECOND OR AHEAD OVER TEN IS NO TIME THAT PASSED
                    if ((inputs[n].iformat().flags() & avformat.AVFMT_TS_DISCONT) != 0 && expected[track] != Long.MIN_VALUE
                            && (us < expected[track] - JUMP_BACK_US || us > expected[track] + JUMP_AHEAD_US)) {
                        starts[n] += us - expected[track];
                        us = expected[track];
                    }
                    expected[track] = us + avutil.av_rescale_q(Math.max(0, this.packet.duration()), base, this.micros);
                    clocks[n] = us;
                }
                // FROM HERE ON THE PACKET COUNTS FROM ZERO, SO THE DECODERS AND THE MUXER SEE ONE CONTINUOUS TIMELINE
                final long shift = avutil.av_rescale_q(starts[n], this.micros, base);
                if (pts != avutil.AV_NOPTS_VALUE) this.packet.pts(pts - shift);
                if (dts != avutil.AV_NOPTS_VALUE) this.packet.dts(dts - shift);
                if (video) this.video(false);
                else this.audio(false);
            } finally {
                avcodec.av_packet_unref(this.packet);
            }
        }
    }

    // ONE VIDEO PACKET, OR THE END OF ITS INPUT WHEN drain IS SET: COPIED AS IT IS, OR DECODED AND ENCODED AGAIN
    private void video(final boolean drain) throws IOException {
        if (this.videoDecoder == null) {
            if (!drain) this.videoDts = this.copy(this.videoIn, this.videoOut, this.videoDts);
            return;
        }
        final int sent = avcodec.avcodec_send_packet(this.videoDecoder, drain ? null : this.packet);
        final int again = avutil.AVERROR_EAGAIN();
        int received = sent < 0 && sent != avutil.AVERROR_EOF ? sent : 0;
        while (received >= 0) {
            received = avcodec.avcodec_receive_frame(this.videoDecoder, this.frame);
            if (received < 0) break;
            try {
                // TIMESTAMP ON THE OUTPUT CLOCK, OUT OF PACKETS THAT ALREADY COUNT FROM ZERO
                long stamp = this.frame.best_effort_timestamp();
                if (stamp == avutil.AV_NOPTS_VALUE) stamp = this.frame.pts();
                long ticks = stamp == avutil.AV_NOPTS_VALUE ? this.lastTicks + this.frameTicks
                        : avutil.av_rescale_q(stamp, this.videoIn.time_base(), this.videoEncoder.time_base());

                // FRAME RATE LIMIT: THE FIRST FRAME OF EACH OUTPUT SLOT STAYS AND THE REST OF THE SLOT IS DROPPED
                if (this.limitFps > 0) {
                    final long slot = Math.max(0, ticks) * this.limitFps / VIDEO_CLOCK_HZ;
                    if (slot <= this.lastSlot) continue;
                    this.lastSlot = slot;
                }
                // STRICTLY INCREASING EVEN WHEN TWO FRAMES ROUND TO THE SAME TICK
                if (ticks <= this.lastTicks) ticks = this.lastTicks + 1;
                this.lastTicks = ticks;

                // THE DECODED FRAME GOES IN AS IT IS WHEN IT ALREADY HAS THE ENCODER'S LAYOUT AND SIZE
                AVFrame picture = this.frame;
                if (this.frame.format() != this.videoEncoder.pix_fmt() || this.frame.width() != this.videoEncoder.width() || this.frame.height() != this.videoEncoder.height()) {
                    this.convert();
                    picture = this.converted;
                }
                picture.pts(ticks);
                // THE SOURCE'S FRAME TYPE WOULD FORCE THE SAME ONE ON THE ENCODER
                picture.pict_type(avutil.AV_PICTURE_TYPE_NONE);
                this.send(this.videoEncoder, this.videoOut, picture);
            } finally {
                avutil.av_frame_unref(this.frame);
            }
        }
        // A DAMAGED PACKET COSTS ITS FRAMES, NOT THE DOWNLOAD
        if (received != again && received != avutil.AVERROR_EOF && !this.videoWarned) {
            this.videoWarned = true;
            LOGGER.warn(IT, "Video decoder refused a packet ({}); damaged ones are skipped", FFTool.error(received));
        }
    }

    // CONVERTS THE DECODED FRAME INTO converted, REBUILDING THE SCALER WHEN THE STREAM CHANGES ITS SIZE OR LAYOUT MID-WAY
    private void convert() throws IOException {
        final int width = this.frame.width();
        final int height = this.frame.height();
        final int format = this.frame.format();
        if (this.scaler == null || width != this.scalerWidth || height != this.scalerHeight || format != this.scalerFormat) {
            if (this.scaler != null) swscale.sws_freeContext(this.scaler);
            this.scaler = swscale.sws_getContext(width, height, format, this.videoEncoder.width(), this.videoEncoder.height(), this.videoEncoder.pix_fmt(),
                    swscale.SWS_BILINEAR, null, null, (double[]) null);
            if (FFTool.isNull(this.scaler)) {
                this.scaler = null;
                throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_SCALER_CREATE) + "Failed to create the pixel converter");
            }
            this.scalerWidth = width;
            this.scalerHeight = height;
            this.scalerFormat = format;
            // THE SOURCE'S MATRIX AND RANGE ON BOTH SIDES, SO YUV KEEPS ITS LEVELS, WHILE RGB GOES THROUGH BT.709 INTO LIMITED RANGE
            final boolean rgb = (avutil.av_pix_fmt_desc_get(format).flags() & avutil.AV_PIX_FMT_FLAG_RGB) != 0;
            final int space = this.frame.colorspace();
            final IntPointer matrix = swscale.sws_getCoefficients(rgb ? swscale.SWS_CS_ITU709
                    : space == avutil.AVCOL_SPC_UNSPECIFIED ? (height >= 720 ? swscale.SWS_CS_ITU709 : swscale.SWS_CS_ITU601) : space);
            final int range = this.frame.color_range() == avutil.AVCOL_RANGE_JPEG ? 1 : 0;
            swscale.sws_setColorspaceDetails(this.scaler, matrix, range, matrix, rgb ? 0 : range, 0, 1 << 16, 1 << 16);
        }
        final int writable = avutil.av_frame_make_writable(this.converted);
        if (writable < 0) throw FFTool.failure("av_frame_make_writable", writable);
        swscale.sws_scale(this.scaler, this.frame.data(), this.frame.linesize(), 0, height, this.converted.data(), this.converted.linesize());
    }

    // ONE AUDIO PACKET, OR THE END OF ITS INPUT WHEN drain IS SET: COPIED AS IT IS, OR DECODED, RESAMPLED AND ENCODED AGAIN
    private void audio(final boolean drain) throws IOException {
        if (this.audioDecoder == null) {
            if (!drain) this.audioDts = this.copy(this.audioIn, this.audioOut, this.audioDts);
            return;
        }
        final int sent = avcodec.avcodec_send_packet(this.audioDecoder, drain ? null : this.packet);
        final int again = avutil.AVERROR_EAGAIN();
        int received = sent < 0 && sent != avutil.AVERROR_EOF ? sent : 0;
        while (received >= 0) {
            received = avcodec.avcodec_receive_frame(this.audioDecoder, this.frame);
            if (received < 0) break;
            try {
                if (this.frame.nb_samples() <= 0) continue;
                final int channels = this.frame.ch_layout().nb_channels();
                final long stamp = this.frame.best_effort_timestamp();
                final long sample = stamp == avutil.AV_NOPTS_VALUE ? -1 : avutil.av_rescale_q(stamp, this.audioIn.time_base(), this.audioEncoder.time_base());
                if (!this.audioStarted) {
                    // THE FIRST FRAME SAYS WHERE THE SOUND STARTS: THE ENCODER STAMPS BY COUNTING SAMPLES FROM THERE
                    this.audioStarted = true;
                    this.samplesSent = Math.max(0, sample);
                } else if (sample >= 0) {
                    // SOUND THAT COMES BACK LATER THAN ITS COUNT SAYS (A HOLE IN THE SOURCE, A SKIPPED SEGMENT) GETS THE HOLE AS
                    // SILENCE, OR EVERYTHING AFTER IT WOULD PLAY EARLY. TWO FRAMES OF SLACK ABSORB THE ROUNDING OF TIMESTAMPS
                    final long delay = this.resampler == null ? 0 : swresample.swr_get_delay(this.resampler, this.audioEncoder.sample_rate());
                    long hole = sample - this.samplesSent - avutil.av_audio_fifo_size(this.fifo) - delay;
                    if (hole <= this.chunkSamples * 2L) hole = 0;
                    while (hole > 0) {
                        final int piece = (int) Math.min(hole, this.resampled.nb_samples());
                        avutil.av_samples_set_silence(this.resampled.data(), 0, piece, this.audioEncoder.ch_layout().nb_channels(), this.audioEncoder.sample_fmt());
                        if (avutil.av_audio_fifo_write(this.fifo, this.resampled.data(), piece) < piece)
                            throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_QUEUE_SILENCE) + "Failed to queue the silence of a hole in the audio");
                        this.sendChunks(false);
                        hole -= piece;
                    }
                }
                if (this.resampler == null || this.frame.format() != this.resamplerFormat || this.frame.sample_rate() != this.resamplerRate || channels != this.resamplerChannels) {
                    // A STREAM THAT CHANGES ITS LAYOUT MID-WAY GETS A NEW RESAMPLER, AFTER THE OLD ONE GIVES ITS TAIL
                    if (this.resampler != null) {
                        this.resample(null);
                        swresample.swr_free(this.resampler);
                        this.resampler = null;
                    }
                    final AVChannelLayout input = new AVChannelLayout();
                    final SwrContext created = new SwrContext(null);
                    try {
                        // A LAYOUT WITHOUT AN ORDER (RAW PCM) IS READ AS THE DEFAULT ONE OF ITS CHANNEL COUNT
                        avutil.av_channel_layout_default(input, channels);
                        if (this.frame.ch_layout().order() != avutil.AV_CHANNEL_ORDER_UNSPEC) avutil.av_channel_layout_copy(input, this.frame.ch_layout());
                        final int allocated = swresample.swr_alloc_set_opts2(created, this.audioEncoder.ch_layout(), this.audioEncoder.sample_fmt(), this.audioEncoder.sample_rate(),
                                input, this.frame.format(), this.frame.sample_rate(), 0, null);
                        if (allocated < 0) throw FFTool.failure("swr_alloc_set_opts2", allocated);
                        this.resampler = created;
                    } finally {
                        avutil.av_channel_layout_uninit(input);
                    }
                    final int initialized = swresample.swr_init(this.resampler);
                    if (initialized < 0) throw FFTool.failure("swr_init", initialized);
                    this.resamplerFormat = this.frame.format();
                    this.resamplerRate = this.frame.sample_rate();
                    this.resamplerChannels = channels;
                }
                this.resample(this.frame);
                this.sendChunks(false);
            } finally {
                avutil.av_frame_unref(this.frame);
            }
        }
        if (received != again && received != avutil.AVERROR_EOF && !this.audioWarned) {
            this.audioWarned = true;
            LOGGER.warn(IT, "Audio decoder refused a packet ({}); damaged ones are skipped", FFTool.error(received));
        }
    }

    // ==========================================================================
    // MUXING
    // ==========================================================================
    // MUXES THE READ PACKET AS IT IS, UNDER A DECODE ORDER THAT ALWAYS GROWS. RETURNS THE LAST DECODE TIMESTAMP OF THE STREAM,
    // WHICH THE NEXT CALL GETS BACK AS last
    private long copy(final AVStream input, final AVStream output, final long last) throws IOException {
        // A PACKET WITHOUT A DECODE TIME TAKES ITS PRESENTATION ONE
        if (this.packet.dts() == avutil.AV_NOPTS_VALUE) this.packet.dts(this.packet.pts());
        avcodec.av_packet_rescale_ts(this.packet, input.time_base(), output.time_base());
        long order = this.packet.dts();
        if (order == avutil.AV_NOPTS_VALUE || order <= last) {
            order = last == Long.MIN_VALUE ? 0 : last + 1;
            this.packet.dts(order);
        }
        if (this.packet.pts() == avutil.AV_NOPTS_VALUE || this.packet.pts() < order) this.packet.pts(order);
        this.packet.pos(-1);
        this.write(this.packet, output);
        return order;
    }

    // RESAMPLES A DECODED FRAME INTO THE FIFO, OR FLUSHES THE RESAMPLER'S DELAYED TAIL FOR A NULL ONE
    private void resample(final AVFrame input) throws IOException {
        final int count = input == null ? 0 : input.nb_samples();
        final int capacity = swresample.swr_get_out_samples(this.resampler, count);
        if (capacity <= 0) return;
        if (capacity > this.resampled.nb_samples()) {
            avutil.av_frame_free(this.resampled);
            this.resampled = null;
            this.resampled = this.audioFrame(capacity);
        }
        final int converted = swresample.swr_convert(this.resampler, this.resampled.data(), capacity, input == null ? null : input.data(), count);
        if (converted < 0) throw FFTool.failure("swr_convert", converted);
        if (converted > 0 && avutil.av_audio_fifo_write(this.fifo, this.resampled.data(), converted) < converted)
            throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_QUEUE_AUDIO) + "Failed to queue the resampled audio");
    }

    // SENDS EVERY FULL CHUNK THE FIFO HOLDS, AND WITH drain SET THE LAST ONE GOES SHORT OR PADDED WITH SILENCE
    private void sendChunks(final boolean drain) throws IOException {
        while (true) {
            final int queued = avutil.av_audio_fifo_size(this.fifo);
            if (queued == 0 || (queued < this.chunkSamples && !drain)) return;
            final int writable = avutil.av_frame_make_writable(this.chunk);
            if (writable < 0) throw FFTool.failure("av_frame_make_writable", writable);
            final int read = avutil.av_audio_fifo_read(this.fifo, this.chunk.data(), Math.min(queued, this.chunkSamples));
            if (read <= 0) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_READ_AUDIO) + "Failed to read the queued audio");
            int samples = read;
            if (read < this.chunkSamples && !this.shortLast) {
                avutil.av_samples_set_silence(this.chunk.data(), read, this.chunkSamples - read, this.audioEncoder.ch_layout().nb_channels(), this.audioEncoder.sample_fmt());
                samples = this.chunkSamples;
            }
            this.chunk.nb_samples(samples);
            this.chunk.pts(this.samplesSent);
            this.samplesSent += samples;
            this.send(this.audioEncoder, this.audioOut, this.chunk);
            this.chunk.nb_samples(this.chunkSamples);
        }
    }

    // SENDS A FRAME, OR NULL TO FLUSH, AND MUXES EVERY PACKET THE ENCODER HAS READY
    private void send(final AVCodecContext context, final AVStream stream, final AVFrame input) throws IOException {
        final int sent = avcodec.avcodec_send_frame(context, input);
        if (sent < 0 && !(input == null && sent == avutil.AVERROR_EOF)) throw FFTool.failure("avcodec_send_frame", sent);
        final int again = avutil.AVERROR_EAGAIN();
        while (true) {
            final int received = avcodec.avcodec_receive_packet(context, this.encoded);
            if (received == again || received == avutil.AVERROR_EOF) return;
            if (received < 0) throw FFTool.failure("avcodec_receive_packet", received);
            avcodec.av_packet_rescale_ts(this.encoded, context.time_base(), stream.time_base());
            this.write(this.encoded, stream);
        }
    }

    // MUXES A PACKET ALREADY IN ITS STREAM'S TIME BASE (THE MUXER TAKES AND UNREFS IT) AND MOVES THE PROGRESS
    private void write(final AVPacket out, final AVStream stream) throws IOException {
        out.stream_index(stream.index());
        final long stamp = out.pts();
        if (stamp != avutil.AV_NOPTS_VALUE && this.durationUs > 0)
            this.progress = Math.max(this.progress, MathUtil.clamp(avutil.av_rescale_q(stamp, stream.time_base(), this.micros) / (float) this.durationUs, 0f, 1f));
        final int wrote = avformat.av_interleaved_write_frame(this.muxer, out);
        if (wrote < 0) throw FFTool.failure("av_interleaved_write_frame", wrote);
        this.packets++;
        final long now = System.nanoTime();
        if (this.progressed != null && now - this.notifiedNs >= PROGRESS_STEP_NS) {
            this.notifiedNs = now;
            this.progressed.run();
        }
    }

    // FREES THE WHOLE NATIVE PIPELINE AND THE UNFINISHED FILE, CLOSING THE MUXER FILE WHEN run() DID NOT
    private void free() {
        if (this.scaler != null) { swscale.sws_freeContext(this.scaler); this.scaler = null; }
        if (this.converted != null) { avutil.av_frame_free(this.converted); this.converted = null; }
        if (this.videoDecoder != null) { avcodec.avcodec_free_context(this.videoDecoder); this.videoDecoder = null; }
        if (this.videoEncoder != null) { avcodec.avcodec_free_context(this.videoEncoder); this.videoEncoder = null; }
        if (this.resampler != null) { swresample.swr_free(this.resampler); this.resampler = null; }
        if (this.fifo != null) { avutil.av_audio_fifo_free(this.fifo); this.fifo = null; }
        if (this.resampled != null) { avutil.av_frame_free(this.resampled); this.resampled = null; }
        if (this.chunk != null) { avutil.av_frame_free(this.chunk); this.chunk = null; }
        if (this.audioDecoder != null) { avcodec.avcodec_free_context(this.audioDecoder); this.audioDecoder = null; }
        if (this.audioEncoder != null) { avcodec.avcodec_free_context(this.audioEncoder); this.audioEncoder = null; }
        if (this.frame != null) { avutil.av_frame_free(this.frame); this.frame = null; }
        if (this.packet != null) { avcodec.av_packet_free(this.packet); this.packet = null; }
        if (this.encoded != null) { avcodec.av_packet_free(this.encoded); this.encoded = null; }
        // A REFUSED avformat_alloc_output_context2 LEAVES A NULL CONTEXT BEHIND, WHOSE FIELDS CANNOT BE READ
        if (!FFTool.isNull(this.muxer)) {
            final AVIOContext pb = this.muxer.pb();
            if (!FFTool.isNull(pb)) { avformat.avio_close(pb); this.muxer.pb(null); }
            avformat.avformat_free_context(this.muxer);
        }
        this.muxer = null;
        if (this.main != null) { avformat.avformat_close_input(this.main); this.main = null; }
        if (this.side != null) { avformat.avformat_close_input(this.side); this.side = null; }
        if (this.tempFile != null) {
            try { Files.deleteIfExists(this.tempFile); }
            catch (final IOException e) { LOGGER.warn(IT, "Failed to delete the unfinished transcode {}", this.tempFile, e); }
            this.tempFile = null;
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // OPENS AN INPUT WITH THE PLAYERS' TRANSPORT POLICY AND READS ITS STREAMS, WITH abort AS THE FLAG THE INTERRUPT CALLBACK POLLS
    private static AVFormatContext open(final URI uri, final HttpHeaders headers, final IntPointer abort) throws IOException {
        // FILE URIS GO THROUGH Path: THE RAW PATH OF ONE (/C:/x) IS NO WINDOWS PATH
        String url = uri.toString();
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            try { url = Path.of(uri).toString(); }
            catch (final RuntimeException e) { url = uri.getPath(); }
        }
        final AVFormatContext context = avformat.avformat_alloc_context();
        context.interrupt_callback().callback(FFDecoder.INTERRUPT).opaque(abort);
        final AVDictionary options = new AVDictionary();
        try {
            FFDecoder.inputOptions(options, uri, headers);
            // FFMPEG FREES THE CONTEXT WHEN THE OPEN FAILS
            final int opened = avformat.avformat_open_input(context, url, null, options);
            if (opened < 0) throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_OPEN_INPUT) + "Failed to open the media (" + FFTool.error(opened) + "): " + url);
        } finally {
            avutil.av_dict_free(options);
        }
        final int info = avformat.avformat_find_stream_info(context, (PointerPointer<?>) null);
        if (info < 0) {
            avformat.avformat_close_input(context);
            throw new IOException(DataTool.errCode(WaterMedia.ERR_FFTRANSCODER_STREAM_INFO) + "Failed to read the streams of the media (" + FFTool.error(info) + "): " + url);
        }
        return context;
    }

    // THE STREAM OF A TYPE A JOB READS, OR -1: THE LARGEST PICTURE THAT IS NO COVER ART, THE DEFAULT SOUND OR ELSE THE FIRST
    private static int stream(final AVFormatContext context, final int type) {
        int pick = -1;
        long best = -1;
        for (int i = 0; i < context.nb_streams(); i++) {
            final AVStream stream = context.streams(i);
            final AVCodecParameters parameters = stream.codecpar();
            if (parameters.codec_type() != type || (stream.disposition() & avformat.AV_DISPOSITION_ATTACHED_PIC) != 0) continue;
            final long score = type == avutil.AVMEDIA_TYPE_VIDEO ? (long) parameters.width() * parameters.height()
                    : (stream.disposition() & avformat.AV_DISPOSITION_DEFAULT) != 0 ? 1 : 0;
            if (score <= best) continue;
            best = score;
            pick = i;
        }
        return pick;
    }

    // AVERAGE BITS PER SECOND OF EVERY STREAM, READ FROM WINDOWS AT EVEN STEPS OF THE MEDIA; 0 FOR A STREAM IT SAW TOO LITTLE OF
    private static long[] measure(final AVFormatContext context, final AVPacket packet, final long durationUs) {
        final int streams = context.nb_streams();
        final long[] bytes = new long[streams];
        final long[] spans = new long[streams];
        final long[] rates = new long[streams];
        final AVRational micros = avutil.av_make_q(1, 1_000_000);
        final long start = context.start_time() == avutil.AV_NOPTS_VALUE ? 0 : context.start_time();
        // A SHORT MEDIA IS READ ONCE FROM ITS START, AND SO IS ONE THAT REFUSES TO SEEK
        final int windows = durationUs >= MEASURE_WINDOW_US * MEASURE_WINDOWS * 2 ? MEASURE_WINDOWS : 1;
        for (int w = 0; w < windows; w++) {
            if (w > 0 && avformat.av_seek_frame(context, -1, start + durationUs * w / windows, avformat.AVSEEK_FLAG_BACKWARD) < 0) break;
            final long[] first = new long[streams];
            final long[] last = new long[streams];
            final long[] counted = new long[streams];
            Arrays.fill(first, avutil.AV_NOPTS_VALUE);
            long read = 0;
            boolean full = false;
            while (!full && read < MEASURE_WINDOW_BYTES && avformat.av_read_frame(context, packet) >= 0) {
                final int i = packet.stream_index();
                final long stamp = packet.dts() != avutil.AV_NOPTS_VALUE ? packet.dts() : packet.pts();
                if (i < streams && stamp != avutil.AV_NOPTS_VALUE) {
                    final AVRational base = context.streams(i).time_base();
                    final long us = avutil.av_rescale_q(stamp, base, micros);
                    if (first[i] == avutil.AV_NOPTS_VALUE) first[i] = us;
                    last[i] = Math.max(last[i], us + avutil.av_rescale_q(Math.max(0, packet.duration()), base, micros));
                    counted[i] += packet.size();
                    full = last[i] - first[i] >= MEASURE_WINDOW_US;
                }
                read += packet.size();
                avcodec.av_packet_unref(packet);
            }
            for (int i = 0; i < streams; i++) {
                if (first[i] == avutil.AV_NOPTS_VALUE || last[i] <= first[i]) continue;
                bytes[i] += counted[i];
                spans[i] += last[i] - first[i];
            }
        }
        for (int i = 0; i < streams; i++) if (spans[i] >= MEASURE_MIN_US) rates[i] = bytes[i] * 8_000_000L / spans[i];
        return rates;
    }

    // BITRATE TARGET AND EFFORT ON EACH ENCODER'S OWN SCALE, WITH A BOUNDED PEAK THAT KEEPS THE SIZE NEAR ITS ESTIMATE
    private static void tune(final AVCodecContext context, final String name, final Video video) {
        final Pointer options = context.priv_data();
        final Effort effort = video.effort();
        if (video.bitrate() > 0) {
            context.bit_rate(video.bitrate());
            // SVT-AV1 ONLY TAKES A PEAK IN ITS CONSTANT QUALITY MODE AND REFUSES TO OPEN WITH ONE NEXT TO A BITRATE
            if (!name.equals("libsvtav1")) context.rc_max_rate(video.bitrate() * 3 / 2);
            context.rc_buffer_size((int) Math.min(Integer.MAX_VALUE, video.bitrate() * 2));
        }
        if (name.endsWith("_nvenc")) {
            avutil.av_opt_set(options, "preset", effort.pick("p2", "p4", "p6"), 0);
            avutil.av_opt_set(options, "rc", "vbr", 0);
        } else if (name.endsWith("_qsv")) {
            avutil.av_opt_set(options, "preset", effort.pick("veryfast", "medium", "slow"), 0);
        } else if (name.endsWith("_amf")) {
            avutil.av_opt_set(options, "quality", effort.pick("speed", "balanced", "quality"), 0);
        } else if (name.endsWith("_mf")) {
            // WITHOUT IT MEDIA FOUNDATION ENCODES ON THE CPU
            avutil.av_opt_set(options, "hw_encoding", "1", 0);
        } else switch (name) {
            case "libx264", "libx265" -> avutil.av_opt_set(options, "preset", effort.pick("veryfast", "medium", "slow"), 0);
            case "libopenh264" -> avutil.av_opt_set(options, "rc_mode", "bitrate", 0);
            case "libkvazaar" -> avutil.av_opt_set(options, "kvazaar-params", "preset=" + effort.pick("ultrafast", "veryfast", "fast"), 0);
            case "libsvtav1" -> avutil.av_opt_set(options, "preset", effort.pick("10", "8", "5"), 0);
            case "libaom-av1" -> {
                avutil.av_opt_set(options, "cpu-used", effort.pick("8", "6", "4"), 0);
                avutil.av_opt_set(options, "row-mt", "1", 0);
            }
            case "libvpx-vp9" -> {
                avutil.av_opt_set(options, "deadline", "good", 0);
                avutil.av_opt_set(options, "cpu-used", effort.pick("6", "4", "2"), 0);
                avutil.av_opt_set(options, "row-mt", "1", 0);
            }
            case "libvpx" -> {
                avutil.av_opt_set(options, "deadline", "good", 0);
                avutil.av_opt_set(options, "cpu-used", effort.pick("6", "4", "2"), 0);
            }
            // PRORES HAS NO BITRATE TARGET, ITS PROFILE SETS THE SIZE
            case "prores_ks" -> avutil.av_opt_set(options, "profile", effort.pick("lt", "standard", "hq"), 0);
            default -> LOGGER.debug(IT, "No effort mapping for {}; it keeps its own defaults", name);
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** How long an encoder may work on each frame: a slower one gives more quality at the same bitrate. */
    public enum Effort {
        FAST, BALANCED, BEST;

        // THE VALUE OF THIS EFFORT ON AN ENCODER'S OWN SCALE
        String pick(final String fast, final String balanced, final String best) {
            return this == FAST ? fast : this == BEST ? best : balanced;
        }
    }

    /**
     * One transcode.
     *
     * @param input     media that carries the video, and the audio when {@code track} is null
     * @param track     separate audio input, or null
     * @param headers   request headers of both inputs
     * @param container file format of the result
     * @param file      target, replaced when it exists
     * @param video     video of the result, or null for none
     * @param audio     audio of the result, or null for none
     * @param title     title tag of the file, or null
     * @param author    artist tag of the file, or null
     */
    public record Job(URI input, URI track, HttpHeaders headers, Container container, Path file, Video video, Audio audio, String title, String author) {}

    /**
     * Video of a job.
     *
     * @param codec    compression of the result, or null to copy the track as it comes
     * @param bitrate  target bits per second of an encode, 0 for the encoder default
     * @param fps      frame rate limit of an encode, 0 for the source's; a rate above the source's limits nothing
     * @param effort   time the encoder may spend per frame
     * @param hardware whether a GPU encoder is tried before the software ones
     */
    public record Video(VideoCodec codec, long bitrate, int fps, Effort effort, boolean hardware) {}

    /**
     * Audio of a job.
     *
     * @param codec   compression of the result, or null to copy the track as it comes
     * @param bitrate target bits per second of a lossy encode, 0 for the codec default
     */
    public record Audio(AudioCodec codec, long bitrate) {}

    /** What an input holds: its length in milliseconds (0 when unknown, as a live one) and its tracks, null for one it lacks. */
    public record Info(long durationMs, VideoInfo video, AudioInfo audio) {}

    /** Video track of an input; {@code codec} is null for a compression no container here encodes, {@code bitrate} and {@code fps} 0 when unknown. */
    public record VideoInfo(VideoCodec codec, String name, int width, int height, float fps, long bitrate) {}

    /** Audio track of an input; {@code codec} is null for a compression no container here encodes, {@code bitrate} 0 when unknown. */
    public record AudioInfo(AudioCodec codec, String name, int sampleRate, int channels, long bitrate) {}
}
