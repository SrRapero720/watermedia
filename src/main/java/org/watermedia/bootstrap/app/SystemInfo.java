package org.watermedia.bootstrap.app;

import com.sun.management.OperatingSystemMXBean;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.watermedia.WaterMedia;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.bootstrap.AppBootstrap;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.FFTool;
import org.watermedia.tools.IOTool;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WaterMedia, FFmpeg, OS, CPU, GPU and JVM facts for the issue report; a fact that cannot be read reads "unknown". */
final class SystemInfo {
    private SystemInfo() {}

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // MARKDOWN LIST OF THE PROBLEMS DETECTED ON THIS MACHINE, OR A "None detected" LINE
    static String alerts() {
        final List<String> alerts = new ArrayList<>();
        final String nvidia = nvidiaThreadedOptimizationAlert();
        if (nvidia != null) alerts.add(nvidia);
        if (CodecsAPI.ffmpegError())
            alerts.add("FFmpeg failed to load: media playback is unavailable. See the attached logs for the native load error.");
        if (alerts.isEmpty()) return "- None detected\n";
        final StringBuilder sb = new StringBuilder();
        for (final String a: alerts) sb.append("- ").append(a).append('\n');
        return sb.toString();
    }

    // THE "Environment" AND "System Properties" MARKDOWN SECTIONS OF THE REPORT
    static String summary() {
        final Runtime rt = Runtime.getRuntime();
        return "## Environment\n" +
                "- WaterMedia: " + WaterMedia.VERSION + " (" + RenderSystem.engineVersionLabel() + ")\n" +
                "- Binaries: " + binariesVersion() + "\n" +
                "- FFmpeg: " + ffmpegVersion() + "\n" +
                "- FFmpeg HW accel: " + ffmpegHwAccel() + "\n" +
                "- FFmpeg codecs (SW): " + ffmpegSoftwareCodecs() + "\n" +
                "- Extensions: " + detectedExtensions() + "\n\n" +
                "## System Properties\n" +
                "- OS: " + osInfo() + "\n" +
                "- CPU: " + cpuInfo() + "\n" +
                "- GPU: " + gpuInfo() + "\n" +
                "- RAM (JVM max): " + rt.maxMemory() / 1_024 / 1_024 + " MB\n" +
                "- RAM (system): " + systemRam() + "\n" +
                "- Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\n" +
                "- Java Home: " + System.getProperty("java.home") + "\n" +
                "- FFmpeg Path: " + ffmpegPath() + "\n" +
                "- FFmpeg Loaded: " + CodecsAPI.ffmpegLoaded() + "\n" +
                "- User Dir: " + System.getProperty("user.dir") + "\n";
    }

    // ==========================================================================
    // WATERMEDIA AND FFMPEG
    // ==========================================================================
    // BINARIES ARE OPTIONAL FOR THE APP: WITHOUT THEM THE REPORT SAYS SO INSTEAD OF FAILING ON THE MISSING CLASS
    private static String binariesVersion() {
        try { return IOTool.jarVersion(WaterMediaBinaries.class); }
        catch (final LinkageError missing) { return "not installed"; }
    }

    private static String ffmpegVersion() {
        if (!CodecsAPI.ffmpegLoaded()) return "not loaded";
        try {
            return FFTool.string(avutil.av_version_info(), "unknown")
                    + " (avutil " + FFTool.parseVersion(avutil.avutil_version())
                    + ", avcodec " + FFTool.parseVersion(avcodec.avcodec_version())
                    + ", avformat " + FFTool.parseVersion(avformat.avformat_version()) + ")";
        } catch (final Throwable t) {
            return "unknown";
        }
    }

    private static String ffmpegHwAccel() {
        // WATERMEDIA'S OWN IMAGE CODECS ARE PURE-JAVA (ALWAYS PRESENT), SO THE USEFUL, VARIABLE SIGNAL IS
        // WHICH FFMPEG HARDWARE ACCELERATIONS THE PLATFORM BUILD/GPU EXPOSES (dxva2/d3d11va/cuda/qsv/vaapi...)
        if (!CodecsAPI.ffmpegLoaded()) return "not loaded";
        try {
            final StringBuilder sb = new StringBuilder();
            int type = avutil.AV_HWDEVICE_TYPE_NONE;
            while ((type = avutil.av_hwdevice_iterate_types(type)) != avutil.AV_HWDEVICE_TYPE_NONE) {
                final BytePointer name = avutil.av_hwdevice_get_type_name(type);
                if (name == null) continue;
                final String s = name.getString();
                if (s != null && !s.isBlank()) {
                    if (!sb.isEmpty()) sb.append(", ");
                    sb.append(s);
                }
            }
            return sb.isEmpty() ? "none" : sb.toString();
        } catch (final Throwable t) {
            return "unknown";
        }
    }

    private static String ffmpegSoftwareCodecs() {
        // SOFTWARE DECODERS OF THIS BUILD; THE SET VARIES BY OS/ARCH, SO IT SHOWS WHETHER A CODEC IS MISSING FROM
        // THE PLATFORM BUILD
        if (!CodecsAPI.ffmpegLoaded()) return "not loaded";
        try {
            final TreeSet<String> names = new TreeSet<>();
            try (final PointerPointer<Pointer> opaque = new PointerPointer<>(1L)) {
                opaque.put(0L, null);
                AVCodec c;
                while ((c = avcodec.av_codec_iterate(opaque)) != null) {
                    if (avcodec.av_codec_is_decoder(c) == 0) continue;
                    final int type = c.type();
                    if (type != avutil.AVMEDIA_TYPE_VIDEO && type != avutil.AVMEDIA_TYPE_AUDIO) continue;
                    if ((c.capabilities() & avcodec.AV_CODEC_CAP_HARDWARE) != 0) continue;
                    final BytePointer name = c.name();
                    if (name == null) continue;
                    final String s = name.getString();
                    if (s != null && !s.isBlank()) names.add(s);
                }
            }
            return names.isEmpty() ? "none" : String.join(", ", names);
        } catch (final Throwable t) {
            return "unknown";
        }
    }

    private static String ffmpegPath() {
        try {
            final Path p = WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID);
            return p != null ? p.toAbsolutePath().toString() : "N/A";
        } catch (final Throwable t) {
            return "N/A";
        }
    }

    private static String detectedExtensions() {
        // ENUMERATE THE BOOTSTRAP EXTENSION SPI PROVIDERS BY CLASS; provider.type() DOES NOT INSTANTIATE THEM
        try {
            final StringBuilder sb = new StringBuilder();
            for (final var provider: ServiceLoader.load(AppBootstrap.Extension.class).stream().toList()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(provider.type().getSimpleName());
            }
            return sb.isEmpty() ? "none" : sb.toString();
        } catch (final Throwable t) {
            return "unknown";
        }
    }

    // ==========================================================================
    // MACHINE
    // ==========================================================================
    private static String nvidiaThreadedOptimizationAlert() {
        // NVIDIA "Threaded Optimization" CRASHES OR STUTTERS LWJGL APPS AND HAS NO JAVA API; THE GL WORKER THREADS
        // IT SPAWNS INSIDE nvoglv64.dll GIVE IT AWAY. WINDOWS + OPENGL + NVIDIA ONLY
        try {
            if (RenderSystem.engineKind() != RenderSystem.Engine.OPENGL) return null;
            if (!"windows".equals(IOTool.os())) return null;
            final String gpu = RenderSystem.deviceName();
            if (gpu == null || !gpu.toLowerCase(Locale.ROOT).contains("nvidia")) return null;

            final long pid = ProcessHandle.current().pid();
            final String out = runCommand(4_000, "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "$p=Get-Process -Id " + pid + "; $m=$p.Modules|?{$_.ModuleName -ieq 'nvoglv64.dll'}|select -First 1; "
                            + "if($m){$b=[int64]$m.BaseAddress;$e=$b+$m.ModuleMemorySize;"
                            + "($p.Threads|?{$a=[int64]$_.StartAddress;$a -ge $b -and $a -lt $e}).Count}else{'nomodule'}");
            if (out == null) return null;
            final int workers = DataTool.parseInt(out, -1);
            if (workers > 0)
                return "NVIDIA \"Threaded Optimization\" appears ENABLED (" + workers + " GL worker thread(s) inside nvoglv64.dll). "
                        + "It is a known cause of OpenGL crashes/stutter with LWJGL; turn it Off in "
                        + "NVIDIA Control Panel > Manage 3D Settings > Threaded Optimization.";
        } catch (final Throwable ignored) {}
        return null;
    }

    private static String osInfo() {
        final String base = System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")";
        if (!"windows".equals(IOTool.os())) return base;
        // os.version ON WINDOWS IS ONLY "10.0": THE REAL BUILD NUMBER COMES FROM "cmd /c ver"; MATCH THE DOTTED
        // VERSION ITSELF, NOT THE WORD "Version" (cmd IS LOCALIZED, e.g. "Versión")
        final String out = runCommand(2_500, "cmd", "/c", "ver");
        if (out == null) return base;
        final Matcher build = Pattern.compile("(\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?)").matcher(out);
        return build.find() ? base + " [Build " + build.group(1) + "]" : base;
    }

    private static String cpuInfo() {
        // COMMERCIAL NAME (e.g. "AMD Ryzen 7 2700X") THEN THE RAW IDENTIFIER IN PARENTHESES, THEN CORE/THREAD COUNTS
        final int logical = Runtime.getRuntime().availableProcessors();
        final String env = System.getenv("PROCESSOR_IDENTIFIER"), arch = System.getProperty("os.arch");
        final String identifier = (env != null && !env.isBlank() ? env : arch != null && !arch.isBlank() ? arch : "unknown").trim();
        String name = null;
        int cores = -1;
        int threads = logical;

        if ("windows".equals(IOTool.os())) {
            final String[] cpu = queryWindowsCpu();
            if (cpu != null) {
                if (!cpu[0].isBlank()) name = cleanCpuName(cpu[0]);
                cores = DataTool.parseInt(cpu[1], -1);
                threads = DataTool.parseInt(cpu[2], logical);
            }
        }

        final String counts = cores > 0 ? cores + " cores / " + threads + " threads" : threads + " threads";
        return (name != null && !name.isBlank() ? name + " (" + identifier + ")" : identifier) + " - " + counts;
    }

    private static String cleanCpuName(final String name) {
        // STRIPS (R)/(TM) MARKS, THE "@ 3.60GHz" CLOCK, "Eight-Core Processor", A STANDALONE "CPU" AND A TRAILING "Processor":
        // "Intel(R) Xeon(R) CPU E5-2680 v4 @ 2.40GHz" -> "Intel Xeon E5-2680 v4"
        return name.replaceAll("\\((?:R|r|TM|tm)\\)", "")
                .replaceAll("(?i)\\s*@.*$", "")
                .replaceAll("(?i)\\s+\\w+-Core Processor\\b", "")
                .replaceAll("(?i)\\bCPU\\b", "")
                .replaceAll("(?i)\\s+Processor\\b", "")
                .replaceAll("\\s+", " ").trim();
    }

    private static String[] queryWindowsCpu() {
        // {commercialName, physicalCores, logicalThreads} FROM Win32_Processor, OR null ON FAILURE
        final String out = runCommand(4_000, "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "$p=@(Get-CimInstance Win32_Processor)[0]; '{0}|{1}|{2}' -f $p.Name,$p.NumberOfCores,$p.NumberOfLogicalProcessors");
        if (out == null) return null;
        for (final String raw: out.split("\\R")) {
            final String[] parts = raw.trim().split("\\|", -1);
            if (parts.length >= 3 && !parts[0].isBlank()) return new String[]{parts[0].trim(), parts[1].trim(), parts[2].trim()};
        }
        return null;
    }

    private static String gpuInfo() {
        try {
            final String gpu = RenderSystem.deviceName();
            return gpu != null && !gpu.isBlank() ? gpu : "unknown";
        } catch (final Throwable t) {
            return "unknown";
        }
    }

    private static String systemRam() {
        try {
            final var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof final OperatingSystemMXBean sun) return sun.getTotalMemorySize() / 1_024 / 1_024 + " MB";
        } catch (final Throwable ignored) {}
        return "unknown";
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static String runCommand(final long timeoutMs, final String... command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            try (final InputStream in = p.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (final Throwable t) {
            if (p != null) p.destroyForcibly();
            return null;
        }
    }
}
