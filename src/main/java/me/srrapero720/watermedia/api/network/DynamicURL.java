package me.srrapero720.watermedia.api.network;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;

public final class DynamicURL {
    private final String source;
    private final URL url;
    private final File file;
    boolean video = false;
    boolean stream = false;
    boolean cached = false;
    boolean exists = false;

    public DynamicURL(String source, URL url, File file) {
        this.source = source;
        this.url = url;
        this.file = file;
    }

    public DynamicURL(String urlOrPath) {
        if (urlOrPath.startsWith("local://")) {
            urlOrPath = urlOrPath.replace("local://", "");
            if (urlOrPath.startsWith(File.pathSeparator)) urlOrPath = urlOrPath.substring(1);
            urlOrPath = new File(urlOrPath).getAbsolutePath();
            this.stream = false;
            this.cached = true;
        }
        this.source = urlOrPath;
        this.url = NetworkAPI.parseUrl(urlOrPath);
        this.file = (this.url == null) ? new File(urlOrPath) : null;
    }

    public DynamicURL(final String urlOrPath, final boolean isVideo, final boolean isStream) {
        this(urlOrPath);
        this.video = isVideo;
        this.stream = isStream;
    }

    public DynamicURL(final File file) {
        this.source = file.getAbsolutePath();
        this.file = file;
        this.url = NetworkAPI.parseUrl(file.toURI());
    }

    public DynamicURL(final Path path) {
        this(path.toFile());
    }

    public String getSource() {
        return source;
    }

    public URL asURL() {
        return this.url;
    }

    public File asFile() {
        if (!isLocal()) throw new UnsupportedOperationException("DynamicURL doesn't point to any file");
        return file;
    }

    public boolean isLocal() {
        return this.file != null;
    }

    public boolean isVideo() {
        return this.video;
    }

    public boolean isStream() {
        return this.stream;
    }

    public boolean isCached() {
        return this.cached;
    }

    public boolean exists() {
        return cached ? exists : (exists = this.file.exists());
    }
}
