package me.srrapero720.watermedia.api.network.patch;

import me.srrapero720.watermedia.api.network.DynamicURL;
import me.srrapero720.watermedia.api.network.patch.util.Twitch;
import me.srrapero720.watermedia.tools.exceptions.PatchingURLException;

public class TwitchPatch extends URLPatch {
    @Override
    public String platform() {
        return "Twitch";
    }

    @Override
    public boolean isValid(DynamicURL url) {
        return (url.asURL().getHost().equals("www.twitch.tv") || url.asURL().getHost().equals("twitch.tv")) && url.asURL().getPath().startsWith("/");
    }

    @Override
    public DynamicURL patch(DynamicURL url, Quality preferQuality) throws PatchingURLException {
        super.patch(url, preferQuality);
        try {
            String path = url.asURL().getPath();
            if (path.startsWith("/videos/")) {
                return new DynamicURL(Twitch.getVod(path.substring(8)).get(0).getUrl(), true, false);
            }

            return new DynamicURL(Twitch.getStream(path.substring(1)).get(0).getUrl(), true, true);
        } catch (Exception e) {
            throw new PatchingURLException(url, e);
        }
    }
}