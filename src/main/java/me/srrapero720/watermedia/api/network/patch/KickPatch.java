package me.srrapero720.watermedia.api.network.patch;

import me.srrapero720.watermedia.api.network.DynamicRequest;
import me.srrapero720.watermedia.api.network.DynamicURL;
import me.srrapero720.watermedia.api.network.NetworkAPI;
import me.srrapero720.watermedia.api.network.models.kick.KickChannel;
import me.srrapero720.watermedia.api.network.models.kick.KickVideo;
import me.srrapero720.watermedia.tools.exceptions.PatchingURLException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;

public class KickPatch extends URLPatch {
    private static final String API_URL = "https://kick.com/api/v1/";

    @Override
    public String platform() {
        return "Kick.com";
    }

    @Override
    public boolean isValid(DynamicURL dynamicURL) {
        return dynamicURL.asURL().getHost().contains("kick.com");
    }

    @Override
    public DynamicURL patch(DynamicURL dynamicURL, Quality preferQuality) throws PatchingURLException {
        super.patch(dynamicURL, preferQuality);

        try {
            if (dynamicURL.asURL().getPath().contains("/video/")) {
                String videoID = dynamicURL.asURL().getPath().replace("/video/", "");
                KickVideo video = getVideoInfo(videoID);
                return new DynamicURL(video.url, true, false);
            } else {
                String streamerName = dynamicURL.asURL().getPath().replace("/", "");
                KickChannel channel = getChannelInfo(streamerName);
                if (channel.livestream == null || !channel.livestream.isStreaming) throw new ConnectException("Streamer is not online");
                return new DynamicURL(channel.url, true, true);
            }
        } catch (Exception e) {
            throw new PatchingURLException(dynamicURL, e);
        }
    }

    public KickChannel getChannelInfo(String channel) throws IOException {
        try (DynamicRequest request = new DynamicRequest(new DynamicURL(API_URL + "channels/" + channel)); InputStreamReader in = new InputStreamReader(request.getInputStream())) {
            return NetworkAPI.GSON.fromJson(in, KickChannel.class);
        }
    }

    public KickVideo getVideoInfo(String videoId) throws IOException {
        try (DynamicRequest request = new DynamicRequest(new DynamicURL(API_URL + "video/" + videoId)); InputStreamReader in = new InputStreamReader(request.getInputStream())) {
            return NetworkAPI.GSON.fromJson(in, KickVideo.class);
        }
    }
}