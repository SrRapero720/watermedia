package me.srrapero720.watermedia.api.network.streams;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamQuality implements Comparable<StreamQuality> {
    private static final Pattern M3U8_STREAM_INF_RE = Pattern.compile("^#EXT-X-STREAM-INF:(.*)");
    private static final Pattern M3U8_INF_VALUE_RE = Pattern.compile("([A-Z-]+)=(?:([^,]+)|\"([^\"]+?)\")");
    private static final Pattern HTTP_URL_RE = Pattern.compile("https?://.*");

    private int bandwidth;
    private int width;
    private int height;
    private int framerate;
    private String codecs;
    private String url;

    public void setWidth(int width) {
        this.width = width;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public void setFramerate(int framerate) {
        this.framerate = framerate;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setBandwidth(int bandwidth) {
        this.bandwidth = bandwidth;
    }

    public void setCodecs(String codecs) {
        this.codecs = codecs;
    }

    public void setResolution(String resolution) {
        String[] res = resolution.split("x");
        this.width = Integer.parseInt(res[0]);
        this.height = Integer.parseInt(res[1]);
    }

    public String getCodecs() {
        return codecs;
    }

    public int getBandwidth() {
        return bandwidth;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFramerate() {
        return framerate;
    }

    public String getUrl() {
        return url;
    }

    public static List<StreamQuality> parse(String playlistData) {
        String[] lines = playlistData.split("\n");
        List<StreamQuality> streamQualities = new ArrayList<>();
        StreamQuality currentQuality = null;

        for (String line : lines) {
            Matcher matcher = M3U8_STREAM_INF_RE.matcher(line);
            if (matcher.matches()) {
                String streamInformation = matcher.group(1);
                currentQuality = new StreamQuality();

                Matcher valueMatcher = M3U8_INF_VALUE_RE.matcher(streamInformation);
                while (valueMatcher.find()) {
                    String key = valueMatcher.group(1);
                    String value = valueMatcher.group(2);
                    if (value == null) value = valueMatcher.group(3);

                    // Note: using `parseFloat` to have a more lax parser which does not panic on "60.000".
                    // Twitch sends framerate using this notation which causes parseInt to throw.
                    switch (key) {
                        case "BANDWIDTH":
                            currentQuality.setBandwidth((int) Float.parseFloat(value));
                            break;
                        case "RESOLUTION":
                            currentQuality.setResolution(value);
                            break;
                        case "CODECS":
                            currentQuality.setCodecs(value);
                            break;
                        case "FRAME-RATE":
                            currentQuality.setFramerate((int) Float.parseFloat(value));
                            break;
                    }
                }
            } else if (HTTP_URL_RE.matcher(line).matches()) {
                if (currentQuality != null) {
                    currentQuality.setUrl(line);
                    streamQualities.add(currentQuality);
                    currentQuality = null;
                }
            }
        }

        // Sort them (in reverse) based on their width and framerate
        // Assumptions made here:
        // - All videos have the same aspect ratio
        //   => as height is proportional to width don't need to take it into account
        // - Video resolution is more important than framerate
        //   => in practice higher resolutions always come with higher framerate (we parse from YT and Twitch)
        streamQualities.sort((q1, q2) -> {
            int res = q2.width - q1.width;
            if (res == 0) res = q2.framerate - q1.framerate;
            return res;
        });

        return streamQualities;
    }

    @Override
    public int compareTo(StreamQuality streamQuality) {
        int res = streamQuality.width - width;
        if (res == 0) res = streamQuality.framerate - framerate;
        return res;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StreamQuality)) return false;
        StreamQuality that = (StreamQuality) o;
        return bandwidth == that.bandwidth && width == that.width && height == that.height && framerate == that.framerate && Objects.equals(codecs, that.codecs) && Objects.equals(url, that.url);
    }

    @Override
    public String toString() {
        return String.format("Bandwidth: %d, Resolution: %dx%d, Framerate: %d, Codecs: %s, URL: %s",
                bandwidth, width, height, framerate, codecs, url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bandwidth, width, height, framerate, codecs, url);
    }
}
