package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.JSONTool;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.tools.JSONTool.*;

/** Resolves Odysee streams and live channels through the JSON-RPC proxy and live API its own web player uses. */
public final class OdyseePlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(OdyseePlatform.class.getSimpleName());
    public static final String NAME = "Odysee";
    private static final String[] HOSTS = { "odysee.com", "www.odysee.com" };
    private static final String API = "https://api.na-backend.odysee.com/api/v1/proxy";
    private static final String LIVE_API = "https://api.odysee.live/livestream/is_live?channel_claim_id=";
    // THE NSFW TAG LIST THE ODYSEE WEB CLIENT HIDES BEHIND ITS MATURE SWITCH (odysee-frontend ui/constants/tags.ts)
    private static final Set<String> MATURE_TAGS = Set.of("porn", "porno", "nsfw", "mature", "xxx", "sex", "creampie", "blowjob",
            "handjob", "boobs", "big boobs", "big dick", "pussy", "cumshot", "anal", "hard fucking", "ass", "fuck", "hentai");
    // A LIVE PLAYLIST DIES WITH ITS BROADCAST; RE-RESOLVING KEEPS A DEAD ONE FROM BEING SERVED
    private static final Duration LIVE_TTL = Duration.ofMinutes(30);
    // EPOCH SECONDS PAST THIS ARE PUBLISHER GARBAGE, NOT A DATE
    private static final long MAX_EPOCH_SECONDS = 100_000_000_000L;

    private final String api;
    private final String liveApi;

    /** Creates the handler bound to the public Odysee proxy and live APIs. */
    public OdyseePlatform() { this(API, LIVE_API); }

    // TEST SEAM: POINTS BOTH APIS AT A LOCAL SERVER
    OdyseePlatform(final String api, final String liveApi) {
        this.api = api;
        this.liveApi = liveApi;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        final String claimUri = claimUri(uri);
        if (claimUri == null) throw PlatformException.unsupported(NAME, uri);

        LOGGER.debug(IT, "Odysee resolving {} from {}", claimUri, uri);
        JsonObject claim = obj(this.rpc("resolve", Map.of("urls", List.of(claimUri))), claimUri);
        if (claim == null) throw new PlatformException(NAME, "API returned no claim for " + claimUri);
        final JsonObject error = obj(claim, "error");
        if (error != null) throw new PlatformException(NAME, "Claim " + claimUri + " is unavailable: " + str(error, "text"));

        // A REPOST IS A POINTER; THE SHARED CLAIM CARRIES THE STREAM AND get TAKES ITS OWN URL
        final JsonObject reposted = obj(claim, "reposted_claim");
        if (reposted != null) claim = reposted;
        final String streamUri = reposted == null ? claimUri : str(claim, "permanent_url");
        if (streamUri == null) throw new PlatformException(NAME, "Repost " + claimUri + " names no stream URL");
        final String valueType = str(claim, "value_type");
        if (!"stream".equals(valueType))
            throw new PlatformException(NAME, "Claim " + claimUri + " is a " + valueType + ", not a stream");

        final JsonObject value = obj(claim, "value");
        if (!WaterMediaConfig.platforms.allowMatureContent) {
            final JsonArray tags = arr(value, "tags");
            if (tags != null) for (final JsonElement tag: tags)
                if (tag.isJsonPrimitive() && MATURE_TAGS.contains(tag.getAsString().toLowerCase(Locale.ROOT)))
                    throw new MatureContentException(NAME, "Stream " + claimUri + " is tagged '" + tag.getAsString() + "'");
        }

        final JsonObject channel = obj(claim, "signing_channel");
        final String channelTitle = str(obj(channel, "value"), "title");
        final String author = channelTitle != null ? channelTitle : str(channel, "name");
        final String title = str(value, "title");
        if (title == null) LOGGER.warn(IT, "Odysee stream {} has no title", claimUri);
        final URI thumbnail = DataTool.uri(str(obj(value, "thumbnail"), "url"));
        final long release = longOr(value, "release_time", longOr(claim, "timestamp", 0L));
        Instant postedAt = release > 0 && release < MAX_EPOCH_SECONDS ? Instant.ofEpochSecond(release) : null;
        final HttpHeaders headers = HttpHeaders.defaults(uri);

        // A STREAM WITHOUT A FILE IS A LIVESTREAM CLAIM; ITS CHANNEL TELLS WHETHER IT IS ON AIR
        if (obj(value, "source") == null) {
            final String channelId = str(channel, "claim_id");
            if (channelId == null) throw new PlatformException(NAME, "Stream " + claimUri + " has no file and no channel to be live on");
            final JsonObject live = obj(WebRequests.fetchJson(NAME, this.liveApi + channelId, JsonObject.class), "data");
            final URI playlist = DataTool.uri(str(live, "VideoURL"));
            if (!bool(live, "Live") || playlist == null)
                throw new PlatformException(NAME, "Stream " + claimUri + " is not live and has no replay");
            // THE CHANNEL MAY BE LIVE ON A NEWER CLAIM; A PAST ONE WITHOUT A FILE HAS NOTHING TO PLAY
            final String active = str(obj(live, "ActiveClaim"), "ClaimID");
            if (active != null && !active.equals(str(claim, "claim_id")))
                throw new PlatformException(NAME, "Stream " + claimUri + " has ended and has no replay");

            final String start = str(live, "Start");
            if (start != null) {
                try { postedAt = Instant.parse(start); }
                catch (final DateTimeParseException e) { LOGGER.warn(IT, "Odysee unparseable live start '{}' for {}", start, claimUri, e); }
            }
            final List<Quality> variants = WebRequests.hls(playlist, headers);
            LOGGER.debug(IT, "Odysee resolved live {} with {} variant(s)", claimUri, variants.size());
            final Metadata metadata = new Metadata(title, str(value, "description"), postedAt, 0, author);
            return new Resolution(Instant.now().plus(LIVE_TTL), new Source(MediaType.VIDEO, thumbnail, metadata, headers, variants, null, null));
        }

        final String streamType = str(value, "stream_type");
        final MediaType type = switch (streamType == null ? "" : streamType) {
            case "video" -> MediaType.VIDEO;
            case "audio" -> MediaType.AUDIO;
            case "image" -> MediaType.IMAGE;
            default -> throw new PlatformException(NAME, "Stream " + claimUri + " is a '" + streamType + "' file, not playable media");
        };
        final JsonObject fee = obj(value, "fee");
        if (fee != null && num(fee, "amount") > 0)
            throw new PlatformException(NAME, "Stream " + claimUri + " is paid content (" + str(fee, "amount") + " " + str(fee, "currency") + ")");

        // THE PLAYER URL THE SITE ITSELF STREAMS: EDGE-CACHED AND UNSIGNED, SO IT NEVER EXPIRES
        final URI stream = DataTool.uri(str(this.rpc("get", Map.of("uri", streamUri, "save_file", false)), "streaming_url"));
        if (stream == null) throw new PlatformException(NAME, "API returned no streaming URL for " + claimUri);

        final JsonObject media = obj(value, streamType);
        final Quality quality = new Quality(stream, intOr(media, "width", 0), intOr(media, "height", 0));
        final long durationMs = Math.max(0L, longOr(media, "duration", 0L)) * 1_000L;
        LOGGER.debug(IT, "Odysee resolved {} {} ({}x{}, durationMs={}) for {}", streamType, stream, quality.width(), quality.height(), durationMs, uri);
        final Metadata metadata = new Metadata(title, str(value, "description"), postedAt, durationMs, author);
        return new Resolution(null, new Source(type, thumbnail, metadata, headers, List.of(quality), null, null));
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // ONE JSON-RPC CALL TO THE ODYSEE PROXY; AN error ENVELOPE OR A MISSING result FAILS THE RESOLUTION
    private JsonObject rpc(final String method, final Map<String, ?> params) throws PlatformException {
        final NetRequest request = new NetRequest(this.api + "?m=" + method).method("POST").accept(NetRequest.ACCEPT_JSON)
                .contentType("application/json").body(JSONTool.write(Map.of("jsonrpc", "2.0", "method", method, "params", params, "id", 1)));
        final JsonObject envelope = WebRequests.fetchJson(NAME, request, JsonObject.class);
        final JsonObject error = obj(envelope, "error");
        if (error != null) throw new PlatformException(NAME, "API '" + method + "' failed: " + str(error, "message"));
        final JsonObject result = obj(envelope, "result");
        if (result == null) throw new PlatformException(NAME, "API '" + method + "' returned no result");
        return result;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // lbry://[@channel#id/]name#id FROM AN odysee.com PATH, EMBED AND DOWNLOAD ROUTES INCLUDED; NULL FOR A CHANNEL,
    // A PLAYLIST OR ANY OTHER PAGE, WHICH NAME NO STREAM
    static String claimUri(final URI uri) {
        String path = uri.getPath();
        if (path == null || path.length() < 2) return null;
        path = path.substring(1);
        if (path.startsWith("$/")) {
            if (path.startsWith("$/embed/")) path = path.substring("$/embed/".length());
            else if (path.startsWith("$/download/")) path = path.substring("$/download/".length());
            else return null;
        }
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        final String[] parts = path.split("/");
        if (parts[0].isEmpty() || parts.length > 2 || (parts.length == 2 && parts[1].isEmpty())) return null;
        if (parts.length == 1) return parts[0].startsWith("@") ? null : "lbry://" + parts[0].replace(':', '#');
        // name/claimid IS THE EMBED SHAPE OF A CHANNEL-LESS CLAIM; @channel/name THE CANONICAL ONE
        if (!parts[0].startsWith("@")) return parts[1].matches("[0-9a-f]{1,40}") ? "lbry://" + parts[0] + "#" + parts[1] : null;
        return "lbry://" + parts[0].replace(':', '#') + "/" + parts[1].replace(':', '#');
    }
}
