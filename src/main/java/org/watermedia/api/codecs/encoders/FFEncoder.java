package org.watermedia.api.codecs.encoders;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVAudioFifo;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVPixFmtDescriptor;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.Encoder;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.FFTool;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * FFmpeg encoder of the audio and video containers. Frames are converted by libswscale to the codec's pixel format
 * and tagged BT.709 limited range, samples are converted by libswresample to the codec's sample format and rate and
 * cut into the codec's frame size, and both tracks are interleaved into one file. Codecs are software encoders:
 * the GPL natives add libx264 and libx265 ahead of OpenH264 and Kvazaar.
 */
public final class FFEncoder extends Encoder {
    private static final Marker IT = MarkerManager.getMarker(FFEncoder.class.getSimpleName());

    // TIMING
    private static final int VIDEO_CLOCK_HZ = 90_000; // MPEG CLOCK: FINE ENOUGH FOR VARIABLE FRAME RATES AND SAFE FOR MP4 AND MOV
    private static final int GOP_SECONDS = 2;          // A KEYFRAME EVERY TWO SECONDS KEEPS SEEKING CHEAP
    private static final int PCM_CHUNK_SAMPLES = 1_024; // FRAME SIZE OF CODECS THAT TAKE ANY, SUCH AS PCM

    private final Path file;           // TARGET THE FINISHED tempFile REPLACES, OR NULL WHEN out GETS A COPY OF IT
    private final OutputStream out;
    private Path tempFile;
    private AVFormatContext muxer;
    private AVPacket packet;
    private boolean written;

    // VIDEO
    private AVCodecContext picture;
    private AVStream pictureStream;
    private SwsContext scaler;
    private AVFrame source;            // BINDS THE CALLER'S PIXELS, NO STORAGE OF ITS OWN
    private AVFrame converted;
    private int sourceFormat;
    private ByteBuffer mirror;         // DIRECT COPY OF HEAP FRAMES
    private long lastTicks = -1;

    // AUDIO
    private AVCodecContext sound;
    private AVStream soundStream;
    private SwrContext resampler;
    private AVAudioFifo fifo;
    private AVFrame resampled;         // RESAMPLER OUTPUT, GROWN ON DEMAND
    private AVFrame chunk;             // ONE ENCODER FRAME READ FROM THE FIFO
    private ByteBuffer pcm;            // DIRECT COPY OF HEAP SAMPLES
    private int chunkSamples;
    private boolean shortLast;         // THE CODEC TAKES A SHORTER LAST FRAME; OTHERWISE IT IS PADDED WITH SILENCE
    private long samplesSent;

    /** Opens the encode into a temporary file that replaces {@code file} or is copied into {@code out} once finished. */
    public FFEncoder(final Container container, final Path file, final OutputStream out, final Video video, final Audio audio) throws IOException {
        super(container, video, audio);
        if ((file == null) == (out == null)) throw new IllegalArgumentException("Exactly one of the file and the stream is the target");
        if (!CodecsAPI.ffmpegLoaded()) throw new IOException("FFmpeg is not available");
        this.file = file;
        this.out = out;

        final String format = switch (container) {
            case MP4 -> "mp4";
            case MOV -> "mov";
            case MKV -> "matroska";
            case WEBM -> "webm";
            case OGG -> "ogg";
            default -> throw new IllegalArgumentException(container + " is not an FFmpeg container");
        };
        try {
            // A SEEKABLE FILE, SINCE MP4 AND MATROSKA PATCH THEIR HEADER: NEXT TO THE TARGET SO IT ONLY APPEARS FINISHED, OR A
            // PRIVATE ONE FOR A STREAM. ABSOLUTE, SO FFMPEG NEVER READS A "scheme:" PREFIX AS A PROTOCOL
            this.tempFile = this.file != null
                    ? this.file.toAbsolutePath().resolveSibling(this.file.getFileName() + ".part")
                    : Files.createTempFile("wm-encode-", "." + format).toAbsolutePath();
            // THE MUXER KEEPS ITS PATH: MP4 FASTSTART REOPENS THE FILE BY IT TO MOVE THE INDEX FORWARD
            try (final BytePointer name = new BytePointer(format, StandardCharsets.US_ASCII);
                 final BytePointer path = new BytePointer(this.tempFile.toString(), StandardCharsets.UTF_8)) {
                this.muxer = new AVFormatContext(null);
                if (avformat.avformat_alloc_output_context2(this.muxer, null, name, path) < 0 || this.muxer.isNull())
                    throw new IOException("Failed to allocate the " + format + " muxer");
                final boolean globalHeader = (this.muxer.oformat().flags() & avformat.AVFMT_GLOBALHEADER) != 0;
                this.packet = avcodec.av_packet_alloc();
                if (FFTool.isNull(this.packet)) throw new IOException("Failed to allocate a packet");

                // TRACKS OPEN BEFORE THE FILE, SO A CODEC THAT REFUSES ITS TRACK CREATES NOTHING
                if (video != null) this.openVideo(video, globalHeader);
                if (audio != null) this.openAudio(audio, globalHeader);

                final AVIOContext pb = new AVIOContext(null);
                final int opened = avformat.avio_open(pb, path, avformat.AVIO_FLAG_WRITE);
                if (opened < 0) throw FFTool.failure("avio_open", opened);
                this.muxer.pb(pb);
            }
            final AVDictionary options = new AVDictionary(null);
            try {
                // MP4 AND MOV MOVE THEIR INDEX TO THE FRONT, SO PLAYERS START BEFORE THE WHOLE FILE ARRIVES
                if (container == Container.MP4 || container == Container.MOV) avutil.av_dict_set(options, "movflags", "+faststart", 0);
                final int header = avformat.avformat_write_header(this.muxer, options);
                if (header < 0) throw FFTool.failure("avformat_write_header", header);
            } finally {
                avutil.av_dict_free(options);
            }
            LOGGER.debug(IT, "Encoding {} with {} and {}", container,
                    this.picture == null ? "no video" : FFTool.string(this.picture.codec().name(), "?") + " " + this.picture.width() + "x" + this.picture.height(),
                    this.sound == null ? "no audio" : FFTool.string(this.sound.codec().name(), "?") + " " + this.sound.sample_rate() + " Hz");
        } catch (final IOException | RuntimeException e) {
            this.free();
            throw e;
        }
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Override
    protected void frame(final ByteBuffer frame, final long ptsUs) throws IOException {
        // BIND THE CALLER'S PIXELS: ZERO-COPY WHEN DIRECT, ELSE A REUSED DIRECT MIRROR
        final ByteBuffer pixels;
        if (frame.isDirect()) {
            pixels = frame;
        } else {
            if (this.mirror == null) this.mirror = ByteBuffer.allocateDirect(frame.remaining());
            this.mirror.clear();
            this.mirror.put(frame.duplicate()).flip();
            pixels = this.mirror;
        }
        try (final BytePointer bound = new BytePointer(pixels)) {
            if (avutil.av_image_fill_arrays(this.source.data(), this.source.linesize(), bound, this.sourceFormat, this.video.width(), this.video.height(), 1) < 0)
                throw new IOException("Failed to bind the frame pixels");
            final int writable = avutil.av_frame_make_writable(this.converted);
            if (writable < 0) throw FFTool.failure("av_frame_make_writable", writable);
            swscale.sws_scale(this.scaler, this.source.data(), this.source.linesize(), 0, this.video.height(),
                    this.converted.data(), this.converted.linesize());
        }

        // MICROSECONDS TO THE 90 KHZ CLOCK, STRICTLY INCREASING EVEN WHEN TWO FRAMES ROUND TO THE SAME TICK
        long ticks = ptsUs * 9 / 100;
        if (ticks <= this.lastTicks) ticks = this.lastTicks + 1;
        this.lastTicks = ticks;
        this.converted.pts(ticks);
        this.send(this.picture, this.pictureStream, this.converted);
        this.written = true;
    }

    @Override
    protected void samples(final ByteBuffer samples) throws IOException {
        final int count = samples.remaining() / this.audio.frameBytes();
        final ByteBuffer input;
        if (samples.isDirect()) {
            input = samples;
        } else {
            if (this.pcm == null || this.pcm.capacity() < samples.remaining()) this.pcm = ByteBuffer.allocateDirect(samples.remaining());
            this.pcm.clear();
            this.pcm.put(samples.duplicate()).flip();
            input = this.pcm;
        }
        try (final BytePointer data = new BytePointer(input); final PointerPointer<BytePointer> planes = new PointerPointer<>(1)) {
            planes.put(0, data);
            this.resample(planes, count);
        }
        this.sendChunks(false);
        this.written = true;
    }

    @Override
    protected void finish() throws IOException {
        try {
            if (!this.written) throw new IllegalStateException(this.container + " needs at least one frame or sample");

            // FLUSH THE RESAMPLER TAIL AND THE LAST SHORT CHUNK, THEN EVERY ENCODER'S LOOKAHEAD
            if (this.sound != null) {
                this.resample(null, 0);
                this.sendChunks(true);
                this.send(this.sound, this.soundStream, null);
            }
            if (this.picture != null) this.send(this.picture, this.pictureStream, null);
            final int trailer = avformat.av_write_trailer(this.muxer);
            if (trailer < 0) throw FFTool.failure("av_write_trailer", trailer);

            // CLOSE THE NATIVE FILE SO EVERY PATCHED BYTE IS ON DISK BEFORE IT BECOMES THE TARGET
            final AVIOContext pb = this.muxer.pb();
            this.muxer.pb(null);
            final int closed = avformat.avio_close(pb);
            if (closed < 0) throw FFTool.failure("avio_close", closed);
            if (this.out != null) {
                Files.copy(this.tempFile, this.out);
            } else {
                Files.move(this.tempFile, this.file, StandardCopyOption.REPLACE_EXISTING);
                this.tempFile = null;
            }
        } finally {
            this.free();
            if (this.out != null) this.out.close();
        }
    }

    // ==========================================================================
    // SETUP
    // ==========================================================================
    private void openVideo(final Video video, final boolean globalHeader) throws IOException {
        // SOFTWARE ENCODERS IN PREFERENCE ORDER; THE GPL ONES ONLY EXIST IN THE GPL NATIVES
        final AVCodec codec = encoder(video.codec().name(), switch (video.codec()) {
            case H264 -> new String[] { "libx264", "libopenh264" };
            case HEVC -> new String[] { "libx265", "libkvazaar" };
            case AV1 -> new String[] { "libsvtav1", "libaom-av1" };
            case VP8 -> new String[] { "libvpx" };
            case VP9 -> new String[] { "libvpx-vp9" };
            case PRORES -> new String[] { "prores_ks" };
            case PNG, GIF -> throw new IllegalArgumentException(video.codec() + " is encoded in pure Java");
        });
        this.picture = avcodec.avcodec_alloc_context3(codec);
        if (FFTool.isNull(this.picture)) throw new IOException("Failed to allocate the " + video.codec() + " encoder");

        // PIXEL FORMAT: YUV 4:2:0 WHEN THE CODEC TAKES IT, ITS FIRST FORMAT OTHERWISE (PRORES IS 4:2:2 10-BIT)
        final int[] formats = supported(this.picture, codec, avcodec.AV_CODEC_CONFIG_PIX_FORMAT);
        int target = formats.length == 0 ? avutil.AV_PIX_FMT_YUV420P : formats[0];
        for (final int candidate: formats) if (candidate == avutil.AV_PIX_FMT_YUV420P) target = candidate;

        // SUBSAMPLED CHROMA NEEDS EVEN SIDES; AN ODD ONE GROWS BY A PIXEL THAT THE SCALER STRETCHES INTO
        final AVPixFmtDescriptor layout = avutil.av_pix_fmt_desc_get(target);
        final int width = video.width() + (layout.log2_chroma_w() > 0 ? video.width() & 1 : 0);
        final int height = video.height() + (layout.log2_chroma_h() > 0 ? video.height() & 1 : 0);

        this.picture.width(width);
        this.picture.height(height);
        this.picture.pix_fmt(target);
        this.picture.time_base(new AVRational().num(1).den(VIDEO_CLOCK_HZ));
        this.picture.framerate(new AVRational().num(video.fps()).den(1));
        this.picture.gop_size(video.fps() * GOP_SECONDS);
        this.picture.thread_count(0); // EVERY SOFTWARE ENCODER RUNS ONE THREAD UNLESS TOLD TO PICK ITS OWN COUNT
        // TAGGED BT.709 LIMITED RANGE, SO NO PLAYER GUESSES BT.601 FOR A SMALL FRAME; THE SCALER BELOW MATCHES IT
        this.picture.colorspace(avutil.AVCOL_SPC_BT709);
        this.picture.color_primaries(avutil.AVCOL_PRI_BT709);
        this.picture.color_trc(avutil.AVCOL_TRC_BT709);
        this.picture.color_range(avutil.AVCOL_RANGE_MPEG);
        if (globalHeader) this.picture.flags(this.picture.flags() | avcodec.AV_CODEC_FLAG_GLOBAL_HEADER);
        quality(this.picture, FFTool.string(codec.name(), ""), video, (long) width * height * video.fps());
        final int opened = avcodec.avcodec_open2(this.picture, codec, (PointerPointer<?>) null);
        if (opened < 0) throw FFTool.failure("avcodec_open2 (" + FFTool.string(codec.name(), video.codec().name()) + ")", opened);

        this.pictureStream = this.stream(this.picture);
        // PACKETS CARRY NO DURATION, SO THE MUXER TAKES IT FROM THE RATE AND THE LAST FRAME LASTS 1 / fps INSTEAD OF 0
        this.pictureStream.avg_frame_rate(new AVRational().num(video.fps()).den(1));
        // APPLE PLAYERS ONLY OPEN HEVC TAGGED hvc1, WHILE FFMPEG TAGS IT hev1 IN MP4 AND MOV
        if (video.codec() == VideoCodec.HEVC && (this.container == Container.MP4 || this.container == Container.MOV))
            this.pictureStream.codecpar().codec_tag('h' | 'v' << 8 | 'c' << 16 | '1' << 24);

        // CONVERSION: ANY RAW LAYOUT TO THE CODEC'S, RGB THROUGH THE BT.709 MATRIX AND YUV TAKEN AS ALREADY IN IT
        this.sourceFormat = FFTool.avPixelFormat(video.format());
        this.scaler = swscale.sws_getContext(video.width(), video.height(), this.sourceFormat, width, height, target,
                swscale.SWS_BILINEAR, null, null, (double[]) null);
        if (FFTool.isNull(this.scaler)) throw new IOException("Failed to create the pixel converter");
        final PixelFormat input = video.format();
        if (input == PixelFormat.RGB || input == PixelFormat.RGBA || input == PixelFormat.BGRA) {
            final IntPointer bt709 = swscale.sws_getCoefficients(swscale.SWS_CS_ITU709);
            final int details = swscale.sws_setColorspaceDetails(this.scaler, bt709, 1, bt709, 0, 0, 1 << 16, 1 << 16);
            if (details < 0) throw new IOException("sws_setColorspaceDetails refused the BT.709 conversion (code " + details + ")");
        }
        this.source = avutil.av_frame_alloc();
        this.converted = avutil.av_frame_alloc();
        if (FFTool.isNull(this.source) || FFTool.isNull(this.converted)) throw new IOException("Failed to allocate the video frames");
        this.converted.format(target);
        this.converted.width(width);
        this.converted.height(height);
        final int buffer = avutil.av_frame_get_buffer(this.converted, 32);
        if (buffer < 0) throw FFTool.failure("av_frame_get_buffer", buffer);
    }

    private void openAudio(final Audio audio, final boolean globalHeader) throws IOException {
        final AVCodec codec = encoder(audio.codec().name(), switch (audio.codec()) {
            case PCM -> new String[] { "pcm_s16le" };
            case OPUS -> new String[] { "libopus" };
            case AAC -> new String[] { "aac" };
            case MP3 -> new String[] { "libmp3lame" };
            case FLAC -> new String[] { "flac" };
        });
        this.sound = avcodec.avcodec_alloc_context3(codec);
        if (FFTool.isNull(this.sound)) throw new IOException("Failed to allocate the " + audio.codec() + " encoder");

        // SAMPLE FORMAT: THE INPUT'S OWN WHEN THE CODEC TAKES IT, ITS FIRST ONE OTHERWISE
        final int inputFormat = FFTool.avSampleFormat(audio.type());
        final int[] formats = supported(this.sound, codec, avcodec.AV_CODEC_CONFIG_SAMPLE_FORMAT);
        int format = formats.length == 0 ? inputFormat : formats[0];
        for (final int candidate: formats) if (candidate == inputFormat) format = candidate;

        // SAMPLE RATE: THE INPUT'S WHEN TAKEN, ELSE THE CLOSEST ABOVE IT (OPUS GOES TO 48 KHZ), ELSE THE HIGHEST
        final int[] rates = supported(this.sound, codec, avcodec.AV_CODEC_CONFIG_SAMPLE_RATE);
        int rate = rates.length == 0 ? audio.sampleRate() : -1;
        if (rate < 0) {
            int highest = 0;
            for (final int candidate: rates) {
                highest = Math.max(highest, candidate);
                if (candidate >= audio.sampleRate() && (rate < 0 || candidate < rate)) rate = candidate;
            }
            if (rate < 0) rate = highest;
        }

        // CHANNEL LAYOUT: THE CODEC'S OWN FOR THAT CHANNEL COUNT, OR THE DEFAULT ONE WHEN IT TAKES ANY
        this.layout(this.sound.ch_layout(), codec, audio);
        this.sound.sample_fmt(format);
        this.sound.sample_rate(rate);
        this.sound.time_base(new AVRational().num(1).den(rate));
        final int bitrate = audio.bitrate() > 0 ? audio.bitrate() : switch (audio.codec()) {
            case OPUS -> Math.min(510_000, 48_000 * audio.channels());
            case AAC -> 64_000 * audio.channels();
            case MP3 -> Math.min(320_000, 64_000 * audio.channels());
            case PCM, FLAC -> 0;
        };
        if (bitrate > 0) this.sound.bit_rate(bitrate);
        if (globalHeader) this.sound.flags(this.sound.flags() | avcodec.AV_CODEC_FLAG_GLOBAL_HEADER);
        final int opened = avcodec.avcodec_open2(this.sound, codec, (PointerPointer<?>) null);
        if (opened < 0) throw FFTool.failure("avcodec_open2 (" + FFTool.string(codec.name(), audio.codec().name()) + ")", opened);

        this.soundStream = this.stream(this.sound);

        // CODECS WITH A FIXED FRAME SIZE TAKE EXACTLY THAT MANY SAMPLES PER FRAME, SO THE FIFO CUTS THEM
        final int capabilities = codec.capabilities();
        final boolean variable = (capabilities & avcodec.AV_CODEC_CAP_VARIABLE_FRAME_SIZE) != 0 || this.sound.frame_size() <= 0;
        this.chunkSamples = variable ? PCM_CHUNK_SAMPLES : this.sound.frame_size();
        this.shortLast = variable || (capabilities & avcodec.AV_CODEC_CAP_SMALL_LAST_FRAME) != 0;

        // RESAMPLER FROM THE INTERLEAVED INPUT TO THE CODEC'S FORMAT, RATE AND LAYOUT
        // THE CONTEXT STARTS NULL: swr_alloc_set_opts2 REUSES WHATEVER *ps ALREADY POINTS TO
        final AVChannelLayout inputLayout = new AVChannelLayout();
        avutil.av_channel_layout_default(inputLayout, audio.channels());
        final SwrContext resampler = new SwrContext(null);
        try {
            final int allocated = swresample.swr_alloc_set_opts2(resampler, this.sound.ch_layout(), format, rate,
                    inputLayout, inputFormat, audio.sampleRate(), 0, null);
            if (allocated < 0) throw FFTool.failure("swr_alloc_set_opts2", allocated);
            this.resampler = resampler;
        } finally {
            avutil.av_channel_layout_uninit(inputLayout);
        }
        final int initialized = swresample.swr_init(this.resampler);
        if (initialized < 0) throw FFTool.failure("swr_init", initialized);

        this.fifo = avutil.av_audio_fifo_alloc(format, this.sound.ch_layout().nb_channels(), this.chunkSamples * 2);
        this.chunk = this.audioFrame(this.chunkSamples);
        this.resampled = this.audioFrame(this.chunkSamples);
        if (FFTool.isNull(this.fifo)) throw new IOException("Failed to allocate the audio FIFO");
    }

    // PICKS THE CODEC'S OWN LAYOUT FOR THE TRACK'S CHANNEL COUNT, OR THE DEFAULT ONE WHEN THE CODEC TAKES ANY
    private void layout(final AVChannelLayout target, final AVCodec codec, final Audio audio) {
        try (final PointerPointer<Pointer> list = new PointerPointer<>(1); final IntPointer count = new IntPointer(1)) {
            final int found = avcodec.avcodec_get_supported_config(this.sound, codec, avcodec.AV_CODEC_CONFIG_CHANNEL_LAYOUT, 0, list, count);
            final Pointer entries = list.get(0);
            if (found < 0 || FFTool.isNull(entries) || count.get() <= 0) {
                avutil.av_channel_layout_default(target, audio.channels());
                return;
            }
            final AVChannelLayout layouts = new AVChannelLayout(entries);
            final StringBuilder offered = new StringBuilder();
            for (int i = 0; i < count.get(); i++) {
                final int channels = layouts.position(i).nb_channels();
                if (channels == audio.channels()) {
                    avutil.av_channel_layout_copy(target, layouts);
                    return;
                }
                offered.append(offered.isEmpty() ? "" : ", ").append(channels);
            }
            throw new IllegalArgumentException(audio.codec() + " encodes " + offered + " channels, not " + audio.channels());
        }
    }

    private AVStream stream(final AVCodecContext context) throws IOException {
        final AVStream stream = avformat.avformat_new_stream(this.muxer, null);
        if (FFTool.isNull(stream)) throw new IOException("Failed to create a stream");
        stream.time_base(context.time_base());
        final int parameters = avcodec.avcodec_parameters_from_context(stream.codecpar(), context);
        if (parameters < 0) throw FFTool.failure("avcodec_parameters_from_context", parameters);
        return stream;
    }

    private AVFrame audioFrame(final int samples) throws IOException {
        final AVFrame frame = avutil.av_frame_alloc();
        if (FFTool.isNull(frame)) throw new IOException("Failed to allocate an audio frame");
        frame.format(this.sound.sample_fmt());
        frame.sample_rate(this.sound.sample_rate());
        frame.nb_samples(samples);
        final int copied = avutil.av_channel_layout_copy(frame.ch_layout(), this.sound.ch_layout());
        final int buffer = copied < 0 ? copied : avutil.av_frame_get_buffer(frame, 0);
        if (buffer < 0) {
            avutil.av_frame_free(frame);
            throw FFTool.failure("av_frame_get_buffer", buffer);
        }
        return frame;
    }

    // ==========================================================================
    // ENCODING
    // ==========================================================================
    // RESAMPLES count INPUT SAMPLES INTO THE FIFO; A NULL INPUT FLUSHES THE RESAMPLER'S DELAYED TAIL
    private void resample(final PointerPointer<BytePointer> input, final int count) throws IOException {
        final int capacity = swresample.swr_get_out_samples(this.resampler, count);
        if (capacity <= 0) return;
        if (capacity > this.resampled.nb_samples()) {
            avutil.av_frame_free(this.resampled);
            this.resampled = null;
            this.resampled = this.audioFrame(capacity);
        }
        final int converted = swresample.swr_convert(this.resampler, this.resampled.data(), capacity, input, count);
        if (converted < 0) throw FFTool.failure("swr_convert", converted);
        if (converted > 0 && avutil.av_audio_fifo_write(this.fifo, this.resampled.data(), converted) < converted)
            throw new IOException("Failed to queue the resampled audio");
    }

    // SENDS EVERY FULL CHUNK THE FIFO HOLDS; THE LAST ONE GOES SHORT OR PADDED WITH SILENCE WHEN drain IS SET
    private void sendChunks(final boolean drain) throws IOException {
        while (true) {
            final int queued = avutil.av_audio_fifo_size(this.fifo);
            if (queued == 0 || (queued < this.chunkSamples && !drain)) return;
            final int writable = avutil.av_frame_make_writable(this.chunk);
            if (writable < 0) throw FFTool.failure("av_frame_make_writable", writable);
            final int read = avutil.av_audio_fifo_read(this.fifo, this.chunk.data(), Math.min(queued, this.chunkSamples));
            if (read <= 0) throw new IOException("Failed to read the queued audio");
            int samples = read;
            if (read < this.chunkSamples && !this.shortLast) {
                avutil.av_samples_set_silence(this.chunk.data(), read, this.chunkSamples - read, this.sound.ch_layout().nb_channels(), this.sound.sample_fmt());
                samples = this.chunkSamples;
            }
            this.chunk.nb_samples(samples);
            this.chunk.pts(this.samplesSent);
            this.samplesSent += samples;
            this.send(this.sound, this.soundStream, this.chunk);
            this.chunk.nb_samples(this.chunkSamples);
        }
    }

    // SENDS A FRAME, OR NULL TO FLUSH, AND MUXES EVERY PACKET THE ENCODER HAS READY
    private void send(final AVCodecContext context, final AVStream stream, final AVFrame frame) throws IOException {
        final int sent = avcodec.avcodec_send_frame(context, frame);
        if (sent < 0 && !(frame == null && sent == avutil.AVERROR_EOF)) throw FFTool.failure("avcodec_send_frame", sent);
        final int again = avutil.AVERROR_EAGAIN();
        while (true) {
            final int received = avcodec.avcodec_receive_packet(context, this.packet);
            if (received == again || received == avutil.AVERROR_EOF) return;
            if (received < 0) throw FFTool.failure("avcodec_receive_packet", received);
            this.packet.stream_index(stream.index());
            avcodec.av_packet_rescale_ts(this.packet, context.time_base(), stream.time_base());
            final int wrote = avformat.av_interleaved_write_frame(this.muxer, this.packet); // TAKES AND UNREFS THE PACKET
            if (wrote < 0) throw FFTool.failure("av_interleaved_write_frame", wrote);
        }
    }

    // FREES THE WHOLE NATIVE PIPELINE AND THE TEMPORARY FILE; THE MUXER FILE IS CLOSED HERE WHEN finish() DID NOT
    private void free() {
        if (this.scaler != null) { swscale.sws_freeContext(this.scaler); this.scaler = null; }
        if (this.source != null) { avutil.av_frame_free(this.source); this.source = null; }
        if (this.converted != null) { avutil.av_frame_free(this.converted); this.converted = null; }
        if (this.picture != null) { avcodec.avcodec_free_context(this.picture); this.picture = null; }
        if (this.resampler != null) { swresample.swr_free(this.resampler); this.resampler = null; }
        if (this.fifo != null) { avutil.av_audio_fifo_free(this.fifo); this.fifo = null; }
        if (this.resampled != null) { avutil.av_frame_free(this.resampled); this.resampled = null; }
        if (this.chunk != null) { avutil.av_frame_free(this.chunk); this.chunk = null; }
        if (this.sound != null) { avcodec.avcodec_free_context(this.sound); this.sound = null; }
        if (this.packet != null) { avcodec.av_packet_free(this.packet); this.packet = null; }
        // A REFUSED avformat_alloc_output_context2 LEAVES A NULL CONTEXT BEHIND, WHOSE FIELDS CANNOT BE READ
        if (!FFTool.isNull(this.muxer)) {
            final AVIOContext pb = this.muxer.pb();
            if (!FFTool.isNull(pb)) { avformat.avio_close(pb); this.muxer.pb(null); }
            avformat.avformat_free_context(this.muxer);
        }
        this.muxer = null;
        if (this.tempFile != null) {
            try { Files.deleteIfExists(this.tempFile); }
            catch (final IOException e) { LOGGER.warn(IT, "Failed to delete the temporary encode {}", this.tempFile, e); }
            this.tempFile = null;
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static AVCodec encoder(final String codec, final String[] names) throws IOException {
        for (final String name: names) {
            final AVCodec found = avcodec.avcodec_find_encoder_by_name(name);
            if (!FFTool.isNull(found)) return found;
        }
        throw new IOException("No " + codec + " encoder in these natives (tried " + String.join(", ", names) + ")");
    }

    // READS ONE INTEGER LIST OF avcodec_get_supported_config; EMPTY WHEN THE CODEC TAKES ANY VALUE
    private static int[] supported(final AVCodecContext context, final AVCodec codec, final int config) {
        try (final PointerPointer<Pointer> list = new PointerPointer<>(1); final IntPointer count = new IntPointer(1)) {
            final int found = avcodec.avcodec_get_supported_config(context, codec, config, 0, list, count);
            final Pointer values = list.get(0);
            if (found < 0 || FFTool.isNull(values) || count.get() <= 0) return new int[0];
            final int[] out = new int[count.get()];
            new IntPointer(values).get(out);
            return out;
        }
    }

    // MAPS quality (0 SMALLEST .. 100 BEST) LINEARLY ONTO EACH ENCODER'S OWN CONSTANT-QUALITY SCALE
    private static void quality(final AVCodecContext context, final String name, final Video video, final long pixelsPerSecond) {
        final Pointer options = context.priv_data();
        final int q = video.quality();
        switch (name) {
            case "libx264", "libx265" -> {
                avutil.av_opt_set(options, "crf", String.valueOf(scale(q, 40, 12)), 0);
                avutil.av_opt_set(options, "preset", "veryfast", 0);
            }
            case "libopenh264" -> {
                // NO CONSTANT QUALITY MODE: A GENEROUS BITRATE TARGET UNDER A QUANTIZER CEILING
                final int ceiling = scale(q, 45, 18);
                context.qmax(ceiling);
                context.qmin(Math.max(1, ceiling - 12));
                context.bit_rate(Math.max(250_000L, (long) (pixelsPerSecond * (0.05 + 0.25 * q / 100.0))));
            }
            case "libkvazaar" -> avutil.av_opt_set(options, "kvazaar-params", "preset=veryfast,qp=" + scale(q, 42, 14), 0);
            case "libsvtav1" -> {
                avutil.av_opt_set(options, "crf", String.valueOf(scale(q, 50, 18)), 0);
                avutil.av_opt_set(options, "preset", "8", 0);
            }
            case "libaom-av1" -> {
                context.bit_rate(0);
                avutil.av_opt_set(options, "crf", String.valueOf(scale(q, 50, 18)), 0);
                avutil.av_opt_set(options, "cpu-used", "6", 0);
                avutil.av_opt_set(options, "row-mt", "1", 0);
            }
            case "libvpx-vp9" -> {
                context.bit_rate(0); // 0 BITRATE + CRF IS CONSTANT QUALITY
                avutil.av_opt_set(options, "crf", String.valueOf(scale(q, 50, 15)), 0);
                avutil.av_opt_set(options, "deadline", "good", 0);
                avutil.av_opt_set(options, "cpu-used", "4", 0);
                avutil.av_opt_set(options, "row-mt", "1", 0);
            }
            case "libvpx" -> {
                // VP8 CONSTANT QUALITY NEEDS A BITRATE CEILING NEXT TO THE CRF
                context.bit_rate(Math.max(500_000L, Math.min(50_000_000L, pixelsPerSecond / 2)));
                avutil.av_opt_set(options, "crf", String.valueOf(scale(q, 50, 10)), 0);
                avutil.av_opt_set(options, "deadline", "good", 0);
                avutil.av_opt_set(options, "cpu-used", "4", 0);
            }
            case "prores_ks" -> avutil.av_opt_set(options, "profile", q < 25 ? "proxy" : q < 50 ? "lt" : q < 75 ? "standard" : "hq", 0);
            default -> LOGGER.warn(IT, "No quality mapping for {}; it keeps its own defaults", name.toLowerCase(Locale.ROOT));
        }
    }

    // LINEAR MAP OF 0..100 ONTO [worst, best], ROUNDED
    private static int scale(final int quality, final int worst, final int best) {
        return Math.round(worst + (best - worst) * quality / 100f);
    }
}
