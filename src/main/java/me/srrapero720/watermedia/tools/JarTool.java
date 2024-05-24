package me.srrapero720.watermedia.tools;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import me.srrapero720.watermedia.api.image.decoders.GifDecoder;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static me.srrapero720.watermedia.WaterMedia.LOGGER;

public class JarTool {
    static final Marker IT = MarkerManager.getMarker("Tools");
    private static final Gson GSON = new Gson();

    @Deprecated
    public static String readString(ClassLoader loader, String source) {
        try {
            byte[] bytes = ByteTool.readAllBytes(readResource$byClassLoader(source, loader));
            return new String(bytes, Charset.defaultCharset());
        } catch (Exception e) {
            return null;
        }
    }

    @Deprecated
    public static boolean copyAsset(ClassLoader loader, String source, Path dest) {
        try (InputStream is = readResource$byClassLoader(source, loader)) {
            if (is == null) throw new FileNotFoundException("Resource was not found in " + source);

            File destParent = dest.getParent().toFile();
            if (!destParent.exists() && !destParent.mkdirs()) LOGGER.fatal(IT, "Cannot be created parent directories to {}", dest.toString());
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            LOGGER.fatal(IT, "Failed to extract from (JAR) {} to {} due to unexpected error", source, dest, e);
        }
        return false;
    }

    @Deprecated
    public static List<String> readStringList(ClassLoader loader, String source) {
        List<String> result = new ArrayList<>();
        try (InputStreamReader reader = new InputStreamReader(readResource$byClassLoader(source, loader))) {
            result.addAll(GSON.fromJson(reader, new TypeToken<List<String>>() {}.getType()));
        } catch (Exception e) {
            LOGGER.fatal(IT, "Exception trying to read JSON from {}", source, e);
        }

        return result;
    }

    @Deprecated
    public static BufferedImage readImage(ClassLoader loader, String path) {
        try (InputStream in = readResource$byClassLoader(path, loader)) {
            BufferedImage image = ImageIO.read(in);
            if (image != null) return image;
            else throw new FileNotFoundException("result of BufferedImage was null");
        } catch (Exception e) {
            throw new IllegalStateException("Failed loading BufferedImage from resources", e);
        }
    }

    @Deprecated
    public static GifDecoder readGif(ClassLoader loader, String path) {
        try (BufferedInputStream in = new BufferedInputStream(readResource$byClassLoader(path, loader))) {
            GifDecoder gif = new GifDecoder();
            int status = gif.read(in);
            if (status == GifDecoder.STATUS_OK) return gif;

            throw new IOException("Failed to process GIF - Decoder status: " + status);
        } catch (Exception e) {
            throw new IllegalStateException("Failed loading GIF from resources", e);
        }
    }

    // WITHOUT CLASSLOADER OPTIONS
    public static String readString(String from) {
        try (InputStream is = readResource(from)) {
            byte[] bytes = ByteTool.readAllBytes(is);
            return new String(bytes, Charset.defaultCharset());
        } catch (Exception e) {
            return null;
        }
    }
    public static boolean copyAsset(String origin, Path dest) {
        try (InputStream is = readResource(origin)) {
            if (is == null) throw new FileNotFoundException("Resource was not found in " + origin);

            File destParent = dest.getParent().toFile();
            if (!destParent.exists() && !destParent.mkdirs()) LOGGER.fatal(IT, "Cannot be created parent directories to {}", dest.toString());
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            LOGGER.fatal(IT, "Failed to extract from (JAR) {} to {} due to unexpected error", origin, dest, e);
        }
        return false;
    }

    public static List<String> readStringList(String path) {
        List<String> result = new ArrayList<>();
        try (InputStreamReader reader = new InputStreamReader(readResource(path))) {
            result.addAll(new Gson().fromJson(reader, new TypeToken<List<String>>() {}.getType()));
        } catch (Exception e) {
            LOGGER.fatal(IT, "Exception trying to read JSON from {}", path, e);
        }

        return result;
    }

    public static String[] readArrayAndParse(String path, Map<String, String> values) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(readResource(path)))) {
            String[] keyset = values.keySet().toArray(new String[0]);
            String[] str = new Gson().fromJson(reader, new TypeToken<String[]>() {}.getType());

            String v;
            for (int i = 0; i < str.length; i++) {
                v = str[i];
                for (int j = 0; j < keyset.length; j++) {
                    str[i] = v.replace("{" + keyset[j] + "}", values.get(keyset[j]));
                }
            }
            return str;
        }
    }

    public static BufferedImage readImage(String path) {
        try (InputStream in = readResource(path)) {
            BufferedImage image = ImageIO.read(in);
            if (image != null) return image;
            else throw new FileNotFoundException("result of BufferedImage was null");
        } catch (Exception e) {
            throw new IllegalStateException("Failed loading BufferedImage from resources", e);
        }
    }

    public static GifDecoder readGif(String path) {
        try (BufferedInputStream in = new BufferedInputStream(readResource(path))) {
            GifDecoder gif = new GifDecoder();
            int status = gif.read(in);
            if (status == GifDecoder.STATUS_OK) return gif;

            throw new IOException("Failed to process GIF - Decoder status: " + status);
        } catch (Exception e) {
            throw new IllegalStateException("Failed loading GIF from resources", e);
        }
    }

    public static InputStream readResource(String source) {
        return readResource$byClassLoader(source, JarTool.class.getClassLoader()); // InputStream still can be null
    }

    private static InputStream readResource$byClassLoader(String source, ClassLoader classLoader) {
        InputStream is = classLoader.getResourceAsStream(source);
        if (is == null && source.startsWith("/")) is = classLoader.getResourceAsStream(source.substring(1));
        return is;
    }
}