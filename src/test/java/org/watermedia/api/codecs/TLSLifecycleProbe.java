package org.watermedia.api.codecs;

import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.watermedia.WaterMedia;
import org.watermedia.tools.FFTool;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;

import static org.bytedeco.ffmpeg.global.avutil.av_dict_count;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_get;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_parse_string;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;

/** Verifies the native TLS bundle built from the JVM truststore, trusted and empty, in a fresh JVM each. */
public final class TLSLifecycleProbe {
    private TLSLifecycleProbe() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    public static void run(final Path root, final boolean empty) throws Exception {
        final TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        X509Certificate anchor = null;
        for (final var manager: factory.getTrustManagers()) {
            if (manager instanceof final X509TrustManager trust && trust.getAcceptedIssuers().length != 0) {
                anchor = trust.getAcceptedIssuers()[0];
                break;
            }
        }
        if (anchor == null) throw new AssertionError("The fixture requires a JVM certificate authority");
        final char[] password = "tls-fixture".toCharArray();
        final Path store = root.resolve(empty ? "empty.p12" : "trusted.p12");
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, password);
        if (!empty) {
            keys.setCertificateEntry("first", anchor);
            keys.setCertificateEntry("duplicate", anchor);
        }
        try (final var output = Files.newOutputStream(store)) { keys.store(output, password); }

        final var properties = new HashMap<String, String>();
        for (final String key: new String[] { "javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType", "javax.net.ssl.trustStorePassword" })
            properties.put(key, System.getProperty(key));
        try {
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
            System.setProperty("javax.net.ssl.trustStorePassword", new String(password));
            System.setProperty("javax.net.ssl.trustStore", store.toString());
            WaterMedia.start("TLS_TEST", root.resolve("tls"), root, true);
            final var options = new AVDictionary(null);
            try {
                if (empty) {
                    if (CodecsAPI.ffmpegLoaded() || !CodecsAPI.ffmpegError())
                        throw new AssertionError("An empty truststore did not refuse native HTTPS initialization");
                    // FFMPEG IS OPTIONAL: THE CODECS MODULE LOGS THE NATIVE FAILURE AND THE LOAD COMPLETES
                    if (!WaterMedia.loaded()) throw new AssertionError("The native failure ended the load");
                    try {
                        FFTool.configureTLS(options);
                        throw new AssertionError("A failed TLS initialization still configured a native connection");
                    } catch (final IllegalStateException expected) {}
                    return;
                }
                if (!CodecsAPI.ffmpegLoaded()) throw new AssertionError("Patched FFmpeg did not load");
                try {
                    FFTool.configureTLS(null);
                    throw new AssertionError("A null TLS dictionary reached the native boundary");
                } catch (final IllegalArgumentException expected) {}
                av_dict_set(options, "tls_verify", "0", 0);
                av_dict_set(options, "ca_file", "untrusted-fixture.pem", 0);
                av_dict_set(options, "verify", "0", 0);
                av_dict_set(options, "cafile", "untrusted-alias.pem", 0);
                av_dict_set(options, "verifyhost", "wrong.invalid", 0);
                av_dict_set(options, "protocol_opts", "tls_verify=0:ca_file=untrusted.pem:verify=0:cafile=untrusted-alias.pem", 0);
                FFTool.configureTLS(options);
                if (!"1".equals(av_dict_get(options, "tls_verify", null, 0).value().getString()))
                    throw new AssertionError("Input configuration preserved insecure TLS verification");
                for (final String alias: new String[] { "verify", "cafile", "verifyhost" }) {
                    final var entry = av_dict_get(options, alias, null, 0);
                    if (entry != null && !entry.isNull()) throw new AssertionError("An insecure TLS alias survived configuration");
                }
                final String ca = av_dict_get(options, "ca_file", null, 0).value().getString(StandardCharsets.UTF_8);
                final var nested = new AVDictionary(null);
                try {
                    final String protocols = av_dict_get(options, "protocol_opts", null, 0).value().getString(StandardCharsets.UTF_8);
                    if (av_dict_parse_string(nested, protocols, "=", ":", 0) < 0 || av_dict_count(nested) != 2
                            || !"1".equals(av_dict_get(nested, "tls_verify", null, 0).value().getString())
                            || !ca.equals(av_dict_get(nested, "ca_file", null, 0).value().getString(StandardCharsets.UTF_8)))
                        throw new AssertionError("Nested connections did not retain the exact trusted CA path and verification policy");
                } finally { av_dict_free(nested); }
                final Path bundle = Path.of(ca);
                if (!Files.isRegularFile(bundle)) throw new AssertionError("The TLS bundle is not a regular file");
                try (final var input = Files.newInputStream(bundle)) {
                    final var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
                    if (certificates.size() != 1 || !Arrays.equals(anchor.getEncoded(), certificates.iterator().next().getEncoded()))
                        throw new AssertionError("The PEM bundle did not match the JVM's deduplicated trust anchors");
                }
                // THE BUNDLE LIVES FOR THE WHOLE PROCESS: A LATER CONNECTION GETS THE SAME FILE
                FFTool.configureTLS(options);
                if (!Files.isRegularFile(bundle) || !ca.equals(av_dict_get(options, "ca_file", null, 0).value().getString(StandardCharsets.UTF_8)))
                    throw new AssertionError("A second configuration did not reuse the process bundle");
            } finally { av_dict_free(options); }
        } finally {
            for (final var entry: properties.entrySet()) {
                if (entry.getValue() == null) System.clearProperty(entry.getKey());
                else System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }
}
