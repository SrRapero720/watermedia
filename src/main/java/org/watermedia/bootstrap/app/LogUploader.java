package org.watermedia.bootstrap.app;

import com.google.gson.JsonObject;
import me.srrapero720.waterconfig.WaterConfig;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.ThreadTool;

import java.awt.Desktop;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The log upload dialog: its state, the scan of the instance's logs and config copies, their upload to mclo.gs and the
 * GitHub issue opened with them under the user's title and description.
 */
public final class LogUploader {
    private static final Marker IT = MarkerManager.getMarker(LogUploader.class.getSimpleName());
    public static final String REPO = "SrRapero720/watermedia"; // MIRRORS github IN gradle.properties
    // THE APP RUNS FROM A MINECRAFT mods FOLDER; IT ONLY DECIDES THE FOLDER LAYOUT THE SCAN READS
    public static final boolean WORKDIR_IS_MODS = new File("").getAbsoluteFile().getName().equalsIgnoreCase("mods");
    // DEVELOPMENT RUN: THE APP CLASSES COME FROM A CLASSES DIRECTORY INSTEAD OF A JAR
    public static final boolean DEV = String.valueOf(LogUploader.class.getResource("LogUploader.class")).startsWith("file:");
    // THE DIALOG OPENS WHERE AN INSTANCE CAN BE READ: INSIDE A mods FOLDER OR IN THE RUN DIRECTORY OF A DEVELOPMENT RUN
    public static final boolean AVAILABLE = WORKDIR_IS_MODS || DEV;
    // INPUT LIMITS OF THE ISSUE TITLE AND DESCRIPTION
    public static final int TITLE_LIMIT_CHARS = 80;
    public static final int DESCRIPTION_LIMIT_CHARS = 2_000;
    // GITHUB ANSWERS 414 PAST ABOUT 8 KB OF LINK, SO A LONGER ONE CARRIES THE NOTE INSTEAD OF THE REPORT
    static final int URL_LIMIT_CHARS = 8_000;
    static final String PASTE_NOTE = "Paste the report here.";
    // MODS BUILT ON WATERMEDIA, NAMED IN THE REPORT WHEN A JAR NAME OF THE MODS FOLDER CONTAINS THEIR id
    private static final List<SuspectMod> SUSPECT_MODS = List.of(
            new SuspectMod("fancymenu", "FancyMenu", "Keksuccino/FancyMenu"),
            new SuspectMod("waterframes", "WaterFrames", "SrRapero720/waterframes"),
            new SuspectMod("watervision", "WaterVision", "SrRapero720/watervision"),
            new SuspectMod("littleframes", "LittleFrames", "CreativeMD/littleframes")
    );
    // FILES PAST EITHER LIMIT ARE NOT UPLOADED TO MCLOGS
    private static final long UPLOAD_LIMIT_BYTES = 10L * 1_024 * 1_024;
    private static final int UPLOAD_LIMIT_LINES = 25_000;
    // USER FOLDER OF A HOME PATH: A WINDOWS DRIVE PATH UNDER Users, OR /home AND /Users OUTSIDE A LINK
    private static final Pattern USER_PATH = Pattern.compile("((?:[A-Za-z]:[\\\\/]+(?i:users)|(?<![\\w.-])/+(?:home|Users))[\\\\/]+)[^\\\\/\\s\"'<>|:*?]+");
    // USER NAME LINE OF THE ENVIRONMENT VARIABLES AN hs_err DUMP LISTS
    private static final Pattern USER_ENV = Pattern.compile("(?m)^((?:USERNAME|USER|LOGNAME)=).+$");
    private static final Pattern HOME_PATH = homePath(System.getProperty("user.home"));
    private static final String USER_MASK = "$1********";

    public volatile boolean visible;
    public volatile boolean working;
    public volatile boolean done;
    public volatile boolean error;
    public volatile boolean uploadsDone;
    public volatile boolean issueCopied;
    public volatile boolean issueOpened;
    // THE LAST SUBMIT FAILED AT THE BROWSER, NOT ONLY AT THE CLIPBOARD
    public volatile boolean browserFailed;
    public volatile int stage = 1;
    // WRITTEN BY THE INPUTS OF THE THIRD STAGE; THEY OUTLIVE THE DIALOG UNTIL THEIR ISSUE PAGE OPENS
    public volatile String title = "";
    public volatile String description = "";
    public final List<FileEntry> files = new CopyOnWriteArrayList<>();
    // LOWERCASED JAR NAMES OF THE MODS FOLDER, LISTED ONCE BY THE SCAN
    volatile List<String> jars = List.of();
    // MACHINE HALF OF THE REPORT, READ BY THE REPORT WORKER WHILE THE USER WRITES
    private volatile SystemInfo info;
    // WAKES THE RENDER LOOP AFTER A WORKER STAGE CHANGES WHAT THE DIALOG SHOWS
    private final Runnable repaint;
    // PENDING: ERROR CODES ARE NOT LOGGED YET (THE SORTED wm_err CODES OF THE UPLOADED FILES)
    // private final Set<String> codes = new ConcurrentSkipListSet<>();

    LogUploader(final Runnable repaint) {
        this.repaint = repaint;
    }

    /** Returns whether the report of the third stage is gathered, so the issue can be submitted. */
    public boolean ready() {
        return this.info != null;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // RUNS THE DIALOG'S NEXT ACTION ON A WORKER: OPEN AND SCAN, UPLOAD THE FILES, READ THE SYSTEM FOR THE REPORT (AGAIN
    // WHEN THAT READ FAILED) OR SUBMIT THE ISSUE (IGNORED WITHOUT A TITLE)
    void step() {
        if (!AVAILABLE) {
            this.visible = false;
            return;
        }
        if (this.working) {
            // CHECKED BEFORE THE HIDDEN STATE: REOPENING SHOWS THE RUNNING STAGE INSTEAD OF STARTING A SECOND WORKER
            this.visible = true;
            return;
        }
        if (!this.visible && this.stage == 3 && !this.issueOpened) {
            // AN ISSUE NOT OPENED YET RESUMES WITH ITS UPLOADS AND REPORT: A NEW SCAN WOULD PASTE THE SAME FILES AGAIN
            this.visible = true;
            return;
        }
        if (!this.visible) {
            // A FRESH SESSION, SCANNED ON A WORKER (exceedsLineLimit() READS SEVERAL MULTI-MB FILES); THE TITLE AND
            // DESCRIPTION OF AN ISSUE ALREADY OPENED ARE SPENT
            if (this.issueOpened) {
                this.title = "";
                this.description = "";
            }
            this.done = false;
            this.error = false;
            this.uploadsDone = false;
            this.issueCopied = false;
            this.issueOpened = false;
            this.browserFailed = false;
            this.stage = 1;
            this.files.clear();
            // PENDING: ERROR CODES ARE NOT LOGGED YET
            // this.codes.clear();
            this.visible = true;
            this.working = true;
            ThreadTool.thread("LogUploader-Scan", () -> this.scan(Path.of("").toAbsolutePath(), WORKDIR_IS_MODS));
            return;
        }
        if (this.stage <= 1) {
            if (this.files.stream().noneMatch(LogUploader::isUploadable)) return;
            this.working = true;
            ThreadTool.thread("LogUploader-Upload", this::upload);
        } else if (this.stage == 2 || this.info == null) {
            if (this.files.stream().noneMatch(entry -> entry.uploaded)) return;
            this.stage = 3;
            this.error = false;
            this.info = null;
            this.working = true;
            ThreadTool.thread("LogUploader-Report", this::report);
        } else {
            if (this.title.isBlank()) return;
            this.done = false;
            this.error = false;
            this.browserFailed = false;
            this.working = true;
            ThreadTool.thread("LogUploader-Submit", this::submit);
        }
    }

    // LISTS THE FILES AND THE JAR NAMES OF THE INSTANCE. INSIDE A mods FOLDER THE GAME FILES SIT ONE LEVEL UP AND THE
    // APP FILES IN IT; A DEVELOPMENT RUN HOLDS BOTH IN ITS WORKING DIRECTORY, WITH THE JARS UNDER ITS mods FOLDER
    void scan(final Path cwd, final boolean inMods) {
        try {
            final Path instance = inMods ? cwd.getParent() : cwd;
            final Path mods = inMods ? cwd : cwd.resolve("mods");
            final Path crash = latestFile(p -> p.toString().endsWith(".txt"), instance.resolve("crash-reports"));
            // A JVM FATAL ERROR LOG (hs_err_pid<pid>.log) LANDS IN THE WORKING DIRECTORY OF ITS PROCESS: THE GAME'S OR THE APP'S
            final Path fatal = latestFile(p -> Files.isRegularFile(p) && p.getFileName().toString().startsWith("hs_err_pid"), instance, cwd);
            // THE APP WRITES ITS LOG AND ITS WATERCONFIG FOLDER RELATIVE TO ITS OWN WORKING DIRECTORY
            final Path gameConfig = instance.resolve("config").resolve(WaterMedia.ID + ".toml");
            final Path appConfig = cwd.resolve(WaterConfig.getPath()).resolve(WaterMedia.ID + ".toml");

            this.addScannedFile("latest.log", "Game log", instance.resolve("logs").resolve("latest.log"));
            this.addScannedFile(crash != null ? crash.getFileName().toString() : "crash-reports", "Crash report", crash);
            this.addScannedFile(fatal != null ? fatal.getFileName().toString() : "hs_err_pid.log", "JVM crash (hs_err)", fatal);
            this.addScannedFile("watermedia-app.log", "App log", cwd.resolve("logs").resolve("watermedia-app.log"));
            // A DEVELOPMENT RUN SHARES ONE LIBRARY CONFIG BETWEEN THE GAME AND THE APP: ONE ROW FOR ONE PATH
            final boolean shared = gameConfig.equals(appConfig);
            this.addScannedFile(WaterMedia.ID + (shared ? ".toml" : ".toml (game)"), shared ? "Config" : "Config (game)", gameConfig);
            if (!shared) this.addScannedFile(WaterMedia.ID + ".toml (app)", "Config (app)", appConfig);
            this.addScannedFile(AppConfig.ID + ".properties", "App preferences", appConfig.resolveSibling(AppConfig.ID + ".properties"));

            // THE JAR NAMES FEED BOTH THE SUSPECTED MODS AND THE EXTENSION ALERTS OF THE REPORT
            try (final Stream<Path> stream = Files.isDirectory(mods) ? Files.list(mods) : Stream.empty()) {
                this.jars = stream.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                        .filter(name -> name.endsWith(".jar"))
                        .toList();
            } catch (final IOException e) {
                this.jars = List.of();
                WaterMedia.LOGGER.warn(IT, "Cannot list the mods folder; the report names no suspected mods or extensions", e);
            }
        } finally {
            this.working = false;
            this.repaint.run();
        }
    }

    // FILE TEXT WITH ITS SECRETS AND THE USER NAME OF ITS PATHS MASKED BEFORE IT LEAVES THE MACHINE, OR null (AND A READ
    // ERROR ROW): hs_err DUMPS AND LAUNCH LOGS CARRY THE FULL --accessToken, CONFIG COPIES THE FILE-SERVER TOKEN AND COOKIES
    String readContent(final FileEntry entry) {
        try (final BufferedReader reader = lenientReader(entry.path)) {
            final StringBuilder sb = new StringBuilder();
            final char[] buf = new char[8_192];
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            return maskUser(DataTool.redact(sb.toString()), HOME_PATH);
        } catch (final Exception e) {
            entry.state = "READ ERROR";
            entry.progress = 0;
            this.error = true;
            WaterMedia.LOGGER.error(IT, "Failed to read {}", entry.name, e);
            return null;
        }
    }

    // ISSUE BODY IN GITHUB MARKDOWN: THE DESCRIPTION WHEN THERE IS ONE, THEN THE ALERTS, THE SUSPECTED MODS, THE FILE
    // LINKS AND THE SYSTEM FACTS, WITH THE USER NAME OF EVERY PATH MASKED
    static String body(final String description, final SystemInfo info, final List<String> jars, final List<FileEntry> files) {
        final StringBuilder sb = new StringBuilder();
        final String text = description.strip();
        if (!text.isEmpty()) sb.append(text).append("\n\n");

        sb.append("## Alerts\n");
        for (final String alert: info.alerts()) sb.append("- ").append(alert).append('\n');
        if (info.alerts().isEmpty()) sb.append("- None detected\n");

        // A SUSPECT HAS ITS id INSIDE A LOWERCASED JAR NAME ("fancymenu" IN "fancymenu-forge-1.20.1-x.y.z.jar")
        final List<SuspectMod> suspects = SUSPECT_MODS.stream().filter(mod -> jars.stream().anyMatch(jar -> jar.contains(mod.id()))).toList();
        sb.append("\n## Suspected mods\n");
        for (final SuspectMod mod: suspects) sb.append("- ").append(mod.name()).append(" (`").append(mod.slug()).append("`)\n");
        if (suspects.isEmpty()) sb.append("- None detected\n");

        // PENDING: ERROR CODES ARE NOT LOGGED YET (body() TAKES final Set<String> codes LAST AND submit() PASSES this.codes)
        // sb.append("\n## Error codes\n");
        // sb.append(codes.isEmpty() ? "- None detected\n" : "- `wm_err:" + String.join("`, `wm_err:", codes) + "`\n");

        sb.append("\n## Files\n");
        for (final FileEntry entry: files) sb.append("- ").append(entry.label).append(": ").append(entry.uploaded ? entry.url : "N/A").append('\n');

        // THE CODEC LIST IS LONG, SO IT FOLDS; GITHUB RENDERS MARKDOWN INSIDE THE FOLD ONLY BETWEEN BLANK LINES
        sb.append("\n## Environment\n").append(info.environment());
        if (info.codecs() == null) sb.append("- FFmpeg SW codecs: not loaded\n");
        else sb.append("\n<details>\n<summary>FFmpeg SW codecs (").append(info.codecs().size()).append(")</summary>\n\n")
                .append(String.join(", ", info.codecs())).append("\n\n</details>\n");

        return maskUser(sb.append("\n## System Properties\n").append(info.system()).toString(), HOME_PATH);
    }

    // HIDES THE USER NAME OF HOME PATHS AND OF AN hs_err USERNAME LINE, KEEPING THE REST OF EVERY PATH; home GOES FIRST
    // BECAUSE ONLY IT KNOWS A NAME WITH SPACES OR A HOME OUTSIDE THE USUAL FOLDERS
    static String maskUser(final String text, final Pattern home) {
        final String out = home == null ? text : home.matcher(text).replaceAll(USER_MASK);
        return USER_ENV.matcher(USER_PATH.matcher(out).replaceAll(USER_MASK)).replaceAll(USER_MASK);
    }

    // A HOME FOLDER WITH ANY SEPARATOR AND ITS LAST NAME APART, OR null WHEN NO PARENT FOLDER TELLS IT FROM A PLAIN WORD
    static Pattern homePath(final String home) {
        final String[] names = home == null ? new String[0] : home.strip().split("[\\\\/]+");
        if (names.length < 3) return null;
        final StringBuilder regex = new StringBuilder("(");
        for (int i = 0; i < names.length - 1; i++) regex.append(Pattern.quote(names[i])).append("[\\\\/]+");
        regex.append(')').append(Pattern.quote(names[names.length - 1])).append("(?![^\\\\/\\s\"'<>|:*?])");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    // LINK OF THE NEW ISSUE PAGE; THE BODY RIDES IN IT ONLY WHILE THE WHOLE LINK FITS, ELSE A NOTE ASKS FOR THE CLIPBOARD
    static String issueUrl(final String title, final String body) {
        final String page = "https://github.com/" + REPO + "/issues/new?title=" + URLEncoder.encode(title, StandardCharsets.UTF_8) + "&body=";
        final String full = page + URLEncoder.encode(body, StandardCharsets.UTF_8);
        return full.length() <= URL_LIMIT_CHARS ? full : page + URLEncoder.encode(PASTE_NOTE, StandardCharsets.UTF_8);
    }

    // ==========================================================================
    // WORKER STAGES
    // ==========================================================================
    // HEAVY FILE READS, SYSTEM QUERIES AND NETWORK CALLS MUST NOT BLOCK THE RENDER LOOP

    private void addScannedFile(final String name, final String label, final Path path) {
        final FileEntry entry = new FileEntry(name, label, path);
        if (path != null && Files.isRegularFile(path)) {
            try {
                entry.present = true;
                entry.valid = true;
                final long size = Files.size(path);
                entry.sizeLabel = MathUtil.displayBytes(size);
                if (size > UPLOAD_LIMIT_BYTES || exceedsLineLimit(path)) {
                    entry.valid = false;
                    entry.state = "TOO LARGE";
                    this.error = true;
                } else {
                    entry.state = "READY";
                }
            } catch (final IOException e) {
                entry.present = false;
                entry.valid = false;
                entry.state = "READ ERROR";
                entry.sizeLabel = "-";
                this.error = true;
                WaterMedia.LOGGER.error(IT, "Failed to measure {}", name, e);
            }
        } else {
            entry.state = "NOT FOUND";
        }
        this.files.add(entry);
    }

    private void upload() {
        try {
            this.stage = 2;
            this.error = false;
            this.repaint.run();

            boolean anyUploaded = false;
            for (final FileEntry entry: this.files) {
                if (!isUploadable(entry)) continue;
                entry.state = "UPLOADING";
                entry.progress = 18;
                final String content = this.readContent(entry);
                if (content == null) continue;
                // PENDING: ERROR CODES ARE NOT LOGGED YET
                // final Matcher codes = Pattern.compile("\\bwm_err:(\\d{4})\\b").matcher(content);
                // while (codes.find()) this.codes.add(codes.group(1));
                entry.progress = 64;
                anyUploaded |= this.uploadToMclogs(content, entry);
            }

            // A FAILED FILE ALREADY RAISED THE ERROR; A STAGE THAT SENT NOTHING FAILED AS A WHOLE
            if (!anyUploaded) this.error = true;
            this.uploadsDone = !this.error;
        } catch (final Exception e) {
            this.error = true;
            WaterMedia.LOGGER.error(IT, "The upload stage failed", e);
        } finally {
            this.working = false;
            this.repaint.run();
        }
    }

    private boolean uploadToMclogs(final String content, final FileEntry entry) {
        // NetRequest BOUNDS THE REQUEST WITH THE NETWORK TIMEOUTS AND ITS REPLY WITH THE TEXT BUDGET, AND KEEPS EVERY HOP ON HTTPS
        try (final NetRequest req = new NetRequest(URI.create("https://api.mclo.gs/1/log")).method("POST").httpsOnly()
                .contentType(NetRequest.MIMETYPE_FORM).body("content=" + URLEncoder.encode(content, StandardCharsets.UTF_8)).send()) {
            if (req.statusCode() == 200) {
                final JsonObject json = req.json(JsonObject.class);
                if (json != null && json.get("success").getAsBoolean()) {
                    entry.url = json.get("url").getAsString();
                    entry.uploaded = true;
                    entry.progress = 100;
                    entry.state = "UPLOADED";
                    return true;
                }
            }
            WaterMedia.LOGGER.warn(IT, "mclo.gs refused {} with status {}", entry.name, req.statusCode());
        } catch (final Exception e) {
            WaterMedia.LOGGER.error(IT, "Failed to upload {}", entry.name, e);
        }
        entry.progress = 0;
        entry.state = "FAILED";
        this.error = true;
        return false;
    }

    private void report() {
        try {
            this.info = SystemInfo.read(this.jars);
        } catch (final Throwable t) {
            // NO SYSTEM FACTS, NO REPORT, WHATEVER BROKE: THE STAGE SHOWS THE FAILURE AND ITS NEXT step() READS THEM AGAIN.
            // AN Error LEFT UNCAUGHT WOULD LEAVE THE STRIP PREPARING FOREVER NEXT TO A RETRY BUTTON
            this.error = true;
            WaterMedia.LOGGER.error(IT, "Failed to read the system for the report", t);
        } finally {
            this.working = false;
            this.repaint.run();
        }
    }

    private void submit() {
        // WHETHER A FAILURE CAME FROM THE BROWSER: THE CLIPBOARD ALONE FAILS BEFORE THE BROWSER IS EVER TRIED
        boolean browsing = false;
        try {
            final String title = this.title.strip();
            final String body = body(this.description, this.info, this.jars, this.files);
            final String url = issueUrl(title, body);
            final String note = issueUrl(title, PASTE_NOTE);
            // THE FULL REPORT GOES TO THE CLIPBOARD FIRST; WITHOUT IT ONLY A LINK THAT CARRIES THE REPORT IS WORTH OPENING
            this.issueCopied = AppContext.clipboard(body);
            if (!this.issueCopied && url.equals(note)) throw new IOException("The report fits neither the clipboard nor the link");
            browsing = true;
            try { Desktop.getDesktop().browse(URI.create(url)); }
            catch (final Exception e) {
                // THE SYSTEM MAY REFUSE A LONG LINK: ONE RETRY WITH THE SHORT ONE WHILE THE CLIPBOARD HOLDS THE REPORT
                if (!this.issueCopied || url.equals(note)) throw e;
                WaterMedia.LOGGER.warn(IT, "The system refused the issue link; retrying without the report in it", e);
                Desktop.getDesktop().browse(URI.create(note));
            }
            this.issueOpened = true;
            this.done = true;
        } catch (final Exception e) {
            this.error = true;
            this.browserFailed = browsing;
            WaterMedia.LOGGER.error(IT, "Failed to open the issue page", e);
        } finally {
            this.working = false;
            this.repaint.run();
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static boolean isUploadable(final FileEntry entry) {
        return entry.present && entry.valid && !entry.uploaded && !"FAILED".equals(entry.state) && !"READ ERROR".equals(entry.state);
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

    // NEWEST MATCHING FILE OF THE DIRECTORIES BY MODIFICATION TIME, OR NULL; AN UNREADABLE TIME COUNTS AS THE OLDEST AND
    // AN UNLISTABLE DIRECTORY OFFERS NOTHING
    private static Path latestFile(final Predicate<Path> filter, final Path... dirs) {
        Path latest = null;
        long latestTime = Long.MIN_VALUE;
        for (final Path dir: dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (final Stream<Path> stream = Files.list(dir)) {
                for (final Path file: stream.filter(filter).toList()) {
                    long time = 0L;
                    try { time = Files.getLastModifiedTime(file).toMillis(); }
                    catch (final IOException ignored) {}
                    if (time <= latestTime) continue;
                    latest = file;
                    latestTime = time;
                }
            } catch (final IOException | RuntimeException ignored) {}
        }
        return latest;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    private record SuspectMod(String id, String name, String slug) {}

    /** One file of the dialog: its row name, the label of its line in the report, where it lives and how its upload went. */
    public static final class FileEntry {
        public final String name;
        public final String label;
        public final Path path;
        public volatile String sizeLabel = "-";
        public volatile String state = "PENDING";
        public volatile String url = "";
        public volatile int progress;
        public volatile boolean present;
        public volatile boolean valid;
        public volatile boolean uploaded;

        public FileEntry(final String name, final String label, final Path path) {
            this.name = name;
            this.label = label;
            this.path = path;
        }
    }
}
