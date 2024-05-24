package me.srrapero720.watermedia.tools;

import me.srrapero720.watermedia.api.image.decoders.GifDecoder;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static me.srrapero720.watermedia.WaterMedia.LOGGER;

public class IOTool {
    private static final Marker IT = MarkerManager.getMarker("Tools");

    public static String readString(Path from) {
        try {
            byte[] bytes = Files.readAllBytes(from);
            return new String(bytes, Charset.defaultCharset());
        } catch (Exception e) {
            return null;
        }
    }

    public static GifDecoder readGif(Path path) {
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(path.toFile().toPath()))) {
            GifDecoder gif = new GifDecoder();
            int status = gif.read(in);
            if (status == GifDecoder.STATUS_OK) return gif;

            throw new IOException("Failed to process GIF - Decoder status: " + status);
        } catch (Exception e) {
            LOGGER.error(IT, "Failed loading GIF from WaterMedia resources", e);
        }
        return null;
    }

    public static void un7zip(Marker it, Path zipFilePath) throws IOException { un7zip(it, zipFilePath, zipFilePath.getParent()); }
    public static void un7zip(Marker it, Path zipFilePath, Path destDirectory) throws IOException {
        LOGGER.debug(it, "Un7zipping file from '{}' to directory '{}'", zipFilePath, destDirectory);
        if (zipFilePath.toString().endsWith(".zip")) throw new IOException("Attempted to extract a .zip as a .7z");
        File destDir = destDirectory.toFile();
        if (!destDir.exists()) destDir.mkdir();

//        try (SevenZFile sevenZFile = new SevenZFile(zipFilePath.toFile(), "watermedia-is-my-lord".getBytes(StandardCharsets.UTF_16LE))) {
        try (SevenZFile sevenZFile = new SevenZFile(zipFilePath.toFile())) {
            SevenZArchiveEntry entry = sevenZFile.getNextEntry();

            while (entry != null) {
                File outputFile = new File(destDirectory + File.separator + entry.getName());
                if (!outputFile.exists()) {
                    if (entry.isDirectory()) {
                        if (!outputFile.mkdirs()) LOGGER.error(IT, "Cannot create directories of '{}'", entry.getName());
                    } else {
                        un7zip$extract(sevenZFile, outputFile.toPath());
                    }
                } else {
                    LOGGER.warn(it, "Cancelled un7zip attempt of '{}', file already exists", entry.getName());
                }

                entry = sevenZFile.getNextEntry();
            }
        }
    }

    private static void un7zip$extract(SevenZFile sevenZFile, Path filePath) throws IOException {
        try (BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(filePath))) {
            byte[] content = new byte[4096 * 4]; // MEMORY AHEAD
            int read;
            while ((read = sevenZFile.read(content)) != -1) bos.write(content, 0, read);
        }
    }

    public static void unzip(Marker it, Path zipFilePath) throws IOException { unzip(it, zipFilePath, zipFilePath.getParent()); }
    public static void unzip(Marker it, Path zipFilePath, Path destDirectory) throws IOException {
        LOGGER.debug(it, "Unzipping file from '{}' to directory '{}'", zipFilePath, destDirectory);
        if (zipFilePath.toString().endsWith(".7z")) throw new IOException("Attempted to extract a 7z as a .zip");
        File destDir = destDirectory.toFile();
        if (!destDir.exists()) destDir.mkdirs();

        try (ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(zipFilePath))) {
            ZipEntry entry = zipIn.getNextEntry();
            // iterates over entries in the zip file
            while (entry != null) {
                String filePath = destDirectory + File.separator + entry.getName();
                if (!entry.isDirectory()) {
                    // if the entry is a file, extracts it
                    unzip$extract(zipIn, filePath);
                } else {
                    // if the entry is a directory, make the directory
                    File dir = new File(filePath);
                    dir.mkdirs();
                }
                zipIn.closeEntry();
                entry = zipIn.getNextEntry();
            }
        }
    }

    private static void unzip$extract(ZipInputStream zipIn, String filePath) throws IOException {
        try (BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(Paths.get(filePath)))) {
            byte[] bytesIn = new byte[4096];
            int read;
            while ((read = zipIn.read(bytesIn)) != -1) output.write(bytesIn, 0, read);
        }
    }
}