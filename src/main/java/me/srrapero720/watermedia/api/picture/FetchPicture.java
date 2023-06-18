package me.srrapero720.watermedia.api.picture;

import me.srrapero720.watermedia.MediaUtil;
import me.srrapero720.watermedia.api.external.GifDecoder;
import me.srrapero720.watermedia.api.external.ThreadUtil;
import me.srrapero720.watermedia.api.picture.cache.CachePicture;
import me.srrapero720.watermedia.api.picture.cache.CacheStorage;
import org.apache.commons.io.IOUtils;

import javax.imageio.ImageIO;
import java.io.*;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import static me.srrapero720.watermedia.WaterMedia.LOGGER;

public abstract class FetchPicture extends Thread {
    private static final Object LOCK = new Object();
    private static final String USER_AGENT = MediaUtil.getUserAgentBasedOnOS();
    private static final DateFormat FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");

    // STATUS
    public static final int MAX_FETCH = 6;
    public static int ACTIVE_FETCH = 0;

    private final String url;
    public FetchPicture(String url) {
        this.url = url;
        this.setName("WaterMedia-Picture");
        this.setDaemon(true);
        this.start();
    }

    public static boolean canSeek() { synchronized(LOCK) { return ACTIVE_FETCH < MAX_FETCH; } }

    public abstract void onFailed(Exception e);
    public abstract void onSuccess(CachePicture cachePicture);

    @Override
    public void run() {
        synchronized (LOCK) { ACTIVE_FETCH++; }

        try {
            var data = load(url);
            var type = readType(data);

            try (var in = new ByteArrayInputStream(data)) {
                if (type != null && type.equalsIgnoreCase("gif")) {
                    var gif = new GifDecoder();
                    var status = gif.read(in);

                    if (status == GifDecoder.STATUS_OK) {
                        onSuccess(new CachePicture(gif));
                    } else {
                        LOGGER.error("Failed to read gif: {}", status);
                        throw new IOException("");
                    }
                } else {
                    try {
                        var image = ImageIO.read(in);
                        if (image != null) {
                            onSuccess(new CachePicture(image));
                        }
                    } catch (IOException e1) {
                        LOGGER.error("Failed to parse BufferedImage from stream", e1);
                        throw e1;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("An exception occurred while loading Waterframes image", e);
            onFailed(e);
            CacheStorage.deleteEntry(url);
        }

        synchronized (LOCK) {
            ACTIVE_FETCH--;
        }
    }

    public static byte[] load(String url) throws IOException, VideoContentException {
        var entry = CacheStorage.getEntry(url);
        var requestTime = System.currentTimeMillis();
        var request = new URL(url).openConnection();

        var code = -1;

        request.addRequestProperty("User-Agent", USER_AGENT);
        if (request instanceof HttpURLConnection conn) {
            if (entry != null && CacheStorage.getFile(entry.getUrl()).exists()) {
                if (entry.getTag() != null) conn.setRequestProperty("If-None-Match", entry.getTag());
                else if (entry.getTime() != -1) conn.setRequestProperty("If-Modified-Since", FORMAT.format(new Date(entry.getTime())));
            }
            code = conn.getResponseCode();
        }

        try (InputStream in = request.getInputStream()) {
            if (code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                var type = request.getContentType();
                if (type == null) throw new ConnectException();
                if (!type.startsWith("image")) throw new VideoContentException();
            }

            var tag = request.getHeaderField("ETag");
            long lastTimestamp = -1, expTimestamp = -1;
            var maxAge = request.getHeaderField("max-age");

            // EXPIRATION GETTER FIRST
            if (maxAge != null && !maxAge.isEmpty())
                expTimestamp = ThreadUtil.tryAndReturn(defaultVar -> requestTime + Long.parseLong(maxAge) * 1000, expTimestamp);

            // EXPIRATION GETTER SECOND WAY
            var expires = request.getHeaderField("Expires");
            if (expires != null && !expires.isEmpty())
                expTimestamp = ThreadUtil.tryAndReturn(defaultVar -> FORMAT.parse(expires).getTime(), expTimestamp);

            // LAST TIMESTAMP
            var lastMod = request.getHeaderField("Last-Modified");
            if (lastMod != null && !lastMod.isEmpty()) {
                lastTimestamp = ThreadUtil.tryAndReturn(defaultVar -> FORMAT.parse(lastMod).getTime(), requestTime);
            } else lastTimestamp = requestTime;

            if (entry != null) {
                var freshTag = entry.getTag();
                if (tag != null && !tag.isEmpty()) freshTag = tag;

                if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    File file = CacheStorage.getFile(entry.getUrl());

                    if (file.exists()) try (var fileStream = new FileInputStream(file)) {
                        return IOUtils.toByteArray(fileStream);
                    } finally {
                        CacheStorage.updateEntry(new CacheStorage.Entry(url, freshTag, lastTimestamp, expTimestamp));
                    }
                }
            }

            byte[] data = IOUtils.toByteArray(in);
            if (readType(data) == null) throw new VideoContentException();
            CacheStorage.saveFile(url, tag, lastTimestamp, expTimestamp, data);
            return data;
        } finally {
            if (request instanceof HttpURLConnection http) http.disconnect();
        }
    }

    private static String readType(byte[] input) throws IOException {
        try (var in = new ByteArrayInputStream(input)) {
            return readType(in);
        }
    }

    private static String readType(InputStream input) throws IOException {
        var stream = ImageIO.createImageInputStream(input);
        var iterator = ImageIO.getImageReaders(stream);

        if (!iterator.hasNext()) return null;

        var reader = iterator.next();
        if (reader.getFormatName().equalsIgnoreCase("gif")) return "gif";

        var param = reader.getDefaultReadParam();
        reader.setInput(stream, true, true);

        try {
            reader.read(0, param);
        } catch (IOException e) {
            LOGGER.error("Failed to parse input format", e);
        } finally {
            reader.dispose();
            IOUtils.closeQuietly(stream);
        }
        input.reset();
        return reader.getFormatName();
    }

    public static final class VideoContentException extends Exception {}
}