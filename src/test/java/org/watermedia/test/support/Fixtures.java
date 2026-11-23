package org.watermedia.test.support;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avcodec.AVPacketSideData;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.watermedia.api.codecs.AudioCodec;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.Encoder;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.api.util.SampleType;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Catalog of the on-disk test fixtures, resolved against the working directory (Gradle runs tests from the module
 * root), and writers of the media no file can hold: WAV tones and the Matroska, WebM and MPEG-TS clips FFmpeg muxes.
 */
public final class Fixtures {

    public static final Path RESOURCES = Path.of("src", "test", "resources");
    public static final Path PNG_DIR = RESOURCES.resolve("png");
    public static final Path JPEG_DIR = RESOURCES.resolve("jpeg");
    public static final Path GIF_DIR = RESOURCES.resolve("gif");
    public static final Path WEBP_LOSSLESS_DIR = RESOURCES.resolve("webp").resolve("lossless");
    public static final Path WEBP_LOSSY_DIR = RESOURCES.resolve("webp").resolve("lossy");
    public static final Path WEBP_ANIMATED_DIR = RESOURCES.resolve("webp").resolve("animated");
    public static final Path NETPBM_DIR = RESOURCES.resolve("netpbm");
    public static final Path SVG_DIR = RESOURCES.resolve("svg");
    private static final Path MP4_DIR = RESOURCES.resolve("mp4");
    /** Hostile corpus driving {@code PentestTest}; regenerate with {@link MaliciousImages}. */
    public static final Path PENTEST_DIR = RESOURCES.resolve("pentest");

    public static final Path MP4_H264 = MP4_DIR.resolve("fibonaccisongh264.mp4");
    /** Static 64x40 RGBA PNG with a translucent gradient: one IDAT, no acTL, no tRNS. */
    public static final Path PNG_STATIC = PNG_DIR.resolve("static.png");
    /** Real-world static PNG with trailing garbage after IEND (waterframes field-test upload). */
    public static final Path PNG_BROKEN = PNG_DIR.resolve("broken.png");
    public static final Path PNG_ANIMATED = PNG_DIR.resolve("1.png");
    public static final Path GIF_ANIMATED = GIF_DIR.resolve("1.gif");
    public static final Path WEBP_ANIMATED = WEBP_ANIMATED_DIR.resolve("1.webp");
    public static final Path WEBP_LOSSLESS = WEBP_LOSSLESS_DIR.resolve("1.webp");
    /** Progressive 600x800 JPEG with one component (grayscale). */
    public static final Path JPEG_GRAY = JPEG_DIR.resolve("1.jpg");
    public static final Path NETPBM_PPM = NETPBM_DIR.resolve("test.ppm");
    /** IPTV playlist of three channels with logos and groups. */
    public static final Path M3U_IPTV = RESOURCES.resolve("m3u").resolve("iptv_sample.m3u8");

    private Fixtures() {}

    /** Reads a fixture file, rethrowing IO failures unchecked for {@code @TestFactory} sites. */
    public static byte[] readAll(final Path path) {
        try { return Files.readAllBytes(path); }
        catch (final IOException e) { throw new UncheckedIOException("Failed to read fixture " + path, e); }
    }

    /** Returns a {@code file://} URI for a fixture, suitable for {@code MRL}/PlayerAPI. */
    public static URI fileUri(final Path path) {
        return path.toAbsolutePath().toUri();
    }

    /** Writes 16-bit little-endian PCM frames as a WAV file. */
    public static void wav(final Path file, final int sampleRate, final int channels, final byte[] pcm) throws IOException {
        try (final AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(pcm),
                new AudioFormat(sampleRate, 16, channels, true, false), pcm.length / (2L * channels))) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, file.toFile());
        }
    }

    /** Writes 16-bit little-endian PCM frames as a WAVE_FORMAT_EXTENSIBLE file whose channel {@code mask} (FFmpeg AV_CH_* bits) names the layout. */
    public static void wav(final Path file, final int sampleRate, final int channels, final long mask, final byte[] pcm) throws IOException {
        final ByteBuffer header = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(60 + pcm.length).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(40).putShort((short) 0xFFFE).putShort((short) channels)
                .putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
                .putShort((short) 22).putShort((short) 16).putInt((int) mask);
        // KSDATAFORMAT_SUBTYPE_PCM
        header.put(new byte[] { 1, 0, 0, 0, 0, 0, 0x10, 0, (byte) 0x80, 0, 0, (byte) 0xAA, 0, 0x38, (byte) 0x9B, 0x71 });
        header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        try (final OutputStream out = Files.newOutputStream(file)) {
            out.write(header.array());
            out.write(pcm);
        }
    }

    /** Returns {@code samples} mono 16-bit little-endian PCM samples that all hold {@code level}, a value a test can recognize. */
    public static byte[] tone(final short level, final int samples) {
        final ByteBuffer pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) pcm.putShort(level);
        return pcm.array();
    }

    /** Returns the sample value {@link #dubbed} fills stream {@code index} with. */
    public static short level(final int index) { return (short) ((index + 1) * 1_000); }

    /**
     * Writes a Matroska file of mono 16-bit PCM streams, one per entry of {@code languages} (a null entry leaves its stream
     * untagged), each titled {@code "Audio <index>"} and filled with {@link #level(int)}; the stream at {@code defaults}
     * carries the default disposition (-1 for none). Needs the FFmpeg natives.
     */
    public static void dubbed(final Path file, final int rate, final int seconds, final int defaults, final String... languages) throws IOException {
        final String[] titles = new String[languages.length];
        final short[] levels = new short[languages.length];
        for (int i = 0; i < languages.length; i++) {
            titles[i] = "Audio " + i;
            levels[i] = level(i);
        }
        matroska(file, rate, seconds, defaults, titles, languages, levels, -1, 0);
    }

    /** Writes a Matroska file of one untitled, untagged mono 16-bit PCM stream filled with {@code level}: an HLS segment, as HLS refuses WAV ones. */
    public static void segment(final Path file, final int rate, final int seconds, final short level) throws IOException {
        matroska(file, rate, seconds, -1, new String[1], new String[1], new short[] { level }, -1, 0);
    }

    /** Writes {@link #segment} with a hole: after {@code holeAt} seconds of sound its timestamps skip {@code hole} seconds, as a stream that lost a part does. */
    public static void holed(final Path file, final int rate, final int seconds, final int holeAt, final int hole, final short level) throws IOException {
        matroska(file, rate, seconds, -1, new String[1], new String[1], new short[] { level }, holeAt * 10, hole * 10);
    }

    /** Copies the video of {@code source} into an MP4 whose display matrix turns it by {@code degrees}, as phones record; needs the FFmpeg natives. */
    public static void rotated(final Path source, final Path file, final double degrees) throws IOException {
        final String path = file.toAbsolutePath().toString();
        final AVFormatContext input = new AVFormatContext(null);
        if (avformat.avformat_open_input(input, source.toAbsolutePath().toString(), null, null) < 0) throw new IOException("Cannot read " + source);
        final AVFormatContext muxer = new AVFormatContext(null);
        final AVPacket packet = avcodec.av_packet_alloc();
        try {
            if (avformat.avformat_find_stream_info(input, (PointerPointer<?>) null) < 0) throw new IOException("No streams in " + source);
            int video = -1;
            for (int i = 0; i < input.nb_streams(); i++) if (input.streams(i).codecpar().codec_type() == avutil.AVMEDIA_TYPE_VIDEO) video = i;
            if (video < 0) throw new IOException("No video in " + source);
            if (avformat.avformat_alloc_output_context2(muxer, null, "mp4", path) < 0 || muxer.isNull()) throw new IOException("No MP4 muxer");
            final AVStream stream = avformat.avformat_new_stream(muxer, null);
            if (avcodec.avcodec_parameters_copy(stream.codecpar(), input.streams(video).codecpar()) < 0) throw new IOException("MP4 refused the stream");
            stream.codecpar().codec_tag(0);
            stream.time_base(input.streams(video).time_base());

            // THE 3x3 MATRIX OF 32-BIT FIXED POINT NUMBERS GOES INTO THE STREAM'S SIDE DATA, WHICH OWNS IT ONCE ADDED
            final IntPointer matrix = new IntPointer(avutil.av_malloc(36));
            avutil.av_display_rotation_set(matrix, degrees);
            final AVPacketSideData list = stream.codecpar().coded_side_data() == null ? new AVPacketSideData(null) : stream.codecpar().coded_side_data();
            final IntPointer count = new IntPointer(1).put(stream.codecpar().nb_coded_side_data());
            if (avcodec.av_packet_side_data_add(list, count, avcodec.AV_PKT_DATA_DISPLAYMATRIX, matrix, 36, 0) == null) throw new IOException("No room for the display matrix");
            stream.codecpar().coded_side_data(list).nb_coded_side_data(count.get());

            final AVIOContext pb = new AVIOContext(null);
            if (avformat.avio_open(pb, path, avformat.AVIO_FLAG_WRITE) < 0) throw new IOException("Cannot write " + file);
            muxer.pb(pb);
            if (avformat.avformat_write_header(muxer, (AVDictionary) null) < 0) throw new IOException("MP4 refused the header");
            while (avformat.av_read_frame(input, packet) >= 0) {
                if (packet.stream_index() == video) {
                    packet.stream_index(0).pos(-1);
                    avcodec.av_packet_rescale_ts(packet, input.streams(video).time_base(), stream.time_base());
                    if (avformat.av_interleaved_write_frame(muxer, packet) < 0) throw new IOException("MP4 refused a packet");
                }
                avcodec.av_packet_unref(packet);
            }
            if (avformat.av_write_trailer(muxer) < 0) throw new IOException("MP4 refused the trailer");
        } finally {
            avcodec.av_packet_free(packet);
            avformat.avformat_close_input(input);
            if (!muxer.isNull()) {
                final AVIOContext pb = muxer.pb();
                if (pb != null && !pb.isNull()) avformat.avio_close(pb);
                muxer.pb(null);
                avformat.avformat_free_context(muxer);
            }
        }
    }

    /** Encodes a moving BGRA gradient into a VP9 WebM through {@link CodecsAPI#encoder}; needs the FFmpeg natives. */
    public static byte[] webm(final int width, final int height, final int frames, final int fps) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (final Encoder writer = CodecsAPI.encoder(Container.WEBM, out, new Encoder.Video(VideoCodec.VP9, width, height, PixelFormat.BGRA, fps), null)) {
            final ByteBuffer frame = ByteBuffer.allocateDirect(width * height * 4);
            // CONSECUTIVE FRAMES DIFFER SO VP9 HAS REAL CONTENT TO CODE
            for (int f = 0; f < frames; f++) {
                frame.clear();
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    frame.put((byte) ((x + f * 4) & 0xFF));      // B
                    frame.put((byte) ((y + f * 4) & 0xFF));      // G
                    frame.put((byte) ((x + y + f * 8) & 0xFF));  // R
                    frame.put((byte) 0xFF);                       // A
                }
                frame.flip();
                writer.video(frame, f * 1_000_000L / fps);
            }
        }
        return out.toByteArray();
    }

    /** Encodes a moving BGRA gradient and a 440 Hz stereo tone at 48 kHz through {@link CodecsAPI#encoder}, a null codec leaving its track out; needs the FFmpeg natives. */
    public static void clip(final Path file, final Container container, final VideoCodec video, final AudioCodec audio,
                            final int width, final int height, final int fps, final int seconds) throws IOException {
        final Encoder.Video picture = video == null ? null : new Encoder.Video(video, width, height, PixelFormat.BGRA, fps);
        final Encoder.Audio sound = audio == null ? null : new Encoder.Audio(audio, 48_000, 2, SampleType.S16);
        try (final Encoder writer = CodecsAPI.encoder(container, file, picture, sound)) {
            final ByteBuffer frame = picture == null ? null : ByteBuffer.allocateDirect(width * height * 4);
            long sent = 0;
            // AUDIO FOLLOWS EVERY FRAME, LIKE A CAPTURE WOULD INTERLEAVE IT
            for (int f = 0; f < seconds * fps; f++) {
                if (picture != null) {
                    frame.clear();
                    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                        frame.put((byte) ((x + f * 4) & 0xFF));      // B
                        frame.put((byte) ((y + f * 4) & 0xFF));      // G
                        frame.put((byte) ((x + y + f * 8) & 0xFF));  // R
                        frame.put((byte) 0xFF);                       // A
                    }
                    frame.flip();
                    writer.video(frame, f * 1_000_000L / fps);
                }
                if (sound != null) {
                    final long until = 48_000L * (f + 1) / fps;
                    final ByteBuffer pcm = ByteBuffer.allocate((int) (until - sent) * 4).order(ByteOrder.LITTLE_ENDIAN);
                    for (long s = sent; s < until; s++) {
                        final short value = (short) (0.25 * Math.sin(2 * Math.PI * 440 * s / 48_000.0) * Short.MAX_VALUE);
                        pcm.putShort(value).putShort(value);
                    }
                    writer.audio(pcm.flip());
                    sent = until;
                }
            }
        }
    }

    /** Writes one MPEG-2 video of fresh noise as {@code count} MPEG-TS files {@code segment<i>.ts}, cut the way an HLS segmenter cuts it; needs the FFmpeg natives. */
    public static List<Path> transportSegments(final Path directory, final int width, final int height, final int fps,
                                               final int seconds, final int count, final int qscale) throws IOException {
        // INTRA-ONLY, SO TIMESTAMPS AND CONTINUITY COUNTERS RUN ON ACROSS THE FILES AND EACH ONE OPENS WITH ITS TABLES;
        // A LOWER qscale (2..31) MAKES LARGER FRAMES
        final AVCodec codec = avcodec.avcodec_find_encoder(avcodec.AV_CODEC_ID_MPEG2VIDEO);
        if (codec == null || codec.isNull()) throw new IOException("No MPEG-2 encoder");
        final AVCodecContext encoder = avcodec.avcodec_alloc_context3(codec);
        final AVFormatContext muxer = new AVFormatContext(null);
        final AVFrame frame = avutil.av_frame_alloc();
        final AVPacket packet = avcodec.av_packet_alloc();
        final List<Path> files = new ArrayList<>();
        for (int i = 0; i < count; i++) files.add(directory.resolve("segment" + i + ".ts"));
        try {
            // EVERY FRAME A KEYFRAME AT A FIXED QUANTIZER: A SEGMENT OPENS ON ONE WHEREVER IT IS CUT, AND qscale SETS ITS SIZE
            encoder.width(width).height(height).pix_fmt(avutil.AV_PIX_FMT_YUV420P).time_base(avutil.av_make_q(1, fps))
                    .framerate(avutil.av_make_q(fps, 1)).gop_size(1).max_b_frames(0)
                    .flags(encoder.flags() | avcodec.AV_CODEC_FLAG_QSCALE).global_quality(qscale * avutil.FF_QP2LAMBDA);
            if (avcodec.avcodec_open2(encoder, codec, (PointerPointer<?>) null) < 0) throw new IOException("The MPEG-2 encoder refused the picture");
            frame.format(avutil.AV_PIX_FMT_YUV420P).width(width).height(height);
            if (avutil.av_frame_get_buffer(frame, 0) < 0) throw new IOException("No memory for a picture");
            if (avformat.avformat_alloc_output_context2(muxer, null, "mpegts", files.get(0).toAbsolutePath().toString()) < 0 || muxer.isNull())
                throw new IOException("No MPEG-TS muxer");
            final AVStream stream = avformat.avformat_new_stream(muxer, null);
            if (avcodec.avcodec_parameters_from_context(stream.codecpar(), encoder) < 0) throw new IOException("MPEG-TS refused the stream");
            stream.time_base(encoder.time_base());

            final SplittableRandom random = new SplittableRandom(275);
            final byte[] row = new byte[width];
            final int frames = fps * seconds;
            int open = -1; // THE SEGMENT WHOSE FILE THE MUXER WRITES
            for (int f = 0; f <= frames * count; f++) {
                // THE LAST PASS SENDS NO PICTURE, WHICH FLUSHES THE ENCODER
                final boolean last = f == frames * count;
                if (!last) {
                    if (avutil.av_frame_make_writable(frame) < 0) throw new IOException("The picture stays read-only");
                    for (int plane = 0; plane < 3; plane++) {
                        final BytePointer data = frame.data(plane);
                        final int columns = plane == 0 ? width : (width + 1) / 2;
                        final int rows = plane == 0 ? height : (height + 1) / 2;
                        for (int y = 0; y < rows; y++) {
                            random.nextBytes(row);
                            data.position((long) y * frame.linesize(plane)).put(row, 0, columns);
                        }
                    }
                    frame.pts(f).quality(qscale * avutil.FF_QP2LAMBDA);
                }
                if (avcodec.avcodec_send_frame(encoder, last ? null : frame) < 0) throw new IOException("The MPEG-2 encoder refused a picture");
                while (avcodec.avcodec_receive_packet(encoder, packet) >= 0) {
                    // THE FIRST PACKET OF A SEGMENT CLOSES THE FILE BEFORE IT; THE MUXER GOES ON IN THE NEXT ONE, TABLES FIRST
                    final int segment = (int) (packet.pts() / frames);
                    if (segment != open) {
                        if (open >= 0) {
                            avformat.av_write_frame(muxer, null);
                            avformat.avio_close(muxer.pb());
                            muxer.pb(null);
                        }
                        final AVIOContext pb = new AVIOContext(null);
                        if (avformat.avio_open(pb, files.get(segment).toAbsolutePath().toString(), avformat.AVIO_FLAG_WRITE) < 0)
                            throw new IOException("Cannot write " + files.get(segment));
                        muxer.pb(pb);
                        if (open < 0 && avformat.avformat_write_header(muxer, (AVDictionary) null) < 0) throw new IOException("MPEG-TS refused the header");
                        if (open >= 0) avutil.av_opt_set(muxer.priv_data(), "mpegts_flags", "resend_headers", 0);
                        open = segment;
                    }
                    packet.stream_index(0);
                    avcodec.av_packet_rescale_ts(packet, encoder.time_base(), stream.time_base());
                    final int wrote = avformat.av_write_frame(muxer, packet);
                    avcodec.av_packet_unref(packet);
                    if (wrote < 0) throw new IOException("MPEG-TS refused a packet");
                }
            }
            if (avformat.av_write_trailer(muxer) < 0) throw new IOException("MPEG-TS refused the trailer");
            return files;
        } finally {
            avcodec.av_packet_free(packet);
            avutil.av_frame_free(frame);
            avcodec.avcodec_free_context(encoder);
            if (!muxer.isNull()) {
                final AVIOContext pb = muxer.pb();
                if (pb != null && !pb.isNull()) avformat.avio_close(pb);
                muxer.pb(null);
                avformat.avformat_free_context(muxer);
            }
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // WRITES ONE MONO S16 PCM STREAM PER LEVEL, EACH WITH ITS TITLE AND LANGUAGE WHEN NOT NULL; defaults FLAGS ONE DEFAULT.
    // FROM PACKET holeAt ON (-1 FOR NEVER) THE TIMESTAMPS RUN hole PACKETS LATE
    private static void matroska(final Path file, final int rate, final int seconds, final int defaults, final String[] titles,
                                 final String[] languages, final short[] levels, final int holeAt, final int hole) throws IOException {
        final String path = file.toAbsolutePath().toString();
        final AVFormatContext muxer = new AVFormatContext(null);
        if (avformat.avformat_alloc_output_context2(muxer, null, "matroska", path) < 0 || muxer.isNull())
            throw new IOException("No Matroska muxer");
        final AVPacket packet = avcodec.av_packet_alloc();
        try {
            final AVRational clock = avutil.av_make_q(1, rate);
            for (int i = 0; i < levels.length; i++) {
                final AVStream stream = avformat.avformat_new_stream(muxer, null);
                stream.codecpar().codec_type(avutil.AVMEDIA_TYPE_AUDIO).codec_id(avcodec.AV_CODEC_ID_PCM_S16LE).sample_rate(rate)
                        .format(avutil.AV_SAMPLE_FMT_S16).bits_per_coded_sample(16).block_align(2);
                avutil.av_channel_layout_default(stream.codecpar().ch_layout(), 1);
                stream.time_base(clock);
                stream.disposition(i == defaults ? avformat.AV_DISPOSITION_DEFAULT : 0);
                final AVDictionary metadata = new AVDictionary(null);
                if (titles[i] != null) avutil.av_dict_set(metadata, "title", titles[i], 0);
                if (languages[i] != null) avutil.av_dict_set(metadata, "language", languages[i], 0);
                stream.metadata(metadata);
            }
            final AVIOContext pb = new AVIOContext(null);
            if (avformat.avio_open(pb, path, avformat.AVIO_FLAG_WRITE) < 0) throw new IOException("Cannot write " + file);
            muxer.pb(pb);
            if (avformat.avformat_write_header(muxer, (AVDictionary) null) < 0) throw new IOException("Matroska refused the streams");

            // 100 ms PACKETS, INTERLEAVED ACROSS THE STREAMS
            final int samples = rate / 10;
            for (int p = 0; p < seconds * 10; p++) {
                for (int i = 0; i < levels.length; i++) {
                    if (avcodec.av_new_packet(packet, samples * 2) < 0) throw new IOException("No memory for a packet");
                    final ByteBuffer data = packet.data().limit(samples * 2L).asBuffer().order(ByteOrder.LITTLE_ENDIAN);
                    for (int s = 0; s < samples; s++) data.putShort(levels[i]);
                    final long stamp = (long) (p + (holeAt >= 0 && p >= holeAt ? hole : 0)) * samples;
                    packet.stream_index(i).pts(stamp).dts(stamp).duration(samples);
                    avcodec.av_packet_rescale_ts(packet, clock, muxer.streams(i).time_base());
                    if (avformat.av_interleaved_write_frame(muxer, packet) < 0) throw new IOException("Matroska refused a packet");
                }
            }
            if (avformat.av_write_trailer(muxer) < 0) throw new IOException("Matroska refused the trailer");
        } finally {
            avcodec.av_packet_free(packet);
            final AVIOContext pb = muxer.pb();
            if (pb != null && !pb.isNull()) avformat.avio_close(pb);
            muxer.pb(null);
            avformat.avformat_free_context(muxer);
        }
    }
}
