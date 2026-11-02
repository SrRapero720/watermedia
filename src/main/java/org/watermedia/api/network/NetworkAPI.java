package org.watermedia.api.network;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.internal.WaterPlatform;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.NetRequest;
import org.watermedia.bootstrap.WaterMediaModule;
import org.watermedia.tools.ThreadTool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.watermedia.WaterMedia.LOGGER;

/** File transfer facade: uploads to the remote WaterMedia file server and the local one's startup. */
public final class NetworkAPI {
    private static final Marker IT = MarkerManager.getMarker(NetworkAPI.class.getSimpleName());
    /** URI scheme of the internal sources the WaterMedia file servers serve. */
    public static final String PROTOCOL_WATER = "water";
    /** Request header carrying the upload token the file server authenticates with. */
    public static final String X_WATERMEDIA_TOKEN = "X-WaterMedia-Token";
    /** Request header carrying the original name of an uploaded file. */
    public static final String X_WATERMEDIA_FILENAME = "X-WaterMedia-Filename";
    private static final int UPLOAD_THREADS = 4;
    // THE UPLOAD WORKERS, CREATED WHEN THE MODULE LOADS AND KEPT FOR THE LIFE OF THE PROCESS
    private static volatile ExecutorService uploads;

    private NetworkAPI() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Uploads every file to the remote WaterMedia server, one status each; all of them are checked before the first upload starts. */
    public static UploadStatus[] upload(final File... files) {
        if (files == null) throw new IllegalArgumentException("Files cannot be null");
        for (final File file: files) if (file == null || !file.isFile()) throw new IllegalArgumentException("Not a file: " + file);
        final UploadStatus[] statuses = new UploadStatus[files.length];
        for (int i = 0; i < files.length; i++) statuses[i] = upload(files[i]);
        return statuses;
    }

    /** Uploads a file to the remote WaterMedia server in the background; an unreachable or misconfigured server fails the returned status instead of throwing. */
    public static UploadStatus upload(final File file) {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("Not a file: " + file);
        final ExecutorService workers = uploads;
        if (!WaterMedia.loaded() || workers == null) throw new IllegalStateException("The network module is not loaded");
        final UploadStatus status = new UploadStatus(file.length());
        workers.execute(() -> {
            try {
                upload(file, status);
            } finally {
                if (!status.completed() && !status.failed()) status.fail("Upload ended before completion");
            }
        });
        return status;
    }

    /** Returns {@code network.remoteUrl} without its trailing slash; throws when it is not an absolute http(s) URL. */
    public static String remoteUrl() {
        final String base = WaterMediaConfig.network.remoteUrl;
        if (base == null || base.isBlank() || !(base.startsWith("http://") || base.startsWith("https://")))
            throw new IllegalStateException("network.remoteUrl is not an absolute http(s) URL: " + base);
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // THE FILE SERVER RUNS EVERYWHERE UNDER ALWAYS AND ONLY ON HOSTS WITHOUT A CLIENT UNDER DEDICATED
    static boolean serverEnabled(final boolean client, final WaterMediaConfig.Network.Server server) {
        return server == WaterMediaConfig.Network.Server.ALWAYS || (!client && server == WaterMediaConfig.Network.Server.DEDICATED);
    }

    private static void upload(final File file, final UploadStatus status) {
        synchronized (status) { status.worker = Thread.currentThread(); }
        if (status.failed()) return; // CANCELLED BEFORE IT STARTED: NOTHING IS SENT, NOT EVEN THE TOKEN
        final String remote;
        try {
            remote = remoteUrl();
        } catch (final IllegalStateException misconfigured) { // A WRONG network.remoteUrl IS AN EXPECTED CONDITION, NOT A BUG: NO TRACE
            LOGGER.warn(IT, "Could not upload '{}': {}", file.getName(), misconfigured.getMessage());
            status.fail(misconfigured.toString());
            return;
        }
        HttpURLConnection conn = null;
        try {
            // STREAMING UPLOADS WITH BYTE-LEVEL PROGRESS ARE OUT OF SCOPE FOR NetRequest, SO THIS DRIVES HttpURLConnection
            final URL url = URI.create(remote + "/upload").toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(WaterMediaConfig.network.timeout);
            conn.setReadTimeout(WaterMediaConfig.network.timeout);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", WaterMedia.USER_AGENT);
            conn.setRequestProperty(X_WATERMEDIA_TOKEN, WaterMediaConfig.network.token);
            conn.setRequestProperty(X_WATERMEDIA_FILENAME, file.getName());
            conn.setRequestProperty("Content-Type", NetRequest.contentTypeOf(file.getName()));
            conn.setFixedLengthStreamingMode(file.length());

            try (final var fis = new FileInputStream(file);
                 final var os = conn.getOutputStream()) {
                final byte[] buffer = new byte[NetworkServer.CHUNK_BYTES];
                long uploaded = 0;
                long lastTime = System.nanoTime();
                long lastUploaded = 0;
                int read;

                while ((read = fis.read(buffer)) != -1) {
                    if (status.cancelled) throw new IOException("Upload cancelled");
                    os.write(buffer, 0, read);
                    uploaded += read;
                    status.uploadedBytes(uploaded);

                    final long now = System.nanoTime();
                    final long elapsed = now - lastTime;
                    if (elapsed >= 500_000_000L) {
                        final long bytesInPeriod = uploaded - lastUploaded;
                        status.speed((long) (bytesInPeriod * 1_000_000_000.0 / elapsed));
                        lastTime = now;
                        lastUploaded = uploaded;
                    }
                }
            }

            if (status.cancelled) throw new IOException("Upload cancelled");
            final int code = conn.getResponseCode();
            if (code == 200) {
                final String id;
                try (final var is = conn.getInputStream()) {
                    id = new String(is.readNBytes(9), StandardCharsets.US_ASCII);
                }
                if (!id.matches("[A-Za-z0-9]{8}")) throw new IOException("Server returned an invalid upload identifier");
                status.complete(id);
            } else {
                status.fail(code == HttpURLConnection.HTTP_UNAVAILABLE ? "The server is busy, try again in a moment" : "Server returned HTTP " + code);
            }
        } catch (final ConnectException | UnknownHostException | SocketTimeoutException unreachable) {
            if (status.cancelled) return;
            // A REMOTE SERVER THAT IS DOWN OR UNRESOLVABLE IS AN EXPECTED CONDITION, NOT A BUG: NO TRACE
            LOGGER.warn(IT, "Could not reach the remote file server to upload '{}': {}", file.getName(), unreachable.getMessage());
            status.fail(unreachable.toString());
        } catch (final Exception e) {
            if (status.cancelled) return; // THE POOL CLEARS A LATE CANCEL INTERRUPT BEFORE ITS NEXT UPLOAD
            // e.getMessage() IS OFTEN null (E.G. NPE); e.toString() KEEPS THE EXCEPTION TYPE FOR DIAGNOSIS
            LOGGER.error(IT, "Failed to upload '{}' to remote server", file.getName(), e);
            status.fail(e.toString());
        } finally {
            // A cancel() THAT LANDS AFTER THIS UPLOAD MUST NOT INTERRUPT THE WORKER'S NEXT ONE
            synchronized (status) { status.worker = null; }
            if (conn != null) conn.disconnect();
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** Tracks a file upload to the remote WaterMedia server; every accessor is thread-safe and can be polled from any thread. */
    public static final class UploadStatus {
        private final long totalBytes;
        private volatile long uploadedBytes;
        private volatile long speed;
        private volatile String id;
        private volatile boolean complete;
        private volatile boolean failed;
        private volatile boolean cancelled;
        private volatile String error;
        volatile Thread worker;

        UploadStatus(final long totalBytes) { this.totalBytes = totalBytes; }

        /** Expected upload size in bytes. */
        public long totalBytes() { return this.totalBytes; }
        /** Bytes written to the connection so far, confirmed by the server only once {@link #completed()}. */
        public long uploadedBytes() { return this.uploadedBytes; }
        /** Current upload speed in bytes per second. */
        public long speed() { return this.speed; }
        /** Server-assigned ID, null until the upload completes. */
        public String id() { return this.id; }
        /** The {@code water://remote/<id>} address of the uploaded file, null until the upload completes. */
        public URI uri() { return this.complete ? URI.create(PROTOCOL_WATER + "://" + WaterPlatform.HOST_REMOTE + "/" + this.id) : null; }
        /** Whether the server accepted the upload and assigned its ID. */
        public boolean completed() { return this.complete; }
        /** Whether the upload ended without completing, cancellation included. */
        public boolean failed() { return this.failed; }
        /** Failure detail, null until the upload fails. */
        public String error() { return this.error; }
        /** Upload progress from 0.0 to 100.0. */
        public double percentage() { return this.totalBytes > 0 ? (this.uploadedBytes * 100.0) / this.totalBytes : 0; }
        /** Formatted speed such as "1.5 MB/s". */
        public String displaySpeed() { return MathUtil.displayBytes(this.speed) + "/s"; }

        /** Stops an upload still in flight: the worker aborts its transfer and the status ends failed as "Cancelled". */
        public synchronized void cancel() {
            if (this.complete || this.failed) return;
            this.cancelled = true;
            this.fail("Cancelled");
            final Thread thread = this.worker;
            if (thread != null) thread.interrupt();
        }

        synchronized void uploadedBytes(final long bytes) { if (!this.complete && !this.failed) this.uploadedBytes = bytes; }
        synchronized void speed(final long speed) { if (!this.complete && !this.failed) this.speed = speed; }

        synchronized void complete(final String id) {
            if (this.complete || this.failed) return;
            this.id = id;
            this.uploadedBytes = this.totalBytes;
            this.complete = true;
        }

        synchronized void fail(final String error) {
            if (this.complete || this.failed) return;
            this.error = error;
            this.failed = true;
        }
    }

    /** Internal module: the upload workers and, when configured, the local file server. */
    public static final class Module extends WaterMediaModule {
        public Module(final WaterMedia watermedia) { super(watermedia); }

        @Override
        public void start() {
            uploads = Executors.newFixedThreadPool(UPLOAD_THREADS, ThreadTool.workerFactory("NetworkAPI-Upload", Thread.NORM_PRIORITY));
            if (!serverEnabled(this.watermedia.clientSide, WaterMediaConfig.network.server)) return;
            try {
                NetworkServer.start(WaterMediaConfig.network.serverPort, this.watermedia.cwd);
            } catch (final Exception failure) { // UPLOADS STILL WORK WITHOUT THE LOCAL SERVER
                LOGGER.error(IT, "The local file server did not start; uploads still work without it", failure);
            }
        }
    }
}
