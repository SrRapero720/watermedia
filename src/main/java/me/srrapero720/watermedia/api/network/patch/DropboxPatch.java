package me.srrapero720.watermedia.api.network.patch;

import me.srrapero720.watermedia.api.network.DynamicURL;
import me.srrapero720.watermedia.tools.exceptions.PatchingURLException;

public class DropboxPatch extends URLPatch {
    @Override
    public String platform() {
        return "Dropbox";
    }

    @Override
    public boolean isValid(DynamicURL dynamicURL) {
        String q;
        return dynamicURL.asURL().getHost().contains("dropbox.com") && ((q = dynamicURL.asURL().getQuery()) != null) && q.contains("dl=0");
    }

    @Override
    public DynamicURL patch(DynamicURL dynamicURL, Quality prefQuality) throws PatchingURLException {
        super.patch(dynamicURL, prefQuality);
        try {
            return new DynamicURL(dynamicURL.toString().replace("dl=0", "dl=1"), false, false);
        } catch (Exception e) {
            throw new PatchingURLException(dynamicURL.getSource(), e);
        }
    }
}
