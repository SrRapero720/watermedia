package me.srrapero720.watermedia.api.network.patch;

import me.srrapero720.watermedia.api.network.DynamicURL;
import me.srrapero720.watermedia.tools.exceptions.PatchingURLException;

import java.net.URL;

public class GoogleDrivePatch extends URLPatch {
    private static final String API_KEY = "AIzaSyBiFNT6TTo506kCYYwA2NHqs36TlXC1DMo";
    private static final String API_URL = "https://www.googleapis.com/drive/v3/files/%s?alt=media&key=%s";

    @Override
    public String platform() {
        return "Google Drive";
    }

    @Override
    public boolean isValid(DynamicURL dynamicURL) {
        URL normalURL = dynamicURL.asURL();
        return normalURL.getHost().equals("drive.google.com") && normalURL.getPath().startsWith("/file/d/");
    }

    @Override
    public DynamicURL patch(DynamicURL dynamicURL, Quality prefQuality) throws PatchingURLException {
        super.patch(dynamicURL, prefQuality);
        try {
            final URL url = dynamicURL.asURL();
            final int start = url.getPath().indexOf("/file/d/") + 4;

            int end = url.getPath().indexOf('/', start);
            if (end == -1) end = url.getPath().length();
            String fileID = url.getPath().substring(start, end);

            return new DynamicURL(String.format(API_URL, fileID, API_KEY), false, false);
        } catch (Exception e) {
            throw new PatchingURLException(dynamicURL.getSource(), e);
        }
    }
}