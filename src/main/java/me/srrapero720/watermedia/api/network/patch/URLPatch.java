package me.srrapero720.watermedia.api.network.patch;

import me.srrapero720.watermedia.api.network.DynamicURL;
import me.srrapero720.watermedia.tools.exceptions.PatchingURLException;

/**
 * Abstract fixers class
 * To make your own fixer, you should use services.<br>
 * Create a file in:
 * <code>resources/META-INF/services/</code><br>
 * named:
 * <code>me.srrapero720.watermedia.api.url.fixers.URLFixer</code>
 * and put inside the entire package to your fixer.
 */
public abstract class URLPatch {
    /**
     * Get the name of the current patcher
     * @return class name
     */
    public String name() { return this.getClass().getSimpleName(); }

    /**
     * Name of the platform used for this patcher
     * @return platform name
     */
    public abstract String platform();

    /**
     * Validates if URL can be processed by this URLPatch instance
     * @param dynamicURL Valid URL to check
     * @return Can be built a static dynamicURL
     */
    public abstract boolean isValid(DynamicURL dynamicURL);

    /**
     * Patch the URL
     * @param dynamicURL URL to patch
     * @return static URL
     * @throws PatchingURLException if URL is null or invalid in this patch
     */
    public DynamicURL patch(DynamicURL dynamicURL, Quality prefQuality) throws PatchingURLException {
        if (!isValid(dynamicURL)) throw new PatchingURLException(dynamicURL.getSource(), new IllegalArgumentException("Attempt to build a invalid URL in a invalid compat"));
        return null;
    }

    @Override
    public String toString() {
        return name();
    }

    public enum Quality {
        LOWEST, LOW, MIDDLE, HIGH, HIGHEST,
    }
}
