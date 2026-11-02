package org.watermedia.tools;

import org.bytedeco.ffmpeg.avutil.AVClass;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.api.util.SampleType;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.util.Base64;
import java.util.TreeSet;

import static org.bytedeco.ffmpeg.global.avutil.*;

/** FFmpeg helpers shared by the codecs and the FFmpeg player: pixel formats, native errors and strings, native origin and TLS. */
public final class FFTool {
    /** 8-bit packed BGRA, the layout every scaler conversion produces. */
    public static final Pixels BGRA = new Pixels(PixelFormat.BGRA, 8);

    // NATIVE STATE, PUBLISHED ONCE WHEN FFMPEG STARTS AND KEPT FOR THE LIFE OF THE PROCESS
    private static volatile boolean bundled;
    private static volatile Certificates certificates;

    private FFTool() {}

    // ==========================================================================
    // PUBLIC API: FORMATS AND NATIVES
    // ==========================================================================
    /** Maps an FFmpeg pixel format to its engine layout, or null when no engine layout matches. */
    public static Pixels pixels(final int avPixFmt) {
        return switch (avPixFmt) {
            // 8-BIT PLANAR YUV
            case AV_PIX_FMT_YUV420P, AV_PIX_FMT_YUVJ420P -> new Pixels(PixelFormat.YUV420P, 8);
            case AV_PIX_FMT_YUV422P, AV_PIX_FMT_YUVJ422P -> new Pixels(PixelFormat.YUV422P, 8);
            case AV_PIX_FMT_YUV444P, AV_PIX_FMT_YUVJ444P -> new Pixels(PixelFormat.YUV444P, 8);
            // 8-BIT SEMI-PLANAR
            case AV_PIX_FMT_NV12 -> new Pixels(PixelFormat.NV12, 8);
            case AV_PIX_FMT_NV21 -> new Pixels(PixelFormat.NV21, 8);
            // 8-BIT PACKED RGB
            case AV_PIX_FMT_BGRA  -> BGRA;
            case AV_PIX_FMT_RGBA  -> new Pixels(PixelFormat.RGBA, 8);
            case AV_PIX_FMT_RGB24 -> new Pixels(PixelFormat.RGB, 8);
            // 8-BIT GRAYSCALE
            case AV_PIX_FMT_GRAY8 -> new Pixels(PixelFormat.GRAY, 8);
            // 8-BIT PACKED YUV
            case AV_PIX_FMT_YUYV422 -> new Pixels(PixelFormat.YUYV, 8);
            case AV_PIX_FMT_UYVY422 -> new Pixels(PixelFormat.UYVY, 8);
            // 8-BIT YUVA (4-PLANE)
            case AV_PIX_FMT_YUVA420P -> new Pixels(PixelFormat.YUVA420P, 8);
            case AV_PIX_FMT_YUVA422P -> new Pixels(PixelFormat.YUVA422P, 8);
            case AV_PIX_FMT_YUVA444P -> new Pixels(PixelFormat.YUVA444P, 8);
            // 10-BIT PLANAR YUV
            case AV_PIX_FMT_YUV420P10LE -> new Pixels(PixelFormat.YUV420P, 10);
            case AV_PIX_FMT_YUV422P10LE -> new Pixels(PixelFormat.YUV422P, 10);
            case AV_PIX_FMT_YUV444P10LE -> new Pixels(PixelFormat.YUV444P, 10);
            // 10-BIT YUVA
            case AV_PIX_FMT_YUVA420P10LE -> new Pixels(PixelFormat.YUVA420P, 10);
            case AV_PIX_FMT_YUVA422P10LE -> new Pixels(PixelFormat.YUVA422P, 10);
            case AV_PIX_FMT_YUVA444P10LE -> new Pixels(PixelFormat.YUVA444P, 10);
            // 12-BIT PLANAR YUV
            case AV_PIX_FMT_YUV420P12LE -> new Pixels(PixelFormat.YUV420P, 12);
            case AV_PIX_FMT_YUV422P12LE -> new Pixels(PixelFormat.YUV422P, 12);
            case AV_PIX_FMT_YUV444P12LE -> new Pixels(PixelFormat.YUV444P, 12);
            // 12-BIT YUVA
            case AV_PIX_FMT_YUVA422P12LE -> new Pixels(PixelFormat.YUVA422P, 12);
            case AV_PIX_FMT_YUVA444P12LE -> new Pixels(PixelFormat.YUVA444P, 12);
            // 16-BIT PLANAR YUV
            case AV_PIX_FMT_YUV420P16LE -> new Pixels(PixelFormat.YUV420P, 16);
            case AV_PIX_FMT_YUV422P16LE -> new Pixels(PixelFormat.YUV422P, 16);
            case AV_PIX_FMT_YUV444P16LE -> new Pixels(PixelFormat.YUV444P, 16);
            // 16-BIT YUVA
            case AV_PIX_FMT_YUVA420P16LE -> new Pixels(PixelFormat.YUVA420P, 16);
            case AV_PIX_FMT_YUVA422P16LE -> new Pixels(PixelFormat.YUVA422P, 16);
            case AV_PIX_FMT_YUVA444P16LE -> new Pixels(PixelFormat.YUVA444P, 16);
            // P010/P016: LEFT-SHIFTED 10/16-BIT NV12, MAPPED AS 16-BIT (bitScale=1.0 IS CORRECT)
            case AV_PIX_FMT_P010LE, AV_PIX_FMT_P016LE -> new Pixels(PixelFormat.NV12, 16);
            // HIGH-BIT GRAYSCALE
            case AV_PIX_FMT_GRAY10LE  -> new Pixels(PixelFormat.GRAY, 10);
            case AV_PIX_FMT_GRAY12LE  -> new Pixels(PixelFormat.GRAY, 12);
            case AV_PIX_FMT_GRAY16LE  -> new Pixels(PixelFormat.GRAY, 16);
            case AV_PIX_FMT_GRAYF32LE -> new Pixels(PixelFormat.GRAY, 32);
            // 16-BIT PACKED RGB
            case AV_PIX_FMT_RGB48LE  -> new Pixels(PixelFormat.RGB, 16);
            case AV_PIX_FMT_RGBA64LE -> new Pixels(PixelFormat.RGBA, 16);
            default -> null;
        };
    }

    /** Maps an 8-bit engine layout to its FFmpeg pixel format; block-compressed layouts have none. */
    public static int avPixelFormat(final PixelFormat format) {
        return switch (format) {
            case GRAY -> AV_PIX_FMT_GRAY8;
            case RGB -> AV_PIX_FMT_RGB24;
            case RGBA -> AV_PIX_FMT_RGBA;
            case BGRA -> AV_PIX_FMT_BGRA;
            case YUYV -> AV_PIX_FMT_YUYV422;
            case UYVY -> AV_PIX_FMT_UYVY422;
            case NV12 -> AV_PIX_FMT_NV12;
            case NV21 -> AV_PIX_FMT_NV21;
            case YUV420P -> AV_PIX_FMT_YUV420P;
            case YUV422P -> AV_PIX_FMT_YUV422P;
            case YUV444P -> AV_PIX_FMT_YUV444P;
            case YUVA420P -> AV_PIX_FMT_YUVA420P;
            case YUVA422P -> AV_PIX_FMT_YUVA422P;
            case YUVA444P -> AV_PIX_FMT_YUVA444P;
            default -> throw new IllegalArgumentException("No FFmpeg pixel format for " + format);
        };
    }

    /** Maps an FFmpeg sample format, packed or planar, to its PCM sample type, or null when it has none. */
    public static SampleType sampleType(final int avSampleFormat) {
        return switch (avSampleFormat) {
            case AV_SAMPLE_FMT_U8, AV_SAMPLE_FMT_U8P -> SampleType.U8;
            case AV_SAMPLE_FMT_S16, AV_SAMPLE_FMT_S16P -> SampleType.S16;
            case AV_SAMPLE_FMT_S32, AV_SAMPLE_FMT_S32P -> SampleType.S32;
            case AV_SAMPLE_FMT_FLT, AV_SAMPLE_FMT_FLTP -> SampleType.FLT;
            case AV_SAMPLE_FMT_DBL, AV_SAMPLE_FMT_DBLP -> SampleType.DBL;
            default -> null;
        };
    }

    /** Maps a PCM sample type to its packed (interleaved) FFmpeg sample format. */
    public static int avSampleFormat(final SampleType type) {
        return switch (type) {
            case U8 -> AV_SAMPLE_FMT_U8;
            case S16 -> AV_SAMPLE_FMT_S16;
            case S32 -> AV_SAMPLE_FMT_S32;
            case FLT -> AV_SAMPLE_FMT_FLT;
            case DBL -> AV_SAMPLE_FMT_DBL;
        };
    }

    /** Decodes a negative FFmpeg return code into its native message. */
    public static String error(final int code) {
        final byte[] detail = new byte[256];
        return av_strerror(code, detail, detail.length) >= 0 ? new String(detail, StandardCharsets.UTF_8).trim() : "unknown native error";
    }

    /** Creates an exception naming the failed FFmpeg operation, its native message and its code. */
    public static IOException failure(final String operation, final int code) {
        return new IOException(operation + " failed: " + error(code) + " (" + code + ")");
    }

    /** Returns whether a native pointer is missing or null. */
    public static boolean isNull(final Pointer pointer) { return pointer == null || pointer.isNull(); }

    /** Reads a native string, or returns {@code orElse} as text when the pointer is null. */
    public static String string(final BytePointer pointer, final Object orElse) {
        return !isNull(pointer) ? pointer.getString() : orElse != null ? String.valueOf(orElse) : null;
    }

    /** Returns a library version packed by {@code AV_VERSION_INT}, such as {@code avformat_version()}, as {@code major.minor.micro}. */
    public static String parseVersion(final int version) {
        return (version >> 16) + "." + ((version >> 8) & 0xFF) + "." + (version & 0xFF);
    }

    /** Returns whether the natives link the Vulkan bindings of libavutil; a JNI library built without them lacks their symbols. */
    public static boolean vulkanBindings() {
        try {
            final Pointer extensions = av_vk_get_optional_device_extensions(new IntPointer(1));
            if (isNull(extensions)) return false;
            av_free(extensions);
            return true;
        } catch (final RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Returns whether the loaded natives came from WaterMedia Binaries, whose FFmpeg exports its Vulkan decoder pools. */
    public static boolean bundled() { return bundled; }

    /** Records whether the loaded natives came from WaterMedia Binaries; the FFmpeg boot calls it once. */
    public static void bundled(final boolean value) { bundled = value; }

    // ==========================================================================
    // PUBLIC API: NATIVE TLS
    // ==========================================================================
    /** Exports the JVM trust anchors as the PEM bundle every input verifies against, after checking the patched transport options. */
    public static void startTLS() throws Exception {
        // HTTP MUST RETAIN TLS OPTIONS AND CREDENTIAL ORIGINS FOR NESTED MEDIA REQUESTS.
        for (final String protocol: new String[] { "http", "https", "hls", "dash" }) {
            final boolean http = protocol.equals("http") || protocol.equals("https");
            final AVClass type;
            if (http) {
                type = avformat.avio_protocol_get_class(protocol);
            } else {
                final var format = avformat.av_find_input_format(protocol);
                type = isNull(format) ? null : format.priv_class();
            }
            if (isNull(type)) throw new IllegalStateException("Required native transport is unavailable: " + protocol);
            try (final var object = new PointerPointer<AVClass>(1).put(type)) {
                for (final String option: http ? new String[] { "tls_verify", "ca_file", "header_origin" } : new String[] { "protocol_opts" }) {
                    final var definition = av_opt_find(object, option, null, 0, AV_OPT_SEARCH_FAKE_OBJ);
                    if (isNull(definition))
                        throw new IllegalStateException("FFmpeg lacks the required transport security patches; install the WaterMedia native build");
                }
            }
        }

        final TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        final var anchors = new TreeSet<String>();
        final var encoder = Base64.getMimeEncoder(64, new byte[] { '\n' });
        for (final var manager: factory.getTrustManagers()) {
            if (manager instanceof final X509TrustManager trust)
                for (final var certificate: trust.getAcceptedIssuers())
                    anchors.add(encoder.encodeToString(certificate.getEncoded()));
        }
        if (anchors.isEmpty()) throw new CertificateException("The JVM truststore exposes no certificate authorities");
        final var pem = new StringBuilder();
        for (final String certificate: anchors)
            pem.append("-----BEGIN CERTIFICATE-----\n").append(certificate).append("\n-----END CERTIFICATE-----\n");

        // THE BUNDLE LIVES AS LONG AS THE PROCESS; ONLY A COMPLETE ONE BECOMES AVAILABLE TO INPUTS
        final Path bundle = Files.createTempFile("watermedia-ca-", ".pem");
        bundle.toFile().deleteOnExit();
        Files.writeString(bundle, pem, StandardCharsets.US_ASCII);
        final String file = bundle.toString();
        final var nested = new AVDictionary(null);
        final var serialized = new BytePointer((Pointer) null);
        try {
            // NATIVE ESCAPING PRESERVES WINDOWS DRIVE LETTERS AND SPECIAL CHARACTERS IN THE CA PATH.
            if (av_dict_set(nested, "tls_verify", "1", 0) < 0 || av_dict_set(nested, "ca_file", file, 0) < 0
                    || av_dict_get_string(nested, serialized, (byte) '=', (byte) ':') < 0)
                throw new IllegalStateException("Cannot configure nested HTTPS verification");
            certificates = new Certificates(file, serialized.getString(StandardCharsets.UTF_8));
        } finally {
            av_free(serialized);
            av_dict_free(nested);
        }
    }

    /** Requires certificate and peer-name verification for an input and its nested connections. */
    public static void configureTLS(final AVDictionary options) {
        if (options == null) throw new IllegalArgumentException("Options are required");
        final Certificates current = certificates;
        if (current == null) throw new IllegalStateException("Native HTTPS verification has not initialized");
        if (av_dict_set(options, "verify", null, 0) < 0 || av_dict_set(options, "cafile", null, 0) < 0
                || av_dict_set(options, "verifyhost", null, 0) < 0
                || av_dict_set(options, "tls_verify", "1", 0) < 0 || av_dict_set(options, "ca_file", current.file(), 0) < 0
                || av_dict_set(options, "protocol_opts", current.protocols(), 0) < 0)
            throw new IllegalStateException("Cannot configure native HTTPS verification");
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** Engine pixel layout and sample precision of an FFmpeg pixel format. */
    public record Pixels(PixelFormat format, int bits) {}

    // THE PEM BUNDLE PATH AND THE SERIALIZED NESTED OPTIONS EVERY INPUT RECEIVES
    private record Certificates(String file, String protocols) {}
}
