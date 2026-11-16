package org.watermedia.api.network;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.internal.WaterPlatform;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.ThreadTool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.watermedia.WaterMedia.LOGGER;

/** File transfer facade: uploads to the remote WaterMedia file server and the local one's startup. */
public final class NetworkAPI extends WaterMedia.Module {
    private static final Marker IT = MarkerManager.getMarker(NetworkAPI.class.getSimpleName());
    public static final String PROTOCOL_WATER = "water";
    public static final String X_WATERMEDIA_TOKEN = "X-WaterMedia-Token";
    public static final String X_WATERMEDIA_FILENAME = "X-WaterMedia-Filename";
    private static final int UPLOAD_THREADS = 4;
    // BOUNDS OF THE REQUEST THAT ASKS A FILE SERVER FOR ITS ROOT LINE, AND THE PAUSE BEFORE ASKING A SILENT ONE AGAIN
    private static final int PROBE_TIMEOUT_MS = 5_000;
    private static final int PROBE_MAX_BYTES = 64;
    private static final long PROBE_RETRY_NS = 5_000_000_000L;
    private static final AtomicBoolean LOADED = new AtomicBoolean(); // FLIPS ON THE FIRST CONSTRUCTION, WHEN WATERMEDIA STARTS
    private static final AtomicBoolean PROBING = new AtomicBoolean(); // HELD BY THE ONE REQUEST serverEnabled() HAS IN FLIGHT
    private static volatile ExecutorService uploads; // THE UPLOAD WORKERS, CREATED WHEN THE MODULE STARTS
    private static volatile boolean serverUp; // LATCHED ONCE THE FILE SERVER STARTED HERE OR ANSWERED AT network.remoteUrl
    private static volatile boolean probeWarned; // ONLY THE FIRST REQUEST LOGS A SILENT REMOTE SERVER
    private static volatile long probeAt = System.nanoTime(); // EARLIEST TIME OF THE NEXT REQUEST, WRITTEN ONLY UNDER PROBING

    /** Creates the network module; {@link WaterMedia#start} calls it once per process and a second call throws. */
    public NetworkAPI() {
        if (!LOADED.compareAndSet(false, true))
            throw new IllegalStateException(DataTool.errCode(WaterMedia.ERR_NETWORK_ALREADY_LOADED) + "Failed to load NetworkAPI, it is already loaded");
    }

    /** Returns whether the WaterMedia file server is up: true on the host that started it, and elsewhere false until {@code network.remoteUrl} answers a background request; a true answer is kept. */
    public static boolean serverEnabled() {
        if (serverUp) return true;
        final ExecutorService workers = uploads;
        if (workers == null || !PROBING.compareAndSet(false, true)) return false; // NOT LOADED, OR ALREADY ASKING
        // A SILENT SERVER IS ASKED AGAIN ONLY AFTER A PAUSE, HOWEVER OFTEN THE CALLER POLLS
        if (System.nanoTime() - probeAt < 0) {
            PROBING.set(false);
            return false;
        }
        workers.execute(() -> {
            final String remote = WaterMediaConfig.network.remoteUrl;
            try {
                if (answers(remoteUrl())) {
                    serverUp = true;
                    LOGGER.info(IT, "Reached the remote file server at {}", remote);
                } else if (!probeWarned) LOGGER.warn(IT, "Could not reach the remote file server at {}: another service answers there", remote);
            } catch (final IOException | IllegalStateException | IllegalArgumentException unreachable) {
                // DOWN, UNRESOLVABLE OR MISCONFIGURED: EXPECTED CONDITIONS, NOT BUGS: NO TRACE
                if (!probeWarned) LOGGER.warn(IT, "Could not reach the remote file server at {}: {}", remote, unreachable.getMessage());
            } finally {
                probeWarned = true;
                probeAt = System.nanoTime() + PROBE_RETRY_NS;
                PROBING.set(false);
            }
        });
        return false;
    }

    /** Uploads every file to the remote WaterMedia server, one status each; all of them are checked before the first upload starts. */
    public static UploadStatus[] upload(final File... files) {
        if (files == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_NETWORK_UPLOAD_NULL_FILES) + "Failed to upload due to null files");
        for (final File file: files) if (file == null || !file.isFile()) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_NETWORK_UPLOAD_NOT_FILE_BATCH) + "Failed to upload, not a file: " + file);
        final UploadStatus[] statuses = new UploadStatus[files.length];
        for (int i = 0; i < files.length; i++) statuses[i] = upload(files[i]);
        return statuses;
    }

    /** Uploads a file to the remote WaterMedia server in the background; an unreachable or misconfigured server fails the returned status instead of throwing. */
    public static UploadStatus upload(final File file) {
        if (file == null || !file.isFile()) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_NETWORK_UPLOAD_NOT_FILE) + "Failed to upload, not a file: " + file);
        final ExecutorService workers = uploads;
        if (workers == null) throw new IllegalStateException(DataTool.errCode(WaterMedia.ERR_NETWORK_UPLOAD_NOT_LOADED) + "Failed to upload, the network module is not loaded");
        final UploadStatus status = new UploadStatus(file.length());
        workers.execute(() -> {
            try {
                upload(file, status);
            } finally {
                if (!status.completed() && !status.failed()) status.fail(DataTool.errCode(WaterMedia.ERR_NETWORK_UPLOAD_UNFINISHED) + "Failed to upload, it ended before completion");
            }
        });
        return status;
    }

    /** Returns {@code network.remoteUrl} without its trailing slash; throws when it is not an absolute http(s) URL. */
    public static String remoteUrl() {
        final String base = WaterMediaConfig.network.remoteUrl;
        if (base == null || base.isBlank() || !(base.startsWith("http://") || base.startsWith("https://")))
            throw new IllegalStateException(DataTool.errCode(WaterMedia.ERR_NETWORK_REMOTE_URL) + "Failed to read network.remoteUrl, it is not an absolute http(s) URL: " + base);
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    protected void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        uploads = Executors.newFixedThreadPool(UPLOAD_THREADS, ThreadTool.workerFactory("NetworkAPI-Upload", Thread.NORM_PRIORITY));
        if (!enable(clientSide, WaterMediaConfig.network.server)) return;
        try {
            NetworkServer.start(WaterMediaConfig.network.serverPort, cwd);
            serverUp = true; // NO REQUEST NEEDED: ITS STARTUP CHECK ALREADY ASKED network.remoteUrl
        } catch (final Exception failure) { // UPLOADS STILL WORK WITHOUT THE LOCAL SERVER
            LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_NETWORK_SERVER_START) + "Failed to start the local file server, uploads still work without it", failure);
        }
    }

    static boolean enable(final boolean client, final WaterMediaConfig.Network.Server server) {
        return server == WaterMediaConfig.Network.Server.ALWAYS || (!client && server == WaterMediaConfig.Network.Server.DEDICATED);
    }

    // ASKS A FILE SERVER FOR ITS ROOT LINE, THE PROJECT NAME: A SILENT HOST THROWS AND ANY OTHER SERVICE ANSWERS FALSE
    static boolean answers(final String remote) throws IOException {
        try (final NetRequest probe = new NetRequest(remote + "/").connectTimeout(PROBE_TIMEOUT_MS).readTimeout(PROBE_TIMEOUT_MS).maxBytes(PROBE_MAX_BYTES).send()) {
            return probe.statusCode() == HttpURLConnection.HTTP_OK && WaterMedia.NAME.equals(probe.text());
        }
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
                    if (status.cancelled) throw new IOException(DataTool.errCode(WaterMedia.ERR_NETWORK_UPLOAD_CANCELLED_WRITING) + "Failed to upload, it was cancelled");
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

            if (status.cancelled) throw new IOException(DataTool.errCode(WaterMedia.ERR_NETWORK_UPLOAD_CANCELLED) + "Failed to upload, it was cancelled");
            final int code = conn.getResponseCode();
            if (code == 200) {
                final String id;
                try (final var is = conn.getInputStream()) {
                    id = new String(is.readNBytes(9), StandardCharsets.US_ASCII);
                }
                if (!id.matches("[A-Za-z0-9]{8}")) throw new IOException(DataTool.errCode(WaterMedia.ERR_NETWORK_UPLOAD_INVALID_ID) + "Failed to upload due to invalid identifier from the server");
                status.complete(id);
            } else if (code == HttpURLConnection.HTTP_UNAVAILABLE) {
                status.fail(DataTool.errCode(WaterMedia.ERR_NETWORK_UPLOAD_SERVER_BUSY) + "Failed to upload due to busy server, try again!");
            } else {
                status.fail(DataTool.errCode(WaterMedia.ERR_NETWORK_UPLOAD_HTTP_STATUS) + "Failed to upload, the server returned HTTP " + code);
            }
        } catch (final ConnectException | UnknownHostException | SocketTimeoutException unreachable) {
            if (status.cancelled) return;
            // A REMOTE SERVER THAT IS DOWN OR UNRESOLVABLE IS AN EXPECTED CONDITION, NOT A BUG: NO TRACE
            LOGGER.warn(IT, "Could not reach the remote file server to upload '{}': {}", file.getName(), unreachable.getMessage());
            status.fail(unreachable.toString());
        } catch (final Exception e) {
            if (status.cancelled) return; // THE POOL CLEARS A LATE CANCEL INTERRUPT BEFORE ITS NEXT UPLOAD
            // e.getMessage() IS OFTEN null (E.G. NPE); e.toString() KEEPS THE EXCEPTION TYPE FOR DIAGNOSIS
            LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_NETWORK_UPLOAD_LOG) + "Failed to upload '{}' to remote server", file.getName(), e);
            status.fail(e.toString());
        } finally {
            // A cancel() THAT LANDS AFTER THIS UPLOAD MUST NOT INTERRUPT THE WORKER'S NEXT ONE
            synchronized (status) { status.worker = null; }
            if (conn != null) conn.disconnect();
        }
    }

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
}
