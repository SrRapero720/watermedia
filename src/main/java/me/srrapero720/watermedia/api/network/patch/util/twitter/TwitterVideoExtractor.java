package me.srrapero720.watermedia.api.network.patch.util.twitter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TwitterVideoExtractor {

    public List<String> extractMp4s(String jsonString, String tweetUrl) {
        // regex patterns
        Pattern amplitudePattern = Pattern.compile("(https://video.twimg.com/amplify_video/(\\d+)/vid/(\\d+x\\d+)/[^.]+.mp4\\?tag=\\d+)");
        Pattern extTwPattern = Pattern.compile("(https://video.twimg.com/ext_tw_video/(\\d+)/pu/vid/(\\d+x\\d+)/[^.]+.mp4\\?tag=\\d+)");
        Pattern tweetVideoPattern = Pattern.compile("https://video.twimg.com/tweet_video/[^\"]+");
        Pattern containerPattern = Pattern.compile("https://video.twimg.com/[^\"].*container=fmp4");

        String mediaId = getAssociatedMediaId(jsonString, tweetUrl);

        List<String> matches = findMatches(jsonString, amplitudePattern);
        matches.addAll(findMatches(jsonString, extTwPattern));
        List<String> containerMatches = findMatches(jsonString, containerPattern);
        List<String> tweetVideoMatches = findMatches(jsonString, tweetVideoPattern);

        if (matches.isEmpty() && !tweetVideoMatches.isEmpty()) {
            return tweetVideoMatches;
        }

        Map<String, Map<String, String>> results = new HashMap<>();

        for (String match : matches) {
            Matcher matcher = amplitudePattern.matcher(match);

            boolean find = matcher.find();
            if (!find) {
                matcher = extTwPattern.matcher(match);
                find = matcher.find();
            }

            if (find) {
                String url = matcher.group(1);
                String tweetId = matcher.group(2);
                String resolution = matcher.group(3);

                if (!results.containsKey(tweetId)) {
                    results.put(tweetId, new HashMap<>());
                    results.get(tweetId).put("resolution", resolution);
                    results.get(tweetId).put("url", url);
                } else {
                    int[] myDims = Arrays.stream(resolution.split("x")).mapToInt(Integer::parseInt).toArray();
                    int[] theirDims = Arrays.stream(results.get(tweetId).get("resolution").split("x")).mapToInt(Integer::parseInt).toArray();

                    if (myDims[0] * myDims[1] > theirDims[0] * theirDims[1]) {
                        results.get(tweetId).put("resolution", resolution);
                        results.get(tweetId).put("url", url);
                    }
                }
            }
        }

        // Fix urls in the containerMatches list splitting ?
        containerMatches.replaceAll(s -> s.split("\\?")[0]);

        if (mediaId != null) {
            List<String> allUrls = new ArrayList<>();
            for (Map<String, String> value : results.values()) {
                allUrls.add(value.get("url").split("\\?")[0]);
            }
            allUrls.addAll(containerMatches);

            List<String> urlWithMediaId = new ArrayList<>();
            for (String url : allUrls) {
                if (url.contains(mediaId)) {
                    urlWithMediaId.add(url);
                }
            }

            if (!urlWithMediaId.isEmpty()) {
                return urlWithMediaId;
            }
        }

        if (!containerMatches.isEmpty()) {
            return containerMatches;
        }

        List<String> resultUrls = new ArrayList<>();
        for (Map<String, String> value : results.values()) {
            resultUrls.add(value.get("url").split("\\?")[0]);
        }

        return resultUrls;
    }

    private List<String> findMatches(String inputString, Pattern pattern) {
        List<String> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(inputString);

        while (matcher.find()) {
            matches.add(matcher.group());
        }

        return matches;
    }

    public String getAssociatedMediaId(String jsonString, String tweetUrl) {
        String sid = getTweetStatusId(tweetUrl);
        Pattern pattern = Pattern.compile("\"expanded_url\"\\s*:\\s*\"https://twitter\\.com/[^/]+/status/" + sid + "/[^\"]+\",\\s*\"id_str\"\\s*:\\s*\"\\d+\",");
        Matcher matcher = pattern.matcher(jsonString);

        if (matcher.find()) {
            String target = matcher.group();
            target = target.substring(0, target.length() - 1); //remove the coma at the end
            JsonObject jsonObject = JsonParser.parseString("{" + target + "}").getAsJsonObject();
            return jsonObject.get("id_str").getAsString();
        }

        return null;
    }

    public String getTweetStatusId(String tweetUrl) {
        String sidPattern = "https://twitter\\.com/[^/]+/status/(\\d+)";

        if (tweetUrl.charAt(tweetUrl.length() - 1) != '/') {
            tweetUrl = tweetUrl + "/";
        }

        Pattern pattern = Pattern.compile(sidPattern);
        Matcher matcher = pattern.matcher(tweetUrl);

        if (matcher.find()) {
            return matcher.group(1);
        } else {
            System.out.println("error, could not get status id from this tweet url: " + tweetUrl);
            System.exit(1);
        }

        return null;
    }
}