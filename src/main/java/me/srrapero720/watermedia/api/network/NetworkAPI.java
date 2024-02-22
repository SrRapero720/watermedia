package me.srrapero720.watermedia.api.network;

import com.google.gson.Gson;
import me.lib720.watermod.safety.TryCore;
import me.srrapero720.watermedia.api.WaterMediaAPI;
import me.srrapero720.watermedia.api.network.patch.URLPatch;
import me.srrapero720.watermedia.loaders.ILoader;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.net.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import static me.srrapero720.watermedia.WaterMedia.LOGGER;

public class NetworkAPI extends WaterMediaAPI {
    public static final Marker IT = MarkerManager.getMarker(NetworkAPI.class.getSimpleName());
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36 Edg/112.0.1722.68";
    public static final Gson GSON = new Gson();
    private static final ServiceLoader<URLPatch> URL_PATCHES = ServiceLoader.load(URLPatch.class);
    static {
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

    public static DynamicURL patchURL(DynamicURL url) {
        if (url.isLocal()) return url;
        try {
            for (URLPatch patcher: URL_PATCHES) {
                if (!patcher.isValid(url)) continue;
                return patcher.patch(url, URLPatch.Quality.HIGHEST);
            }
        } catch (Exception e) {
            LOGGER.error(IT, "Failed to patch URL '{}'", url.getSource(), e);
        }
        return url;
    }

    public static String[] getPatchNames() {
        ArrayList<String> result = new ArrayList<>();
        for (URLPatch patcher: URL_PATCHES) {
            result.add(patcher.name());
        }
        return result.toArray(new String[0]);
    }

    /**
     * Parses a query string from a {@link URL#getQuery()} in a Map
     * @param query query string
     * @return map with all values as a String
     */
    public static Map<String, String> parseQuery(String query) {
        Map<String, String> queryParams = new HashMap<>();
        String[] params = query.split("&");
        for (String param : params) {
            String[] keyValue = param.split("=");
            if (keyValue.length == 2) {
                String key = keyValue[0];
                String value = keyValue[1];
                queryParams.put(key, value);
            }
        }
        return queryParams;
    }

    /**
     * Gets a URL instance based on a given string, null if is malformed or invalid
     * @param url string url
     * @return the instanced URL, null if was not a valid URL
     */
    public static URL parseUrl(String url) {
        return TryCore.withReturn(defaultVar -> new URL(url), null);
    }

    /**
     * Gets a URL instance based on a given string, null if is malformed or invalid
     * @param uri string url
     * @return if was valid
     */
    public static URL parseUrl(URI uri) {
        return TryCore.withReturn(defaultVar -> uri.toURL(), null);
    }

    @Override
    public Priority priority() {
        return Priority.LOW;
    }

    @Override
    public boolean prepare(ILoader bootCore) throws Exception {
        return false;
    }

    @Override
    public void start(ILoader bootCore) throws Exception {

    }

    @Override
    public void release() {

    }
}