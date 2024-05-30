/*
 * This file is part of VLCJ.
 *
 * VLCJ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VLCJ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VLCJ.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2009-2019 Caprica Software Limited.
 */

package uk.co.caprica.vlcj.discovery.strategy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static uk.co.caprica.vlcj.discovery.NativeDiscovery.IT;
import static uk.co.caprica.vlcj.discovery.NativeDiscovery.LOGGER;

/**
 * Base implementation of a native discovery strategy that searches a list of directories for a list of files.
 */
public abstract class BaseNativeDiscoveryStrategy implements NativeDiscoveryStrategy {

    /**
     * Name of the system environment variable containing the VLC plugin path location.
     * <p>
     * This is optional, and might not be set.
     */
    protected static final String PLUGIN_ENV_NAME = "VLC_PLUGIN_PATH";

    /**
     * Filename patterns that must all be matched successfully.
     */
    private final Pattern[] patternsToMatch;

    /**
     * Directory name templates that will be tried to locate the VLC plugin directory, relative to the successfully
     * discovered native library directory.
     */
    private final String[] pluginPathFormats;

    /**
     * Create a new native discovery strategy.
     *
     * @param filenamePatterns filename patterns to search for, as regular expressions
     * @param pluginPathFormats directory name templates used to find the VLC plugin directory, printf style.
     */
    public BaseNativeDiscoveryStrategy(String[] filenamePatterns, String[] pluginPathFormats) {
        this.patternsToMatch = new Pattern[filenamePatterns.length];
        for (int i = 0; i < filenamePatterns.length; i++) {
            this.patternsToMatch[i] = Pattern.compile(filenamePatterns[i]);
        }
        this.pluginPathFormats = pluginPathFormats;
    }

    @Override
    public final String discover() {
        for (String discoveryDirectory : discoveryDirectories()) {
            String discoveredDirectory = find(discoveryDirectory);
            if (discoveredDirectory != null) {
                return discoveredDirectory;
            }
        }
        return null;
    }

    /**
     * Provide the list of directories to search.
     *
     * @return list of directories to search
     */
    protected abstract List<String> discoveryDirectories();

    /**
     * Returns the real path of the symlink
     * if fails or wasn't a symlink, it returns the exact same path as a File
     * @param path path to the symlink
     * @return File of the real path or the argument-ed path
     */
    private static File getSymLinkPathOrSelf(Path path) {
        if (!Files.isSymbolicLink(path)) return path.toFile();
        try {
            File symLink = Files.readSymbolicLink(path).toFile();
            if (symLink.isDirectory()) {
                LOGGER.warn(IT, "Path '{}' is a directory symlink to '{}'", path.toString(), symLink.toPath());
            } else {
                LOGGER.warn(IT, "Path '{}' is a file symlink to '{}'", path.toString(), symLink.toPath());
            }
            return symLink;
        } catch (Exception ignored) {}
        return path.toFile();
    }

    /**
     * Attempt to match all required files in a particular directory.
     * <p>
     * The directory is <em>not</em> searched <em>recursively</em>.
     *
     * @param directoryName name of the directory to search
     * @return <code>true</code> if all required files were found; <code>false</code> otherwise
     */
    // WATERMEDIA PATCH - method patched
    private String find(String directoryName) {
        File rootFile = new File(directoryName);
        File[] rootFolder = getSymLinkPathOrSelf(rootFile.toPath()).listFiles();
        if (rootFolder == null) {
            LOGGER.debug(IT, "Cannot search on '{}', exists: {} - isDirectory: {} - canRead: {} - canExecute: {} ", directoryName, rootFile.exists(), rootFile.isDirectory(), rootFile.canRead(), rootFile.canExecute());
            return null;
        }

        LOGGER.info(IT, "Searching on '{}'", directoryName);

        Set<String> matches = new HashSet<>(patternsToMatch.length);
        for (File mainFile: rootFolder) {
            if (mainFile.isDirectory()) continue;
            // check files directly
            for (Pattern pattern : patternsToMatch) {
                Matcher matcher = pattern.matcher(mainFile.getName());
                if (matcher.matches()) {
                    // A match was found for this pattern (note that it may be possible to match multiple times, any
                    // one of those matches will do so a Set is used to ignore duplicates)
                    matches.add(pattern.pattern());
                    if (matches.size() == patternsToMatch.length) {
                        return directoryName;
                    }
                }
            }
        }

        // NOTHING FOUND? CHECK RECURSIVELY
        for (File mainFile: rootFolder) {
            mainFile = getSymLinkPathOrSelf(mainFile.toPath());

            File[] subFolders = mainFile.listFiles();
            if (subFolders == null) return null;

            if (subFolders.length > 16) {
                LOGGER.debug(IT, "Skipped subdirectory '{}', contains more than 16 entries", mainFile.toString());
                continue;
            }
            LOGGER.info(IT, "Searching on subdirectory '{}'", mainFile.toString());
            Set<String> subMatches = new HashSet<>(patternsToMatch.length);
            for (File subFile: subFolders) {
                for (Pattern pattern: patternsToMatch) {
                    Matcher matcher = pattern.matcher(subFile.getName());
                    if (matcher.matches()) {
                        // A match was found for this pattern (note that it may be possible to match multiple times, any
                        // one of those matches will do so a Set is used to ignore duplicates)
                        subMatches.add(pattern.pattern());
                        if (subMatches.size() == patternsToMatch.length) {
                            return mainFile.toPath().toAbsolutePath().toString();
                        }
                    }
                }
            }
        }


        return null;
    }

    @Override
    public boolean onFound(String path) {
        return true;
    }

    @Override
    public final boolean onSetPluginPath(String path) {
        for (String pathFormat : pluginPathFormats) {
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String pluginPath = String.format(pathFormat, path);
            if (new File(pluginPath).exists()) {
                return setPluginPath(pluginPath);
            }
        }
        return false;
    }

    /**
     * Set the VLC_PLUGIN_PATH environment variable.
     *
     * @param pluginPath value to set
     * @return <code>true</code> if the environment variable was successfully set; <code>false</code> on error
     */
    protected abstract boolean setPluginPath(String pluginPath);

}