package me.srrapero720.watermedia.api.network.patch;

import me.srrapero720.watermedia.api.network.DynamicRequest;
import me.srrapero720.watermedia.api.network.DynamicURL;
import me.srrapero720.watermedia.api.network.models.pornhub.VideoQuality;
import me.srrapero720.watermedia.tools.ByteTools;
import me.srrapero720.watermedia.tools.exceptions.PatchingURLException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PornhubPatch extends URLPatch {
    private static final Pattern VIDEO_QUALITY_PATTERN = Pattern.compile("(?<=\\*/)\\w+");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("(?<=[_/])\\d*P(?=_)");

    @Override
    public String platform() {
        return "Pornhub";
    }

    @Override
    public boolean isValid(DynamicURL dynamicURL) {
        return (dynamicURL.asURL().getHost().equals("es.pornhub.com") || dynamicURL.asURL().getHost().equals("www.pornhub.com")) && dynamicURL.asURL().getPath().startsWith("/view_video.php");
    }

    @Override
    public DynamicURL patch(DynamicURL dynamicURL, Quality prefQuality) throws PatchingURLException {
        super.patch(dynamicURL, prefQuality);
        try (DynamicRequest connection = new DynamicRequest(dynamicURL); InputStream reader = connection.getInputStream()) {
            String source = new String(ByteTools.readAllBytes(reader), StandardCharsets.UTF_8);

            List<String> urls = new ArrayList<>();
            List<VideoQuality> videoQualities = new ArrayList<>();
            List<String> matches = new ArrayList<>();

            // GET QUALITIES
            Matcher matcher = VIDEO_QUALITY_PATTERN.matcher(source);
            while (matcher.find()) {
                matches.add(matcher.group());
            }

            // PARSE QUALITIES
            for (String match: matches) {
                String regexString = "(?<=" + match + "=\")[^;]+(?=\")";
                Pattern regexPattern = Pattern.compile(regexString);
                Matcher regexMatcher = regexPattern.matcher(source);
                if (regexMatcher.find()) {
                    String value = regexMatcher.group().replaceAll("[\" +]", "");

                    if (value.startsWith("https")) {
                        if (urls.size() == 4) {
                            break; // may is not a good option
                        }
                        urls.add(value);
                    } else {
                        urls.set(urls.size() - 1, urls.get(urls.size() - 1) + value);
                    }
                }
            }

            // PARSE RESOLUTIONS
            for (String x: urls) {
                Matcher resolutionMatcher = RESOLUTION_PATTERN.matcher(x);
                if (resolutionMatcher.find()) {
                    String resolution = resolutionMatcher.group();
                    videoQualities.add(new VideoQuality(resolution, x));
                }
            }

            return new DynamicURL(videoQualities.get(0).getUri(), true, false);
        } catch (Exception e) {
            throw new PatchingURLException(dynamicURL, e);
        }
    }
}
