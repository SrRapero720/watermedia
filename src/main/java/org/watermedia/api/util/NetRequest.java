package org.watermedia.api.util;

import com.google.gson.JsonSyntaxException;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.JSONTool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * One HTTP, FTP or {@code file:} request and, once {@link #send() sent}, its response. Configure it, send it
 * and read the body on one thread; a request is sent once.
 * <p>
 * Redirects (3xx) are followed by hand up to {@link WaterMediaConfig.Network#maxRedirects}, so an http and
 * https switch works although {@link HttpURLConnection} refuses it, while a hop to any other scheme
 * ({@code file:}, {@code jar:}, {@code ftp:}) fails and a server can never point a request at local resources.
 * Credentials and bodies stay with the original origin: a body is resent to another origin only when set
 * with {@code crossOrigin}, otherwise that redirect fails.
 */
public final class NetRequest implements AutoCloseable {
    private static final Marker IT = MarkerManager.getMarker(NetRequest.class.getSimpleName());

    /** Content type of a JSON body. */
    public static final String MIMETYPE_JSON = "application/json";
    /** Content type of an HTML form body. */
    public static final String MIMETYPE_FORM = "application/x-www-form-urlencoded";
    /** Content type of an opaque binary body. */
    public static final String MIMETYPE_OCTET_STREAM = "application/octet-stream";

    /** Accept value taking any response. */
    public static final String ACCEPT_ANY = "*/*";
    /** Accept value for playable media, images and the HLS and DASH manifests that describe them. */
    public static final String ACCEPT_MEDIA = "audio/*, video/*, image/*, application/vnd.apple.mpegurl, application/x-mpegurl, application/dash+xml, application/ogg, */*;q=0.8";
    /** Accept value for a JSON response. */
    public static final String ACCEPT_JSON = "application/json";

    // A TRICKLING SERVER CAN KEEP EVERY READ UNDER THE SOCKET TIMEOUT, SO A TEXT BODY GETS THIS MANY READ TIMEOUTS IN TOTAL
    private static final int TEXT_DEADLINE_READ_TIMEOUTS = 4;

    // REQUEST
    private final URI uri;
    private final HttpHeaders headers = new HttpHeaders();
    private String method = "GET";
    private byte[] body;
    private boolean crossOrigin;
    private int connectTimeout = WaterMediaConfig.network.timeout;
    private int readTimeout = WaterMediaConfig.network.timeout;
    private int maxRedirects = WaterMediaConfig.network.maxRedirects;
    private boolean httpsOnly;
    private long maxBytes = -1L;
    private long deadlineMs;

    // RESPONSE
    // FILLED BY send(); THE RESPONSE ACCESSORS THROW UNTIL THEN
    private URLConnection connection;
    private URI location;
    private int statusCode;
    private long deadlineNs;
    private HttpHeaders responseHeaders;
    private boolean consumed;

    /** Creates a GET request with the WaterMedia User-Agent, {@link #ACCEPT_ANY} and the configured network limits. */
    public NetRequest(final URI uri) {
        if (uri == null) throw new IllegalArgumentException("URI cannot be null");
        this.uri = uri;
        // THE DEFAULTS LIVE IN THE BAG, SO header(), headers() AND THE PRESETS REPLACE THEM IN CALL ORDER
        this.headers.set("Accept", ACCEPT_ANY).set("User-Agent", UserAgent.WATERMEDIA.value);
    }

    /** Creates a request for URI text; invalid syntax throws {@link IllegalArgumentException}. */
    public NetRequest(final String uri) {
        this(URI.create(uri));
    }

    // ==========================================================================
    // PUBLIC API: CONFIGURATION
    // ==========================================================================
    /** Sets the HTTP method, uppercased; the connection validates it on {@link #send()}. */
    public NetRequest method(final String method) { this.method = method.toUpperCase(Locale.ROOT); return this; }
    /** Replaces the {@code Content-Type} header. */
    public NetRequest contentType(final String contentType) { this.headers.set("Content-Type", contentType); return this; }
    /** Replaces the {@code Accept} header. */
    public NetRequest accept(final String accept) { this.headers.set("Accept", accept); return this; }
    /** Replaces the {@code Referer} header, overriding the derived default. */
    public NetRequest referer(final String referer) { this.headers.set("Referer", referer); return this; }
    /** Replaces the {@code User-Agent} header with a preset; the last writer of that header wins. */
    public NetRequest userAgent(final UserAgent userAgent) { this.headers.set("User-Agent", userAgent.value); return this; }
    /** Replaces every value of a header; names must be HTTP tokens and values free of control characters. */
    public NetRequest header(final String name, final String value) { this.headers.set(name, value); return this; }

    /** Applies {@code headers}: the first value of each name replaces the current ones (like {@link #header}), later values append; null or empty changes nothing. */
    public NetRequest headers(final HttpHeaders headers) { this.headers.merge(headers); return this; }

    /** Sets the body without copying, or clears it with null; redirects resend it only to the original origin. */
    public NetRequest body(final byte[] body) { return this.body(body, false); }
    /** Sets the body as UTF-8 text, or clears it with null; redirects resend it only to the original origin. */
    public NetRequest body(final String body) { return this.body(body, false); }
    /** Sets the body without copying, or clears it with null; {@code crossOrigin} lets redirects resend it to other origins. */
    public NetRequest body(final byte[] body, final boolean crossOrigin) { this.body = body; this.crossOrigin = crossOrigin; return this; }
    /** Sets the body as UTF-8 text, or clears it with null; {@code crossOrigin} lets redirects resend it to other origins. */
    public NetRequest body(final String body, final boolean crossOrigin) { return this.body(body == null ? null : body.getBytes(StandardCharsets.UTF_8), crossOrigin); }
    /** Sets the connect timeout in milliseconds; zero waits forever and negatives fail on {@link #send()}. */
    public NetRequest connectTimeout(final int ms) { this.connectTimeout = ms; return this; }
    /** Sets the read timeout in milliseconds, which also sizes the total text deadline; zero waits forever and negatives fail on {@link #send()}. */
    public NetRequest readTimeout(final int ms) { this.readTimeout = ms; return this; }
    /** Sets how many redirects {@link #send()} may follow; zero or less rejects the first one. */
    public NetRequest maxRedirects(final int n) { this.maxRedirects = n; return this; }
    /** Requires https without credentials on the request and on every redirect hop. */
    public NetRequest httpsOnly() { this.httpsOnly = true; return this; }
    /** Caps the body {@link #text()}, {@link #json(Class)} and {@link #download(Path, String)} accept. */
    public NetRequest maxBytes(final long bytes) {
        if (bytes < 1) throw new IllegalArgumentException("The byte limit must be positive");
        this.maxBytes = bytes;
        return this;
    }
    /** Sets a total deadline in milliseconds covering the redirect chain and the body transfer. */
    public NetRequest deadline(final long ms) {
        if (ms < 1) throw new IllegalArgumentException("The deadline must be positive");
        this.deadlineMs = ms;
        return this;
    }

    // ==========================================================================
    // PUBLIC API: SEND
    // ==========================================================================
    /** Opens the connection, writes the body and follows redirects by hand, failing on a hop outside http(s) or past {@link #maxRedirects}; the response accessors then answer for the final hop. */
    public NetRequest send() throws IOException {
        if (this.connection != null) throw new IllegalStateException("The request was already sent");
        URI current = this.uri;
        String method = this.method;
        byte[] body = this.body;
        int hops = 0;
        final long deadline = this.deadlineMs > 0 ? System.nanoTime() + this.deadlineMs * 1_000_000L : 0L;

        while (true) {
            // EVERY HOP IS CHECKED, SO A REDIRECT CAN NEITHER DOWNGRADE A VERIFIED TRANSFER NOR OUTLIVE ITS DEADLINE
            if (this.httpsOnly && (!"https".equalsIgnoreCase(current.getScheme()) || current.getHost() == null || current.getUserInfo() != null))
                throw new IOException("HTTPS without credentials is required: " + current);
            if (deadline > 0 && System.nanoTime() > deadline) throw new IOException("Transfer deadline exceeded: " + this.uri);

            final URLConnection conn = this.open(current, method, body);
            if (!(conn instanceof final HttpURLConnection http)) {
                // getContentType() ONLY GUESSES FROM THE NAME: WITHOUT A REAL CONNECT A MISSING file:// WOULD LOOK LIKE VALID MEDIA
                conn.connect();
                return this.receive(current, conn, HttpURLConnection.HTTP_OK, deadline);
            }

            final int code = http.getResponseCode();
            final boolean redirect = code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308;
            if (!redirect) return this.receive(current, http, code, deadline);
            if (hops >= this.maxRedirects) {
                http.disconnect();
                throw new IOException("Too many redirects (" + this.maxRedirects + " hops) for " + this.uri);
            }

            // A 3xx WITHOUT A USABLE Location IS THE RESPONSE ITSELF
            final String location = http.getHeaderField("Location");
            final URI next;
            try {
                next = location == null || location.isEmpty() ? null : current.resolve(new URI(location));
            } catch (final URISyntaxException malformed) {
                return this.receive(current, http, code, deadline);
            }
            if (next == null) return this.receive(current, http, code, deadline);
            http.disconnect();

            // ONLY THE INITIAL REQUEST MAY BE LOCAL: A SERVER-CHOSEN HOP OUTSIDE HTTP(S) WOULD READ LOCAL FILES OR ARCHIVES
            if (!IOTool.web(next)) throw new IOException("Redirect to unsupported scheme: " + next.getScheme() + " (from " + this.uri + ")");

            // RFC 9110: A 303 SEE OTHER IS RE-REQUESTED AS GET WITHOUT THE ORIGINAL BODY
            if (code == HttpURLConnection.HTTP_SEE_OTHER) {
                method = "GET";
                body = null;
            } else if (body != null && body.length > 0 && !this.crossOrigin && !sameOrigin(this.uri, next)) {
                throw new IOException("Redirect to another origin would resend the request body: " + next);
            }

            // THE HOPS ARE OFTEN SIGNED CDN LINKS, SO THEY ONLY REACH THE REDACTING LOGGER, NEVER AN EXCEPTION
            LOGGER.debug(IT, "Redirect {} of {}: {} -> {}", hops + 1, this.uri, current, next);
            current = next;
            hops++;
        }
    }

    // ==========================================================================
    // PUBLIC API: RESPONSE
    // ==========================================================================
    /** The URI the response came from, after any followed redirects; the request's own URI before {@link #send()}. */
    public URI uri() { return this.location != null ? this.location : this.uri; }

    /** Returns the HTTP status code; {@code file:} and {@code ftp:} answer 200, so one {@code != 200} check fits every protocol. */
    public int statusCode() { this.connection(); return this.statusCode; }

    /** Returns the reported content type, or null when unavailable. */
    public String contentType() { return this.connection().getContentType(); }

    /** Returns the reported content length in bytes, or -1 when unknown. */
    public long contentLength() { return this.connection().getContentLengthLong(); }

    /** Shortcut for {@code responseHeaders().value(name)}. */
    public String header(final String name) { return this.responseHeaders().value(name); }

    /** Headers received from the server, snapshotted when the response arrived. */
    public HttpHeaders responseHeaders() { this.connection(); return this.responseHeaders; }

    /** Returns the raw body stream for the caller to close, the error body (or an empty stream) on a 400+ status; the body reads once through this, {@link #text()} or {@link #json(Class)}. */
    public InputStream inputStream() throws IOException {
        final URLConnection connection = this.connection();
        if (this.consumed) throw new IllegalStateException("The response body was already read");
        this.consumed = true;
        if (this.statusCode >= 400 && connection instanceof final HttpURLConnection http) {
            final InputStream error = http.getErrorStream();
            return error != null ? error : InputStream.nullInputStream();
        }
        return connection.getInputStream();
    }

    /** Reads the whole body as UTF-8, error bodies included, failing past {@link #maxBytes} (else {@code network.maxTextBytes}) or {@link #deadline} (else four read timeouts, none at zero). */
    public String text() throws IOException {
        try (final InputStream in = this.inputStream()) {
            final long limit = this.maxBytes > 0 ? this.maxBytes : WaterMediaConfig.network.maxTextBytes;
            final long deadlineMs = this.deadlineNs > 0 ? Math.max(1L, (this.deadlineNs - System.nanoTime()) / 1_000_000L)
                    : (long) TEXT_DEADLINE_READ_TIMEOUTS * this.connection.getReadTimeout();
            return new String(IOTool.readLimited(in, limit, this.contentLength(), deadlineMs), StandardCharsets.UTF_8);
        }
    }

    /** Streams a 200 body into a new file within the request's size and time budgets and verifies its hex SHA-256; any failure deletes the file again. */
    public void download(final Path destination, final String expectedSha256) throws IOException {
        final String digest = IOTool.requireSha256(expectedSha256);
        if (this.statusCode() != HttpURLConnection.HTTP_OK) throw new IOException("Download returned HTTP " + this.statusCode + ": " + this.uri);
        final long limit = this.maxBytes > 0 ? this.maxBytes : Long.MAX_VALUE;
        final long expected = this.contentLength();
        if (expected > limit) throw new IOException("Download exceeds its size limit: " + this.uri);
        boolean created = false;
        try (final InputStream in = this.inputStream()) {
            try (final OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                created = true;
                final byte[] buffer = new byte[IOTool.BUFFER_SIZE];
                long total = 0;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > limit || (this.deadlineNs > 0 && System.nanoTime() > this.deadlineNs))
                        throw new IOException("Download budget exceeded: " + this.uri);
                    out.write(buffer, 0, count);
                }
                if (expected >= 0 && total != expected) throw new IOException("Truncated download: " + this.uri);
            }
            IOTool.verifySha256(destination, digest);
        } catch (final IOException failure) {
            if (created) {
                try { Files.deleteIfExists(destination); }
                catch (final IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    /** Binds a JSON body ({@code application/json}, {@code text/json}, any {@code +json}) to {@code type} within the {@link #text()} budgets; null for an empty body or another type, left unread. */
    public <T> T json(final Class<T> type) throws IOException {
        final String contentType = this.contentType();
        if (this.consumed) throw new IllegalStateException("The response body was already read");
        final String mime = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!mime.equals(MIMETYPE_JSON) && !mime.equals("text/json") && !mime.endsWith("+json")) return null;
        final String body = this.text();
        try {
            return JSONTool.parse(body, type);
        } catch (final JsonSyntaxException e) {
            throw new IOException("Malformed JSON in response from " + this.uri, e);
        }
    }

    /** Releases the connection; a request that was never sent has nothing to release. */
    @Override
    public void close() {
        if (this.connection == null) return;
        if (this.connection instanceof final HttpURLConnection http) {
            http.disconnect();
            return;
        }
        // FILE AND FTP CONNECTIONS OPEN THEIR STREAM AT connect(): RELEASE IT OR THE HANDLE LIVES UNTIL GC
        try { this.connection.getInputStream().close(); }
        catch (final IOException ignored) {}
    }

    // ==========================================================================
    // PUBLIC API: STATIC HELPERS
    // ==========================================================================
    /** Splits the raw query into an unmodifiable map decoded once: a malformed pair stays raw, a repeated key keeps its last value, a bare key maps to "". */
    public static Map<String, String> query(final URI uri) {
        final String query = uri == null ? null : uri.getRawQuery();
        if (query == null || query.isEmpty()) return Map.of();
        final Map<String, String> result = new HashMap<>();
        for (final String param: query.split("&")) {
            if (param.isEmpty()) continue;
            final int eq = param.indexOf('=');
            final String key = eq < 0 ? param : param.substring(0, eq);
            final String value = eq < 0 ? "" : param.substring(eq + 1);
            try {
                result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (final IllegalArgumentException malformed) {
                result.put(key, value);
            }
        }
        return Map.copyOf(result);
    }

    /** Returns the content type of a file name from WaterMedia's extension table, then the JDK's, else {@link #MIMETYPE_OCTET_STREAM}. */
    public static String contentTypeOf(final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        final String known = dot < 0 || dot == fileName.length() - 1 ? null : MediaType.mime(fileName.substring(dot + 1));
        if (known != null) return known;
        final String guessed = URLConnection.guessContentTypeFromName(fileName);
        return guessed != null ? guessed : MIMETYPE_OCTET_STREAM;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // THE HEADERS THAT GO ON THE WIRE TO target: THE DERIVED REFERER, AND NO CREDENTIALS OUTSIDE THE REQUEST'S OWN ORIGIN
    HttpHeaders headersFor(final URI target) {
        final HttpHeaders out = new HttpHeaders(this.headers);
        if (!out.has("Referer")) {
            final String referer = HttpHeaders.referer(target);
            if (referer != null) out.set("Referer", referer);
        }
        if (!sameOrigin(this.uri, target)) {
            out.removeAll("Authorization");
            out.removeAll("Proxy-Authorization");
            out.removeAll("Cookie");
            out.removeAll("Cookie2");
            out.removeAll(NetworkAPI.X_WATERMEDIA_TOKEN);
        }
        return out;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private URLConnection open(final URI target, final String method, final byte[] body) throws IOException {
        final URLConnection conn = target.toURL().openConnection();
        conn.setConnectTimeout(this.connectTimeout);
        conn.setReadTimeout(this.readTimeout);
        // FTP AND FILE TAKE NO METHOD, HEADERS OR BODY
        if (!(conn instanceof final HttpURLConnection http)) return conn;
        http.setInstanceFollowRedirects(false); // send() WALKS THE REDIRECTS ITSELF
        http.setRequestMethod(method);
        this.headersFor(target).writeTo(http);
        if (body != null && body.length > 0) {
            http.setDoOutput(true);
            http.setFixedLengthStreamingMode(body.length);
            try (final OutputStream os = http.getOutputStream()) {
                os.write(body);
            }
        }
        return http;
    }

    private NetRequest receive(final URI location, final URLConnection connection, final int statusCode, final long deadlineNs) {
        this.location = location;
        this.statusCode = statusCode;
        this.deadlineNs = deadlineNs;
        this.responseHeaders = HttpHeaders.fromResponse(connection);
        this.connection = connection;
        return this;
    }

    private URLConnection connection() {
        final URLConnection connection = this.connection;
        if (connection == null) throw new IllegalStateException("The request was not sent");
        return connection;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // CREDENTIALS AND BODIES BELONG TO AN ORIGIN: SCHEME, HOST AND EFFECTIVE PORT MUST ALL MATCH
    private static boolean sameOrigin(final URI origin, final URI target) {
        final int originPort = origin.getPort() != -1 ? origin.getPort() : ("https".equalsIgnoreCase(origin.getScheme()) ? 443 : 80);
        final int targetPort = target.getPort() != -1 ? target.getPort() : ("https".equalsIgnoreCase(target.getScheme()) ? 443 : 80);
        return origin.getScheme() != null && origin.getScheme().equalsIgnoreCase(target.getScheme())
                && origin.getHost() != null && origin.getHost().equalsIgnoreCase(target.getHost()) && originPort == targetPort;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** User-Agent presets: {@link #WATERMEDIA} identifies WaterMedia, {@link #GENERIC} mimics a desktop browser for servers that filter other clients. */
    public enum UserAgent {
        WATERMEDIA(WaterMedia.USER_AGENT),
        GENERIC(WaterMedia.BROWSER_AGENT);

        private final String value;
        UserAgent(final String value) { this.value = value; }
        /** Returns the {@code User-Agent} header value of this preset. */
        public String value() { return this.value; }
    }
}
