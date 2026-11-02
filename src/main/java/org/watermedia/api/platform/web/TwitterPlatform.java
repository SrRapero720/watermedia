package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

/** Resolves tweets through the public syndication endpoint into one source per photo or progressive MP4 video. */
public final class TwitterPlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(TwitterPlatform.class.getSimpleName());
    public static final String NAME = "Twitter";
    private static final String[] HOSTS = { "x.com", "www.x.com", "twitter.com", "www.twitter.com" };
    private static final String API_URL = "https://cdn.syndication.twimg.com/tweet-result?id=%s&token=%s&lang=en";
    private static final String API_TOKEN = "watermedia-java-x-access-token";
    private static final Pattern ID_PATTERN = Pattern.compile("/status/(\\d+)(?:/|$)");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("/(\\d{2,5})x(\\d{2,5})/");
    private static final int TITLE_LENGTH = 72;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
        // STATUS LINKS MAY END WITH A SEGMENT LIKE /video/1 OR /photo/1, SO THE ID IS NOT ANCHORED TO THE PATH END
        final Matcher m = ID_PATTERN.matcher(uri.getPath() == null ? "" : uri.getPath());
        if (!m.find()) throw PlatformException.unsupported(NAME, uri);

        final String tweetId = m.group(1);
        LOGGER.debug(IT, "Twitter fetching tweet '{}'", tweetId);

        final Tweet tweet = WebRequests.fetchJson(NAME, String.format(API_URL, tweetId, API_TOKEN), Tweet.class);

        if ("TweetTombstone".equals(tweet.typename)) {
            // THE TOMBSTONE TEXT SAYS WHY: AN AGE GATE IS A MATURE BLOCK, THE REST IS GONE FOR GOOD
            final String reason = tweet.tombstone != null && tweet.tombstone.text != null ? tweet.tombstone.text.text : null;
            if (reason != null && reason.toLowerCase(Locale.ROOT).contains("age-restricted") && !WaterMediaConfig.platforms.allowMatureContent)
                throw new MatureContentException(NAME, "Tweet '" + tweetId + "' is age-restricted");
            throw new PlatformException(NAME, "Tweet '" + tweetId + "' is unavailable" + (reason == null ? " (deleted or protected)" : ": " + reason));
        }

        if (tweet.possiblySensitive && !WaterMediaConfig.platforms.allowMatureContent)
            throw new MatureContentException(NAME, "Tweet '" + tweetId + "' is marked as sensitive");

        if (tweet.mediaDetails == null || tweet.mediaDetails.length == 0)
            throw new PlatformException(NAME, "Tweet '" + tweetId + "' carries no media (text-only or protected)");

        // THE VISIBLE TWEET TEXT IS THE DESCRIPTION AND, CUT TO ONE LINE, THE TITLE; THE ACCOUNT THAT POSTED IT IS THE AUTHOR
        final String description = text(tweet.text, tweet.displayTextRange);
        final String text = description.replace('\n', ' ');
        final String title = text.isBlank() ? "Tweet #" + tweetId : text.length() > TITLE_LENGTH ? text.substring(0, TITLE_LENGTH) + "..." : text;
        final String author = tweet.user == null ? null : tweet.user.name != null ? tweet.user.name : tweet.user.screenName;

        // ONE TIMESTAMP FOR EVERY MEDIA ENTRY; A BAD DATE MUST NOT DROP MEDIA
        Instant postedAt = null;
        if (tweet.createdAt != null) {
            try { postedAt = Instant.parse(tweet.createdAt); }
            catch (final DateTimeParseException e) { LOGGER.warn(IT, "Twitter unparseable created_at '{}'", tweet.createdAt, e); }
        }

        final HttpHeaders headers = HttpHeaders.defaults(uri);
        final List<Source> entries = new ArrayList<>(tweet.mediaDetails.length);
        for (int i = 0; i < tweet.mediaDetails.length; i++) {
            try {
                final Source entry = this.entry(tweet.mediaDetails[i], title, description, author, postedAt, headers);
                if (entry != null) entries.add(entry);
            } catch (final RuntimeException e) {
                LOGGER.warn(IT, "Twitter failed to build entry for media {} of tweet '{}'", i, tweetId, e);
            }
        }

        if (entries.isEmpty())
            throw new PlatformException(NAME, "All " + tweet.mediaDetails.length + " media source(s) failed to resolve for tweet '" + tweetId + "'");

        LOGGER.debug(IT, "Twitter resolved {} of {} media entry(es) for tweet '{}'", entries.size(), tweet.mediaDetails.length, tweetId);
        return new Resolution(null, entries);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // ONE MEDIA ITEM OF THE TWEET AS A SOURCE, OR NULL WHEN IT HAS NOTHING PLAYABLE
    private Source entry(final MediaDetail media, final String title, final String description, final String author, final Instant postedAt, final HttpHeaders headers) {
        if ("photo".equals(media.type)) {
            final URI photo = DataTool.uri(media.mediaUrlHttps);
            if (photo == null) {
                LOGGER.warn(IT, "Twitter photo without a usable URL, skipping");
                return null;
            }
            final int w = media.originalInfo != null ? Math.max(0, media.originalInfo.width) : 0;
            final int h = media.originalInfo != null ? Math.max(0, media.originalInfo.height) : 0;
            final Metadata metadata = new Metadata(title, description, postedAt, 0, author);
            return new Source(MediaType.IMAGE, null, metadata, headers,
                    List.of(new Quality(photo, w, h)),
                    null, null);
        }

        if ("video".equals(media.type) || "animated_gif".equals(media.type)) {
            if (media.videoInfo == null || media.videoInfo.variants == null) {
                LOGGER.warn(IT, "Twitter media type '{}' has no video_info/variants, skipping", media.type);
                return null;
            }
            final URI thumbnail = DataTool.uri(media.mediaUrlHttps);
            final Metadata metadata = new Metadata(title, description, postedAt, Math.max(0, media.videoInfo.durationMillis), author);

            final List<Quality> variants = new ArrayList<>(media.videoInfo.variants.length);
            for (final var variant: media.videoInfo.variants) {
                final URI rendition = DataTool.uri(variant.url);
                if (rendition == null) continue;
                final Matcher resMatcher = RESOLUTION_PATTERN.matcher(variant.url);
                if (!resMatcher.find()) continue; // SKIP NON-PROGRESSIVE RENDITIONS (E.G. THE m3u8 PLAYLIST ENTRY)
                variants.add(new Quality(rendition, DataTool.parseInt(resMatcher.group(1), 0), DataTool.parseInt(resMatcher.group(2), 0)));
            }

            if (variants.isEmpty()) {
                LOGGER.warn(IT, "Twitter video media in tweet exposed {} variant(s) but none were progressive MP4 renditions, skipping", media.videoInfo.variants.length);
                return null;
            }

            return new Source(MediaType.VIDEO, thumbnail, metadata, headers,
                    variants,
                    null, null);
        }

        LOGGER.warn(IT, "Twitter unsupported media type '{}', skipping", media.type);
        return null;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // THE TEXT X SHOWS: display_text_range (UTF-16 OFFSETS INTO THE ESCAPED TEXT) DROPS REPLY MENTIONS AND THE MEDIA'S OWN
    // t.co LINK, AND THE SYNDICATION ESCAPES ONLY &, < AND > (&amp; LAST, SO AN ESCAPED ENTITY STAYS LITERAL)
    static String text(final String text, final int[] range) {
        if (text == null) return "";
        final boolean visible = range != null && range.length == 2 && range[0] >= 0 && range[0] <= range[1] && range[1] <= text.length();
        return (visible ? text.substring(range[0], range[1]) : text).replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").strip();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    private record Tweet(
            @SerializedName("__typename") String typename,
            String text,
            @SerializedName("display_text_range") int[] displayTextRange,
            User user,
            @SerializedName("created_at") String createdAt,
            @SerializedName("possibly_sensitive") boolean possiblySensitive,
            MediaDetail[] mediaDetails,
            Tombstone tombstone
    ) {}

    private record User(String name, @SerializedName("screen_name") String screenName) {}
    // A TOMBSTONE CARRIES ITS REASON AS text.text ("Age-restricted adult content...", "This Post was deleted...")
    private record Tombstone(TombstoneText text) {}
    private record TombstoneText(String text) {}

    private record MediaDetail(
            String type,
            @SerializedName("media_url_https") String mediaUrlHttps,
            @SerializedName("video_info") VideoInfo videoInfo,
            @SerializedName("original_info") MediaOriginalInfo originalInfo
    ) {}

    private record MediaOriginalInfo(int width, int height) {}

    private record VideoInfo(
            @SerializedName("duration_millis") int durationMillis,
            VideoVariant[] variants
    ) {}

    private record VideoVariant(
            int bitrate,
            @SerializedName("content_type") String contentType,
            String url
    ) {}
}
