package org.watermedia.api.codecs.decoders;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB.Callback_Pointer;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.swresample.*;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.SampleType;
import org.watermedia.api.util.Track;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.FFTool;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.watermedia.WaterMedia.LOGGER;

/**
 * FFmpeg demuxer and decoders of one input. A demux thread opens the input, then fills one packet queue per stream; a
 * video and an audio thread decode them into the {@link #videoFrames()} and {@link #audioFrames()} queues a player
 * consumes. Hardware decoders download to RAM, except on the player's Vulkan device, whose frames stay on the GPU and
 * may pass through libplacebo. The audio is the stream an {@link AudioChoice} picks in a separate track or in the main
 * input, and the streams nobody decodes are discarded. The player's clock drives it through a {@link Timeline}; one
 * decoder serves one run.
 */
public final class FFDecoder {
    private static final Marker IT = MarkerManager.getMarker(FFDecoder.class.getSimpleName());
    // PIPELINE THREADS ARE OWNED WORKERS NAMED BY THE PLAYER'S GROUP, FFThread-<PLAYER>-<ROLE>
    private static final ThreadFactory THREADS = ThreadTool.workerFactory("FFThread", Thread.NORM_PRIORITY);
    // ONE CALLBACK FOR EVERY DECODER, READING THE ABORT FLAG ITS opaque POINTS AT: JAVACPP BINDS AT MOST 10 LIVE INSTANCES PER
    // CALLBACK CLASS AND FREES ONE ONLY ON DEALLOCATION, SO A CALLBACK PER DECODER WOULD STOP WORKING AFTER THE TENTH
    private static final Callback_Pointer INTERRUPT = new Callback_Pointer() {
        @Override
        public int call(final Pointer opaque) {
            return new IntPointer(opaque).get();
        }
    };

    /** Seconds a timestamp may jump before it counts as a discontinuity (HLS ad stitching) instead of time passing. */
    public static final double PTS_DISCONTINUITY_SEC = 10.0;

    // AUDIO OUTPUT FORMAT
    private static final int AUDIO_SAMPLES = 2_048;

    // THREADING
    private static final int MAX_DECODE_THREADS = ThreadTool.processors() / 2;

    // QUEUE CAPACITIES
    private static final long VIDEO_PACKET_QUEUE_BYTES = 16L * 1_024 * 1_024;
    private static final long AUDIO_PACKET_QUEUE_BYTES = 8L * 1_024 * 1_024;
    private static final int VIDEO_FRAME_QUEUE_SLOTS = 3;
    private static final int AUDIO_FRAME_QUEUE_SLOTS = 9;

    // HW DECODE HEALTH: FALL BACK TO SOFTWARE WHEN THE GPU PATH MISBEHAVES; SOME DRIVERS (NOTABLY AMD D3D11VA) DECODE FINE
    // BUT STALL ON THE GPU→CPU FRAME TRANSFER, MAKING HW DECODE SLOWER THAN SOFTWARE
    private static final int HW_TRANSFER_FAIL_LIMIT = 30;       // CONSECUTIVE av_hwframe_transfer_data FAILURES
    private static final int HW_TRANSFER_PERF_WINDOW = 120;     // FRAMES PER PERFORMANCE EVALUATION
    private static final double HW_TRANSFER_BUDGET_RATIO = 0.75; // MAX AVG TRANSFER TIME VS FRAME DURATION

    // HARDWARE DEVICE TYPES, TRIED IN THIS ORDER WHEN THE ENGINE'S VULKAN DEVICE DOES NOT TAKE THE STREAM
    private static final int[] VIDEO_HW_CODECS = {
            AV_HWDEVICE_TYPE_CUDA,          // NVIDIA
            AV_HWDEVICE_TYPE_QSV,           // INTEL
            AV_HWDEVICE_TYPE_AMF,           // AMD
            // DEDICATED HW LIBRARY
            AV_HWDEVICE_TYPE_OHCODEC,
            // OS DEDICATED HARDWARE
            AV_HWDEVICE_TYPE_D3D11VA,       // WINDOWS DIRECTX 11
            AV_HWDEVICE_TYPE_D3D12VA,       // WINDOWS DIRECTX 12
            AV_HWDEVICE_TYPE_VIDEOTOOLBOX,  // MACOS
            AV_HWDEVICE_TYPE_VAAPI,         // LINUX VA-API
            AV_HWDEVICE_TYPE_VDPAU,         // LINUX VDPAU
            AV_HWDEVICE_TYPE_DRM,           // LINUX DRM
            AV_HWDEVICE_TYPE_MEDIACODEC,    // ANDROID MEDIACODEC
            // GENERIC GPU PATH, KEPT LAST ON PURPOSE AS THE LAST RESORT
            AV_HWDEVICE_TYPE_VULKAN         // VULKAN
    };

    // LANGUAGES: THE ISO 639-2/B CODES CONTAINERS MAY CARRY, MAPPED TO THE 639-2/T ONES Locale REPORTS
    private static final Map<String, String> BIBLIOGRAPHIC = Map.ofEntries(
            Map.entry("alb", "sqi"), Map.entry("arm", "hye"), Map.entry("baq", "eus"), Map.entry("bur", "mya"),
            Map.entry("chi", "zho"), Map.entry("cze", "ces"), Map.entry("dut", "nld"), Map.entry("fre", "fra"),
            Map.entry("geo", "kat"), Map.entry("ger", "deu"), Map.entry("gre", "ell"), Map.entry("ice", "isl"),
            Map.entry("mac", "mkd"), Map.entry("mao", "mri"), Map.entry("may", "msa"), Map.entry("per", "fas"),
            Map.entry("rum", "ron"), Map.entry("slo", "slk"), Map.entry("tib", "bod"), Map.entry("wel", "cym"));

    // INPUT
    private final String group;
    private final URI uri;
    private final HttpHeaders headers;
    private final boolean videoWanted;
    private final AudioChoice audioChoice; // null: NO AUDIO WANTED
    private final Timeline timeline;
    private final Host host;

    // FFMPEG COMPONENTS
    AVFormatContext formatContext;
    AVFormatContext trackFormatContext;
    private AVCodecContext videoCodecContext;
    private AVCodecContext audioCodecContext;
    private SwrContext swrContext;

    // HW
    private volatile AVBufferRef hwDeviceCtx;
    private int hwPixelFormat = AV_PIX_FMT_NONE;
    // THE DECODER WRITES AV_PIX_FMT_VULKAN FRAMES ON THE ENGINE'S DEVICE OR ITS TWIN: NOTHING COMES DOWN TO RAM, THE ENGINE SAMPLES THEM
    private boolean vulkanFrames;
    private boolean filters; // LIBPLACEBO RUNS ON THAT DEVICE

    // STREAMS
    private volatile int videoStreamIndex = -1;
    private volatile int audioStreamIndex = -1;
    private AVStream videoStream; // HELD SO THE DECODE THREAD NEVER INDEXES THE STREAM ARRAY THE DEMUXER MAY GROW (MPEG-TS ADDS STREAMS)
    private boolean useAudioTrack;
    private double videoTimeBase;
    private double audioTimeBase;
    // EVERY AUDIO STREAM OF THE OPENED INPUTS AND THE ONE DECODING, READ BY THE PLAYER ONCE THE INPUT OPENED
    private volatile List<AudioStream> audioStreams = List.of();
    private volatile AudioStream audioStream;

    // THREADS: THE DECODERS ARE (RE)STARTED BY THE DEMUXER'S SEEK PATH, SO THEIR CREATION AND TEARDOWN SHARE decodeLock: TWO
    // DECODERS ON ONE CODEC CONTEXT CRASH NATIVELY
    private final Object decodeLock = new Object();
    private volatile Thread demuxThread;
    private volatile Thread videoDecodeThread;
    private volatile Thread audioDecodeThread;

    // QUEUES
    private final PacketQueue videoPacketQueue = new PacketQueue(VIDEO_PACKET_QUEUE_BYTES);
    private final PacketQueue audioPacketQueue = new PacketQueue(AUDIO_PACKET_QUEUE_BYTES);
    private final FrameQueue videoFrameQueue = new FrameQueue(VIDEO_FRAME_QUEUE_SLOTS);
    private final FrameQueue audioFrameQueue = new FrameQueue(AUDIO_FRAME_QUEUE_SLOTS);

    // STATE
    private volatile boolean aborted;
    private final IntPointer abortFlag = new IntPointer(new int[1]); // aborted AS INTERRUPT READS IT, FREED WITH THIS DECODER
    private boolean closed;
    private Throwable failure; // WHY THE OPEN FAILED, REPORTED BY THE DEMUX THREAD THAT RECORDED IT
    private volatile float fps;
    private boolean hls;

    // PTS DISCONTINUITY (HLS AD STITCHING): TWITCH AND SIMILAR SERVICES INSERT ADS VIA #EXT-X-DISCONTINUITY AND FFMPEG'S HLS
    // DEMUXER DOES NOT ALWAYS NORMALIZE PTS ACROSS THEM (RAW PTS CAN JUMP BY HOURS), SO EACH STREAM KEEPS AN OFFSET
    private double videoPtsOffset;
    private double lastRawVideoPts = Double.NaN;
    private double audioPtsOffset;
    private double lastRawAudioPts = Double.NaN;

    // AUDIO FORMAT NEGOTIATION
    private boolean audioPassthrough;
    private int audioOutputChannels;
    private int audioOutputSampleRate;
    private int audioOutputAvFormat; // FFMPEG AV_SAMPLE_FMT_* OF THE RESAMPLER OUTPUT

    // AUDIO INPUT PARAMETERS AS NEGOTIATED, COMPARED PER FRAME: CHAINED OGG / ICECAST CAN SWITCH SAMPLE RATE OR LAYOUT
    // MID-STREAM, AND FEEDING THE OLD RESAMPLER PLAYS AUDIO AT THE WRONG SPEED
    private int audioInputSampleRate;
    private int audioInputAvFormat = AV_SAMPLE_FMT_NONE;
    private int audioInputChannels;

    // END PTS OF THE LAST ENQUEUED AUDIO FRAME (DECODE THREAD ONLY): THE PTS OF A RESAMPLER FLUSH AND THE NOPTS FALLBACK
    private double audioNextPtsSec = Double.NaN;

    // ANOMALY LATCHES: EACH REPEATABLE ANOMALY IS LOGGED ONCE PER DECODER
    private boolean placeboWarned;
    private boolean slotWarned;
    private boolean audioUnusableWarned;
    private boolean audioChangeWarned;

    /** Prepares a decoder of {@code uri}, its video when {@code video} and the audio {@code audio} chooses when not null, with its threads named after {@code group}; nothing opens until {@link #start()}. */
    public FFDecoder(final String group, final URI uri, final HttpHeaders headers, final boolean video, final AudioChoice audio,
                     final Timeline timeline, final Host host) {
        this.group = group;
        this.uri = uri;
        this.headers = headers;
        this.videoWanted = video;
        this.audioChoice = audio;
        this.timeline = timeline;
        this.host = host;
    }

    // ==========================================================================
    // PUBLIC API: CONTROL
    // ==========================================================================
    /** Spawns the demux thread, which opens the input, reports it to the host, starts the clock and the decoders. */
    public void start() {
        final Thread demux = this.thread("demux", this::demuxLoop);
        this.demuxThread = demux;
        demux.start();
    }

    /** Cuts blocking native I/O short and interrupts the demuxer; returns at once. */
    public void abort() {
        this.aborted = true;
        this.abortFlag.put(1);
        final Thread demux = this.demuxThread;
        if (demux != null) demux.interrupt();
    }

    /** Aborts the queues and joins every decoder thread, the demuxer first so nothing spawns a decoder behind it. */
    public void stop() {
        this.abort();
        this.videoPacketQueue.abort();
        this.audioPacketQueue.abort();
        this.videoFrameQueue.abort();
        this.audioFrameQueue.abort();
        final boolean wasInterrupted = Thread.interrupted();
        final Thread demux = this.demuxThread;
        if (demux != null) ThreadTool.join(demux);
        this.demuxThread = null;
        this.stopDecodeThreads();
        if (wasInterrupted) Thread.currentThread().interrupt();
    }

    /** Stops the threads and frees the queues and every native context; a second call does nothing. */
    public void close() {
        if (this.closed) return;
        this.closed = true;
        this.stop();
        this.videoFrameQueue.free();
        this.audioFrameQueue.free();
        this.videoPacketQueue.free();
        this.audioPacketQueue.free();
        this.free();
    }

    /** Returns whether {@code thread} is one of this decoder's threads. */
    public boolean owns(final Thread thread) {
        return thread == this.demuxThread || thread == this.videoDecodeThread || thread == this.audioDecodeThread;
    }

    // ==========================================================================
    // PUBLIC API: STATE
    // ==========================================================================
    /** Returns the queue of decoded video frames, read by one consumer thread. */
    public FrameQueue videoFrames() { return this.videoFrameQueue; }
    /** Returns the queue of decoded audio frames, read by one consumer thread. */
    public FrameQueue audioFrames() { return this.audioFrameQueue; }

    /** Returns whether a video stream decodes. */
    public boolean video() { return this.videoStreamIndex >= 0; }
    /** Returns whether an audio stream decodes. */
    public boolean audio() { return this.audioStreamIndex >= 0; }

    /** Returns every audio stream of the inputs this decoder opened, the main input's first; empty without audio wanted, valid from {@code opened}. */
    public List<AudioStream> audioStreams() { return this.audioStreams; }
    /** Returns the audio stream that decodes, or null when none does; valid from {@code opened}. */
    public AudioStream audioStream() { return this.audioStream; }

    /** Returns whether a decode thread is still running. */
    public boolean decoding() {
        final Thread video = this.videoDecodeThread;
        final Thread audio = this.audioDecodeThread;
        return video != null && video.isAlive() || audio != null && audio.isAlive();
    }

    /** Returns whether the demux thread ended on its own, without {@link #stop()}. */
    public boolean demuxDied() {
        final Thread demux = this.demuxThread;
        return demux != null && !demux.isAlive();
    }

    /** Returns how full the packet queues of the decoding streams are, 0 to 100. */
    public int buffered() {
        long sum = 0;
        int parts = 0;
        if (this.videoStreamIndex >= 0) {
            sum += Math.min(100, this.videoPacketQueue.byteSize() * 100 / VIDEO_PACKET_QUEUE_BYTES);
            parts++;
        }
        if (this.audioStreamIndex >= 0) {
            sum += Math.min(100, this.audioPacketQueue.byteSize() * 100 / AUDIO_PACKET_QUEUE_BYTES);
            parts++;
        }
        return parts == 0 ? 0 : (int) (sum / parts);
    }

    /** Returns whether a hardware decoder is in use. */
    public boolean hardware() { return !FFTool.isNull(this.hwDeviceCtx); }
    /** Returns whether the frames stay on the player's Vulkan device. */
    public boolean vulkan() { return this.vulkanFrames; }
    /** Returns whether libplacebo converts the frames on that device. */
    public boolean filters() { return this.vulkanFrames && this.filters; }

    /** Returns the open video decoder, for the format and color tags the player prepares its engine with; valid during {@code opened}. */
    public AVCodecContext videoContext() { return this.videoCodecContext; }

    /** Returns the input the decoder opened. */
    public URI uri() { return this.uri; }
    /** Returns the plausible frame rate of the video stream, or 0 when it reports none. */
    public float fps() { return this.fps; }
    /** Returns whether the input is an HLS playlist. */
    public boolean hls() { return this.hls; }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // MAIN AND SEPARATE AUDIO INPUTS SHARE TRANSPORT AND PROBING POLICY; FFMPEG EXPECTS MICROSECONDS/BYTES WHERE THE CONFIG
    // USES MILLISECONDS/MEBIBYTES
    void applyInputOptions(final AVDictionary options) {
        FFTool.configureTLS(options);
        av_dict_set(options, "headers", this.headers.toRawString(), 0);
        final long analyzeMs = WaterMediaConfig.codecs.ffmpeg.analyzeDuration;
        if (analyzeMs > 0) av_dict_set(options, "analyzeduration", String.valueOf(analyzeMs * 1_000L), 0);
        av_dict_set(options, "probesize", String.valueOf(DataTool.megabytes(WaterMediaConfig.codecs.ffmpeg.probeSize)), 0);
        av_dict_set(options, "buffer_size", "33554432", 0);
        av_dict_set(options, "rtbufsize", "15000000", 0);
        av_dict_set(options, "http_persistent", "1", 0);
        av_dict_set(options, "multiple_requests", "0", 0);
        av_dict_set(options, "reconnect", "1", 0);
        av_dict_set(options, "reconnect_streamed", "1", 0);
        av_dict_set(options, "reconnect_delay_max", "5", 0);
        av_dict_set(options, "timeout", "10000000", 0);
        av_dict_set(options, "rtsp_transport", "tcp", 0);
        av_dict_set(options, "max_delay", "5000000", 0);
    }

    // OPENS THE SEPARATE AUDIO TRACK AND PICKS ITS STREAM (SEE audioIndex); FALSE WHEN IT CANNOT PLAY OR AN ABORT CUT THE OPEN SHORT
    boolean initAudioTrack(final Track track, final int stream, final String language) {
        final URI trackUri = track.uri();
        if (trackUri == null) {
            LOGGER.error(IT, "Audio track {} has no URI", track.name());
            return false;
        }

        final String trackUrl = this.url(trackUri);
        if (trackUrl == null) return false;

        this.trackFormatContext = avformat.avformat_alloc_context();
        this.trackFormatContext.interrupt_callback().callback(INTERRUPT).opaque(this.abortFlag);
        final AVDictionary trackOptions = new AVDictionary();

        try {
            this.applyInputOptions(trackOptions);

            if (avformat.avformat_open_input(this.trackFormatContext, trackUrl, null, trackOptions) < 0) {
                if (!this.aborted) LOGGER.error(IT, "Failed to open audio track input: {}", trackUrl);
                this.trackFormatContext = null;
                return false;
            }
        } finally {
            av_dict_free(trackOptions);
        }

        if (avformat.avformat_find_stream_info(this.trackFormatContext, (PointerPointer<?>) null) < 0) {
            if (!this.aborted) LOGGER.error(IT, "Failed to find stream info for audio track");
            avformat.avformat_close_input(this.trackFormatContext);
            this.trackFormatContext = null;
            return false;
        }

        final int index = audioIndex(this.trackFormatContext, stream, language, track);
        if (index >= 0) {
            this.audioStreamIndex = index;
            this.audioTimeBase = av_q2d(this.trackFormatContext.streams(index).time_base());
            LOGGER.debug(IT, "Audio track opened: {}", trackUrl);
            return true;
        }

        LOGGER.error(IT, "No audio stream found in audio track: {}", trackUrl);
        avformat.avformat_close_input(this.trackFormatContext);
        this.trackFormatContext = null;
        return false;
    }

    // ==========================================================================
    // DEMUX THREAD
    // ==========================================================================
    private void demuxLoop() {
        try {
            if (!this.init()) {
                // AN OPEN CUT SHORT BY stop()/RESTART/RELEASE IS NOT A FAILURE: THE INTERRUPTED
                // LIFECYCLE LANDS ON STOPPED BY ITSELF AND NO EXCEPTION LEAKS INTO THE NEXT RUN
                if (!this.aborted) this.host.failed(this.failure != null ? this.failure : new IOException("Failed to open " + this.uri));
                return;
            }
            this.host.opened(this);
        } catch (final Throwable e) {
            // WHATEVER AN ABORT LEFT HALF DONE IS NOT A FAILURE
            if (this.aborted) return;
            LOGGER.error(IT, "Init failed with exception", e);
            this.host.failed(e);
            return;
        }
        // THE CLOCK FOLLOWS THESE QUEUES FROM HERE ON: A DECODER OPENED BY A QUALITY SWITCH STARTS THEM AT SERIAL 0 AGAIN. THE DECODERS
        // RUN BEFORE THE CLOCK STARTS, UNLESS A SEEK REQUESTED WHILE LOADING STARTS THEM ONCE IT IS SERVED
        this.timeline.serial(this.videoPacketQueue.serial());
        if (!this.timeline.seekPending()) this.ensureDecodeThreads();
        this.timeline.start();

        final AVPacket packet = avcodec.av_packet_alloc();
        final AVPacket trackPacket = this.useAudioTrack ? avcodec.av_packet_alloc() : null;
        long demuxVideoPackets = 0;
        long demuxAudioPackets = 0;
        boolean mainEof = false;
        boolean trackEof = false;

        LOGGER.debug(IT, "Demux loop starting: videoIdx={}, audioIdx={}, useAudioTrack={}, nb_streams={}",
                this.videoStreamIndex, this.audioStreamIndex, this.useAudioTrack,
                this.formatContext != null ? this.formatContext.nb_streams() : -1);

        try {
            while (!Thread.currentThread().isInterrupted()) {
                // SEEK HANDLING
                final Seek seekReq = this.timeline.consumeSeek();
                if (seekReq != null) {
                    final long targetMs = seekReq.targetMs();
                    final boolean precise = seekReq.precise();

                    this.timeline.signalDemuxResumed();

                    if (precise) {
                        this.stopDecodeThreads();
                        this.videoPacketQueue.reset();
                        this.audioPacketQueue.reset();
                        if (this.videoCodecContext != null) avcodec.avcodec_flush_buffers(this.videoCodecContext);
                        if (this.audioCodecContext != null) avcodec.avcodec_flush_buffers(this.audioCodecContext);
                    } else {
                        // reset(), NOT flush(): ONLY reset() CLEARS THE finished FLAG AN EOF LEFT (AND aborted), WITHOUT WHICH THE
                        // DECODE THREADS GET null FROM THE EMPTY FINISHED QUEUE AND EXIT AT ONCE WITH ZERO FRAMES
                        this.videoPacketQueue.reset();
                        this.audioPacketQueue.reset();
                    }

                    this.timeline.serial(this.videoPacketQueue.serial());
                    LOGGER.debug(IT, "Seek serial sync: vQueueSerial={}, aQueueSerial={}", this.videoPacketQueue.serial(), this.audioPacketQueue.serial());
                    mainEof = false;
                    trackEof = false;

                    if (this.timeline.seekPending()) {
                        LOGGER.debug(IT, "Seek to {}ms superseded; skipping", targetMs);
                        this.ensureDecodeThreads();
                        continue;
                    }

                    // A TARGET WITHOUT A DURATION TO CLAMP IT (LIVE, RELOAD) IS SATURATED, NEVER WRAPPED NEGATIVE
                    final long ffTs = Math.min(targetMs, Long.MAX_VALUE / 1_000L) * 1_000L;
                    final int seekFlags = avformat.AVSEEK_FLAG_BACKWARD;
                    boolean reopened = false;

                    int seekResult = avformat.av_seek_frame(this.formatContext, -1, ffTs, seekFlags);
                    if (seekResult < 0) seekResult = avformat.avformat_seek_file(this.formatContext, -1, Long.MIN_VALUE, ffTs, ffTs, seekFlags);
                    if (seekResult < 0) seekResult = avformat.av_seek_frame(this.formatContext, -1, ffTs, 0);
                    if (seekResult < 0) {
                        // REOPEN NEEDS EXCLUSIVE CODEC ACCESS: ON NON-PRECISE SEEKS THE DECODE THREADS STILL RUN AND reopenFormat()
                        // FLUSHES THE CODECS THEY USE (NATIVE RACE), SO THEY STOP AND THE QUEUES ARE MADE REUSABLE (ABORT CLEARED, SERIAL RESYNCED)
                        this.stopDecodeThreads();
                        final boolean reopenOk = this.reopenFormat();
                        this.videoPacketQueue.reset();
                        this.audioPacketQueue.reset();
                        this.timeline.serial(this.videoPacketQueue.serial());
                        if (!reopenOk) {
                            // reopenFormat ALREADY CLOSED THE OLD CONTEXT: WITH NOTHING LEFT TO READ FROM, THE PIPELINE CANNOT RECOVER;
                            // A REOPEN CUT SHORT BY stop() IS THE CALLER'S OWN DOING, NOT A FAILURE
                            if (this.aborted) return;
                            LOGGER.error(IT, "Seek to {}ms failed and the input could not be reopened; stopping pipeline", targetMs);
                            this.host.failed(new IOException("Seek to " + targetMs + "ms failed and the input could not be reopened"));
                            return;
                        }
                        LOGGER.warn(IT, "Seek to {}ms failed; reopened input from beginning", targetMs);
                        reopened = true;
                        seekResult = 0;
                        if (targetMs > 0) {
                            seekResult = avformat.av_seek_frame(this.formatContext, -1, ffTs, 0);
                            if (seekResult < 0) seekResult = avformat.avformat_seek_file(this.formatContext, -1, Long.MIN_VALUE, ffTs, ffTs, 0);
                        }
                    }
                    if (seekResult < 0) {
                        LOGGER.warn(IT, "Seek to {}ms not honored by reopened input; resuming from beginning", targetMs);
                        this.ensureDecodeThreads();
                        continue;
                    }

                    if (this.timeline.seekPending()) {
                        LOGGER.debug(IT, "Seek to {}ms superseded after main seek; skipping drain", targetMs);
                        this.ensureDecodeThreads();
                        continue;
                    }

                    if (this.useAudioTrack && this.trackFormatContext != null) avformat.av_seek_frame(this.trackFormatContext, -1, ffTs, seekFlags);
                    if (precise && !(reopened && targetMs == 0)) this.syncDrain(targetMs, packet, trackPacket);

                    this.ensureDecodeThreads();

                    LOGGER.debug(IT, "Seek completed to {}ms (precise={}, serial={})", targetMs, precise, this.videoPacketQueue.serial());
                    continue;
                }

                // PAUSE HANDLING
                if (this.timeline.holding()) {
                    this.timeline.awaitChange(0);
                    continue;
                }

                // SEPARATE AUDIO TRACK
                if (this.useAudioTrack && !trackEof && this.trackFormatContext != null && trackPacket != null) {
                    final int reads = WaterMediaConfig.codecs.ffmpeg.trackPacketReads;
                    for (int i = 0; i < reads; i++) {
                        final int trackResult = avformat.av_read_frame(this.trackFormatContext, trackPacket);
                        if (trackResult >= 0) {
                            try {
                                if (trackPacket.stream_index() == this.audioStreamIndex) {
                                    if (!this.audioPacketQueue.put(trackPacket)) break;
                                    demuxAudioPackets++;
                                }
                            } finally {
                                avcodec.av_packet_unref(trackPacket);
                            }
                        } else {
                            trackEof = true;
                            LOGGER.debug(IT, "Audio track reached EOF after {} packets", demuxAudioPackets);
                            this.audioPacketQueue.finish();
                            break;
                        }
                    }
                }

                // READ PACKET (MAIN CONTEXT)
                if (!mainEof) {
                    final int result = avformat.av_read_frame(this.formatContext, packet);
                    // A READ AN ABORT CUT SHORT ENDS THIS DECODER, NOT THE INPUT: THE CLOCK, SHARED WITH THE NEXT DECODER, NEVER HEARS IT AS EOF
                    if (result < 0 && this.aborted) break;
                    if (result < 0) {
                        mainEof = true;
                        LOGGER.debug(IT, "Main context EOF after {} video packets", demuxVideoPackets);
                        this.videoPacketQueue.finish();
                        // WITHOUT A SEPARATE TRACK THE AUDIO PACKETS COME FROM THIS CONTEXT TOO, SO THE AUDIO QUEUE FINISHES AND DRAINS CLEANLY
                        if (!this.useAudioTrack) this.audioPacketQueue.finish();
                    }
                }

                // BOTH AT EOF: SIGNAL IT AND WAIT FOR A SEEK. THE PLAYER HANDLES REPEAT AND ENDED ONCE EVERY DECODED FRAME IS
                // RENDERED; SEEKING TO 0 HERE WOULD FLUSH THE UNPROCESSED PACKETS INTO A RAPID EOF→SEEK→EOF LOOP RENDERING NOTHING
                if (mainEof && (trackEof || !this.useAudioTrack)) {
                    LOGGER.debug(IT, "All contexts EOF (video: {}, audio: {})", demuxVideoPackets, demuxAudioPackets);

                    this.timeline.signalDemuxFinished();
                    LOGGER.debug(IT, "Demux finished; the player handles ENDED/repeat after drain");

                    while (!this.timeline.seekPending() && !Thread.currentThread().isInterrupted()) {
                        this.timeline.awaitChange(0);
                    }
                    if (!this.timeline.seekPending()) break;

                    LOGGER.debug(IT, "Seek after EOF; restarting pipeline");
                    mainEof = false;
                    trackEof = false;
                    this.videoPacketQueue.reset();
                    this.audioPacketQueue.reset();
                    continue;
                }

                if (mainEof) continue;

                try {
                    final int streamIndex = packet.stream_index();
                    if (streamIndex == this.videoStreamIndex) {
                        while (!this.videoPacketQueue.tryPut(packet)) {
                            if (Thread.currentThread().isInterrupted() || this.timeline.seekPending()) break;
                            if (this.useAudioTrack && this.trackFormatContext != null && trackPacket != null) {
                                final int sr = avformat.av_read_frame(this.trackFormatContext, trackPacket);
                                if (sr >= 0) {
                                    try {
                                        if (trackPacket.stream_index() == this.audioStreamIndex) {
                                            this.audioPacketQueue.put(trackPacket);
                                            demuxAudioPackets++;
                                        }
                                    } finally {
                                        avcodec.av_packet_unref(trackPacket);
                                    }
                                }
                            }
                            Thread.sleep(1);
                        }
                        demuxVideoPackets++;
                    } else if (!this.useAudioTrack && streamIndex == this.audioStreamIndex) {
                        if (!this.audioPacketQueue.put(packet)) break;
                        demuxAudioPackets++;
                    }
                } finally {
                    avcodec.av_packet_unref(packet);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            LOGGER.debug(IT, "Demux loop exiting (video: {}, audio: {}, interrupted: {})",
                    demuxVideoPackets, demuxAudioPackets, Thread.currentThread().isInterrupted());

            this.videoPacketQueue.finish();
            this.audioPacketQueue.finish();

            avcodec.av_packet_free(packet);
            if (trackPacket != null) avcodec.av_packet_free(trackPacket);
        }
    }

    // ==========================================================================
    // VIDEO DECODE THREAD
    // ==========================================================================
    private void videoDecodeLoop() {
        final AVFrame tempFrame = avutil.av_frame_alloc();
        // FRAMES DECODED ON THE ENGINE'S VULKAN DEVICE ARE QUEUED AS THEY ARE; EVERY OTHER ACCELERATOR'S COME DOWN TO RAM
        final AVFrame hwTransfer = this.hwDeviceCtx != null && !this.vulkanFrames ? avutil.av_frame_alloc() : null;
        // THEY BECOME RGBA THROUGH LIBPLACEBO WHEN THE ENGINE'S DEVICE RUNS IT: ONE GRAPH PER DECODER POOL, REBUILT AFTER A SEEK SO NO FRAME OF
        // THE PREVIOUS SEGMENT LEAVES IT; A GRAPH FFMPEG REFUSES LEAVES THE FRAMES TO THE ENGINE'S OWN CONVERSION UNTIL THE NEXT START
        boolean filtering = this.vulkanFrames && this.filters;
        final AVFrame filtered = filtering ? avutil.av_frame_alloc() : null;
        Placebo placebo = null;
        final int again = avutil.AVERROR_EAGAIN();
        final int[] serialOut = new int[1];
        int lastSerial = -1;
        long packetsProcessed = 0;
        long framesProduced = 0;
        long sendFailures = 0;
        long receiveFailures = 0;

        // HW DECODE HEALTH TRACKING (SEE HW_TRANSFER_* CONSTANTS)
        int hwFailStreak = 0;
        long hwTransferNs = 0;
        int hwTransferCount = 0;
        boolean hwGaveUp = false;

        try {
            while (!Thread.currentThread().isInterrupted()) {
                final AVPacket packet = this.videoPacketQueue.get(serialOut);

                // A null PACKET ENDS THE QUEUE: A CLEAN EOF DRAINS THE DECODER WITH A null FLUSH PACKET, AN abort() FROM stop()/seek()
                // EXITS (THE BACKLOG IS STALE). DELAYED DECODERS (libdav1d, THE NATIVE av1) RELEASE THEIR REORDER WINDOW ONLY ON
                // FLUSH, AND ONE THAT BUFFERS THE WHOLE STREAM WOULD OTHERWISE END PLAYBACK WITH ZERO RENDERED FRAMES
                final boolean eof = packet == null;
                if (eof && (lastSerial < 0 || !this.videoPacketQueue.endOfFile())) break;

                final int packetSerial = eof ? lastSerial : serialOut[0];

                try {
                    if (!eof && packetSerial != lastSerial) {
                        if (lastSerial >= 0) avcodec.avcodec_flush_buffers(this.videoCodecContext);
                        if (placebo != null) placebo.close();
                        placebo = null;
                        lastSerial = packetSerial;
                        this.lastRawVideoPts = Double.NaN;
                        this.videoPtsOffset = 0;
                    }

                    if (!eof && packetSerial != this.videoPacketQueue.serial()) continue;

                    // A null PACKET PUTS THE DECODER INTO DRAIN MODE; A REAL send FAILURE SKIPS ONLY THAT PACKET
                    final int sent = avcodec.avcodec_send_packet(this.videoCodecContext, packet);
                    if (sent < 0 && sent != again && sent != AVERROR_EOF && ++sendFailures == 1)
                        logCodecError("video", "send packet", this.videoCodecContext, this.videoStreamIndex, sent);
                    if (sent < 0 && !eof) continue;

                    while (true) {
                        final int received = avcodec.avcodec_receive_frame(this.videoCodecContext, tempFrame);
                        if (received < 0) {
                            if (received != again && received != AVERROR_EOF && ++receiveFailures == 1)
                                logCodecError("video", "receive frame", this.videoCodecContext, this.videoStreamIndex, received);
                            break;
                        }
                        AVFrame frameToQueue = tempFrame;

                        if (!hwGaveUp && hwTransfer != null && tempFrame.format() == this.hwPixelFormat) {
                            final long transferStart = System.nanoTime();
                            if (av_hwframe_transfer_data(hwTransfer, tempFrame, 0) < 0) {
                                if (hwFailStreak++ == 0) LOGGER.warn(IT, "Failed to transfer frame from GPU");
                                if (hwFailStreak >= HW_TRANSFER_FAIL_LIMIT) {
                                    LOGGER.error(IT, "GPU frame transfer failed {} times in a row; falling back to software decoding", hwFailStreak);
                                    hwGaveUp = this.reinitVideoCodecSoftware();
                                    hwFailStreak = 0;
                                }
                                continue;
                            }
                            hwFailStreak = 0;
                            frameToQueue = hwTransfer;
                            // THE GPU COPY CARRIES PIXELS ONLY, SO THE COLOR TAGS COME FROM THE DECODED FRAME
                            hwTransfer.colorspace(tempFrame.colorspace()).color_range(tempFrame.color_range())
                                    .chroma_location(tempFrame.chroma_location()).color_trc(tempFrame.color_trc())
                                    .color_primaries(tempFrame.color_primaries());

                            // SOME DRIVERS (NOTABLY AMD D3D11VA) DECODE FINE BUT STALL ON THE GPU→CPU COPY: WHEN THE TRANSFER ALONE
                            // EATS THE FRAME BUDGET, SOFTWARE DECODING IS FASTER, SO EVERY WINDOW OF FRAMES IS EVALUATED
                            hwTransferNs += System.nanoTime() - transferStart;
                            if (++hwTransferCount >= HW_TRANSFER_PERF_WINDOW) {
                                final double avgSec = hwTransferNs / 1_000_000_000.0 / hwTransferCount;
                                final double frameSec = this.timeline.frameDurationSec();
                                if (avgSec > frameSec * HW_TRANSFER_BUDGET_RATIO) {
                                    LOGGER.warn(IT, "GPU frame transfer too slow (avg {}ms vs {}ms frame budget); falling back to software decoding",
                                            String.format("%.1f", avgSec * 1_000.0), (long) (frameSec * 1_000.0));
                                    hwGaveUp = this.reinitVideoCodecSoftware();
                                }
                                hwTransferNs = 0;
                                hwTransferCount = 0;
                            }
                        }

                        if (filtering && tempFrame.format() == AV_PIX_FMT_VULKAN && (placebo == null || placebo.pool != tempFrame.hw_frames_ctx().data().address())) {
                            if (placebo != null) placebo.close();
                            placebo = null;
                            try {
                                placebo = Placebo.open(tempFrame, this.hwDeviceCtx, this.videoStream.time_base());
                            } catch (final IllegalStateException e) {
                                if (!this.placeboWarned) LOGGER.warn(IT, "Decoded frames keep the engine's own conversion: libplacebo is unavailable on this device", e);
                                this.placeboWarned = true;
                                filtering = false;
                            }
                        }
                        // A FRAME WITHOUT ANY TIMESTAMP STAYS OUT OF THE GRAPH, WHICH ORDERS FRAMES BY pts; THE ENGINE CONVERTS IT ITSELF
                        final long bestPts = tempFrame.best_effort_timestamp() != avutil.AV_NOPTS_VALUE ? tempFrame.best_effort_timestamp() : tempFrame.pts();
                        if (placebo != null && tempFrame.format() == AV_PIX_FMT_VULKAN && bestPts != avutil.AV_NOPTS_VALUE) {
                            tempFrame.pts(bestPts);
                            final int pushed = placebo.push(tempFrame);
                            int pulled = again;
                            boolean accepted = true;
                            while (pushed >= 0 && accepted && (pulled = placebo.pull(filtered)) >= 0) {
                                // THE GRAPH RETIMES pts IN ITS OWN TIME BASE; THE BEST-EFFORT TIMESTAMP IT COPIES IS STILL THE DECODER'S
                                filtered.best_effort_timestamp(avutil.AV_NOPTS_VALUE);
                                accepted = this.queueVideo(filtered, filtered, packetSerial, placebo.timeBase);
                                if (accepted) framesProduced++;
                            }
                            if (!accepted) break;
                            if (pushed >= 0 && (pulled == again || pulled == AVERROR_EOF)) continue;
                            // A GRAPH THAT FAILS MID-STREAM WOULD SWALLOW EVERY LATER FRAME: IT GOES, AND THIS FRAME (UNTOUCHED WHEN REFUSED) QUEUES AS IT IS
                            if (!this.placeboWarned) LOGGER.warn(IT, "libplacebo failed on a decoded frame ({}); frames keep the engine's own conversion",
                                    FFTool.error(pushed < 0 ? pushed : pulled));
                            this.placeboWarned = true;
                            placebo.close();
                            placebo = null;
                            filtering = false;
                            if (pushed >= 0) continue;
                        }

                        if (!this.queueVideo(frameToQueue, tempFrame, packetSerial, this.videoTimeBase)) break;
                        framesProduced++;
                    }

                    // THE DRAINED DECODER LEAVES THE FRAMES LIBPLACEBO STILL HOLDS TO TIME THEM
                    if (eof && placebo != null && placebo.push(null) >= 0) {
                        while (placebo.pull(filtered) >= 0) {
                            filtered.best_effort_timestamp(avutil.AV_NOPTS_VALUE);
                            if (!this.queueVideo(filtered, filtered, packetSerial, placebo.timeBase)) break;
                            framesProduced++;
                        }
                    }

                    packetsProcessed++;
                } finally {
                    if (packet != null) avcodec.av_packet_free(packet);
                }

                if (eof) break; // DECODER FULLY DRAINED, NOTHING MORE TO PRODUCE
            }
        } finally {
            if (sendFailures > 1 || receiveFailures > 1)
                LOGGER.warn(IT, "Video decoder suppressed repeated errors: send={}, receive={}, stream={}",
                        Math.max(0L, sendFailures - 1), Math.max(0L, receiveFailures - 1), this.videoStreamIndex);
            LOGGER.debug(IT, "Video decode exiting (packets: {}, frames produced: {}, interrupted: {})",
                    packetsProcessed, framesProduced, Thread.currentThread().isInterrupted());
            // RESET THE CODEC FOR THE NEXT RUN: A CLEAN EOF LEAVES IT DRAINED, REJECTING NEW PACKETS, AND A repeat() RESTARTS THIS
            // THREAD WITH lastSerial=-1, WHICH SKIPS THE PER-SEEK FLUSH; UNFLUSHED, THE REPEAT WOULD EMIT ZERO FRAMES, TRIP THE
            // PLAYER'S ZERO-FRAME → ERROR CHECK AND FREEZE THE VIDEO
            if (this.videoCodecContext != null) avcodec.avcodec_flush_buffers(this.videoCodecContext);
            if (placebo != null) placebo.close();
            avutil.av_frame_free(tempFrame);
            if (hwTransfer != null) avutil.av_frame_free(hwTransfer);
            if (filtered != null) avutil.av_frame_free(filtered);
        }
    }

    // QUEUES frame AT THE PRESENTATION TIME OF timing (OFTEN THE SAME FRAME): ITS BEST-EFFORT TIMESTAMP OR pts IN timeBase SECONDS PER TICK,
    // SYNTHESIZED WHEN MISSING AND KEPT CONTINUOUS ACROSS HLS DISCONTINUITIES. MOVES THE FRAME'S REFERENCE; FALSE WHEN THE QUEUE TAKES NO MORE
    private boolean queueVideo(final AVFrame frame, final AVFrame timing, final int serial, final double timeBase) {
        final double frameSec = this.timeline.frameDurationSec();
        // A RAW AV_NOPTS_VALUE WOULD PRODUCE AN ASTRONOMICAL PTS THAT LATCHES A GARBAGE DISCONTINUITY OFFSET AND WRECKS THE CLOCK
        long rawPts = timing.best_effort_timestamp();
        if (rawPts == avutil.AV_NOPTS_VALUE) rawPts = timing.pts();
        final double rawPtsSec;
        if (rawPts == avutil.AV_NOPTS_VALUE) rawPtsSec = Double.isNaN(this.lastRawVideoPts) ? 0.0 : this.lastRawVideoPts + frameSec;
        else rawPtsSec = rawPts * timeBase;

        // PTS DISCONTINUITY DETECTION (HLS AD STITCHING)
        if (!Double.isNaN(this.lastRawVideoPts)) {
            final double jump = rawPtsSec - this.lastRawVideoPts;
            if (Math.abs(jump) > PTS_DISCONTINUITY_SEC) {
                final double expected = this.lastRawVideoPts + this.videoPtsOffset + frameSec;
                this.videoPtsOffset = expected - rawPtsSec;
                LOGGER.debug(IT, "Video PTS discontinuity: jump={}s, new offset={}s", String.format("%.1f", jump), String.format("%.1f", this.videoPtsOffset));
            }
        }
        this.lastRawVideoPts = rawPtsSec;

        final FrameQueue.Slot slot = this.videoFrameQueue.peekWritable();
        if (slot == null) return false;

        av_frame_unref(slot.frame);
        av_frame_move_ref(slot.frame, frame);
        slot.ptsMs = (long) ((rawPtsSec + this.videoPtsOffset) * 1_000.0);
        slot.durationMs = (long) (frameSec * 1_000.0);
        slot.serial = serial;
        slot.width = slot.frame.width();
        slot.height = slot.frame.height();
        slot.format = slot.frame.format();
        this.videoFrameQueue.push();
        return true;
    }

    // ==========================================================================
    // AUDIO DECODE THREAD
    // ==========================================================================
    private void audioDecodeLoop() {
        final AVFrame tempFrame = avutil.av_frame_alloc();
        final int again = avutil.AVERROR_EAGAIN();
        final int[] serialOut = new int[1];
        int lastSerial = -1;
        long packetsProcessed = 0;
        long framesProduced = 0;
        long sendFailures = 0;
        long receiveFailures = 0;

        try {
            while (!Thread.currentThread().isInterrupted()) {
                final AVPacket packet = this.audioPacketQueue.get(serialOut);

                // SEE videoDecodeLoop: A null PACKET IS A CLEAN EOF (FLUSH THE DECODER'S BUFFERED BACKLOG) OR AN abort() TEARDOWN (DROP IT)
                final boolean eof = packet == null;
                if (eof && (lastSerial < 0 || !this.audioPacketQueue.endOfFile())) break;

                final int packetSerial = eof ? lastSerial : serialOut[0];

                try {
                    if (!eof && packetSerial != lastSerial) {
                        if (lastSerial >= 0) {
                            avcodec.avcodec_flush_buffers(this.audioCodecContext);
                            // DROP SAMPLES BUFFERED INSIDE THE RESAMPLER: THEY BELONG TO THE PRE-SEEK POSITION AND WOULD LEAK INTO POST-SEEK AUDIO
                            if (!this.audioPassthrough && this.swrContext != null) {
                                swresample.swr_close(this.swrContext);
                                swresample.swr_init(this.swrContext);
                            }
                        }
                        lastSerial = packetSerial;
                        this.lastRawAudioPts = Double.NaN;
                        this.audioPtsOffset = 0;
                        this.audioNextPtsSec = Double.NaN;
                    }

                    if (!eof && packetSerial != this.audioPacketQueue.serial()) continue;

                    // A null PACKET PUTS THE DECODER INTO DRAIN MODE; A REAL send FAILURE SKIPS ONLY THAT PACKET
                    final int sent = avcodec.avcodec_send_packet(this.audioCodecContext, packet);
                    if (sent < 0 && sent != again && sent != AVERROR_EOF && ++sendFailures == 1)
                        logCodecError("audio", "send packet", this.audioCodecContext, this.audioStreamIndex, sent);
                    if (sent < 0 && !eof) continue;

                    while (true) {
                        final int received = avcodec.avcodec_receive_frame(this.audioCodecContext, tempFrame);
                        if (received < 0) {
                            if (received != again && received != AVERROR_EOF && ++receiveFailures == 1)
                                logCodecError("audio", "receive frame", this.audioCodecContext, this.audioStreamIndex, received);
                            break;
                        }
                        // MID-STREAM PARAMETER CHANGES (CHAINED OGG / ICECAST) NEED A NEW RESAMPLER: FEEDING THE OLD ONE PLAYS AUDIO AT THE WRONG SPEED
                        if (!this.ensureAudioInputConfig(tempFrame)) continue;

                        // PREFER BEST-EFFORT TIMESTAMPS; SYNTHESIZE CONTINUITY WHEN MISSING
                        // (THE OGG DEMUXER IS A COMMON SOURCE OF AV_NOPTS_VALUE PACKETS)
                        long rawPts = tempFrame.best_effort_timestamp();
                        if (rawPts == avutil.AV_NOPTS_VALUE) rawPts = tempFrame.pts();
                        final double rawPtsSec;
                        if (rawPts == avutil.AV_NOPTS_VALUE) rawPtsSec = Double.isNaN(this.audioNextPtsSec) ? 0.0 : this.audioNextPtsSec - this.audioPtsOffset;
                        else rawPtsSec = rawPts * this.audioTimeBase;

                        // PTS DISCONTINUITY DETECTION (HLS AD STITCHING)
                        if (!Double.isNaN(this.lastRawAudioPts)) {
                            final double jump = rawPtsSec - this.lastRawAudioPts;
                            if (Math.abs(jump) > PTS_DISCONTINUITY_SEC) {
                                final double audioDur = (double) tempFrame.nb_samples() / tempFrame.sample_rate();
                                final double expected = this.lastRawAudioPts + this.audioPtsOffset + audioDur;
                                this.audioPtsOffset = expected - rawPtsSec;
                                LOGGER.debug(IT, "Audio PTS discontinuity: jump={}s, new offset={}s", String.format("%.1f", jump), String.format("%.1f", this.audioPtsOffset));
                            }
                        }
                        this.lastRawAudioPts = rawPtsSec;

                        final double ptsSec = rawPtsSec + this.audioPtsOffset;

                        if (!this.enqueueResampledAudio(tempFrame, ptsSec, packetSerial)) break;
                        framesProduced++;

                        if (!this.audioPassthrough) {
                            final long delay = swresample.swr_get_delay(this.swrContext, this.audioOutputSampleRate);
                            if (delay > AUDIO_SAMPLES / 2) {
                                if (!this.enqueueResamplerFlush(packetSerial)) break;
                                framesProduced++;
                            }
                        }
                    }

                    packetsProcessed++;
                } finally {
                    if (packet != null) avcodec.av_packet_free(packet);
                }

                if (eof) break; // DECODER FULLY DRAINED, NOTHING MORE TO PRODUCE
            }
        } finally {
            if (sendFailures > 1 || receiveFailures > 1)
                LOGGER.warn(IT, "Audio decoder suppressed repeated errors: send={}, receive={}, stream={}",
                        Math.max(0L, sendFailures - 1), Math.max(0L, receiveFailures - 1), this.audioStreamIndex);
            LOGGER.debug(IT, "Audio decode exiting (packets: {}, frames produced: {}, interrupted: {})",
                    packetsProcessed, framesProduced, Thread.currentThread().isInterrupted());
            // RESET THE CODEC FOR THE NEXT RUN, AS THE VIDEO DECODER DOES: A repeat() RESTARTS THIS THREAD WITH lastSerial=-1,
            // SKIPPING THE PER-SEEK FLUSH, AND A DECODER DRAINED AT EOF WOULD REJECT NEW PACKETS. AUDIO RARELY BUFFERS, BUT IT
            // KEEPS THE INVARIANT
            if (this.audioCodecContext != null) avcodec.avcodec_flush_buffers(this.audioCodecContext);
            avutil.av_frame_free(tempFrame);
        }
    }

    private boolean enqueueResampledAudio(final AVFrame srcFrame, final double ptsSec, final int serial) {
        final FrameQueue.Slot slot = this.audioFrameQueue.peekWritable();
        if (slot == null) return false;

        av_frame_unref(slot.frame);

        if (this.audioPassthrough) {
            // AN EMPTY FRAME HAS NOTHING TO PLAY, LIKE AN EMPTY RESAMPLE BELOW; THE ENGINE REFUSES EMPTY CHUNKS
            if (srcFrame.nb_samples() <= 0) return true;
            av_frame_ref(slot.frame, srcFrame);
            final double durationSec = (double) srcFrame.nb_samples() / srcFrame.sample_rate();
            slot.ptsMs = (long) (ptsSec * 1_000.0);
            slot.durationMs = (long) (durationSec * 1_000.0);
            slot.serial = serial;
            this.audioNextPtsSec = ptsSec + durationSec;
            this.audioFrameQueue.push();
            return true;
        }

        slot.frame.format(this.audioOutputAvFormat);
        slot.frame.ch_layout().nb_channels(this.audioOutputChannels);
        engineLayout(slot.frame.ch_layout(), this.audioOutputChannels);
        slot.frame.sample_rate(this.audioOutputSampleRate);
        slot.frame.nb_samples(AUDIO_SAMPLES);

        if (av_frame_get_buffer(slot.frame, 0) < 0) {
            if (!this.slotWarned) LOGGER.warn(IT, "Failed to allocate audio buffer in slot");
            this.slotWarned = true;
            return true;
        }

        // SAMPLES BUFFERED INSIDE THE RESAMPLER COME OUT FIRST, SO THE OUTPUT
        // STARTS EARLIER THAN THE INPUT PTS BY THE BUFFERED DELAY
        final long delaySamples = swresample.swr_get_delay(this.swrContext, this.audioOutputSampleRate);
        final double outPtsSec = ptsSec - (delaySamples > 0 ? (double) delaySamples / this.audioOutputSampleRate : 0.0);

        final int samplesConverted = swresample.swr_convert(
                this.swrContext,
                slot.frame.data(), slot.frame.nb_samples(),
                srcFrame.data(), srcFrame.nb_samples()
        );

        if (samplesConverted <= 0) {
            av_frame_unref(slot.frame);
            return true;
        }

        final double durationSec = (double) samplesConverted / this.audioOutputSampleRate;
        slot.frame.nb_samples(samplesConverted);
        slot.ptsMs = (long) (outPtsSec * 1_000.0);
        slot.durationMs = (long) (durationSec * 1_000.0);
        slot.serial = serial;
        this.audioNextPtsSec = outPtsSec + durationSec;

        this.audioFrameQueue.push();
        return true;
    }

    private boolean enqueueResamplerFlush(final int serial) {
        final FrameQueue.Slot slot = this.audioFrameQueue.peekWritable();
        if (slot == null) return false;

        av_frame_unref(slot.frame);
        slot.frame.format(this.audioOutputAvFormat);
        slot.frame.ch_layout().nb_channels(this.audioOutputChannels);
        engineLayout(slot.frame.ch_layout(), this.audioOutputChannels);
        slot.frame.sample_rate(this.audioOutputSampleRate);
        slot.frame.nb_samples(AUDIO_SAMPLES);

        if (av_frame_get_buffer(slot.frame, 0) < 0) return true;

        final int flushed = swresample.swr_convert(
                this.swrContext,
                slot.frame.data(), slot.frame.nb_samples(),
                null, 0
        );

        if (flushed <= 0) {
            av_frame_unref(slot.frame);
            return true;
        }

        // THE FLUSHED SAMPLES CONTINUE THE LAST ENQUEUED FRAME: STAMPING THEM WITH THE CONSUMER CLOCK (WHICH LAGS DECODE BY THE
        // QUEUE DEPTH) WOULD YANK THE CLOCK BACKWARD ON UPLOAD AND STALL PLAYBACK
        final double ptsSec = Double.isNaN(this.audioNextPtsSec) ? 0.0 : this.audioNextPtsSec;
        final double durationSec = (double) flushed / this.audioOutputSampleRate;
        slot.frame.nb_samples(flushed);
        slot.ptsMs = (long) (ptsSec * 1_000.0);
        slot.durationMs = (long) (durationSec * 1_000.0);
        slot.serial = serial;
        this.audioNextPtsSec = ptsSec + durationSec;

        this.audioFrameQueue.push();
        return true;
    }

    // CHECKS THE DECODED FRAME AGAINST THE NEGOTIATED INPUT, REBUILDING THE RESAMPLER ON A MID-STREAM CHANGE; THE OUTPUT
    // (ENGINE FORMAT) NEVER CHANGES, SO A RATE CHANGE BECOMES REAL RESAMPLING. FALSE WHEN THE FRAME CANNOT PLAY (CALLER SKIPS IT)
    private boolean ensureAudioInputConfig(final AVFrame frame) {
        final int rate = frame.sample_rate();
        final int format = frame.format();
        final int channels = frame.ch_layout().nb_channels();
        if (rate == this.audioInputSampleRate && format == this.audioInputAvFormat && channels == this.audioInputChannels) return true;
        if (rate <= 0 || channels <= 0 || format == AV_SAMPLE_FMT_NONE) {
            if (!this.audioUnusableWarned) LOGGER.warn(IT, "Audio frame with unusable parameters: {}ch {}Hz fmt={}", channels, rate, format);
            this.audioUnusableWarned = true;
            return false;
        }

        if (!this.audioChangeWarned)
            LOGGER.warn(IT, "Audio parameters changed mid-stream: {}ch {}Hz fmt={} -> {}ch {}Hz fmt={}; rebuilding resampler",
                    this.audioInputChannels, this.audioInputSampleRate, this.audioInputAvFormat, channels, rate, format);
        this.audioChangeWarned = true;

        final SwrContext fresh = swresample.swr_alloc();
        if (fresh == null) return false;

        final AVChannelLayout inputLayout = new AVChannelLayout();
        final AVChannelLayout outputLayout = new AVChannelLayout();
        try {
            avutil.av_channel_layout_copy(inputLayout, frame.ch_layout());
            engineLayout(outputLayout, this.audioOutputChannels);

            avutil.av_opt_set_chlayout(fresh, "in_chlayout", inputLayout, 0);
            avutil.av_opt_set_int(fresh, "in_sample_rate", rate, 0);
            avutil.av_opt_set_sample_fmt(fresh, "in_sample_fmt", format, 0);

            avutil.av_opt_set_chlayout(fresh, "out_chlayout", outputLayout, 0);
            avutil.av_opt_set_int(fresh, "out_sample_rate", this.audioOutputSampleRate, 0);
            avutil.av_opt_set_sample_fmt(fresh, "out_sample_fmt", this.audioOutputAvFormat, 0);

            if (swresample.swr_init(fresh) < 0) {
                LOGGER.error(IT, "Failed to initialize resampler for new audio parameters");
                swresample.swr_free(fresh);
                return false;
            }
        } finally {
            av_channel_layout_uninit(inputLayout);
            av_channel_layout_uninit(outputLayout);
        }

        if (this.swrContext != null) swresample.swr_free(this.swrContext);
        this.swrContext = fresh;
        this.audioPassthrough = false;
        this.audioInputSampleRate = rate;
        this.audioInputAvFormat = format;
        this.audioInputChannels = channels;
        return true;
    }

    // ==========================================================================
    // PIPELINE THREADS
    // ==========================================================================
    // AN UNSTARTED PIPELINE THREAD OF THE PLAYER'S GROUP, SO A CALLER CAN PUBLISH IT IN ITS FIELD BEFORE IT RUNS
    private Thread thread(final String role, final Runnable task) {
        final Thread thread = THREADS.newThread(task);
        thread.setName(this.group + "-" + role);
        return thread;
    }

    private void stopDecodeThreads() {
        this.videoPacketQueue.abort();
        this.audioPacketQueue.abort();
        final boolean wasInterrupted = Thread.interrupted();
        synchronized (this.decodeLock) {
            if (this.videoDecodeThread != null) {
                this.videoDecodeThread.interrupt();
                ThreadTool.join(this.videoDecodeThread);
            }
            if (this.audioDecodeThread != null) {
                this.audioDecodeThread.interrupt();
                ThreadTool.join(this.audioDecodeThread);
            }
            this.videoDecodeThread = null;
            this.audioDecodeThread = null;
        }
        if (wasInterrupted) Thread.currentThread().interrupt();
    }

    private void ensureDecodeThreads() {
        synchronized (this.decodeLock) {
            if ((this.videoDecodeThread == null || !this.videoDecodeThread.isAlive()) && this.videoStreamIndex >= 0 && this.videoCodecContext != null) {
                this.videoDecodeThread = this.thread("video", this::videoDecodeLoop);
                this.videoDecodeThread.start();
            }
            if ((this.audioDecodeThread == null || !this.audioDecodeThread.isAlive()) && this.audioStreamIndex >= 0 && this.audioCodecContext != null) {
                this.audioDecodeThread = this.thread("audio", this::audioDecodeLoop);
                this.audioDecodeThread.start();
            }
        }
    }

    // DECODES ON THE DEMUX THREAD UP TO THE PRECISE SEEK TARGET, WITH THE DECODE THREADS STOPPED
    private void syncDrain(final long targetMs, final AVPacket packet, final AVPacket trackPacket) {
        final AVFrame drainFrame = avutil.av_frame_alloc();
        final long threshold = targetMs - (long) (this.timeline.frameDurationSec() * 1_000.0);
        int maxDrain = 500;
        boolean videoReached = this.videoCodecContext == null;
        boolean audioReached = this.audioCodecContext == null;

        while (maxDrain-- > 0 && !Thread.currentThread().isInterrupted()
                && !this.timeline.seekPending() && (!videoReached || !audioReached)) {

            if (this.useAudioTrack) {
                if (!videoReached)
                    videoReached = this.drainRead(this.formatContext, packet, this.videoStreamIndex, this.videoCodecContext, drainFrame,
                            this.videoTimeBase, threshold);
                if (!audioReached && trackPacket != null && this.trackFormatContext != null)
                    audioReached = this.drainRead(this.trackFormatContext, trackPacket, this.audioStreamIndex, this.audioCodecContext, drainFrame,
                            this.audioTimeBase, threshold);
            } else {
                final int readRes = avformat.av_read_frame(this.formatContext, packet);
                if (readRes < 0) break;
                try {
                    final int idx = packet.stream_index();
                    if (idx == this.videoStreamIndex) {
                        if (!videoReached) {
                            videoReached = this.drainDecode(this.videoCodecContext, packet, drainFrame,
                                    this.videoTimeBase, threshold);
                        } else {
                            // THRESHOLD ALREADY MET: STILL FEED THE CODEC TO KEEP THE DPB INTACT
                            avcodec.avcodec_send_packet(this.videoCodecContext, packet);
                            while (avcodec.avcodec_receive_frame(this.videoCodecContext, drainFrame) >= 0) { }
                        }
                    } else if (idx == this.audioStreamIndex) {
                        if (!audioReached) {
                            audioReached = this.drainDecode(this.audioCodecContext, packet, drainFrame,
                                    this.audioTimeBase, threshold);
                        } else {
                            avcodec.avcodec_send_packet(this.audioCodecContext, packet);
                            while (avcodec.avcodec_receive_frame(this.audioCodecContext, drainFrame) >= 0) { }
                        }
                    }
                } finally {
                    avcodec.av_packet_unref(packet);
                }
            }
        }
        avutil.av_frame_free(drainFrame);
    }

    private boolean drainRead(final AVFormatContext ctx, final AVPacket pkt,
                              final int streamIndex, final AVCodecContext codec,
                              final AVFrame frame, final double timeBase, final long threshold) {
        final int res = avformat.av_read_frame(ctx, pkt);
        if (res < 0) return false;
        try {
            if (pkt.stream_index() == streamIndex) return this.drainDecode(codec, pkt, frame, timeBase, threshold);
        } finally {
            avcodec.av_packet_unref(pkt);
        }
        return false;
    }

    private boolean drainDecode(final AVCodecContext codec, final AVPacket pkt,
                                final AVFrame frame, final double timeBase, final long threshold) {
        avcodec.avcodec_send_packet(codec, pkt);
        while (avcodec.avcodec_receive_frame(codec, frame) >= 0) {
            if ((long) (frame.pts() * timeBase * 1_000) >= threshold) return true;
        }
        return false;
    }

    // ==========================================================================
    // INPUT
    // ==========================================================================
    // WHAT FFMPEG OPENS FOR uri: THE PLAYER'S CACHED COPY, A LOCAL PATH OR THE URI ITSELF; NULL WHEN AN ABORT CUT THE CACHE WAIT
    // SHORT, SO THE CALLER NEVER OPENS THE ABANDONED SOURCE
    private String url(final URI uri) {
        try {
            final Path cached = this.host.cached(uri);
            if (cached != null) return cached.toAbsolutePath().toString();
        } catch (final IOException e) {
            if (this.aborted) return null;
            LOGGER.debug(IT, "Cache bypass for {}", uri, e);
        }
        // FILE URIS GO THROUGH Path: STRIPPING THE LEADING SLASH WORKS ONLY FOR WINDOWS DRIVE PATHS (/C:/x) AND BREAKS UNIX
        // ABSOLUTE PATHS (/home/x)
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            try {
                return Path.of(uri).toString();
            } catch (final Exception e) {
                return uri.getPath();
            }
        }
        return uri.toString();
    }

    // OPENS THE ALREADY-ALLOCATED MAIN INPUT; FFMPEG FREES THE CONTEXT WHEN OPENING FAILS, SO ITS JAVA REFERENCE IS CLEARED
    // AND CLEANUP AND READS NEVER TOUCH FREED MEMORY
    private boolean openInput(final String url) {
        final AVDictionary options = new AVDictionary();
        try {
            this.applyInputOptions(options);

            final int ret = avformat.avformat_open_input(this.formatContext, url, null, options);
            if (ret < 0) {
                this.formatContext = null;
                // AN OPEN CUT SHORT BY THE INTERRUPT CALLBACK IS THE CALLER'S OWN stop(), NOT A FAILURE
                if (this.aborted) {
                    LOGGER.debug(IT, "Open aborted by stop/restart: {}", url);
                    return false;
                }
                final String reason = FFTool.error(ret);
                LOGGER.error(IT, "Failed to open input ({}): {}", reason, url);
                this.failure = new IOException("Failed to open input (" + reason + "): " + url);
                return false;
            }
            return true;
        } finally {
            av_dict_free(options);
        }
    }

    private boolean reopenFormat() {
        final String url = this.url(this.uri);
        if (url == null) return false;

        if (this.formatContext != null) avformat.avformat_close_input(this.formatContext);

        this.formatContext = avformat.avformat_alloc_context();
        this.formatContext.interrupt_callback().callback(INTERRUPT).opaque(this.abortFlag);

        if (!this.openInput(url)) return false;

        if (avformat.avformat_find_stream_info(this.formatContext, (PointerPointer<?>) null) < 0) {
            if (!this.aborted) LOGGER.error(IT, "reopenFormat: failed to find stream info");
            return false;
        }

        // FFMPEG MARKS AN UNKNOWN DURATION (LIVE) AS AV_NOPTS_VALUE
        final long duration = this.formatContext.duration();
        this.host.duration(duration == AV_NOPTS_VALUE || duration < 0 ? 0 : duration / 1_000);

        // RE-RESOLVE STREAM INDICES: A REOPENED INPUT MAY EXPOSE STREAMS IN A DIFFERENT ORDER (COMMON WITH HLS), AND THE CODEC
        // CONTEXTS ARE KEPT, SO THE NEW STREAMS MUST MATCH THE CODECS THEY WERE OPENED FOR
        int newVideoIndex = -1;
        for (int i = 0; i < this.formatContext.nb_streams() && newVideoIndex < 0; i++) {
            if (this.formatContext.streams(i).codecpar().codec_type() == avutil.AVMEDIA_TYPE_VIDEO) newVideoIndex = i;
        }
        if (this.videoCodecContext != null) {
            if (newVideoIndex < 0 || this.formatContext.streams(newVideoIndex).codecpar().codec_id() != this.videoCodecContext.codec_id()) {
                LOGGER.error(IT, "reopenFormat: video stream missing or codec mismatch (index={})", newVideoIndex);
                return false;
            }
            this.videoStreamIndex = newVideoIndex;
            this.videoStream = this.formatContext.streams(newVideoIndex);
            this.videoTimeBase = av_q2d(this.videoStream.time_base());
        }
        if (!this.useAudioTrack && this.audioCodecContext != null) {
            // THE AUDIO THAT PLAYED: ITS INDEX WHILE IT KEEPS ITS LANGUAGE, ELSE THAT LANGUAGE, THE DEFAULT OR THE FIRST STREAM
            final AudioStream played = this.audioStream;
            final int newAudioIndex = audioIndex(this.formatContext, played == null ? -1 : played.stream(), played == null ? null : played.lang(), null);
            if (newAudioIndex < 0 || this.formatContext.streams(newAudioIndex).codecpar().codec_id() != this.audioCodecContext.codec_id()) {
                LOGGER.error(IT, "reopenFormat: audio stream missing or codec mismatch (index={})", newAudioIndex);
                return false;
            }
            final AVStream stream = this.formatContext.streams(newAudioIndex);
            this.audioStreamIndex = newAudioIndex;
            this.audioTimeBase = av_q2d(stream.time_base());
            this.audioStream = new AudioStream(AudioChoice.MAIN, newAudioIndex, tag(stream, "title", tag(stream, "comment", null)), tag(stream, "language", null));
        }
        discard(this.formatContext, this.videoStreamIndex, this.useAudioTrack ? -1 : this.audioStreamIndex);

        if (this.videoCodecContext != null) avcodec.avcodec_flush_buffers(this.videoCodecContext);
        if (this.audioCodecContext != null) avcodec.avcodec_flush_buffers(this.audioCodecContext);

        return true;
    }

    // ==========================================================================
    // INIT
    // ==========================================================================
    // OPENS THE INPUT, PICKS THE STREAMS AND OPENS THEIR DECODERS; FALSE WHEN NOTHING CAN PLAY (failure SAYS WHY) OR AN ABORT CUT IT SHORT
    private boolean init() {
        try {
            final String url = this.url(this.uri);
            if (url == null) return false;

            this.formatContext = avformat.avformat_alloc_context();
            this.formatContext.interrupt_callback().callback(INTERRUPT).opaque(this.abortFlag);

            LOGGER.debug(IT, "Opening {}", this.uri);
            if (!this.openInput(url)) return false;

            if (avformat.avformat_find_stream_info(this.formatContext, (PointerPointer<?>) null) < 0) {
                if (this.aborted) {
                    LOGGER.debug(IT, "Stream probe aborted by stop/restart: {}", url);
                    return false;
                }
                LOGGER.error(IT, "Failed to find stream info");
                this.failure = new IOException("Failed to find stream info: " + url);
                return false;
            }

            // FFMPEG MARKS AN UNKNOWN DURATION (LIVE) AS AV_NOPTS_VALUE
            final long duration = this.formatContext.duration();
            this.host.duration(duration == AV_NOPTS_VALUE || duration < 0 ? 0 : duration / 1_000);

            final String fmtName = this.formatContext.iformat() != null ? this.formatContext.iformat().name().getString() : "";
            this.hls = url.contains(".m3u8") || fmtName.contains("hls");

            for (int i = 0; i < this.formatContext.nb_streams() && this.videoWanted && this.videoStreamIndex < 0; i++) {
                final AVStream stream = this.formatContext.streams(i);
                if (stream.codecpar().codec_type() != avutil.AVMEDIA_TYPE_VIDEO) continue;
                this.videoStreamIndex = i;
                this.videoStream = stream;
                this.videoTimeBase = av_q2d(stream.time_base());
            }

            // AUDIO: THE PICKED ORIGIN, ELSE THE SEPARATE TRACK OF THE PREFERRED LANGUAGE, ELSE THE FIRST ONE (THE PLATFORM'S
            // ORIGINAL), ELSE THE MAIN INPUT. A TRACK THAT FAILS TO OPEN FALLS BACK TO THE MAIN INPUT'S OWN AUDIO
            final AudioChoice choice = this.audioChoice;
            int origin = AudioChoice.MAIN;
            if (choice != null) {
                final List<Track> tracks = choice.tracks();
                origin = choice.track();
                if (origin < AudioChoice.MAIN || origin >= tracks.size()) {
                    origin = AudioChoice.MAIN;
                    int best = -1;
                    for (int i = 0; i < tracks.size(); i++) {
                        final int score = match(choice.language(), tracks.get(i).lang());
                        if (score <= best) continue;
                        best = score;
                        origin = i;
                    }
                }
                // THE PICKED STREAM BELONGS TO THE PICKED ORIGIN ONLY
                final int wanted = origin == choice.track() ? choice.stream() : -1;
                if (origin >= 0) {
                    this.useAudioTrack = this.initAudioTrack(tracks.get(origin), wanted, choice.language());
                    // A stop()/RESTART CUT THE TRACK OPEN SHORT: THE WHOLE SETUP IS ABANDONED, THERE IS NOTHING TO FALL BACK TO
                    if (!this.useAudioTrack && this.aborted) return false;
                    if (!this.useAudioTrack) LOGGER.warn(IT, "Audio track failed to open, falling back to main audio stream");
                }
                if (!this.useAudioTrack) {
                    this.audioStreamIndex = audioIndex(this.formatContext, origin == AudioChoice.MAIN ? wanted : -1, choice.language(), null);
                    if (this.audioStreamIndex >= 0) this.audioTimeBase = av_q2d(this.formatContext.streams(this.audioStreamIndex).time_base());
                }

                // EVERY AUDIO STREAM OF THE OPENED INPUTS: NAMED BY ITS TITLE (THE HLS DEMUXER KEEPS A RENDITION'S NAME AS ITS
                // comment) AND TAGGED BY ITS LANGUAGE, A SEPARATE TRACK FILLING WHAT ITS STREAMS LEAVE UNSAID
                final List<AudioStream> streams = new ArrayList<>();
                final AVFormatContext[] inputs = { this.formatContext, this.useAudioTrack ? this.trackFormatContext : null };
                for (int n = 0; n < inputs.length; n++) {
                    if (inputs[n] == null) continue;
                    final Track track = n == 0 ? null : tracks.get(origin);
                    for (int i = 0; i < inputs[n].nb_streams(); i++) {
                        final AVStream stream = inputs[n].streams(i);
                        if (stream.codecpar().codec_type() != avutil.AVMEDIA_TYPE_AUDIO) continue;
                        streams.add(new AudioStream(n == 0 ? AudioChoice.MAIN : origin, i,
                                tag(stream, "title", tag(stream, "comment", track == null ? null : track.name())),
                                tag(stream, "language", track == null ? null : track.lang())));
                    }
                }
                this.audioStreams = List.copyOf(streams);
            }

            final boolean videoInit = this.initVideo();
            final boolean audioInit = this.initAudio();

            // FULLY DISABLE A STREAM WHOSE INIT FAILED: A DECODE THREAD STARTS ON streamIndex >= 0 && codecContext != null, AND A
            // HALF-OPENED PIPELINE (avcodec_open2 DONE, swr_init FAILED) WOULD CALL swr_get_delay WITH in_sample_rate 0, A NATIVE
            // DIVIDE-BY-ZERO THAT CRASHES THE JVM; HLS VARIANTS WITH UNPROBED AUDIO (sample_rate=0, 0 CHANNELS) HIT THIS
            if (!videoInit) {
                if (this.videoCodecContext != null) {
                    avcodec.avcodec_free_context(this.videoCodecContext);
                    this.videoCodecContext = null;
                }
                this.videoStreamIndex = -1;
                this.videoStream = null;
            }
            if (!audioInit) {
                if (this.audioCodecContext != null) {
                    avcodec.avcodec_free_context(this.audioCodecContext);
                    this.audioCodecContext = null;
                }
                if (this.swrContext != null) {
                    swresample.swr_free(this.swrContext);
                    this.swrContext = null;
                }
                this.audioStreamIndex = -1;
            }

            if (!videoInit && !audioInit)
                throw new IllegalStateException("Video and Audio failed to initialize");

            // ONLY THE DECODED STREAMS ARE READ: THE DEMUXER SKIPS THE REST, AND AN HLS MASTER STOPS FETCHING EVERY OTHER RENDITION
            discard(this.formatContext, this.videoStreamIndex, this.useAudioTrack ? -1 : this.audioStreamIndex);
            if (this.useAudioTrack) discard(this.trackFormatContext, this.audioStreamIndex, -1);
            final int decoded = this.useAudioTrack ? origin : AudioChoice.MAIN;
            for (final AudioStream stream: this.audioStreams)
                if (stream.track() == decoded && stream.stream() == this.audioStreamIndex) this.audioStream = stream;
            return true;
        } catch (final Exception e) {
            // A SETUP CUT SHORT BY stop()/RESTART IS NOT A FAILURE WORTH RECORDING
            if (this.aborted) return false;
            LOGGER.error(IT, "Failed to initialize the FFmpeg decoder", e);
            this.failure = e;
            return false;
        }
    }

    private boolean initVideo() {
        // AUDIO-ONLY INPUTS AND PLAYERS WITHOUT A VIDEO ENGINE ARE ROUTINE, NOT AN ANOMALY
        if (!this.videoWanted || this.videoStreamIndex < 0) {
            LOGGER.debug(IT, "No video to decode: {}", this.videoWanted ? "the input has no video stream" : "video is disabled");
            return true;
        }

        final AVStream videoStream = this.videoStream;

        // FRAME RATE FROM avg_frame_rate, ELSE r_frame_rate, EACH ONLY WITHIN 1..1000 FPS: MPEG-TS (COMMON INSIDE HLS) REPORTS
        // ITS 90 KHZ PCR CLOCK AS r_frame_rate UNTIL THE REAL RATE IS PROBED, WHICH WOULD ROUND THE CLOCK'S FRAME DURATION TO
        // 0 MS AND COLLAPSE PACING; WITHOUT A PLAUSIBLE RATE THE CLOCK KEEPS ITS DEFAULT
        final double avgFps = av_q2d(videoStream.avg_frame_rate());
        final double rFps = av_q2d(videoStream.r_frame_rate());
        this.fps = avgFps >= 1.0 && avgFps <= 1_000.0 ? (float) avgFps : rFps >= 1.0 && rFps <= 1_000.0 ? (float) rFps : 0f;
        if (this.fps == 0f) LOGGER.warn(IT, "No plausible frame rate (avg={}, r={}); the clock keeps its default", avgFps, rFps);

        final AVCodecParameters codecpar = videoStream.codecpar();
        final int codecId = codecpar.codec_id();

        final String codecName = FFTool.string(avcodec_get_name(codecId), null);
        final AVCodecDescriptor descriptor = avcodec_descriptor_get(codecId);
        final String codecLongName = descriptor != null ? FFTool.string(descriptor.long_name(), "unknown") : "unknown";
        final long bitrate = codecpar.bit_rate();
        final int profile = codecpar.profile();
        final int level = codecpar.level();
        final int pixFmt = codecpar.format();

        final var profilePointer = avcodec_profile_name(codecId, profile);
        final var fmtNamePointer = av_get_pix_fmt_name(pixFmt);

        final AVCodec decoder = avcodec_find_decoder(codecId);
        if (decoder == null)
            LOGGER.error(IT, "Failed to find video codec with id {} for videoIndex {}", codecId, this.videoStreamIndex);

        // REPORT THE ACTUAL DECODER (libdav1d, libaom-av1 OR THE NATIVE av1 ONE): THE SAME CODEC ID RESOLVES TO DIFFERENT DECODERS
        // PER PLATFORM BUILD, WHICH DIFFER IN REORDER DELAY AND STREAM SUPPORT (KEY CONTEXT WHEN A DECODE YIELDS NO FRAMES)
        LOGGER.debug(IT, "Video codec: {} ({}) via {}, bitrate: {} kbps, profile: {}, level: {}, pixel format: {}",
                codecName, codecLongName, decoder != null ? FFTool.string(decoder.name(), codecName) : "no decoder",
                bitrate > 0 ? bitrate / 1_000 : "N/A", FFTool.string(profilePointer, profile), level, FFTool.string(fmtNamePointer, pixFmt));

        final boolean hwAllowed = WaterMediaConfig.codecs.ffmpeg.hardwareAccel;
        // THE HARDWARE PATH KEEPS THE DEFAULT DECODER (THE NATIVE av1 ONE IS THE HWACCEL HOST), THE SOFTWARE PATH TAKES
        // softwareVideoDecoder. A NULL decoder SKIPS THE HARDWARE PATH, WHOSE avcodec_get_hw_config WOULD DEREFERENCE IT
        // NATIVELY AND SEGFAULT THE JVM, SO THE SOFTWARE PATH FAILS CLEANLY
        if (!hwAllowed || decoder == null || !this.initHwDecoder(decoder, videoStream)) {
            if (!hwAllowed) LOGGER.debug(IT, "Hardware acceleration disabled by config; using software decoding");

            final AVCodec swDecoder = softwareVideoDecoder(codecId, decoder);
            if (swDecoder == null) {
                LOGGER.error(IT, "No software decoder available for codec id {}", codecId);
                return false;
            }
            if (decoder == null || !Objects.equals(FFTool.string(swDecoder.name(), ""), FFTool.string(decoder.name(), "")))
                LOGGER.debug(IT, "Software decode using {}", FFTool.string(swDecoder.name(), "?"));

            this.videoCodecContext = avcodec.avcodec_alloc_context3(swDecoder);
            if (avcodec_parameters_to_context(this.videoCodecContext, videoStream.codecpar()) < 0) {
                LOGGER.error(IT, "Failed to copy video codec parameters to context");
                return false;
            }

            configureSwDecoderThreads(this.videoCodecContext);

            if (avcodec.avcodec_open2(this.videoCodecContext, swDecoder, (PointerPointer<?>) null) < 0) {
                LOGGER.error(IT, "Failed to open video codec");
                return false;
            }
        }
        return true;
    }

    private boolean initHwDecoder(final AVCodec decoder, final AVStream videoStream) {
        // THE ENGINE'S VULKAN DEVICE (OR ITS TWIN) FIRST: ITS FRAMES STAY ON THE GPU, EVERY OTHER ACCELERATOR DOWNLOADS THEM TO RAM. LIBPLACEBO
        // TURNS ANY POOL INTO RGBA THERE; WITHOUT IT ONLY 4:2:0 STREAMS QUALIFY, WHOSE POOLS (NV12, P010) THE ENGINE SAMPLES ITSELF
        final int streamFormat = videoStream.codecpar().format();
        final AVCodecHWConfig vulkan = hwConfig(decoder, AV_HWDEVICE_TYPE_VULKAN);
        final Device device = vulkan == null ? null : this.host.vulkan(videoStream.codecpar().codec_id());
        if (device != null) {
            final boolean sampled = device.filters() || streamFormat == AV_PIX_FMT_YUV420P || streamFormat == AV_PIX_FMT_YUVJ420P || streamFormat == AV_PIX_FMT_YUV420P10LE;
            if (!sampled) av_buffer_unref(device.ref());
            else if (this.openHw(decoder, videoStream, vulkan, device.ref())) {
                this.vulkanFrames = true;
                this.filters = device.filters();
                return true;
            }
        }
        for (final int hw: VIDEO_HW_CODECS) {
            final AVCodecHWConfig config = hwConfig(decoder, hw);
            if (config == null) continue;
            final AVBufferRef ref = new AVBufferRef();
            if (av_hwdevice_ctx_create(ref, hw, (String) null, null, 0) < 0) {
                ref.releaseReference();
                continue;
            }
            if (this.openHw(decoder, videoStream, config, ref)) return true;
        }
        return false;
    }

    // OPENS THE DECODER ON device, A REFERENCE THIS DECODER OWNS FROM HERE ON; FALSE LEAVES NOTHING OPEN
    private boolean openHw(final AVCodec decoder, final AVStream videoStream, final AVCodecHWConfig config, final AVBufferRef device) {
        this.hwDeviceCtx = device;
        this.videoCodecContext = avcodec.avcodec_alloc_context3(decoder);
        if (avcodec_parameters_to_context(this.videoCodecContext, videoStream.codecpar()) < 0) {
            this.cleanupHwContext();
            return false;
        }
        this.videoCodecContext.hw_device_ctx(av_buffer_ref(device));
        this.hwPixelFormat = config.pix_fmt();
        if (avcodec.avcodec_open2(this.videoCodecContext, decoder, (PointerPointer<?>) null) >= 0) {
            final BytePointer hwName = av_hwdevice_get_type_name(config.device_type());
            LOGGER.debug(IT, "Hardware decoder initialized: {} (device type {})", FFTool.string(hwName, "unknown"), config.device_type());
            IOTool.closeQuietly(hwName);
            return true;
        }
        this.cleanupHwContext();
        return false;
    }

    private void cleanupHwContext() {
        if (this.videoCodecContext != null) {
            avcodec.avcodec_free_context(this.videoCodecContext);
            this.videoCodecContext = null;
        }
        if (this.hwDeviceCtx != null) {
            av_buffer_unref(this.hwDeviceCtx);
            this.hwDeviceCtx = null;
        }
        this.hwPixelFormat = AV_PIX_FMT_NONE;
        this.vulkanFrames = false;
    }

    // REPLACES A MISBEHAVING HARDWARE DECODER WITH A SOFTWARE ONE IN PLACE, ON THE VIDEO DECODE THREAD; videoCodecContext IS
    // SWAPPED ONLY ON FULL SUCCESS, SO A FAILURE KEEPS THE HW CONTEXT USABLE. THE CURRENT GOP IS LOST UNTIL THE NEXT
    // KEYFRAME, ACCEPTABLE ON AN ALREADY-FAILING PIPELINE
    private boolean reinitVideoCodecSoftware() {
        final AVStream stream = this.videoStream;
        final int codecId = stream.codecpar().codec_id();
        // ROUTE AROUND THE HWACCEL-ONLY NATIVE av1 DECODER (SEE softwareVideoDecoder): A FAILED AV1 HWACCEL WOULD OTHERWISE FALL
        // BACK TO A DECODER THAT PRODUCES NO FRAMES
        final AVCodec decoder = softwareVideoDecoder(codecId, avcodec_find_decoder(codecId));
        if (decoder == null) return false;

        final AVCodecContext fresh = avcodec.avcodec_alloc_context3(decoder);
        if (fresh == null) return false;
        if (avcodec_parameters_to_context(fresh, stream.codecpar()) < 0) {
            avcodec.avcodec_free_context(fresh);
            LOGGER.error(IT, "SW fallback: failed to copy codec parameters");
            return false;
        }

        configureSwDecoderThreads(fresh);

        if (avcodec.avcodec_open2(fresh, decoder, (PointerPointer<?>) null) < 0) {
            avcodec.avcodec_free_context(fresh);
            LOGGER.error(IT, "SW fallback: failed to open software codec");
            return false;
        }

        final AVCodecContext old = this.videoCodecContext;
        this.videoCodecContext = fresh;
        avcodec.avcodec_free_context(old);
        if (this.hwDeviceCtx != null) {
            av_buffer_unref(this.hwDeviceCtx);
            this.hwDeviceCtx = null;
        }
        this.hwPixelFormat = AV_PIX_FMT_NONE;
        this.vulkanFrames = false;

        LOGGER.info(IT, "Software decoding fallback active");
        return true;
    }

    private boolean initAudio() {
        if (this.audioChoice == null || this.audioStreamIndex < 0)
            return false;

        final AVFormatContext audioContext = this.useAudioTrack ? this.trackFormatContext : this.formatContext;
        final AVStream audioStream = audioContext.streams(this.audioStreamIndex);
        final AVCodecParameters codecParams = audioStream.codecpar();
        final int codecId = codecParams.codec_id();
        final AVCodec audioCodec = avcodec.avcodec_find_decoder(codecId);

        int errorCode;
        if (audioCodec == null) {
            LOGGER.error(IT, "Failed to find audio codec with id {} for audioIndex {}", codecId, this.audioStreamIndex);
            return false;
        }

        final var audioCodecPointer = avcodec_get_name(codecId);
        final var sampleNamePointer = av_get_sample_fmt_name(codecParams.format());
        final String audioCodecName = FFTool.string(audioCodecPointer, null);
        LOGGER.debug(IT, "Audio codec: {} (id={}), channels: {}, sample_rate: {}, format: {}",
                audioCodecName, codecId,
                codecParams.ch_layout().nb_channels(),
                codecParams.sample_rate(),
                FFTool.string(sampleNamePointer, codecParams.format()));

        this.audioCodecContext = avcodec.avcodec_alloc_context3(audioCodec);
        if (this.audioCodecContext == null) {
            LOGGER.error(IT, "Failed to allocate audio codec context");
            return false;
        }

        if ((errorCode = avcodec_parameters_to_context(this.audioCodecContext, codecParams)) < 0) {
            LOGGER.error(IT, "Failed to copy audio codec parameters to context - err: {}", errorCode);
            return false;
        }

        if ((errorCode = avcodec.avcodec_open2(this.audioCodecContext, audioCodec, (PointerPointer<?>) null)) < 0) {
            LOGGER.error(IT, "Failed to open audio codec - err: {}", errorCode);
            return false;
        }

        // THE PLAYER FORMATS ITS ENGINE FOR THIS SOURCE AND SAYS WHAT IT TAKES
        final int srcFormat = codecParams.format();
        final int srcChannels = codecParams.ch_layout().nb_channels();
        final boolean srcPlanar = av_sample_fmt_is_planar(srcFormat) != 0;
        final SampleType srcType = FFTool.sampleType(srcFormat);

        this.audioOutputSampleRate = codecParams.sample_rate();

        // RECORD NEGOTIATED INPUT PARAMETERS FOR MID-STREAM CHANGE DETECTION
        this.audioInputSampleRate = codecParams.sample_rate();
        this.audioInputAvFormat = srcFormat;
        this.audioInputChannels = srcChannels;

        final Pcm target = this.host.audio(srcType, srcChannels, this.audioOutputSampleRate);
        if (target == null) return false;
        this.audioOutputChannels = target.channels();
        // KEPT EVEN IN PASSTHROUGH: A MID-STREAM PARAMETER CHANGE DEMOTES PASSTHROUGH TO THE RESAMPLE PATH, WHICH CONVERTS TOWARD
        // THIS FORMAT (THE ENGINE FORMAT CANNOT CHANGE)
        this.audioOutputAvFormat = FFTool.avSampleFormat(target.type());

        // PASSTHROUGH: AN INTERLEAVED SOURCE THE ENGINE TAKES AS IT IS, ALREADY IN THE ENGINE'S CHANNEL ORDER (AN UNSPECIFIED ORDER
        // COUNTS AS IT, AS THE RESAMPLER WOULD ASSUME THE DEFAULT ONE TOO)
        final AVChannelLayout engineOrder = new AVChannelLayout();
        engineLayout(engineOrder, target.channels());
        final boolean ordered = codecParams.ch_layout().order() == AV_CHANNEL_ORDER_UNSPEC || av_channel_layout_compare(codecParams.ch_layout(), engineOrder) == 0;
        av_channel_layout_uninit(engineOrder);
        if (!srcPlanar && target.type() == srcType && target.channels() == srcChannels && ordered) {
            this.audioPassthrough = true;
            LOGGER.debug(IT, "Audio passthrough: type={}, channels={}, rate={}", srcType, srcChannels, this.audioOutputSampleRate);
            return true;
        }

        this.audioPassthrough = false;
        LOGGER.debug(IT, "Audio resample ({}): {} {}ch {}Hz → {} {}ch {}Hz", srcPlanar ? "de-planarize only" : "format conversion",
                FFTool.string(av_get_sample_fmt_name(srcFormat), srcFormat),
                srcChannels, this.audioOutputSampleRate,
                target.type(), this.audioOutputChannels, this.audioOutputSampleRate);

        this.swrContext = swresample.swr_alloc();
        if (this.swrContext == null) {
            LOGGER.error(IT, "Failed to allocate SWResampler context");
            return false;
        }

        final AVChannelLayout inputLayout = new AVChannelLayout();
        final AVChannelLayout outputLayout = new AVChannelLayout();

        try {
            if (srcChannels > 0) {
                avutil.av_channel_layout_copy(inputLayout, codecParams.ch_layout());
            } else {
                LOGGER.warn(IT, "Audio codec has no channel layout info, defaulting to stereo");
                avutil.av_channel_layout_default(inputLayout, 2);
            }

            engineLayout(outputLayout, this.audioOutputChannels);

            avutil.av_opt_set_chlayout(this.swrContext, "in_chlayout", inputLayout, 0);
            avutil.av_opt_set_int(this.swrContext, "in_sample_rate", this.audioOutputSampleRate, 0);
            avutil.av_opt_set_sample_fmt(this.swrContext, "in_sample_fmt", srcFormat, 0);

            avutil.av_opt_set_chlayout(this.swrContext, "out_chlayout", outputLayout, 0);
            avutil.av_opt_set_int(this.swrContext, "out_sample_rate", this.audioOutputSampleRate, 0);
            avutil.av_opt_set_sample_fmt(this.swrContext, "out_sample_fmt", this.audioOutputAvFormat, 0);

            if (swresample.swr_init(this.swrContext) < 0) {
                LOGGER.error(IT, "Failed to initialize SWResampler context");
                return false;
            }
        } finally {
            av_channel_layout_uninit(inputLayout);
            av_channel_layout_uninit(outputLayout);
        }
        return true;
    }

    // ==========================================================================
    // CLEANUP
    // ==========================================================================
    private void free() {
        LOGGER.debug(IT, "Freeing the FFmpeg decoder...");

        if (this.hwDeviceCtx != null) {
            av_buffer_unref(this.hwDeviceCtx);
            this.hwDeviceCtx = null;
        }
        this.hwPixelFormat = AV_PIX_FMT_NONE;
        this.vulkanFrames = false;

        if (this.swrContext != null) {
            swresample.swr_free(this.swrContext);
            this.swrContext = null;
        }
        if (this.videoCodecContext != null) {
            // FLUSH BEFORE FREE: A FRAME-THREADED DECODER (E.G. THE MULTI-THREAD SOFTWARE FALLBACK) INTERRUPTED MID-DECODE CAN
            // LEAVE ITS ASYNC PIPELINE UNBALANCED, TRIPPING av_assert0(fctx->async_lock) IN pthread_frame.c ON
            // avcodec_free_context; FLUSHING DRAINS THE WORKER THREADS AND RESETS THAT STATE FIRST
            avcodec.avcodec_flush_buffers(this.videoCodecContext);
            avcodec.avcodec_free_context(this.videoCodecContext);
            this.videoCodecContext = null;
        }
        if (this.audioCodecContext != null) {
            avcodec.avcodec_free_context(this.audioCodecContext);
            this.audioCodecContext = null;
        }
        if (this.formatContext != null) {
            try {
                avformat.avformat_close_input(this.formatContext);
            } catch (final Exception e) {
                LOGGER.warn(IT, "Error closing format context", e);
            }
            this.formatContext = null;
        }
        if (this.trackFormatContext != null) {
            try {
                avformat.avformat_close_input(this.trackFormatContext);
            } catch (final Exception e) {
                LOGGER.warn(IT, "Error closing track format context", e);
            }
            this.trackFormatContext = null;
        }
        this.useAudioTrack = false;
        this.videoStreamIndex = -1;
        this.videoStream = null;
        this.audioStreamIndex = -1;

        LOGGER.debug(IT, "FFmpeg decoder freed");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // THE DECODER'S CONFIGURATION THAT TAKES A DEVICE CONTEXT OF TYPE hw, OR NULL
    private static AVCodecHWConfig hwConfig(final AVCodec decoder, final int hw) {
        for (int i = 0; ; i++) {
            final AVCodecHWConfig config = avcodec.avcodec_get_hw_config(decoder, i);
            if (config == null) return null;
            if (config.device_type() == hw && (config.methods() & avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0) return config;
        }
    }

    // A SOFTWARE-CAPABLE VIDEO DECODER: THE NATIVE av1 DECODER avcodec_find_decoder RETURNS IS A HWACCEL HOST THAT EMITS NO
    // FRAMES WITHOUT AN ACCELERATOR, SO AV1 PREFERS libdav1d, THEN libaom-av1, THEN THE DEFAULT. EVERY OTHER CODEC OF THE
    // BUNDLED FFMPEG DEFAULTS TO A NATIVE SOFTWARE DECODER; THE PLAYER'S ZERO-FRAME → ERROR CHECK BACKS ANY FUTURE STUB
    private static AVCodec softwareVideoDecoder(final int codecId, final AVCodec fallback) {
        if (codecId == AV_CODEC_ID_AV1) {
            final AVCodec dav1d = avcodec_find_decoder_by_name("libdav1d");
            if (dav1d != null) return dav1d;
            final AVCodec aom = avcodec_find_decoder_by_name("libaom-av1");
            if (aom != null) return aom;
        }
        return fallback;
    }

    // SIZES THE SOFTWARE DECODER THREAD POOL TO THE FRAME AREA
    private static void configureSwDecoderThreads(final AVCodecContext ctx) {
        final int width = ctx.width();
        final int height = ctx.height();
        final int pixels = width * height;

        final int threads = pixels <= 921_600
                ? Math.min(2, MAX_DECODE_THREADS) : pixels <= 2_073_600
                ? Math.min(4, MAX_DECODE_THREADS) : pixels <= 3_686_400
                ? Math.min(6, MAX_DECODE_THREADS) : MAX_DECODE_THREADS;

        ctx.thread_count(threads);
        ctx.thread_type(AVCodecContext.FF_THREAD_FRAME | AVCodecContext.FF_THREAD_SLICE);

        LOGGER.debug(IT, "Decoder threading: {} threads for {}x{}", threads, width, height);
    }

    // THE CHANNEL ORDER THE ENGINES PLAY FOR channels: FFMPEG'S DEFAULT LAYOUT, BUT QUAD FOR 4, BECAUSE OPENAL'S QUAD IS FL FR BL BR
    // WHILE THE DEFAULT 4.0 (FL FR FC BC) WOULD PLAY THE CENTER FROM THE BACK LEFT
    private static void engineLayout(final AVChannelLayout layout, final int channels) {
        if (channels == 4) av_channel_layout_from_mask(layout, AV_CH_LAYOUT_QUAD);
        else av_channel_layout_default(layout, channels);
    }

    // LOGS THE FIRST UNEXPECTED send/receive FAILURE OF A DECODE LOOP WITH ITS CODEC AND NATIVE DETAIL
    private static void logCodecError(final String media, final String operation, final AVCodecContext codec, final int stream, final int code) {
        LOGGER.warn(IT, "{} decoder {} failed: codec={}, stream={}, code={} ({})",
                media, operation, FFTool.string(avcodec_get_name(codec.codec_id()), "unknown"), stream, code, FFTool.error(code));
    }

    // THE AUDIO STREAM OF ctx TO DECODE, -1 WITHOUT ONE: wanted WHEN IT IS AN AUDIO STREAM OF language (ANY WITHOUT ONE), ELSE THE
    // BEST BY language (THE SAME TAG, THEN THE SAME LANGUAGE), THEN THE DEFAULT DISPOSITION, THEN THE ORDER. A STREAM WITHOUT
    // A language TAG TAKES THE ONE OF track, THE SEPARATE TRACK ctx OPENED
    private static int audioIndex(final AVFormatContext ctx, final int wanted, final String language, final Track track) {
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < ctx.nb_streams(); i++) {
            final AVStream stream = ctx.streams(i);
            if (stream.codecpar().codec_type() != avutil.AVMEDIA_TYPE_AUDIO) continue;
            final int match = match(language, tag(stream, "language", track == null ? null : track.lang()));
            if (i == wanted && (language == null || match > 0)) return i;
            // A LANGUAGE MATCH OUTRANKS THE DEFAULT FLAG, WHICH ONLY BREAKS TIES
            final int score = match * 2 + ((stream.disposition() & avformat.AV_DISPOSITION_DEFAULT) != 0 ? 1 : 0);
            if (score <= bestScore) continue;
            best = i;
            bestScore = score;
        }
        return best;
    }

    // THE NON-BLANK METADATA VALUE OF key ("und", AN UNDETERMINED LANGUAGE, COUNTS AS NONE), ELSE fallback
    private static String tag(final AVStream stream, final String key, final String fallback) {
        final AVDictionaryEntry entry = av_dict_get(stream.metadata(), key, null, 0);
        final String value = entry == null || FFTool.isNull(entry.value()) ? null : entry.value().getString(StandardCharsets.UTF_8).strip();
        return value == null || value.isEmpty() || value.equalsIgnoreCase("und") ? fallback : value;
    }

    // DISCARDS EVERY STREAM OF ctx BUT THE DECODED ONES (-1 KEEPS NONE)
    private static void discard(final AVFormatContext ctx, final int video, final int audio) {
        for (int i = 0; i < ctx.nb_streams(); i++) ctx.streams(i).discard(i == video || i == audio ? AVDISCARD_DEFAULT : AVDISCARD_ALL);
    }

    // HOW WELL tag MATCHES preference: 2 THE SAME TAG, 1 THE SAME LANGUAGE, 0 NEITHER OR ONE IS MISSING. BCP 47 TAGS ("es-419")
    // AND THE ISO 639-2 CODES CONTAINERS CARRY ("spa", "fre") MEET AS ISO 639-2/T; AN HLS "en,fr" PAIR COUNTS BY ITS FIRST
    static int match(final String preference, final String tag) {
        final String wanted = iso(preference);
        final String found = iso(tag);
        if (wanted == null || found == null) return 0;
        if (wanted.equals(found)) return 2;
        return wanted.split("-", 2)[0].equals(found.split("-", 2)[0]) ? 1 : 0;
    }

    // tag LOWERCASED WITH ITS PRIMARY LANGUAGE AS ISO 639-2/T ("es-MX" → "spa-mx", "fre" → "fra"), OR NULL WHEN IT NAMES NONE
    private static String iso(final String tag) {
        if (tag == null) return null;
        final String first = tag.split(",", 2)[0].strip().replace('_', '-').toLowerCase(Locale.ROOT);
        final int dash = first.indexOf('-');
        final String primary = dash < 0 ? first : first.substring(0, dash);
        if (primary.length() < 2 || primary.length() > 3 || primary.equals("und")) return null;
        String code = BIBLIOGRAPHIC.getOrDefault(primary, primary);
        if (primary.length() == 2) {
            try { code = Locale.forLanguageTag(primary).getISO3Language(); }
            catch (final MissingResourceException unknown) { code = primary; }
        }
        return dash < 0 ? code : code + first.substring(dash);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** Playback timeline the decoder follows: seeks, pause, queue serials and the end of the input; the player's clock. */
    public interface Timeline {
        /** Takes the pending seek, or returns null when none is pending. */
        Seek consumeSeek();
        /** Returns whether a seek is pending. */
        boolean seekPending();
        /** Returns whether playback is held paused, so the demuxer parks. */
        boolean holding();
        /** Waits until the status changes or a seek arrives, at most {@code timeoutMs} (0 waits for it). */
        void awaitChange(final long timeoutMs) throws InterruptedException;
        /** Adopts the serial the queues carry after a reset, so frames of that serial count. */
        void serial(final int serial);
        /** Starts the clock once the input is open. */
        void start();
        /** Records that the demuxer resumed after the end of the input. */
        void signalDemuxResumed();
        /** Records that the demuxer reached the end of the input. */
        void signalDemuxFinished();
        /** Returns the duration of one video frame in seconds. */
        double frameDurationSec();
    }

    /** Player side of the decoder: the cache, the Vulkan device, the audio format and the open and failure reports. */
    public interface Host {
        /** Returns the cached copy of {@code uri}, or null to open it remotely; runs on the demux thread, so it may block. */
        Path cached(final URI uri) throws IOException;
        /** Returns the player engine's Vulkan device for {@code codecId}, whose reference the decoder then owns, or null. */
        Device vulkan(final int codecId);
        /** Formats the player's audio engine for a source and returns the PCM it takes, or null to drop the audio. */
        Pcm audio(final SampleType type, final int channels, final int rate);
        /** Reports the duration of the input in milliseconds (0 when unknown, as live) each time it is probed, on the demux thread. */
        void duration(final long ms);
        /** Reports the open input before the clock starts, on the demux thread. */
        void opened(final FFDecoder decoder);
        /** Reports why the input could not open or keep playing; never called for an abort. */
        void failed(final Throwable cause);
    }

    /** A seek to {@code targetMs}, frame-exact when {@code precise}. */
    public record Seek(long targetMs, boolean precise) {}

    /** A Vulkan device reference and whether libplacebo runs on it. */
    public record Device(AVBufferRef ref, boolean filters) {}

    /** Interleaved PCM layout an audio engine takes. */
    public record Pcm(SampleType type, int channels) {}

    /**
     * The audio a player wants from a run: the source's separate {@code tracks}, the origin to play ({@link #MAIN}, a track
     * index or {@link #AUTO}), the {@code stream} inside it (-1 for the best one) and the {@code language} the pick matches.
     */
    public record AudioChoice(List<Track> tracks, int track, int stream, String language) {
        /** Origin of the main input's own audio. */
        public static final int MAIN = -1;
        /** Origin left to the decoder: the separate track of the language, else the first one, else the main input. */
        public static final int AUTO = -2;

        public AudioChoice {
            tracks = tracks == null ? List.of() : List.copyOf(tracks);
        }
    }

    /** An audio stream of an opened input: its origin ({@link AudioChoice#MAIN} or a track index), its index there, name and language tag, or null. */
    public record AudioStream(int track, int stream, String name, String lang) {}
}
