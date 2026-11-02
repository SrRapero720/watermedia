package org.watermedia.test.support;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.Encoder;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.util.PixelFormat;

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

/** Catalog of on-disk test fixtures, resolved against the working directory (Gradle runs tests from the module root). */
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
    public static final Path JPEG_BASELINE = JPEG_DIR.resolve("1.jpg");
    public static final Path NETPBM_PPM = NETPBM_DIR.resolve("test.ppm");

    private Fixtures() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

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
        matroska(file, rate, seconds, defaults, titles, languages, levels);
    }

    /** Writes a Matroska file of one untitled, untagged mono 16-bit PCM stream filled with {@code level}: an HLS segment, as HLS refuses WAV ones. */
    public static void segment(final Path file, final int rate, final int seconds, final short level) throws IOException {
        matroska(file, rate, seconds, -1, new String[1], new String[1], new short[] { level });
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

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // WRITES ONE MONO S16 PCM STREAM PER LEVEL, EACH WITH ITS TITLE AND LANGUAGE WHEN NOT NULL; defaults FLAGS ONE DEFAULT
    private static void matroska(final Path file, final int rate, final int seconds, final int defaults, final String[] titles,
                                 final String[] languages, final short[] levels) throws IOException {
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
                    packet.stream_index(i).pts((long) p * samples).dts((long) p * samples).duration(samples);
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
