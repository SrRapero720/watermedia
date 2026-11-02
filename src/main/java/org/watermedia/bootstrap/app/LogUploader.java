package org.watermedia.bootstrap.app;

import com.google.gson.JsonObject;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.ThreadTool;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The log upload dialog: its state, the scan of the instance's logs, their upload to mclo.gs and the GitHub issue
 * report built from them, filed against WaterMedia or a suspected mod.
 */
public final class LogUploader {
    public static final String REPO = "SrRapero720/watermedia"; // MIRRORS github IN gradle.properties
    // THE APP RUNS FROM A MINECRAFT mods FOLDER, THE ONLY PLACE WITH LOGS AND CRASH REPORTS TO UPLOAD
    public static final boolean WORKDIR_IS_MODS = new File("").getAbsoluteFile().getName().equalsIgnoreCase("mods");
    // MODS BUILT ON WATERMEDIA, OFFERED AS ISSUE TARGETS IN THE THIRD UPLOAD STAGE WHEN A JAR NAME IN THE
    // MODS FOLDER CONTAINS THEIR id
    public static final List<SuspectMod> SUSPECT_MODS = List.of(
            new SuspectMod("fancymenu", "FancyMenu", "Keksuccino/FancyMenu", "https://github.com/Keksuccino/FancyMenu/issues/new"),
            new SuspectMod("waterframes", "WaterFrames", "SrRapero720/waterframes", "https://github.com/SrRapero720/waterframes/issues/new"),
            new SuspectMod("watervision", "WaterVision", "SrRapero720/watervision", "https://github.com/SrRapero720/watervision/issues/new"),
            new SuspectMod("littleframes", "LittleFrames", "CreativeMD/littleframes", "https://github.com/CreativeMD/littleframes/issues/new")
    );
    // LOG FILES PAST EITHER LIMIT ARE NOT UPLOADED TO MCLOGS
    private static final long UPLOAD_LIMIT_BYTES = 10L * 1_024 * 1_024;
    private static final int UPLOAD_LIMIT_LINES = 25_000;

    public volatile boolean visible;
    public volatile boolean working;
    public volatile boolean done;
    public volatile boolean error;
    public volatile boolean uploadsDone;
    public volatile boolean issueCopied;
    public volatile boolean issueOpened;
    public volatile int stage = 1;
    public volatile String status = "READY";
    public volatile String issueUrl;
    // REPOSITORY THE STAGE-3 SUBMIT ACTION WILL OPEN (WATERMEDIA BY DEFAULT, OR A SUSPECTED MOD)
    public volatile String repoUrl;
    public final List<FileEntry> files = new CopyOnWriteArrayList<>();
    // IDS OF SUSPECT_MODS WHOSE JAR WAS FOUND IN THE MODS FOLDER (POPULATED DURING THE UPLOAD SCAN)
    public final Set<String> suspectModIds = ConcurrentHashMap.newKeySet();
    // WAKES THE RENDER LOOP AFTER A WORKER STAGE CHANGES WHAT THE DIALOG SHOWS
    private final Runnable repaint;

    LogUploader(final Runnable repaint) {
        this.repaint = repaint;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // RUNS THE DIALOG'S NEXT ACTION ON A WORKER: OPEN AND SCAN, UPLOAD THE LOGS, BUILD THE REPORT OR OPEN THE PICKED
    // REPOSITORY; A STAGE STILL WORKING JUST KEEPS THE DIALOG OPEN
    void step() {
        if (!WORKDIR_IS_MODS) {
            this.visible = false;
            return;
        }
        if (!this.visible) {
            // BACK TO THE FIRST STAGE, SCANNED ON A WORKER: exceedsLineLimit() READS UP TO FOUR MULTI-MB FILES
            this.done = false;
            this.error = false;
            this.uploadsDone = false;
            this.issueCopied = false;
            this.issueOpened = false;
            this.stage = 1;
            this.status = "SCAN";
            this.issueUrl = null;
            this.repoUrl = null;
            this.files.clear();
            this.visible = true;
            this.working = true;
            ThreadTool.thread("LogUploader-Scan", this::scan);
            return;
        }
        if (this.working) {
            this.visible = true;
            return;
        }
        if (this.stage <= 1) {
            if (this.files.stream().noneMatch(LogUploader::isUploadable)) return;
            this.working = true;
            ThreadTool.thread("LogUploader-Upload", this::upload);
        } else if (this.stage == 2) {
            if (this.files.stream().noneMatch(entry -> entry.uploaded)) return;
            ThreadTool.thread("LogUploader-IssueReport", this::issueReport);
        } else {
            if (this.repoUrl == null || this.repoUrl.isBlank()) return;
            ThreadTool.thread("LogUploader-OpenRepo", this::openRepoPage);
        }
    }

    // ==========================================================================
    // WORKER STAGES
    // ==========================================================================
    // HEAVY FILE READS AND NETWORK CALLS MUST NOT BLOCK THE RENDER LOOP

    private void scan() {
        try {
            this.stage = 1;
            this.status = "SCAN";
            this.error = false;
            this.done = false;
            this.files.clear();

            final Path cwd = Path.of("").toAbsolutePath();
            final Path baseDir = WORKDIR_IS_MODS && cwd.getParent() != null ? cwd.getParent() : cwd;
            final Path logsDir = baseDir.resolve("logs");
            final Path crashDir = baseDir.resolve("crash-reports");
            final Path crashReport = latestFile(crashDir, p -> p.toString().endsWith(".txt"));
            // JVM FATAL ERROR LOGS (hs_err_pid<pid>.log) LAND IN THE PROCESS WORKING DIR BY DEFAULT
            final Path hsErr = latestFile(baseDir, p -> Files.isRegularFile(p) && p.getFileName().toString().startsWith("hs_err_pid"));

            this.addScannedFile("latest.log", logsDir.resolve("latest.log"));
            this.addScannedFile(crashReport != null ? crashReport.getFileName().toString() : "crash-reports", crashReport != null ? crashReport : crashDir);
            this.addScannedFile("watermedia-app.log", logsDir.resolve("watermedia-app.log"));
            this.addScannedFile(hsErr != null ? hsErr.getFileName().toString() : "hs_err_pid.log", hsErr != null ? hsErr : baseDir.resolve("hs_err_pid.log"));
            this.scanSuspectMods();
        } finally {
            this.working = false;
            this.repaint.run();
        }
    }

    private void scanSuspectMods() {
        this.suspectModIds.clear();
        if (!WORKDIR_IS_MODS) return;
        // A CANDIDATE COUNTS ONLY IF A JAR IN THE WORKING DIR WHOSE FILENAME CONTAINS ITS ID IS PRESENT
        // (id "fancymenu" MATCHES "FancyMenu-Forge-1.20.1-x.y.z.jar").
        final Path modsDir = Path.of("").toAbsolutePath();
        try (final var stream = Files.list(modsDir)) {
            final List<String> jars = stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(n -> n.endsWith(".jar"))
                    .toList();
            for (final SuspectMod mod: SUSPECT_MODS)
                if (jars.stream().anyMatch(jar -> jar.contains(mod.id()))) this.suspectModIds.add(mod.id());
        } catch (final IOException ignored) {}
    }

    private void addScannedFile(final String name, final Path path) {
        final FileEntry entry = new FileEntry(name, path);
        if (path != null && Files.isRegularFile(path)) {
            try {
                entry.present = true;
                entry.valid = true;
                final long size = Files.size(path);
                entry.sizeLabel = MathUtil.displayBytes(size);
                if (size > UPLOAD_LIMIT_BYTES || exceedsLineLimit(path)) {
                    entry.valid = false;
                    entry.state = "INVALID";
                    this.error = true;
                } else {
                    entry.state = "READ OK";
                }
            } catch (final IOException e) {
                entry.present = false;
                entry.valid = false;
                entry.state = "READ ERROR";
                entry.sizeLabel = "-";
                this.error = true;
            }
        } else {
            entry.present = false;
            entry.valid = false;
            entry.state = "NOT FOUND";
            entry.sizeLabel = "-";
        }
        this.files.add(entry);
    }

    private void upload() {
        try {
            this.stage = 2;
            this.status("UPLOAD");
            this.error = false;
            this.done = false;
            this.uploadsDone = false;

            boolean anyUploaded = false;
            for (final FileEntry entry: this.files) {
                if (!isUploadable(entry)) continue;
                entry.state = "UPLOADING";
                entry.progress = 18;
                final String content = this.readContent(entry);
                if (content == null) continue;
                entry.progress = 64;
                anyUploaded |= this.uploadToMclogs(content, entry) != null;
            }

            if (anyUploaded) {
                this.status("REPORT READY");
                this.uploadsDone = !this.error && this.uploadsComplete();
            } else {
                this.status("ERROR");
                this.error = true;
            }
        } catch (final Exception e) {
            this.status("ERROR");
            this.error = true;
        } finally {
            this.working = false;
            this.done = false;
            this.repaint.run();
        }
    }

    private void issueReport() {
        try {
            this.working = true;
            this.stage = 3;
            this.status = "SUCCESS";
            this.error = false;

            // THE CRASH REPORT IS THE UPLOADED ENTRY THAT IS NONE OF THE OTHER THREE KINDS
            final String latestUrl = this.uploadedUrl(name -> name.equalsIgnoreCase("latest.log"));
            final String appUrl = this.uploadedUrl(name -> name.equalsIgnoreCase("watermedia-app.log"));
            final String crashUrl = this.uploadedUrl(name -> !name.equalsIgnoreCase("latest.log")
                    && !name.equalsIgnoreCase("watermedia-app.log") && !name.startsWith("hs_err_pid"));
            final String hsErrUrl = this.uploadedUrl(name -> name.startsWith("hs_err_pid"));
            final String issueText = "This is an automated issue report generated by WATERMeDIA: Multimedia API.\n\n" +
                    "## Alerts\n" +
                    SystemInfo.alerts() + "\n" +
                    "## Files\n" +
                    "- Logs: " + (latestUrl != null ? latestUrl : "N/A") + "\n" +
                    "- Crash-report: " + (crashUrl != null ? crashUrl : "N/A") + "\n" +
                    "- JVM crash (hs_err): " + (hsErrUrl != null ? hsErrUrl : "N/A") + "\n" +
                    "- WM Logs: " + (appUrl != null ? appUrl : "N/A") + "\n\n" +
                    SystemInfo.summary();
            this.issueUrl = githubIssueUrl(issueText);
            // DEFAULT SUBMIT TARGET IS WATERMEDIA; THE STAGE-3 SCREEN LETS THE USER PICK A SUSPECTED MOD INSTEAD.
            this.repoUrl = this.issueUrl;

            try {
                final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(new StringSelection(issueText), null);
                this.issueCopied = true;
            } catch (final Exception e) {
                this.error = true;
                this.status = "ERROR";
            }

            // DO NOT OPEN THE BROWSER AUTOMATICALLY: THE USER CHOOSES WHICH ISSUE TRACKER TO SUBMIT TO
            this.done = !this.error;
        } finally {
            this.working = false;
            this.repaint.run();
        }
    }

    private void openRepoPage() {
        try {
            final String target = this.repoUrl;
            final String url = target != null && target.startsWith("http") ? target : githubIssueUrl("");
            Desktop.getDesktop().browse(URI.create(url));
            this.issueOpened = true;
        } catch (final Exception e) {
            this.error = true;
            this.status = "ERROR";
        }
        this.repaint.run();
    }

    private String uploadToMclogs(final String content, final FileEntry entry) {
        // NetRequest BOUNDS THE REQUEST WITH THE NETWORK TIMEOUTS AND ITS REPLY WITH THE TEXT BUDGET, AND KEEPS EVERY HOP ON HTTPS
        try (final NetRequest req = new NetRequest(URI.create("https://api.mclo.gs/1/log")).method("POST").httpsOnly()
                .contentType(NetRequest.MIMETYPE_FORM).body("content=" + URLEncoder.encode(content, StandardCharsets.UTF_8)).send()) {
            if (req.statusCode() == 200) {
                final JsonObject json = req.json(JsonObject.class);
                if (json != null && json.get("success").getAsBoolean()) {
                    final String url = json.get("url").getAsString();
                    entry.url = url;
                    entry.uploaded = true;
                    entry.progress = 100;
                    entry.state = "UPLOADED";
                    return url;
                }
            }
            entry.progress = 0;
            entry.state = "FAILED";
            this.error = true;
        } catch (final Exception e) {
            entry.progress = 0;
            entry.state = "FAILED";
            this.error = true;
        }
        return null;
    }

    private String readContent(final FileEntry entry) {
        try {
            // REDACT SESSION/ACCESS TOKENS AND OTHER SECRETS BEFORE THEY EVER LEAVE THE MACHINE:
            // hs_err DUMPS AND LAUNCH LOGS OFTEN CARRY THE FULL --accessToken IN THE JVM COMMAND LINE
            final StringBuilder sb = new StringBuilder();
            try (final BufferedReader reader = lenientReader(entry.path)) {
                final char[] buf = new char[8_192];
                int n;
                while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            }
            return DataTool.redact(sb.toString());
        } catch (final Exception e) {
            entry.state = "READ ERROR";
            entry.progress = 0;
            this.error = true;
            return null;
        }
    }

    // ==========================================================================
    // STATE
    // ==========================================================================
    private void status(final String status) {
        this.status = status;
        this.repaint.run();
    }

    private boolean uploadsComplete() {
        boolean hadUploadable = false;
        for (final FileEntry entry: this.files) {
            if (!entry.present || !entry.valid) continue;
            hadUploadable = true;
            if (!entry.uploaded) return false;
        }
        return hadUploadable;
    }

    // URL OF THE FIRST UPLOADED ENTRY WHOSE NAME MATCHES, OR NULL
    private String uploadedUrl(final Predicate<String> name) {
        for (final FileEntry entry: this.files)
            if (entry.uploaded && name.test(entry.name)) return entry.url;
        return null;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static boolean isUploadable(final FileEntry entry) {
        return entry.present && entry.valid && !entry.uploaded && !"FAILED".equals(entry.state) && !"READ ERROR".equals(entry.state);
    }

    private static String githubIssueUrl(final String body) {
        return "https://github.com/" + REPO + "/issues/new"
                + "?title=" + URLEncoder.encode("WATERMeDIA Generated Issue", StandardCharsets.UTF_8)
                + "&body=" + URLEncoder.encode(body == null ? "" : body, StandardCharsets.UTF_8);
    }

    private static boolean exceedsLineLimit(final Path path) throws IOException {
        try (final BufferedReader reader = lenientReader(path)) {
            int lines = 0;
            while (reader.readLine() != null)
                if (++lines > UPLOAD_LIMIT_LINES) return true;
            return false;
        }
    }

    // hs_err DUMPS AND SOME LOGS CARRY NON-UTF-8 BYTES; REPLACE THEM INSTEAD OF FAILING THE WHOLE READ
    private static BufferedReader lenientReader(final Path path) throws IOException {
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return new BufferedReader(new InputStreamReader(Files.newInputStream(path), decoder));
    }

    // NEWEST MATCHING FILE OF THE DIRECTORY BY MODIFICATION TIME, OR NULL; AN UNREADABLE TIME COUNTS AS THE OLDEST
    private static Path latestFile(final Path dir, final Predicate<Path> filter) {
        if (!Files.isDirectory(dir)) return null;
        try (final var stream = Files.list(dir)) {
            return stream.filter(filter)
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (final Exception e) {
                            return 0L;
                        }
                    })).orElse(null);
        } catch (final Exception e) {
            return null;
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    public record SuspectMod(String id, String name, String slug, String url) {}

    /** One log file of the dialog: where it lives, whether it can go up and how its upload went. */
    public static final class FileEntry {
        public final String name;
        public final Path path;
        public volatile String sizeLabel = "-";
        public volatile String state = "PENDING";
        public volatile String url = "";
        public volatile int progress;
        public volatile boolean present;
        public volatile boolean valid;
        public volatile boolean uploaded;

        public FileEntry(final String name, final Path path) {
            this.name = name;
            this.path = path;
        }
    }
}
