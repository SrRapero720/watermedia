package org.watermedia.bootstrap.app;

import org.watermedia.WaterMedia;
import org.watermedia.api.util.MathUtil;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** The cache cleanup dialog: its state, the scan of the media cache and the wipe of it. */
public final class CacheCleaner {
    public volatile boolean visible;
    public volatile boolean working;
    public volatile boolean done;
    public volatile boolean error;
    public volatile int stage = 1;
    public volatile int fileCount;
    public volatile String sizeLabel = "0 B";
    public volatile String state = "READY";
    public volatile int progress;
    // WAKES THE RENDER LOOP AFTER A WORKER STAGE CHANGES WHAT THE DIALOG SHOWS
    private final Runnable repaint;

    CacheCleaner(final Runnable repaint) {
        this.repaint = repaint;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Counts the files and bytes in the media cache, as {@code {files, bytes}}. */
    public static long[] scan() throws IOException {
        final long[] stats = new long[2];
        final Path cache = WaterMedia.tmp().resolve("cache");
        if (!Files.exists(cache)) return stats;
        try (final Stream<Path> files = Files.walk(cache)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                stats[0]++;
                try { stats[1] += Files.size(path); }
                catch (final IOException ignored) {} // A FILE DELETED DURING THE WALK STILL COUNTS, WITHOUT ITS SIZE
            });
        } catch (final UncheckedIOException failure) {
            throw failure.getCause();
        }
        return stats;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // RUNS THE DIALOG'S NEXT ACTION ON A WORKER: OPEN AND SCAN, WIPE THE CACHE, OR CLOSE AND RECOUNT FOR THE NEXT OPEN;
    // A STAGE STILL WORKING JUST KEEPS THE DIALOG OPEN
    void step() {
        if (!this.visible) {
            // BACK TO THE FIRST STAGE, SCANNED ON A WORKER: scan() WALKS THE WHOLE MEDIA CACHE, POSSIBLY THOUSANDS OF FILES
            this.done = false;
            this.error = false;
            this.stage = 1;
            this.state = "SCAN";
            this.fileCount = 0;
            this.sizeLabel = "0 B";
            this.progress = 0;
            this.visible = true;
            this.working = true;
            ThreadTool.thread("CacheCleaner-Scan", this::count);
            return;
        }
        if (this.working) {
            this.visible = true;
            return;
        }
        if (this.stage <= 1) {
            if (this.fileCount <= 0) return;
            this.working = true;
            ThreadTool.thread("CacheCleaner-Wipe", this::clean);
        } else {
            // HIDE THE DIALOG AND REFRESH THE COUNTS FOR THE NEXT OPEN OFF THE RENDER THREAD
            this.visible = false;
            ThreadTool.thread("CacheCleaner-Scan", this::count);
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private void count() {
        try {
            this.stage = 1;
            this.done = false;
            this.error = false;
            this.progress = 0;

            final long[] stats = scan();
            this.fileCount = (int) Math.min(Integer.MAX_VALUE, stats[0]);
            this.sizeLabel = MathUtil.displayBytes(stats[1]);
            this.state = stats[0] > 0 ? "FOUND" : "EMPTY";
        } catch (final IOException e) {
            this.error = true;
            this.state = "ERROR";
        } finally {
            this.working = false;
            this.repaint.run();
        }
    }

    private void clean() {
        try {
            this.stage = 2;
            this.state = "CLEANING";
            this.error = false;
            this.done = false;
            this.progress = 12;
            this.repaint.run();

            final Path cache = WaterMedia.tmp().resolve("cache");
            if (Files.exists(cache)) IOTool.deleteTree(cache);
            Files.createDirectories(cache);

            this.progress = 100;
            this.state = "CLEANED";
            this.done = true;
        } catch (final Exception e) {
            this.progress = 0;
            this.state = "ERROR";
            this.error = true;
        } finally {
            this.working = false;
            this.repaint.run();
        }
    }
}
