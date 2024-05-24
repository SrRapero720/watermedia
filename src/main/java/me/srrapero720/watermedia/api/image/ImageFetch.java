package me.srrapero720.watermedia.api.image;

import me.lib720.watermod.concurrent.ThreadCore;
import me.srrapero720.watermedia.api.cache.CacheAPI;
import me.srrapero720.watermedia.api.cache.CacheEntry;
import me.srrapero720.watermedia.api.image.decoders.GifDecoder;
import me.srrapero720.watermedia.api.network.DynamicURL;
import me.srrapero720.watermedia.tools.ByteTool;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static me.srrapero720.watermedia.WaterMedia.LOGGER;
import static me.srrapero720.watermedia.api.network.NetworkAPI.USER_AGENT;

/**
 * Tool to fetch new images from internet
 * stores all loaded pictures in our cache to skip downloading image 2 times
 */
public class ImageFetch {
    private static final Marker IT = MarkerManager.getMarker("ImageAPI");
    private static final DateFormat FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
    private static final ExecutorService EX = Executors.newScheduledThreadPool(ThreadCore.minThreads(), ThreadCore.factory("ImageFetch-Worker"));

    private final String url;
    private TaskSuccessful successful;
    private TaskFailed failed;

    /**
     * Creates a new Fetch instance.
     * The Task is ASYNC by default.
     * You should concern about concurrency on callbacks
     * @param url string url to fetch
     */
    public ImageFetch(String url) { this.url = url; }

    /**
     * Adds a new success callback
     * @param task callback
     * @return current instance
     */
    public ImageFetch setOnSuccessCallback(TaskSuccessful task) { successful = task; return this; }

    /**
     * Adds a new failed callback
     * @param task callback
     * @return current instance
     */
    public ImageFetch setOnFailedCallback(TaskFailed task) { failed = task; return this; }

    /**
     * Start image fetch
     * result is fired on callbacks
     */
    public void start() { EX.execute(this::run); }
    private void run() {
        try {
            DynamicURL result = new DynamicURL(url);
            if (result.isVideo()) throw new NoPictureException();

            byte[] data = load(result);
            String type = readType(data);

            try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
                if (type != null && type.equalsIgnoreCase("gif")) {
                    GifDecoder gif = new GifDecoder();
                    int status = gif.read(in);

                    if (status == GifDecoder.STATUS_OK) {
                        if (successful != null) successful.run(ImageAPI.renderer(gif));
                    } else {
                        LOGGER.error(IT, "Failed to read gif: {}", status);
                        throw new GifDecodingException();
                    }
                } else {
                    try {
                        BufferedImage image = ImageIO.read(in);
                        if (image != null) {
                            if (successful != null) successful.run(ImageAPI.renderer(image));
                        }
                    } catch (IOException e1) {
                        LOGGER.error(IT, "Failed to parse BufferedImage from stream", e1);
                        throw e1;
                    }
                }
            }
        } catch (Exception e) {
            if (!(e instanceof NoPictureException)) {
                LOGGER.error(IT, "An exception occurred while loading image", e);
            }
            if (failed != null) failed.run(e);
            CacheAPI.deleteEntry(url);
        }
    }

    private static byte[] load(DynamicURL url) throws IOException, NoPictureException {
        CacheEntry entry = CacheAPI.getEntry(url.getSource());
        long requestTime = System.currentTimeMillis();
        URLConnection request = url.asURL().openConnection();
        request.setDefaultUseCaches(false);
        request.setRequestProperty("Accept", "image/*");
        int code = -1;

        request.addRequestProperty("User-Agent", USER_AGENT);
        if (request instanceof HttpURLConnection) {
            final HttpURLConnection conn = (HttpURLConnection) request;
            if (entry != null && entry.getFile().exists()) {
                if (entry.getTag() != null) conn.setRequestProperty("If-None-Match", entry.getTag());
                else if (entry.getTime() != -1) conn.setRequestProperty("If-Modified-Since", FORMAT.format(new Date(entry.getTime())));
            }
            code = conn.getResponseCode();
        }

        try (InputStream in = request.getInputStream()) {
            if (code == 400 || code == 403) throw new NoPictureException();
            if (code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                String type = request.getContentType();
                if (type == null) throw new ConnectException();
                if (!type.startsWith("image/")) throw new NoPictureException();
            }

            String tag = request.getHeaderField("ETag");
            long lastTimestamp, expTimestamp = -1;
            String maxAge = request.getHeaderField("max-age");

            // EXPIRATION GETTER FIRST
            if (maxAge != null && !maxAge.isEmpty()) {
                long parsed = ByteTool.parseLongOr(maxAge, -1);
                if (parsed != -1)
                    expTimestamp = requestTime + Long.parseLong(maxAge) * 100;
            }

            // EXPIRATION GETTER SECOND WAY
            String expires = request.getHeaderField("Expires");
            if (expires != null && !expires.isEmpty()) {
                try {
                    expTimestamp = FORMAT.parse(expires).getTime();
                } catch (ParseException | NumberFormatException ignored) {}
            }
            // LAST TIMESTAMP
            String lastMod = request.getHeaderField("Last-Modified");
            if (lastMod != null && !lastMod.isEmpty()) {
                try {
                    lastTimestamp = FORMAT.parse(lastMod).getTime();
                } catch (ParseException | NumberFormatException e) {
                    lastTimestamp = requestTime;
                }
            } else {
                lastTimestamp = requestTime;
            }

            if (entry != null) {
                String freshTag = entry.getTag();
                if (tag != null && !tag.isEmpty()) freshTag = tag;

                if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    File file = entry.getFile();

                    if (file.exists()) try (FileInputStream fileStream = new FileInputStream(file)) {
                        return ByteTool.readAllBytes(fileStream);
                    } finally {
                        CacheAPI.updateEntry(new CacheEntry(url.getSource(), freshTag, lastTimestamp, expTimestamp));
                    }
                }
            }


            byte[] data = ByteTool.readAllBytes(in);
            CacheAPI.saveFile(url.getSource(), tag, lastTimestamp, expTimestamp, data);
            return data;
        } finally {
            if (request instanceof HttpURLConnection) ((HttpURLConnection) request).disconnect();
        }
    }

    private static String readType(byte[] input) throws IOException {
        try (InputStream in = new ByteArrayInputStream(input)) {
            return readType(in);
        }
    }

    private static String readType(InputStream input) throws IOException {
        ImageReader reader = null;
        try(ImageInputStream stream = ImageIO.createImageInputStream(input)) {
            Iterator<ImageReader> iterator = ImageIO.getImageReaders(stream);

            if (!iterator.hasNext()) return null;

            reader = iterator.next();
            if (reader.getFormatName().equalsIgnoreCase("gif")) return "gif";

            ImageReadParam param = reader.getDefaultReadParam();
            reader.setInput(stream, true, true);
            reader.read(0, param);
        } catch (IOException e) {
            LOGGER.error(IT, "Failed to parse input format", e);
        } finally {
            if (reader != null) reader.dispose();
        }
        input.reset();
        if (reader == null) return null;
        return reader.getFormatName();
    }

    public static final class NoPictureException extends Exception {}
    public static final class GifDecodingException extends Exception {}
    public interface TaskSuccessful { void run(ImageRenderer renderer); }
    public interface TaskFailed { void run(Exception e); }
}