package me.srrapero720.watermedia.api.network.patch;

import com.google.gson.Gson;
import me.srrapero720.watermedia.api.network.DynamicURL;
import me.srrapero720.watermedia.api.network.patch.util.twitter.TweetScrapper;
import me.srrapero720.watermedia.tools.exceptions.PatchingURLException;

public class TwitterPatch extends URLPatch {
    private static final Gson gson = new Gson();

    @Override
    public String platform() {
        return "Twitter";
    }

    @Override
    public boolean isValid(DynamicURL dynamicURL) {
        return (dynamicURL.asURL().getHost().equals("www.twitter.com") || dynamicURL.asURL().getHost().equals("twitter.com")) && dynamicURL.asURL().getPath().matches("/[a-zA-Z0-9_]+/status/[0-9]+");
    }

    @Override
    public DynamicURL patch(DynamicURL dynamicURL, Quality preferQuality) throws PatchingURLException {
        super.patch(dynamicURL, preferQuality);
        try {
            return new DynamicURL(new TweetScrapper(gson).extractVideo(dynamicURL.getSource()).get(0), true, false);
        } catch (Exception e) {
            throw new PatchingURLException(dynamicURL, e);
        }
    }
}