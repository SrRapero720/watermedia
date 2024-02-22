package me.srrapero720.watermedia.api.network.patch.util.twitter;

import com.google.gson.Gson;

import java.util.List;

public class TweetScrapper {

    private final TwitterAPI twitterAPI;
    private final TwitterVideoExtractor videoExtractor;

    public TweetScrapper(Gson gson) {
        this.twitterAPI = new TwitterAPI(gson);
        this.videoExtractor = new TwitterVideoExtractor();
    }

    public List<String> extractVideo(String url) {
        try {
            String[] tokens = twitterAPI.getTokens(url);
            String tweet_details = twitterAPI.getTweetDetails(url, tokens[1], tokens[0]);
            return videoExtractor.extractMp4s(tweet_details, url);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}