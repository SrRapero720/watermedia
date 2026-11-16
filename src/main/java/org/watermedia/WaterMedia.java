package org.watermedia;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.apache.logging.log4j.spi.ExtendedLoggerWrapper;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Loads WaterMedia's modules once for the life of the process and holds the environment they run in. */
public final class WaterMedia {
    private static final Marker IT = MarkerManager.getMarker(WaterMedia.class.getSimpleName());

    // IDENTITY
    public static final String ID = "watermedia";
    public static final String NAME = "WaterMedia";
    public static final String VERSION = IOTool.jarVersion();
    public static final String USER_AGENT = "WaterMedia/" + VERSION;
    public static final String BROWSER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + (152 + Math.max(0L, (ChronoUnit.DAYS.between(LocalDate.of(2026, 9, 8), LocalDate.now()) / 14))) + ".0.0.0 Safari/537.36";
    public static final Logger LOGGER = logger(ID);

    // DEFAULT PATHS
    private static final Path DEFAULT_TMP = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve(ID);
    private static final Path DEFAULT_CWD = Path.of("").toAbsolutePath();

    // ERROR CODES: ONE PER EXCEPTION, ERROR LOG AND safeCall MESSAGE, PRINTED BY DataTool.errCode AS (wm_err:0000).
    // EACH PACKAGE OWNS A RANGE AND EACH CLASS A BLOCK OF IT: A NEW SITE TAKES THE NEXT FREE CODE OF ITS CLASS AND A
    // NEW CLASS THE NEXT FREE BLOCK OF ITS PACKAGE. 9000 TO 9999 BELONG TO THE BINARIES AND THE EXTENSIONS
    public static final int ERR_UNKNOWN = 0;

    // ROOT PACKAGE 1-99
    // WaterMedia 1-19
    public static final int ERR_CORE_START_WORKER = 1;
    public static final int ERR_CORE_START_NAME = 2;
    public static final int ERR_CORE_START_RUNNING = 3;
    public static final int ERR_CORE_LOAD_MODULE_LOG = 4;
    public static final int ERR_CORE_LOAD_MODULE = 5;
    public static final int ERR_CORE_CLIENT_ONLY = 6;
    public static final int ERR_CORE_NOT_STARTED = 7;
    // WaterMediaConfig 20-29
    public static final int ERR_CONFIG_LOADED = 20;
    public static final int ERR_CONFIG_LOAD = 21;

    // TOOLS 100-399
    // DataTool 100-119
    public static final int ERR_DATA_PREFIXES_NULL = 100;
    public static final int ERR_DATA_COMPARE_NULL = 101;
    public static final int ERR_DATA_SHA256_MISSING = 102;
    public static final int ERR_DATA_EXIF_ORIENTATION = 103;
    public static final int ERR_DATA_ORIENT_BPP = 104;
    public static final int ERR_DATA_SCALE_FORMAT = 105;
    // FFTool 120-139
    public static final int ERR_FFTOOL_PIXEL_FORMAT = 120;
    public static final int ERR_FFTOOL_OPERATION = 121;
    public static final int ERR_FFTOOL_TLS_TRANSPORT = 122;
    public static final int ERR_FFTOOL_TLS_PATCHES = 123;
    public static final int ERR_FFTOOL_TLS_NO_ANCHORS = 124;
    public static final int ERR_FFTOOL_TLS_NESTED = 125;
    public static final int ERR_FFTOOL_TLS_NULL_OPTIONS = 126;
    public static final int ERR_FFTOOL_TLS_NOT_READY = 127;
    public static final int ERR_FFTOOL_TLS_NATIVE = 128;
    // IOTool 140-169
    public static final int ERR_IO_READ_CANCELLED = 140;
    public static final int ERR_IO_READ_DEADLINE = 141;
    public static final int ERR_IO_READ_LIMIT = 142;
    public static final int ERR_IO_PUBLISH_STORAGE = 143;
    public static final int ERR_IO_PUBLISH_CHILD = 144;
    public static final int ERR_IO_EXEC_MISSING = 145;
    public static final int ERR_IO_EXEC_PERMISSION = 146;
    public static final int ERR_IO_SHA256_MISSING = 147;
    public static final int ERR_IO_DIGEST_INVALID = 148;
    public static final int ERR_IO_DIGEST_MISMATCH = 149;
    public static final int ERR_IO_RESOURCE_MISSING = 150;
    public static final int ERR_IO_SHADER_INCLUDE = 151;
    // M3UTool 170-179
    public static final int ERR_M3U_HTTP_STATUS = 170;
    public static final int ERR_M3U_EMPTY_BODY = 171;
    public static final int ERR_M3U_NO_HEADER = 172;
    public static final int ERR_M3U_UNKNOWN_SHAPE = 173;

    // UTIL 400-699
    // HttpHeaders 400-409
    public static final int ERR_HEADERS_NULL_ARGS = 400;
    public static final int ERR_HEADERS_EMPTY_NAME = 401;
    public static final int ERR_HEADERS_NAME_TOKEN = 402;
    public static final int ERR_HEADERS_VALUE_CONTROL = 403;
    // MediaQuality 410-419
    public static final int ERR_QUALITY_PREFERRED_NULL = 410;
    // Metadata 420-429
    public static final int ERR_METADATA_NEGATIVE_DURATION = 420;
    // NetRequest 430-469
    public static final int ERR_REQUEST_NULL_URI = 430;
    public static final int ERR_REQUEST_BYTE_LIMIT = 431;
    public static final int ERR_REQUEST_DEADLINE_VALUE = 432;
    public static final int ERR_REQUEST_ALREADY_SENT = 433;
    public static final int ERR_REQUEST_HTTPS_ONLY = 434;
    public static final int ERR_REQUEST_TRANSFER_DEADLINE = 435;
    public static final int ERR_REQUEST_MAX_REDIRECTS = 436;
    public static final int ERR_REQUEST_REDIRECT_SCHEME = 437;
    public static final int ERR_REQUEST_REDIRECT_ORIGIN = 438;
    public static final int ERR_REQUEST_BODY_READ_STREAM = 439;
    public static final int ERR_REQUEST_DOWNLOAD_STATUS = 440;
    public static final int ERR_REQUEST_DOWNLOAD_SIZE = 441;
    public static final int ERR_REQUEST_DOWNLOAD_BUDGET = 442;
    public static final int ERR_REQUEST_DOWNLOAD_TRUNCATED = 443;
    public static final int ERR_REQUEST_BODY_READ_JSON = 444;
    public static final int ERR_REQUEST_JSON_MALFORMED = 445;
    public static final int ERR_REQUEST_NOT_SENT = 446;
    // PixelFormat 470-479
    public static final int ERR_PIXELFORMAT_BITS_RANGE = 470;
    public static final int ERR_PIXELFORMAT_PLANE_MISSING = 471;
    public static final int ERR_PIXELFORMAT_PLANE_SIZE = 472;
    // Track 480-489
    public static final int ERR_TRACK_NULL_URI = 480;

    // NETWORK 700-999
    // NetworkAPI 700-719
    public static final int ERR_NETWORK_ALREADY_LOADED = 700;
    public static final int ERR_NETWORK_UPLOAD_NULL_FILES = 701;
    public static final int ERR_NETWORK_UPLOAD_NOT_FILE_BATCH = 702;
    public static final int ERR_NETWORK_UPLOAD_NOT_FILE = 703;
    public static final int ERR_NETWORK_UPLOAD_NOT_LOADED = 704;
    public static final int ERR_NETWORK_UPLOAD_UNFINISHED = 705;
    public static final int ERR_NETWORK_REMOTE_URL = 706;
    public static final int ERR_NETWORK_SERVER_START = 707;
    public static final int ERR_NETWORK_UPLOAD_CANCELLED_WRITING = 708;
    public static final int ERR_NETWORK_UPLOAD_CANCELLED = 709;
    public static final int ERR_NETWORK_UPLOAD_INVALID_ID = 710;
    public static final int ERR_NETWORK_UPLOAD_SERVER_BUSY = 711;
    public static final int ERR_NETWORK_UPLOAD_HTTP_STATUS = 712;
    public static final int ERR_NETWORK_UPLOAD_LOG = 713;
    // NetworkServer 720-769
    public static final int ERR_SERVER_STOP_BUSY = 720;
    public static final int ERR_SERVER_ALREADY_RUNNING = 721;
    public static final int ERR_SERVER_NO_BIND_ADDRESS = 722;
    public static final int ERR_SERVER_NO_TOKEN = 723;
    public static final int ERR_SERVER_DEFAULT_TOKEN = 724;
    public static final int ERR_SERVER_INVALID_LIMITS = 725;
    public static final int ERR_SERVER_PORT_BUSY = 726;
    public static final int ERR_SERVER_STORAGE_OVERLAP = 727;
    public static final int ERR_SERVER_STORAGE_IN_USE = 728;
    public static final int ERR_SERVER_PORT_CLOSED = 729;
    public static final int ERR_SERVER_EXCHANGES_BUSY = 730;
    public static final int ERR_SERVER_STOP_INTERRUPTED = 731;
    public static final int ERR_SERVER_STOP_STORAGE_LOCK = 732;
    public static final int ERR_SERVER_UPLOAD_IDLE = 733;
    public static final int ERR_SERVER_UPLOAD_TRUNCATED = 734;
    public static final int ERR_SERVER_UPLOAD_STOPPED = 735;
    public static final int ERR_SERVER_DOWNLOAD_IDLE = 736;
    public static final int ERR_SERVER_DOWNLOAD_TRUNCATED = 737;

    // PLAYER 1000-1999
    // ALEngine 1000-1019
    public static final int ERR_AL_BUFFER_COUNT = 1000;
    public static final int ERR_AL_NO_CONTEXT = 1001;
    public static final int ERR_AL_SINK_NULL_MIX = 1002;
    public static final int ERR_AL_NO_NATIVE_FORMAT = 1003;
    public static final int ERR_AL_CONTEXT_NOT_CURRENT = 1004;
    public static final int ERR_AL_PROCESSED_EXCEEDS = 1005;
    public static final int ERR_AL_NO_S32_FORMAT = 1006;
    public static final int ERR_AL_CHECK_ERROR = 1007;
    public static final int ERR_AL_GEN_SOURCE = 1008;
    public static final int ERR_AL_MIX_NULL_MIX = 1009;
    // FFMediaPlayer 1020-1049
    public static final int ERR_FFPLAYER_RELEASE_CALLBACK = 1020;
    public static final int ERR_FFPLAYER_NULL_TRACK = 1021;
    public static final int ERR_FFPLAYER_SCALED_ALLOC = 1022;
    public static final int ERR_FFPLAYER_NO_FRAMES_LOG = 1023;
    public static final int ERR_FFPLAYER_NO_FRAMES = 1024;
    public static final int ERR_FFPLAYER_DEMUX_DEAD_LOG = 1025;
    public static final int ERR_FFPLAYER_DEMUX_DEAD = 1026;
    public static final int ERR_FFPLAYER_GPU_FRAMES = 1027;
    public static final int ERR_FFPLAYER_SWS_SCALE = 1028;
    public static final int ERR_FFPLAYER_GFX_REFUSED = 1029;
    public static final int ERR_FFPLAYER_LIFECYCLE = 1030;
    public static final int ERR_FFPLAYER_SWS_CONTEXT = 1031;
    public static final int ERR_FFPLAYER_SFX_EMPTY_TABLE = 1032;
    public static final int ERR_FFPLAYER_SFX_NO_TYPES = 1033;
    public static final int ERR_FFPLAYER_SFX_REJECTED = 1034;
    // GFXEngine 1050-1059
    public static final int ERR_GFX_FRAME_PLANES = 1050;
    public static final int ERR_GFX_FRAME_BUFFER = 1051;
    // GLEngine 1060-1079
    public static final int ERR_GL_RENDER_EX_NULL = 1060;
    public static final int ERR_GL_RENDER_EX_DISPATCH = 1061;
    public static final int ERR_GL_UPLOAD = 1062;
    public static final int ERR_GL_SHADER_READ = 1063;
    public static final int ERR_GL_VERTEX_COMPILE = 1064;
    public static final int ERR_GL_FRAGMENT_COMPILE = 1065;
    public static final int ERR_GL_PROGRAM_LINK = 1066;
    public static final int ERR_GL_DRAIN = 1067;
    // JSEngine 1080-1089
    public static final int ERR_JS_BUFFER_MS = 1080;
    public static final int ERR_JS_NO_LINE = 1081;
    // MRL 1090-1129
    public static final int ERR_MRL_CREATE_NULL_URI = 1090;
    public static final int ERR_MRL_SUBSCRIBE_LISTENER = 1091;
    public static final int ERR_MRL_ALREADY_STARTED = 1092;
    public static final int ERR_MRL_GET_NULL_URI = 1093;
    public static final int ERR_MRL_NOT_LOADED = 1094;
    public static final int ERR_MRL_RELATIVE_URI = 1095;
    public static final int ERR_MRL_DISPOSE_FORGOTTEN = 1096;
    public static final int ERR_MRL_PROBE_HTTP = 1097;
    public static final int ERR_MRL_PROBE_CONTENT_TYPE = 1098;
    public static final int ERR_MRL_IPTV_EMPTY = 1099;
    public static final int ERR_MRL_PLATFORM_NO_SOURCES = 1100;
    public static final int ERR_MRL_PLATFORM_NULL_ENTRY = 1101;
    public static final int ERR_MRL_RESOLVE = 1102;
    public static final int ERR_MRL_FIRE_LISTENER = 1103;
    public static final int ERR_MRL_SOURCE_NO_QUALITIES = 1104;
    public static final int ERR_MRL_SOURCE_NO_QUALITY = 1105;
    // MediaPlayer 1130-1169
    public static final int ERR_PLAYER_NULL_MRL = 1130;
    public static final int ERR_PLAYER_SOURCE_UNAVAILABLE = 1131;
    public static final int ERR_PLAYER_NULL_ENGINES = 1132;
    public static final int ERR_PLAYER_NULL_SNAPSHOT = 1133;
    public static final int ERR_PLAYER_TOLERANCE_RANGE = 1134;
    public static final int ERR_PLAYER_NULL_QUALITY = 1135;
    public static final int ERR_PLAYER_NULL_TRACK = 1136;
    public static final int ERR_PLAYER_NEGATIVE_SIZE = 1137;
    public static final int ERR_PLAYER_NULL_LOD = 1138;
    public static final int ERR_PLAYER_SINK_NULL_MIX = 1139;
    public static final int ERR_PLAYER_NULL_LISTENER = 1140;
    public static final int ERR_PLAYER_LISTENER_LOG = 1141;
    public static final int ERR_PLAYER_SYNC_TICK_LOG = 1142;
    public static final int ERR_PLAYER_STATUS_ORDINAL = 1143;
    public static final int ERR_PLAYER_STATE_NULL_STATUS = 1144;
    public static final int ERR_PLAYER_STATE_NEGATIVE_TIME = 1145;
    public static final int ERR_PLAYER_STATE_SPEED = 1146;
    public static final int ERR_PLAYER_SNAPSHOT_PAYLOAD = 1147;
    public static final int ERR_PLAYER_SNAPSHOT_VERSION = 1148;
    // NetworkCache 1170-1189
    public static final int ERR_CACHE_WAIT_INTERRUPTED = 1170;
    public static final int ERR_CACHE_DOWNLOAD = 1171;
    public static final int ERR_CACHE_HTTP_STATUS = 1172;
    public static final int ERR_CACHE_LIMIT_EXCEEDED = 1173;
    public static final int ERR_CACHE_UNBOUNDED = 1174;
    public static final int ERR_CACHE_TRUNCATED_BODY = 1175;
    public static final int ERR_CACHE_INDEX_FORMAT = 1176;
    public static final int ERR_CACHE_INDEX_TRAILING = 1177;
    public static final int ERR_CACHE_START_ABANDONED = 1178;
    // PlayerAPI 1190-1209
    public static final int ERR_PLAYERAPI_LOADED = 1190;
    public static final int ERR_PLAYERAPI_NULL_URI = 1191;
    public static final int ERR_PLAYERAPI_NULL_ARGS = 1192;
    public static final int ERR_PLAYERAPI_NO_BACKEND = 1193;
    public static final int ERR_PLAYERAPI_CREATE_LOG = 1194;
    public static final int ERR_PLAYERAPI_JAVAFX = 1195;
    public static final int ERR_PLAYERAPI_CACHE_START = 1196;
    // SFXEngine 1210-1229
    public static final int ERR_SFX_AUDIO_DATA = 1210;
    public static final int ERR_SFX_VOLUME = 1211;
    public static final int ERR_SFX_CHANNEL_COUNT = 1212;
    public static final int ERR_SFX_NO_SAMPLE_TYPES = 1213;
    public static final int ERR_SFX_POSITION = 1214;
    public static final int ERR_SFX_DISTANCES = 1215;
    public static final int ERR_SFX_NO_ENVIRONMENT = 1216;
    // SWEngine 1230-1239
    public static final int ERR_SW_SURFACE_ALLOC = 1230;
    public static final int ERR_SW_CALLBACK = 1231;
    // TxMediaPlayer 1240-1269
    public static final int ERR_TXPLAYER_RELEASE_CALLBACK = 1240;
    public static final int ERR_TXPLAYER_RELEASE_BUSY = 1241;
    public static final int ERR_TXPLAYER_DIMENSIONS = 1242;
    public static final int ERR_TXPLAYER_TEXTURE_REFUSED = 1243;
    public static final int ERR_TXPLAYER_LIFECYCLE_LOG = 1244;
    public static final int ERR_TXPLAYER_NO_FRAMES = 1245;
    public static final int ERR_TXPLAYER_PRELOAD_REFUSED = 1246;
    public static final int ERR_TXPLAYER_STREAM_LIFECYCLE_LOG = 1247;
    public static final int ERR_TXPLAYER_CANCELLED = 1248;
    public static final int ERR_TXPLAYER_OPEN_SOURCE = 1249;
    public static final int ERR_TXPLAYER_STREAM_NO_FRAMES = 1250;
    public static final int ERR_TXPLAYER_FRAME_REFUSED_LOG = 1251;
    public static final int ERR_TXPLAYER_BUFFER_LIMIT = 1252;
    // VKEngine 1270-1299
    public static final int ERR_VK_NULL_CONTEXT = 1270;
    public static final int ERR_VK_NO_UPLOAD_PATH = 1271;
    public static final int ERR_VK_RELEASE_WEDGED = 1272;
    public static final int ERR_VK_FRAME_REF = 1273;
    public static final int ERR_VK_POOL_REF = 1274;
    public static final int ERR_VK_STAGING_MEMORY = 1275;
    public static final int ERR_VK_RESULT_LOG = 1276;
    public static final int ERR_VK_UPLOAD_LOG = 1277;
    public static final int ERR_VK_IMAGE_MEMORY = 1278;
    public static final int ERR_VK_SHADERC_INIT = 1279;
    public static final int ERR_VK_SHADERC_NULL = 1280;
    public static final int ERR_VK_SHADER_COMPILE = 1281;
    public static final int ERR_VK_NO_SPIRV = 1282;
    public static final int ERR_VK_SHADER_READ = 1283;
    public static final int ERR_VK_CALL_RESULT = 1284;

    // PLATFORM 2000-3999
    // Platform 2000-2009
    public static final int ERR_PLATFORM_SOURCES_NULL = 2000;
    public static final int ERR_PLATFORM_SOURCE_TYPE_NULL = 2001;
    public static final int ERR_PLATFORM_SOURCE_NO_QUALITIES = 2002;
    public static final int ERR_PLATFORM_QUALITY_URI_NULL = 2003;
    public static final int ERR_PLATFORM_QUALITY_NEGATIVE = 2004;
    // PlatformAPI 2010-2019
    public static final int ERR_PLATFORMAPI_LIMIT_LOW = 2010;
    public static final int ERR_PLATFORMAPI_URI_NULL = 2011;
    public static final int ERR_PLATFORMAPI_RESOLVE_CANCELLED = 2012;
    public static final int ERR_PLATFORMAPI_RESOLVE_UNEXPECTED = 2013;
    public static final int ERR_PLATFORMAPI_REGISTER_NULL = 2014;
    // PlatformException 2020-2029
    public static final int ERR_PLATFORMEXCEPTION_UNSUPPORTED = 2020;
    // PlatformResult 2030-2039
    public static final int ERR_PLATFORMRESULT_ARGS_NULL = 2030;
    // WaterPlatform 2040-2049
    public static final int ERR_WATER_RESOLVE = 2040;
    public static final int ERR_WATER_HOST_MISSING = 2041;
    public static final int ERR_WATER_HOST_UNKNOWN = 2042;
    // BiliBiliPlatform 2050-2079
    public static final int ERR_BILIBILI_RESOLVE = 2050;
    public static final int ERR_BILIBILI_CID_MISSING = 2051;
    public static final int ERR_BILIBILI_SEASON_EMPTY = 2052;
    public static final int ERR_BILIBILI_EPISODE_MISSING = 2053;
    public static final int ERR_BILIBILI_VIP_REQUIRED = 2054;
    public static final int ERR_BILIBILI_LIVE_OFFLINE = 2055;
    public static final int ERR_BILIBILI_LIVE_NO_ROOM_ID = 2056;
    public static final int ERR_BILIBILI_LIVE_NO_STREAM = 2057;
    public static final int ERR_BILIBILI_NO_STREAMS = 2058;
    public static final int ERR_BILIBILI_API_ERROR = 2059;
    public static final int ERR_BILIBILI_API_NO_DATA = 2060;
    public static final int ERR_BILIBILI_BUVID3_MISSING = 2061;
    // BlueskyPlatform 2080-2089
    public static final int ERR_BLUESKY_POST_MISSING = 2080;
    public static final int ERR_BLUESKY_NO_MEDIA = 2081;
    public static final int ERR_BLUESKY_RESOLVE = 2082;
    public static final int ERR_BLUESKY_MATURE = 2083;
    // DTubePlatform 2090-2099
    public static final int ERR_DTUBE_PAGE_STATUS = 2090;
    public static final int ERR_DTUBE_NO_VIDEO_URL = 2091;
    public static final int ERR_DTUBE_VIDEO_URL_NOT_WEB = 2092;
    public static final int ERR_DTUBE_RESOLVE = 2093;
    // DrivePlatform 2100-2109
    public static final int ERR_DRIVE_RESOLVE = 2100;
    public static final int ERR_DRIVE_DOWNLOAD_STATUS = 2101;
    public static final int ERR_DRIVE_FORM_NO_UUID = 2102;
    public static final int ERR_DRIVE_CONFIRM_STATUS = 2103;
    // DropboxPlatform 2110-2119
    public static final int ERR_DROPBOX_DIRECT_STATUS = 2110;
    public static final int ERR_DROPBOX_RESOLVE = 2111;
    // ImgurPlatform 2120-2139
    public static final int ERR_IMGUR_POST_RESPONSE = 2120;
    public static final int ERR_IMGUR_POST_MATURE = 2121;
    public static final int ERR_IMGUR_POST_NO_IMAGES = 2122;
    public static final int ERR_IMGUR_POST_ITEM = 2123;
    public static final int ERR_IMGUR_IMAGE_RESPONSE = 2124;
    public static final int ERR_IMGUR_API_RESPONSE = 2125;
    public static final int ERR_IMGUR_ITEM_MATURE = 2126;
    public static final int ERR_IMGUR_ITEM_NO_LINK = 2127;
    // KickPlatform 2140-2159
    public static final int ERR_KICK_STREAMER_OFFLINE = 2140;
    public static final int ERR_KICK_STREAMER_BANNED = 2141;
    public static final int ERR_KICK_STREAMER_MATURE = 2142;
    public static final int ERR_KICK_LIVE_NO_URL = 2143;
    public static final int ERR_KICK_VOD_NO_URL = 2144;
    public static final int ERR_KICK_USER_BANNED = 2145;
    public static final int ERR_KICK_VOD_MATURE = 2146;
    public static final int ERR_KICK_CLIP_UNAVAILABLE = 2147;
    public static final int ERR_KICK_CLIP_NO_URL = 2148;
    public static final int ERR_KICK_CLIP_MATURE = 2149;
    // LightshotPlatform 2160-2169
    public static final int ERR_LIGHTSHOT_PAGE_STATUS = 2160;
    public static final int ERR_LIGHTSHOT_IMAGE_MISSING = 2161;
    public static final int ERR_LIGHTSHOT_IMAGE_MALFORMED = 2162;
    public static final int ERR_LIGHTSHOT_RESOLVE = 2163;
    // MedalPlatform 2170-2179
    public static final int ERR_MEDAL_DMCA = 2170;
    public static final int ERR_MEDAL_LOGIN_REQUIRED = 2171;
    public static final int ERR_MEDAL_NO_SOURCE = 2172;
    // MediaFirePlatform 2180-2189
    public static final int ERR_MEDIAFIRE_PAGE_STATUS = 2180;
    public static final int ERR_MEDIAFIRE_LINK_MISSING = 2181;
    public static final int ERR_MEDIAFIRE_LINK_NOT_WEB = 2182;
    public static final int ERR_MEDIAFIRE_RESOLVE = 2183;
    // NewgroundsPlatform 2190-2239
    public static final int ERR_NEWGROUNDS_RESOLVE = 2190;
    public static final int ERR_NEWGROUNDS_MOVIE_REMOVED = 2191;
    public static final int ERR_NEWGROUNDS_MOVIE_PAGE_STATUS = 2192;
    public static final int ERR_NEWGROUNDS_MOVIE_FILES_STATUS = 2193;
    public static final int ERR_NEWGROUNDS_MOVIE_SITE_ERROR = 2194;
    public static final int ERR_NEWGROUNDS_MOVIE_NO_VIDEO = 2195;
    public static final int ERR_NEWGROUNDS_AUDIO_REMOVED = 2196;
    public static final int ERR_NEWGROUNDS_AUDIO_LOGIN = 2197;
    public static final int ERR_NEWGROUNDS_AUDIO_PAGE_STATUS = 2198;
    public static final int ERR_NEWGROUNDS_AUDIO_NO_FILE = 2199;
    public static final int ERR_NEWGROUNDS_ART_LOGIN = 2200;
    public static final int ERR_NEWGROUNDS_ART_PAGE_STATUS = 2201;
    public static final int ERR_NEWGROUNDS_ART_NO_DATA = 2202;
    public static final int ERR_NEWGROUNDS_ART_NOT_ARRAY = 2203;
    public static final int ERR_NEWGROUNDS_ART_NO_SOURCES = 2204;
    public static final int ERR_NEWGROUNDS_RATING_MATURE = 2205;
    public static final int ERR_NEWGROUNDS_GUARD_STILL_BLOCKS = 2206;
    public static final int ERR_NEWGROUNDS_GUARD_UNREADABLE = 2207;
    public static final int ERR_NEWGROUNDS_GUARD_NO_PARAMS = 2208;
    public static final int ERR_NEWGROUNDS_GUARD_REJECTED = 2209;
    public static final int ERR_NEWGROUNDS_SHA256_MISSING = 2210;
    public static final int ERR_NEWGROUNDS_GUARD_INTERRUPTED = 2211;
    public static final int ERR_NEWGROUNDS_GUARD_TIMEOUT = 2212;
    public static final int ERR_NEWGROUNDS_RATING_ADULT = 2213;
    public static final int ERR_NEWGROUNDS_GUARD_ARGON_LIMITS = 2214;
    // OdyseePlatform 2240-2269
    public static final int ERR_ODYSEE_NO_CLAIM = 2240;
    public static final int ERR_ODYSEE_CLAIM_UNAVAILABLE = 2241;
    public static final int ERR_ODYSEE_REPOST_NO_URI = 2242;
    public static final int ERR_ODYSEE_CLAIM_TYPE = 2243;
    public static final int ERR_ODYSEE_MATURE = 2244;
    public static final int ERR_ODYSEE_LIVE_NO_CHANNEL = 2245;
    public static final int ERR_ODYSEE_NOT_LIVE = 2246;
    public static final int ERR_ODYSEE_LIVE_ENDED = 2247;
    public static final int ERR_ODYSEE_FILE_TYPE = 2248;
    public static final int ERR_ODYSEE_PAID = 2249;
    public static final int ERR_ODYSEE_NO_STREAM_URL = 2250;
    public static final int ERR_ODYSEE_RPC_ERROR = 2251;
    public static final int ERR_ODYSEE_RPC_NO_RESULT = 2252;
    // PornHubPlatform 2270-2289
    public static final int ERR_PORNHUB_MATURE = 2270;
    public static final int ERR_PORNHUB_PAGE_STATUS = 2271;
    public static final int ERR_PORNHUB_FLASHVARS_MISSING = 2272;
    public static final int ERR_PORNHUB_NO_MEDIA_DEFS = 2273;
    public static final int ERR_PORNHUB_NO_CDN_LINKS = 2274;
    public static final int ERR_PORNHUB_RESOLVE = 2275;
    // SendvidPlatform 2290-2309
    public static final int ERR_SENDVID_QUEUE_FAR = 2290;
    public static final int ERR_SENDVID_PAGE_STATUS = 2291;
    public static final int ERR_SENDVID_SOURCE_MISSING = 2292;
    public static final int ERR_SENDVID_SOURCE_NOT_URL = 2293;
    public static final int ERR_SENDVID_RESOLVE = 2294;
    public static final int ERR_SENDVID_WAIT_INTERRUPTED = 2295;
    public static final int ERR_SENDVID_QUEUE_GREW = 2296;
    public static final int ERR_SENDVID_WAIT_TIMEOUT = 2297;
    // StreamablePlatform 2310-2319
    public static final int ERR_STREAMABLE_NOT_READY = 2310;
    public static final int ERR_STREAMABLE_NO_MP4 = 2311;
    // TikTokPlatform 2320-2349
    public static final int ERR_TIKTOK_NO_VIDEO_DATA = 2320;
    public static final int ERR_TIKTOK_RESOLVE = 2321;
    public static final int ERR_TIKTOK_PRIVATE = 2322;
    public static final int ERR_TIKTOK_IP_BLOCKED = 2323;
    public static final int ERR_TIKTOK_NO_VIDEO_OBJECT = 2324;
    public static final int ERR_TIKTOK_NO_PLAY_URLS = 2325;
    public static final int ERR_TIKTOK_PAGE_STATUS = 2326;
    public static final int ERR_TIKTOK_WAF_EXTRACT = 2327;
    public static final int ERR_TIKTOK_WAF_NO_SEED = 2328;
    public static final int ERR_TIKTOK_WAF_INTERRUPTED = 2329;
    public static final int ERR_TIKTOK_WAF_NO_SHA256 = 2330;
    public static final int ERR_TIKTOK_WAF_UNSOLVED = 2331;
    // TwitchPlatform 2350-2379
    public static final int ERR_TWITCH_RESOLVE = 2350;
    public static final int ERR_TWITCH_SEARCH = 2351;
    public static final int ERR_TWITCH_NO_CHANNEL = 2352;
    public static final int ERR_TWITCH_OFFLINE = 2353;
    public static final int ERR_TWITCH_CLIP_MISSING = 2354;
    public static final int ERR_TWITCH_CLIP_NO_TOKEN = 2355;
    public static final int ERR_TWITCH_CLIP_NO_QUALITY = 2356;
    public static final int ERR_TWITCH_TOKEN_MISSING = 2357;
    public static final int ERR_TWITCH_METADATA_NO_DATA = 2358;
    public static final int ERR_TWITCH_CLASS_UNAVAILABLE = 2359;
    public static final int ERR_TWITCH_MATURE = 2360;
    // TwitterPlatform 2380-2389
    public static final int ERR_TWITTER_MATURE = 2380;
    public static final int ERR_TWITTER_UNAVAILABLE = 2381;
    public static final int ERR_TWITTER_MATURE_SENSITIVE = 2382;
    public static final int ERR_TWITTER_NO_MEDIA = 2383;
    public static final int ERR_TWITTER_ALL_FAILED = 2384;
    // VidLiiPlatform 2390-2399
    public static final int ERR_VIDLII_PAGE_STATUS = 2390;
    public static final int ERR_VIDLII_SOURCE_MISSING = 2391;
    public static final int ERR_VIDLII_SOURCE_MALFORMED = 2392;
    public static final int ERR_VIDLII_RESOLVE = 2393;
    // WebRequests 2400-2409
    public static final int ERR_WEBREQUESTS_URL_MALFORMED = 2400;
    public static final int ERR_WEBREQUESTS_API_STATUS = 2401;
    public static final int ERR_WEBREQUESTS_API_BODY = 2402;
    public static final int ERR_WEBREQUESTS_FETCH = 2403;
    // YouTubePlatform 2410-2429
    public static final int ERR_YOUTUBE_RESOLVE = 2410;
    public static final int ERR_YOUTUBE_SEARCH = 2411;
    public static final int ERR_YOUTUBE_RETRY_IO = 2412;
    public static final int ERR_YOUTUBE_RETRY_RUNTIME = 2413;
    public static final int ERR_YOUTUBE_BOTGUARD_PROVISION = 2414;
    public static final int ERR_YOUTUBE_NO_TOKEN = 2415;
    public static final int ERR_YOUTUBE_MATURE = 2416;
    public static final int ERR_YOUTUBE_AGE_GATE = 2417;
    public static final int ERR_YOUTUBE_NO_VISITOR_DATA = 2418;
    // YtDlpPlatform 2430-2459
    public static final int ERR_YTDLP_RESOLVE = 2430;
    public static final int ERR_YTDLP_PROVISION = 2431;
    public static final int ERR_YTDLP_JSON_UNPARSEABLE = 2432;
    public static final int ERR_YTDLP_NO_JSON = 2433;
    public static final int ERR_YTDLP_START = 2434;
    public static final int ERR_YTDLP_TIMEOUT = 2435;
    public static final int ERR_YTDLP_INTERRUPTED = 2436;
    public static final int ERR_YTDLP_EXIT_CODE = 2437;
    public static final int ERR_YTDLP_ENTRY_JSON = 2438;
    public static final int ERR_YTDLP_NO_ENTRIES = 2439;
    public static final int ERR_YTDLP_JSON_UNEXPECTED = 2440;
    public static final int ERR_YTDLP_MATURE = 2441;
    public static final int ERR_YTDLP_NO_FORMATS = 2442;
    public static final int ERR_YTDLP_NO_STREAMS = 2443;

    // CODECS 4000-7999
    // BcReader 4000-4009
    public static final int ERR_BC_TEXTURE_SIZE = 4000;
    public static final int ERR_BC_TEXTURE_TRUNCATED = 4001;
    public static final int ERR_BC_DELAYS = 4002;
    public static final int ERR_BC_RESET_CLOSED = 4003;
    // CodecsAPI 4010-4039
    public static final int ERR_CODECS_LOADED = 4010;
    public static final int ERR_CODECS_READER_NULL_SOURCE = 4011;
    public static final int ERR_CODECS_READER_FORMAT = 4012;
    public static final int ERR_CODECS_DECODE_NULL_DATA = 4013;
    public static final int ERR_CODECS_DECODE_NULL_STREAM = 4014;
    public static final int ERR_CODECS_ENCODER_NULL_FILE = 4015;
    public static final int ERR_CODECS_ENCODER_NULL_OUT = 4016;
    public static final int ERR_CODECS_SNIFF_NULL_STREAM = 4017;
    public static final int ERR_CODECS_SNIFF_NULL_SOURCE = 4018;
    public static final int ERR_CODECS_FFMPEG_BINARIES = 4019;
    public static final int ERR_CODECS_FFMPEG_LOG = 4020;
    // DownscaleFilter 4040-4049
    public static final int ERR_DOWNSCALE_SCALE = 4040;
    public static final int ERR_DOWNSCALE_TARGET_SIZE = 4041;
    public static final int ERR_DOWNSCALE_SOURCE_SIZE = 4042;
    public static final int ERR_DOWNSCALE_SOURCE_SCALE = 4043;
    // Encoder 4050-4109
    public static final int ERR_ENCODER_FRAME_CLOSED = 4050;
    public static final int ERR_ENCODER_FRAME_NO_VIDEO = 4051;
    public static final int ERR_ENCODER_FRAME_NULL = 4052;
    public static final int ERR_ENCODER_FRAME_SIZE = 4053;
    public static final int ERR_ENCODER_FRAME_TIMESTAMP = 4054;
    public static final int ERR_ENCODER_SAMPLES_CLOSED = 4055;
    public static final int ERR_ENCODER_SAMPLES_NO_AUDIO = 4056;
    public static final int ERR_ENCODER_SAMPLES_NULL = 4057;
    public static final int ERR_ENCODER_SAMPLES_SPLIT = 4058;
    public static final int ERR_ENCODER_SAMPLES_UNSUPPORTED = 4059;
    public static final int ERR_ENCODER_CHECK_NULL_CONTAINER = 4060;
    public static final int ERR_ENCODER_CHECK_DECODE_ONLY = 4061;
    public static final int ERR_ENCODER_CHECK_NO_TRACK = 4062;
    public static final int ERR_ENCODER_CHECK_VIDEO_CODEC = 4063;
    public static final int ERR_ENCODER_CHECK_AUDIO_CODEC = 4064;
    public static final int ERR_ENCODER_VIDEO_NULL = 4065;
    public static final int ERR_ENCODER_VIDEO_COMPRESSED = 4066;
    public static final int ERR_ENCODER_VIDEO_DIMENSIONS = 4067;
    public static final int ERR_ENCODER_VIDEO_BUFFER = 4068;
    public static final int ERR_ENCODER_VIDEO_FPS = 4069;
    public static final int ERR_ENCODER_VIDEO_QUALITY = 4070;
    public static final int ERR_ENCODER_VIDEO_REPEAT = 4071;
    public static final int ERR_ENCODER_AUDIO_NULL = 4072;
    public static final int ERR_ENCODER_AUDIO_RATE = 4073;
    public static final int ERR_ENCODER_AUDIO_CHANNELS = 4074;
    public static final int ERR_ENCODER_AUDIO_BITRATE = 4075;
    // ImageData 4110-4139
    public static final int ERR_IMAGEDATA_NO_FRAMES = 4110;
    public static final int ERR_IMAGEDATA_NULL_FORMAT = 4111;
    public static final int ERR_IMAGEDATA_SIZE = 4112;
    public static final int ERR_IMAGEDATA_DELAYS_LENGTH = 4113;
    public static final int ERR_IMAGEDATA_DELAYS_NEGATIVE = 4114;
    public static final int ERR_IMAGEDATA_DURATION = 4115;
    public static final int ERR_IMAGEDATA_REPEAT = 4116;
    public static final int ERR_IMAGEDATA_SCAN_DELAYS_LENGTH = 4117;
    public static final int ERR_IMAGEDATA_SCAN_DELAYS_NEGATIVE = 4118;
    public static final int ERR_IMAGEDATA_SCAN_DURATION = 4119;
    public static final int ERR_IMAGEDATA_SCAN_REPEAT = 4120;
    public static final int ERR_IMAGEDATA_SCAN_NULL_DELAYS = 4121;
    // ImageMetadata 4140-4149
    public static final int ERR_IMAGEMETADATA_READ_ONLY = 4140;
    // ImageReader 4150-4169
    public static final int ERR_IMAGEREADER_FORMAT = 4150;
    public static final int ERR_IMAGEREADER_NULL_FILTER = 4151;
    public static final int ERR_IMAGEREADER_BUDGET = 4152;
    public static final int ERR_IMAGEREADER_NO_FRAMES = 4153;
    public static final int ERR_IMAGEREADER_NO_MORE = 4154;
    public static final int ERR_IMAGEREADER_CLOSED_DECODING = 4155;
    public static final int ERR_IMAGEREADER_CLOSED = 4156;
    // DdsHeader 4170-4209
    public static final int ERR_DDS_HEADER_TRUNCATED = 4170;
    public static final int ERR_DDS_MAGIC = 4171;
    public static final int ERR_DDS_HEADER_MALFORMED = 4172;
    public static final int ERR_DDS_LEGACY = 4173;
    public static final int ERR_DDS_DEPTH = 4174;
    public static final int ERR_DDS_MIPS = 4175;
    public static final int ERR_DDS_VOLUME = 4176;
    public static final int ERR_DDS_TEXTURE_1D = 4177;
    public static final int ERR_DDS_DIMENSION = 4178;
    public static final int ERR_DDS_CUBE_MAP = 4179;
    public static final int ERR_DDS_DXGI_INVALID = 4180;
    public static final int ERR_DDS_DXGI_UNSUPPORTED = 4181;
    public static final int ERR_DDS_SIZE = 4182;
    public static final int ERR_DDS_ARRAY_SIZE = 4183;
    public static final int ERR_DDS_FOOTER_COUNT_RANGE = 4184;
    public static final int ERR_DDS_FOOTER_TRUNCATED = 4185;
    public static final int ERR_DDS_FOOTER_VERSION = 4186;
    public static final int ERR_DDS_FOOTER_COUNT = 4187;
    // ColorTable 4210-4219
    public static final int ERR_GIF_COLORTABLE_SIZE = 4210;
    public static final int ERR_GIF_COLORTABLE_TRUNCATED = 4211;
    // GraphicExtension 4220-4229
    public static final int ERR_GIF_GRAPHIC_TRUNCATED = 4220;
    public static final int ERR_GIF_GRAPHIC_BLOCK_SIZE = 4221;
    public static final int ERR_GIF_GRAPHIC_TERMINATOR = 4222;
    // ImageDescriptor 4230-4239
    public static final int ERR_GIF_IMAGE_TRUNCATED = 4230;
    public static final int ERR_GIF_IMAGE_DIMENSIONS = 4231;
    public static final int ERR_GIF_IMAGE_PALETTE_SIZE = 4232;
    // ScreenDescriptor 4240-4249
    public static final int ERR_GIF_SCREEN_TRUNCATED = 4240;
    public static final int ERR_GIF_SCREEN_SIZE = 4241;
    // ACTL 4250-4259
    public static final int ERR_PNG_ACTL_TYPE = 4250;
    public static final int ERR_PNG_ACTL_LENGTH = 4251;
    // BKGD 4260-4269
    public static final int ERR_PNG_BKGD_TYPE = 4260;
    public static final int ERR_PNG_BKGD_LENGTH_GREYSCALE = 4261;
    public static final int ERR_PNG_BKGD_LENGTH_TRUECOLOR = 4262;
    public static final int ERR_PNG_BKGD_LENGTH_INDEXED = 4263;
    public static final int ERR_PNG_BKGD_COLOR_TYPE = 4264;
    // CHRM 4270-4279
    public static final int ERR_PNG_CHRM_TYPE = 4270;
    public static final int ERR_PNG_CHRM_LENGTH = 4271;
    // CICP 4280-4289
    public static final int ERR_PNG_CICP_TYPE = 4280;
    public static final int ERR_PNG_CICP_LENGTH = 4281;
    // CLLI 4290-4299
    public static final int ERR_PNG_CLLI_TYPE = 4290;
    public static final int ERR_PNG_CLLI_LENGTH = 4291;
    // ColorType 4300-4309
    public static final int ERR_PNG_COLORTYPE_VALUE = 4300;
    // EXIF 4310-4319
    public static final int ERR_PNG_EXIF_TYPE = 4310;
    public static final int ERR_PNG_EXIF_TRUNCATED = 4311;
    public static final int ERR_PNG_EXIF_BYTE_ORDER = 4312;
    // FCTL 4320-4339
    public static final int ERR_PNG_FCTL_TYPE = 4320;
    public static final int ERR_PNG_FCTL_LENGTH = 4321;
    public static final int ERR_PNG_FCTL_OPERATIONS = 4322;
    public static final int ERR_PNG_FCTL_DIMENSIONS = 4323;
    public static final int ERR_PNG_FCTL_OFFSET = 4324;
    public static final int ERR_PNG_FCTL_BOUNDS = 4325;
    // FDAT 4340-4349
    public static final int ERR_PNG_FDAT_TYPE = 4340;
    public static final int ERR_PNG_FDAT_LENGTH = 4341;
    // GAMA 4350-4359
    public static final int ERR_PNG_GAMA_TYPE = 4350;
    public static final int ERR_PNG_GAMA_LENGTH = 4351;
    public static final int ERR_PNG_GAMA_VALUE = 4352;
    // HIST 4360-4369
    public static final int ERR_PNG_HIST_TYPE = 4360;
    public static final int ERR_PNG_HIST_ENTRIES = 4361;
    public static final int ERR_PNG_HIST_LENGTH = 4362;
    // ICCP 4370-4379
    public static final int ERR_PNG_ICCP_TYPE = 4370;
    public static final int ERR_PNG_ICCP_NAME = 4371;
    public static final int ERR_PNG_ICCP_TRUNCATED = 4372;
    public static final int ERR_PNG_ICCP_COMPRESSION = 4373;
    // IHDR 4380-4389
    public static final int ERR_PNG_IHDR_TYPE = 4380;
    public static final int ERR_PNG_IHDR_LENGTH = 4381;
    public static final int ERR_PNG_IHDR_COLOR_TYPE = 4382;
    // ITXT 4390-4409
    public static final int ERR_PNG_ITXT_TYPE = 4390;
    public static final int ERR_PNG_ITXT_KEYWORD = 4391;
    public static final int ERR_PNG_ITXT_TRUNCATED_FLAGS = 4392;
    public static final int ERR_PNG_ITXT_COMPRESSION = 4393;
    public static final int ERR_PNG_ITXT_TRUNCATED_LANGUAGE = 4394;
    public static final int ERR_PNG_ITXT_TRUNCATED_TRANSLATED = 4395;
    // MDCV 4410-4419
    public static final int ERR_PNG_MDCV_TYPE = 4410;
    public static final int ERR_PNG_MDCV_LENGTH = 4411;
    // PHYS 4420-4429
    public static final int ERR_PNG_PHYS_TYPE = 4420;
    public static final int ERR_PNG_PHYS_LENGTH = 4421;
    // PLTE 4430-4439
    public static final int ERR_PNG_PLTE_TYPE = 4430;
    public static final int ERR_PNG_PLTE_LENGTH = 4431;
    public static final int ERR_PNG_PLTE_ENTRIES = 4432;
    // PngChunk 4440-4459
    public static final int ERR_PNG_CHUNK_HEADER_BUFFER = 4440;
    public static final int ERR_PNG_CHUNK_LENGTH_BUFFER = 4441;
    public static final int ERR_PNG_CHUNK_DATA_BUFFER = 4442;
    public static final int ERR_PNG_CHUNK_TRUNCATED = 4443;
    public static final int ERR_PNG_CHUNK_TYPE = 4444;
    public static final int ERR_PNG_CHUNK_LENGTH_STREAM = 4445;
    public static final int ERR_PNG_CHUNK_SIZE = 4446;
    public static final int ERR_PNG_CHUNK_DATA_STREAM = 4447;
    public static final int ERR_PNG_CHUNK_HEADER_STREAM = 4448;
    // SBIT 4460-4479
    public static final int ERR_PNG_SBIT_TYPE = 4460;
    public static final int ERR_PNG_SBIT_LENGTH_GREYSCALE = 4461;
    public static final int ERR_PNG_SBIT_LENGTH_TRUECOLOR = 4462;
    public static final int ERR_PNG_SBIT_LENGTH_GREYSCALE_ALPHA = 4463;
    public static final int ERR_PNG_SBIT_LENGTH_TRUECOLOR_ALPHA = 4464;
    public static final int ERR_PNG_SBIT_COLOR_TYPE = 4465;
    // SPLT 4480-4489
    public static final int ERR_PNG_SPLT_TYPE = 4480;
    public static final int ERR_PNG_SPLT_NAME = 4481;
    public static final int ERR_PNG_SPLT_TRUNCATED = 4482;
    public static final int ERR_PNG_SPLT_DEPTH = 4483;
    public static final int ERR_PNG_SPLT_ENTRY_LENGTH = 4484;
    // SRGB 4490-4499
    public static final int ERR_PNG_SRGB_TYPE = 4490;
    public static final int ERR_PNG_SRGB_LENGTH = 4491;
    // TEXT 4500-4509
    public static final int ERR_PNG_TEXT_TYPE = 4500;
    public static final int ERR_PNG_TEXT_KEYWORD = 4501;
    // TIME 4510-4519
    public static final int ERR_PNG_TIME_TYPE = 4510;
    public static final int ERR_PNG_TIME_LENGTH = 4511;
    // TRNS 4520-4529
    public static final int ERR_PNG_TRNS_TYPE = 4520;
    public static final int ERR_PNG_TRNS_LENGTH_GREYSCALE = 4521;
    public static final int ERR_PNG_TRNS_LENGTH_TRUECOLOR = 4522;
    public static final int ERR_PNG_TRNS_ENTRIES = 4523;
    public static final int ERR_PNG_TRNS_COLOR_TYPE = 4524;
    // ZTXT 4530-4539
    public static final int ERR_PNG_ZTXT_TYPE = 4530;
    public static final int ERR_PNG_ZTXT_KEYWORD = 4531;
    public static final int ERR_PNG_ZTXT_TRUNCATED = 4532;
    public static final int ERR_PNG_ZTXT_COMPRESSION = 4533;
    // Zlib 4540-4549
    public static final int ERR_PNG_ZLIB_SIZE = 4540;
    public static final int ERR_PNG_ZLIB_DICTIONARY = 4541;
    public static final int ERR_PNG_ZLIB_INCOMPLETE = 4542;
    public static final int ERR_PNG_ZLIB_STREAM = 4543;
    public static final int ERR_PNG_ZLIB_DATA = 4544;
    // FFDecoder 4550-4619
    public static final int ERR_FFDECODER_TRACK_NO_URI = 4550;
    public static final int ERR_FFDECODER_TRACK_OPEN = 4551;
    public static final int ERR_FFDECODER_TRACK_STREAM_INFO = 4552;
    public static final int ERR_FFDECODER_TRACK_NO_STREAM = 4553;
    public static final int ERR_FFDECODER_OPEN_FAILED = 4554;
    public static final int ERR_FFDECODER_INIT_EXCEPTION = 4555;
    public static final int ERR_FFDECODER_SEEK_REOPEN_LOG = 4556;
    public static final int ERR_FFDECODER_SEEK_REOPEN = 4557;
    public static final int ERR_FFDECODER_GPU_TRANSFER = 4558;
    public static final int ERR_FFDECODER_RESAMPLER_REINIT = 4559;
    public static final int ERR_FFDECODER_OPEN_INPUT_LOG = 4560;
    public static final int ERR_FFDECODER_OPEN_INPUT = 4561;
    public static final int ERR_FFDECODER_REOPEN_STREAM_INFO = 4562;
    public static final int ERR_FFDECODER_REOPEN_VIDEO = 4563;
    public static final int ERR_FFDECODER_REOPEN_AUDIO = 4564;
    public static final int ERR_FFDECODER_STREAM_INFO_LOG = 4565;
    public static final int ERR_FFDECODER_STREAM_INFO = 4566;
    public static final int ERR_FFDECODER_INIT_NO_STREAMS = 4567;
    public static final int ERR_FFDECODER_INIT_FAILED = 4568;
    public static final int ERR_FFDECODER_VIDEO_CODEC_FIND = 4569;
    public static final int ERR_FFDECODER_SW_DECODER_FIND = 4570;
    public static final int ERR_FFDECODER_VIDEO_PARAMS = 4571;
    public static final int ERR_FFDECODER_VIDEO_CODEC_OPEN = 4572;
    public static final int ERR_FFDECODER_FALLBACK_PARAMS = 4573;
    public static final int ERR_FFDECODER_FALLBACK_OPEN = 4574;
    public static final int ERR_FFDECODER_AUDIO_CODEC_FIND = 4575;
    public static final int ERR_FFDECODER_AUDIO_CTX_ALLOC = 4576;
    public static final int ERR_FFDECODER_AUDIO_PARAMS = 4577;
    public static final int ERR_FFDECODER_AUDIO_CODEC_OPEN = 4578;
    public static final int ERR_FFDECODER_SWR_ALLOC = 4579;
    public static final int ERR_FFDECODER_SWR_INIT = 4580;
    // FrameQueue 4620-4629
    public static final int ERR_FRAMEQUEUE_CAPACITY = 4620;
    public static final int ERR_FRAMEQUEUE_PUSH_FULL = 4621;
    public static final int ERR_FRAMEQUEUE_FRAME_ALLOC = 4622;
    // PacketQueue 4630-4639
    public static final int ERR_PACKETQUEUE_BYTE_LIMIT = 4630;
    // Placebo 4640-4649
    public static final int ERR_PLACEBO_STEP = 4640;
    // AnimationEncoder 4650-4659
    public static final int ERR_ANIMATION_NULL_OUT = 4650;
    public static final int ERR_ANIMATION_NO_FRAMES = 4651;
    // FFEncoder 4660-4699
    public static final int ERR_FFENCODER_TARGET = 4660;
    public static final int ERR_FFENCODER_NO_FFMPEG = 4661;
    public static final int ERR_FFENCODER_CONTAINER = 4662;
    public static final int ERR_FFENCODER_MUXER_ALLOC = 4663;
    public static final int ERR_FFENCODER_PACKET_ALLOC = 4664;
    public static final int ERR_FFENCODER_BIND_PIXELS = 4665;
    public static final int ERR_FFENCODER_NO_FRAMES = 4666;
    public static final int ERR_FFENCODER_PURE_JAVA = 4667;
    public static final int ERR_FFENCODER_VIDEO_ENC_ALLOC = 4668;
    public static final int ERR_FFENCODER_SCALER_CREATE = 4669;
    public static final int ERR_FFENCODER_BT709 = 4670;
    public static final int ERR_FFENCODER_FRAMES_ALLOC = 4671;
    public static final int ERR_FFENCODER_AUDIO_ENC_ALLOC = 4672;
    public static final int ERR_FFENCODER_FIFO_ALLOC = 4673;
    public static final int ERR_FFENCODER_CHANNELS = 4674;
    public static final int ERR_FFENCODER_STREAM_CREATE = 4675;
    public static final int ERR_FFENCODER_AUDIO_FRAME_ALLOC = 4676;
    public static final int ERR_FFENCODER_QUEUE_AUDIO = 4677;
    public static final int ERR_FFENCODER_READ_AUDIO = 4678;
    public static final int ERR_FFENCODER_ENCODER_MISSING = 4679;
    // GifEncoder 4700-4709
    public static final int ERR_GIFENCODER_SIDE = 4700;
    public static final int ERR_GIFENCODER_FORMAT = 4701;
    public static final int ERR_GIFENCODER_PLAYS = 4702;
    // PngEncoder 4710-4719
    public static final int ERR_PNGENCODER_FORMAT = 4710;
    // GifReader 4720-4749
    public static final int ERR_GIFREADER_CANVAS_SIZE = 4720;
    public static final int ERR_GIFREADER_FRAME_LIMIT = 4721;
    public static final int ERR_GIFREADER_FRAME_SIZE = 4722;
    public static final int ERR_GIFREADER_NO_COLOR_TABLE = 4723;
    public static final int ERR_GIFREADER_LZW_EOF = 4724;
    public static final int ERR_GIFREADER_LZW_MIN_CODE = 4725;
    public static final int ERR_GIFREADER_LZW_FIRST_CODE = 4726;
    public static final int ERR_GIFREADER_LZW_STACK = 4727;
    public static final int ERR_GIFREADER_SUBBLOCK_EOF = 4728;
    public static final int ERR_GIFREADER_SUBBLOCK_TRUNCATED = 4729;
    public static final int ERR_GIFREADER_SUBBLOCK_SIZE = 4730;
    // JpegReader 4750-4869
    public static final int ERR_JPEG_NESTED_SOI = 4750;
    public static final int ERR_JPEG_FRAME_TYPE = 4751;
    public static final int ERR_JPEG_DRI_LENGTH = 4752;
    public static final int ERR_JPEG_SCAN_LIMIT = 4753;
    public static final int ERR_JPEG_SCAN_NO_ENTROPY = 4754;
    public static final int ERR_JPEG_UNKNOWN_MARKER = 4755;
    public static final int ERR_JPEG_SOF_DUPLICATE = 4756;
    public static final int ERR_JPEG_SOF_TRUNCATED = 4757;
    public static final int ERR_JPEG_SOF_PRECISION_12 = 4758;
    public static final int ERR_JPEG_SOF_PRECISION = 4759;
    public static final int ERR_JPEG_SOF_DNL = 4760;
    public static final int ERR_JPEG_SOF_DIMENSIONS = 4761;
    public static final int ERR_JPEG_SOF_NO_COMPONENTS = 4762;
    public static final int ERR_JPEG_SOF_COMPONENT_COUNT = 4763;
    public static final int ERR_JPEG_SOF_LENGTH = 4764;
    public static final int ERR_JPEG_SOF_SAMPLING = 4765;
    public static final int ERR_JPEG_SOF_QUANT_TABLE = 4766;
    public static final int ERR_JPEG_FRAME_TOO_BIG = 4767;
    public static final int ERR_JPEG_DQT_TABLE = 4768;
    public static final int ERR_JPEG_DQT_PRECISION = 4769;
    public static final int ERR_JPEG_DQT_TRUNCATED = 4770;
    public static final int ERR_JPEG_DHT_TABLE = 4771;
    public static final int ERR_JPEG_DHT_COUNTS_TRUNCATED = 4772;
    public static final int ERR_JPEG_DHT_SYMBOLS_TRUNCATED = 4773;
    public static final int ERR_JPEG_SOS_BEFORE_SOF = 4774;
    public static final int ERR_JPEG_SOS_TRUNCATED = 4775;
    public static final int ERR_JPEG_SOS_COMPONENT_COUNT = 4776;
    public static final int ERR_JPEG_SOS_LENGTH = 4777;
    public static final int ERR_JPEG_SOS_SELECTOR = 4778;
    public static final int ERR_JPEG_SOS_UNKNOWN_COMPONENT = 4779;
    public static final int ERR_JPEG_SEGMENT_LENGTH = 4780;
    public static final int ERR_JPEG_SEGMENT_TRUNCATED = 4781;
    public static final int ERR_JPEG_PROGRESSIVE_AC_COMPONENTS = 4782;
    public static final int ERR_JPEG_PROGRESSIVE_AC_RANGE = 4783;
    public static final int ERR_JPEG_PROGRESSIVE_APPROXIMATION = 4784;
    public static final int ERR_JPEG_PROGRESSIVE_FIRST_PASS = 4785;
    public static final int ERR_JPEG_PROGRESSIVE_REFINEMENT = 4786;
    public static final int ERR_JPEG_DC_CATEGORY = 4787;
    public static final int ERR_JPEG_AC_HUFFMAN = 4788;
    public static final int ERR_JPEG_AC_RUN = 4789;
    public static final int ERR_JPEG_AC_REFINE_HUFFMAN = 4790;
    public static final int ERR_JPEG_AC_REFINE_SYMBOL = 4791;
    public static final int ERR_JPEG_AC_REFINE_RUN = 4792;
    public static final int ERR_JPEG_MISSING_QUANT = 4793;
    public static final int ERR_JPEG_MISSING_DC_HUFFMAN = 4794;
    public static final int ERR_JPEG_MISSING_AC_HUFFMAN = 4795;
    public static final int ERR_JPEG_FRAME_NOT_FOUND = 4796;
    public static final int ERR_JPEG_COMPONENT_QUANT = 4797;
    public static final int ERR_JPEG_MARKER_TRUNCATED = 4798;
    public static final int ERR_JPEG_MARKER_NOT_FOUND = 4799;
    public static final int ERR_JPEG_UNEXPECTED_EOF = 4800;
    public static final int ERR_JPEG_HUFFMAN_OVERSUBSCRIBED = 4801;
    public static final int ERR_JPEG_HUFFMAN_CODE = 4802;
    public static final int ERR_JPEG_ENTROPY_TRUNCATED = 4803;
    public static final int ERR_JPEG_ENTROPY_MARKER_BIT = 4804;
    public static final int ERR_JPEG_ENTROPY_MARKER_BITS = 4805;
    public static final int ERR_JPEG_RESTART_TRUNCATED = 4806;
    public static final int ERR_JPEG_RESTART_EXPECTED = 4807;
    // NetpbmReader 4870-4919
    public static final int ERR_NETPBM_VERSION = 4870;
    public static final int ERR_NETPBM_DIMENSIONS = 4871;
    public static final int ERR_NETPBM_RASTER_TRUNCATED = 4872;
    public static final int ERR_NETPBM_PLAIN_TRUNCATED = 4873;
    public static final int ERR_NETPBM_PLAIN_BIT = 4874;
    public static final int ERR_NETPBM_PLAIN_LONG = 4875;
    public static final int ERR_NETPBM_PLAIN_SAMPLE = 4876;
    public static final int ERR_NETPBM_PAM_TRUNCATED = 4877;
    public static final int ERR_NETPBM_PAM_HEADER_SIZE = 4878;
    public static final int ERR_NETPBM_HEADER_TRUNCATED = 4879;
    public static final int ERR_NETPBM_HEADER_SIZE = 4880;
    public static final int ERR_NETPBM_TUPLTYPE_DUP = 4881;
    public static final int ERR_NETPBM_UNKNOWN_VERSION = 4882;
    public static final int ERR_NETPBM_WIDTH = 4883;
    public static final int ERR_NETPBM_HEIGHT = 4884;
    public static final int ERR_NETPBM_MAXVAL_MISSING = 4885;
    public static final int ERR_NETPBM_MAXVAL = 4886;
    public static final int ERR_NETPBM_DEPTH = 4887;
    public static final int ERR_NETPBM_TUPLTYPE_UNSUPPORTED = 4888;
    public static final int ERR_NETPBM_DEPTH_MISMATCH = 4889;
    public static final int ERR_NETPBM_BW_MAXVAL = 4890;
    public static final int ERR_NETPBM_TOKEN_BLANK = 4891;
    public static final int ERR_NETPBM_TOKEN_NUMBER = 4892;
    public static final int ERR_NETPBM_TUPLTYPE_LONG = 4893;
    // PngReader 4920-4999
    public static final int ERR_PNGREADER_IHDR_MISSING = 4920;
    public static final int ERR_PNGREADER_IDAT_MISSING = 4921;
    public static final int ERR_PNGREADER_BKGD_NO_PLTE = 4922;
    public static final int ERR_PNGREADER_BKGD_INDEX_RANGE = 4923;
    public static final int ERR_PNGREADER_FRAME_BOUNDARY = 4924;
    public static final int ERR_PNGREADER_FDAT_TRUNCATED = 4925;
    public static final int ERR_PNGREADER_FRAME_NO_DATA = 4926;
    public static final int ERR_PNGREADER_APNG_FRAME_COUNT = 4927;
    public static final int ERR_PNGREADER_CHUNK_CRC = 4928;
    public static final int ERR_PNGREADER_IHDR_DIMENSIONS = 4929;
    public static final int ERR_PNGREADER_IHDR_DIM_BIG = 4930;
    public static final int ERR_PNGREADER_IHDR_CANVAS_BIG = 4931;
    public static final int ERR_PNGREADER_IHDR_COLOR_TYPE = 4932;
    public static final int ERR_PNGREADER_IHDR_DEPTH_GREY = 4933;
    public static final int ERR_PNGREADER_IHDR_DEPTH_COLOR = 4934;
    public static final int ERR_PNGREADER_IHDR_DEPTH_INDEXED = 4935;
    public static final int ERR_PNGREADER_IHDR_FORBIDDEN = 4936;
    public static final int ERR_PNGREADER_IHDR_COMPRESSION = 4937;
    public static final int ERR_PNGREADER_IHDR_FILTER = 4938;
    public static final int ERR_PNGREADER_IHDR_INTERLACE = 4939;
    public static final int ERR_PNGREADER_CHUNK_MISPLACED = 4940;
    public static final int ERR_PNGREADER_CHUNK_UNKNOWN = 4941;
    public static final int ERR_PNGREADER_HIST_NO_PLTE = 4942;
    public static final int ERR_PNGREADER_INFLATE_TOO_LARGE = 4943;
    public static final int ERR_PNGREADER_INFLATE_OVERSIZE = 4944;
    public static final int ERR_PNGREADER_INFLATE_DICT = 4945;
    public static final int ERR_PNGREADER_INFLATE_STREAM = 4946;
    public static final int ERR_PNGREADER_INFLATE_OVERSIZE_TAIL = 4947;
    public static final int ERR_PNGREADER_INFLATE_DICT_TAIL = 4948;
    public static final int ERR_PNGREADER_INFLATE_INCOMPLETE = 4949;
    public static final int ERR_PNGREADER_INFLATE_STREAM_TAIL = 4950;
    public static final int ERR_PNGREADER_INFLATE_FORMAT = 4951;
    public static final int ERR_PNGREADER_UNFILTER_EOF_FILTER = 4952;
    public static final int ERR_PNGREADER_UNFILTER_EOF_ROW = 4953;
    public static final int ERR_PNGREADER_ROW8_FORBIDDEN = 4954;
    public static final int ERR_PNGREADER_ROW_NO_PLTE = 4955;
    public static final int ERR_PNGREADER_ROW_FORBIDDEN = 4956;
    public static final int ERR_PNGREADER_FILTER_UNKNOWN = 4957;
    public static final int ERR_PNGREADER_LUT_NO_PLTE = 4958;
    // WebPReader 5000-5039
    public static final int ERR_WEBPREADER_VP8_SMALL = 5000;
    public static final int ERR_WEBPREADER_VP8_INTERFRAME = 5001;
    public static final int ERR_WEBPREADER_VP8_START_CODE = 5002;
    public static final int ERR_WEBPREADER_VP8X_SMALL = 5003;
    public static final int ERR_WEBPREADER_FIRST_CHUNK = 5004;
    public static final int ERR_WEBPREADER_DIMENSIONS = 5005;
    public static final int ERR_WEBPREADER_NO_BITSTREAM = 5006;
    public static final int ERR_WEBPREADER_FRAME_LIMIT = 5007;
    public static final int ERR_WEBPREADER_ANMF_TRUNCATED = 5008;
    public static final int ERR_WEBPREADER_ANMF_OUTSIDE = 5009;
    public static final int ERR_WEBPREADER_ANMF_NO_SUBCHUNK = 5010;
    public static final int ERR_WEBPREADER_CHUNK_HEADER = 5011;
    public static final int ERR_WEBPREADER_CHUNK_BODY = 5012;
    public static final int ERR_WEBPREADER_VP8L_SMALL = 5013;
    public static final int ERR_WEBPREADER_VP8L_SIGNATURE = 5014;
    public static final int ERR_WEBPREADER_VP8L_VERSION = 5015;
    public static final int ERR_WEBPREADER_VP8L_MISMATCH = 5016;
    // Path 5040-5049
    public static final int ERR_SVG_PATH_SEGMENTS = 5040;
    public static final int ERR_SVG_PATH_NON_FINITE = 5041;
    public static final int ERR_SVG_PATH_POINTS = 5042;
    // PathParser 5050-5059
    public static final int ERR_SVG_PATHPARSER_NON_FINITE = 5050;
    // SvgColor 5060-5069
    public static final int ERR_SVG_COLOR_HEX_DIGIT = 5060;
    // SvgDocument 5070-5079
    public static final int ERR_SVG_DOCUMENT_RENDER_DEPTH = 5070;
    public static final int ERR_SVG_DOCUMENT_INDEX_DEPTH = 5071;
    public static final int ERR_SVG_DOCUMENT_HREF_CHAIN = 5072;
    public static final int ERR_SVG_DOCUMENT_GRADIENT_STOPS = 5073;
    // SvgParser 5080-5089
    public static final int ERR_SVG_PARSER_ELEMENTS = 5080;
    public static final int ERR_SVG_PARSER_ATTRIBUTE_BUDGET = 5081;
    public static final int ERR_SVG_PARSER_MALFORMED_XML = 5082;
    public static final int ERR_SVG_PARSER_NON_FINITE = 5083;
    // SvgRasterizer 5090-5099
    public static final int ERR_SVG_RASTERIZER_EDGES = 5090;
    public static final int ERR_SVG_RASTERIZER_CROSSINGS = 5091;
    // SvgReader 5100-5109
    public static final int ERR_SVG_READER_ROOT = 5100;
    public static final int ERR_SVG_READER_SIZE = 5101;
    // AlphaDecoder 5110-5119
    public static final int ERR_WEBP_ALPHA_SMALL = 5110;
    public static final int ERR_WEBP_ALPHA_TRUNCATED = 5111;
    public static final int ERR_WEBP_ALPHA_COMPRESSION = 5112;
    // BitReader 5120-5129
    public static final int ERR_VP8L_BITS_PEEK_BURST = 5120;
    public static final int ERR_VP8L_BITS_READ_BURST = 5121;
    public static final int ERR_VP8L_BITS_READ_EOF = 5122;
    public static final int ERR_VP8L_BITS_BOOL_EOF = 5123;
    // ColorCache 5130-5139
    public static final int ERR_VP8L_CACHE_BITS = 5130;
    // HuffmanDecoder 5140-5159
    public static final int ERR_VP8L_HUFFMAN_SYMBOL_0 = 5140;
    public static final int ERR_VP8L_HUFFMAN_SYMBOL_1 = 5141;
    public static final int ERR_VP8L_HUFFMAN_MAX_SYMBOL = 5142;
    public static final int ERR_VP8L_HUFFMAN_REPEAT_CODE = 5143;
    public static final int ERR_VP8L_HUFFMAN_ZERO_SHORT = 5144;
    public static final int ERR_VP8L_HUFFMAN_ZERO_LONG = 5145;
    // HuffmanTable 5160-5169
    public static final int ERR_VP8L_TABLE_OVERSUBSCRIBED = 5160;
    public static final int ERR_VP8L_TABLE_CODE_LONG = 5161;
    public static final int ERR_VP8L_TABLE_CODE_SLOW = 5162;
    // VP8LosslessDecoder 5170-5189
    public static final int ERR_VP8L_DECODER_TRANSFORM_DUP = 5170;
    public static final int ERR_VP8L_DECODER_GROUPS = 5171;
    public static final int ERR_VP8L_DECODER_SIZE = 5172;
    public static final int ERR_VP8L_DECODER_PIXELS = 5173;
    public static final int ERR_VP8L_DECODER_BACKREF_START = 5174;
    public static final int ERR_VP8L_DECODER_BACKREF_END = 5175;
    public static final int ERR_VP8L_DECODER_NO_CACHE = 5176;
    // VP8BoolDecoder 5190-5199
    public static final int ERR_VP8_BOOL_SMALL = 5190;
    // VP8LossyDecoder 5200-5229
    public static final int ERR_VP8_DECODER_INTERFRAME = 5200;
    public static final int ERR_VP8_DECODER_FRAME_SIZE = 5201;
    public static final int ERR_VP8_DECODER_MISMATCH = 5202;
    public static final int ERR_VP8_DECODER_PARTITION_0 = 5203;
    public static final int ERR_VP8_DECODER_HEADER_EOF = 5204;
    public static final int ERR_VP8_DECODER_SIZE_TABLE = 5205;
    public static final int ERR_VP8_DECODER_TOKEN_DECLARED = 5206;
    public static final int ERR_VP8_DECODER_TOKEN_SMALL = 5207;
    public static final int ERR_VP8_DECODER_FRAME_SMALL = 5208;
    public static final int ERR_VP8_DECODER_START_CODE = 5209;
    public static final int ERR_VP8_DECODER_MACROBLOCK_EOF = 5210;

    // BOOTSTRAP AND APP 8000-8999
    // AppBootstrap 8000-8019
    public static final int ERR_LAUNCHER_NESTED_MISSING = 8000;
    public static final int ERR_LAUNCHER_PIN_MISSING = 8001;
    public static final int ERR_LAUNCHER_PROPERTIES_MISSING = 8002;
    public static final int ERR_LAUNCHER_PLATFORM_UNSUPPORTED = 8003;
    public static final int ERR_LAUNCHER_VERSION_INVALID = 8004;
    public static final int ERR_LAUNCHER_LIBRARY_MISSING = 8005;
    public static final int ERR_LAUNCHER_DOWNLOAD_CAP = 8006;
    public static final int ERR_LAUNCHER_DOWNLOAD_LIMIT = 8007;
    public static final int ERR_LAUNCHER_DOWNLOAD_INCOMPLETE = 8008;
    // DefensiveCheck 8020-8029
    public static final int ERR_DEFENSIVE_TLAUNCHER_LOG = 8020;
    public static final int ERR_DEFENSIVE_TLAUNCHER = 8021;
    // FabricBootstrap 8030-8039
    public static final int ERR_FABRIC_BINARIES_MISSING = 8030;
    public static final int ERR_FABRIC_START = 8031;
    // ForgeBootstrap 8040-8049
    public static final int ERR_FORGE_START = 8040;
    // NeoBootstrap 8050-8059
    public static final int ERR_NEOFORGE_START = 8050;
    public static final int ERR_NEOFORGE_DIST = 8051;
    // ClickSound 8060-8069
    public static final int ERR_APP_SOUND_DEVICE = 8060;
    // LogUploader 8070-8089
    public static final int ERR_APP_UPLOADER_READ = 8070;
    public static final int ERR_APP_UPLOADER_MEASURE = 8071;
    public static final int ERR_APP_UPLOADER_STAGE = 8072;
    public static final int ERR_APP_UPLOADER_UPLOAD = 8073;
    public static final int ERR_APP_UPLOADER_SYSTEM = 8074;
    public static final int ERR_APP_UPLOADER_REPORT_SIZE = 8075;
    public static final int ERR_APP_UPLOADER_ISSUE_PAGE = 8076;
    // MediaCatalog 8090-8099
    public static final int ERR_APP_CATALOG_URIS = 8090;
    // WaterMediaApp 8100-8119
    public static final int ERR_APP_MAIN_LOADER = 8100;
    public static final int ERR_APP_MAIN_GLFW_CALLBACK = 8101;
    public static final int ERR_APP_MAIN_GLFW_INIT = 8102;
    public static final int ERR_APP_MAIN_GLFW_WINDOW = 8103;
    public static final int ERR_APP_MAIN_SWAP = 8104;
    public static final int ERR_APP_MAIN_SWAP_RECOVERY = 8105;
    // Spinner 8120-8129
    public static final int ERR_APP_SPINNER_RANGE = 8120;
    // RenderEngine 8130-8139
    public static final int ERR_APP_RENDER_VERTEX_CAP = 8130;
    // RenderSystem 8140-8149
    public static final int ERR_APP_RENDERSYSTEM_VK_CLEANUP = 8140;
    // OpenGLRenderBackend 8150-8159
    public static final int ERR_APP_GL_LINK_PROGRAM = 8150;
    public static final int ERR_APP_GL_LINK_SDF = 8151;
    public static final int ERR_APP_GL_DRAW_VERTICES = 8152;
    public static final int ERR_APP_GL_SHADER_READ = 8153;
    public static final int ERR_APP_GL_SHADER_COMPILE = 8154;
    // VulkanRenderBackend 8160-8189
    public static final int ERR_APP_VK_GLFW_UNSUPPORTED = 8160;
    public static final int ERR_APP_VK_GLFW_EXTENSIONS = 8161;
    public static final int ERR_APP_VK_NO_DEVICES = 8162;
    public static final int ERR_APP_VK_NO_QUEUE_FAMILY = 8163;
    public static final int ERR_APP_VK_MEMORY_LAYER = 8164;
    public static final int ERR_APP_VK_MEMORY_VERTEX = 8165;
    public static final int ERR_APP_VK_MEMORY_STAGING = 8166;
    public static final int ERR_APP_VK_MEMORY_IMAGE = 8167;
    public static final int ERR_APP_VK_SHADER_READ = 8168;
    public static final int ERR_APP_VK_SHADERC_INIT = 8169;
    public static final int ERR_APP_VK_SHADERC_NULL = 8170;
    public static final int ERR_APP_VK_SHADER_COMPILE = 8171;
    public static final int ERR_APP_VK_SHADERC_NO_SPIRV = 8172;
    public static final int ERR_APP_VK_CALL_RESULT = 8173;
    // SettingsModel 8190-8199
    public static final int ERR_APP_SETTINGS_BOOLEAN = 8190;
    public static final int ERR_APP_SETTINGS_ENUM = 8191;
    public static final int ERR_APP_SETTINGS_CHAR_LENGTH = 8192;
    public static final int ERR_APP_SETTINGS_NUMBER_CONVERT = 8193;
    public static final int ERR_APP_SETTINGS_NUMBER_PARSE = 8194;
    // SettingsScreen 8200-8209
    public static final int ERR_APP_SETTINGSSCREEN_SAVE = 8200;

    // ENVIRONMENT: WRITTEN ONCE BY start() BEFORE THE VOLATILE started FLAG, WHICH PUBLISHES THEM;
    // EVERY READER CHECKS THE FLAG FIRST, SO ANY THREAD THAT SEES IT SET SEES THEM TOO
    private static String name;
    private static Path tmp;
    private static Path cwd;
    private static boolean clientSide;
    private static int steps;
    private static volatile boolean started;

    // LOAD PROGRESS: THE APP SPLASH POLLS IT FROM ITS RENDER THREAD WHILE start() HOLDS THE CLASS LOCK, SO ONLY VOLATILE READS FIT
    private static volatile String loading;
    private static volatile int done;

    private WaterMedia() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /**
     * Loads every module, once for the life of the process; nothing is ever unloaded. Each module decides by
     * itself whether this side needs it and handles the parts it can live without (the file server, FFmpeg);
     * a failure that escapes a module ends the load.
     *
     * @throws IllegalStateException when WaterMedia already started, when called from an owned worker thread,
     *         or when a module fails, with that failure as the cause
     */
    public static synchronized void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        if (ThreadTool.workerThread()) throw new IllegalStateException(DataTool.errCode(ERR_CORE_START_WORKER) + "Failed to start WaterMedia from a managed worker, use a host thread");
        if (name == null || name.isBlank()) throw new IllegalArgumentException(DataTool.errCode(ERR_CORE_START_NAME) + "Failed to start WaterMedia due to empty environment name");
        if (started) throw new IllegalStateException(DataTool.errCode(ERR_CORE_START_RUNNING) + "Failed to start WaterMedia, it has already started");

        // THE MODULES IN LOAD ORDER: CONFIGURATION, NATIVES, THEN THE APIS THAT USE THEM; EACH ONE REFUSES A SECOND
        // CONSTRUCTION AND DECIDES BY ITSELF WHETHER THIS SIDE RUNS IT
        final List<Module> modules = new ArrayList<>(5);
        modules.add(new WaterMediaConfig());
        // BINARIES IS OPTIONAL: ITS INSTALL IS CHECKED WITH THE CLASS LOADER'S getResource(), WHICH SEES OTHER JARS WITHOUT LINKING THE CLASS
        if (WaterMedia.class.getClassLoader().getResource("org/watermedia/binaries/WaterMediaBinaries.class") != null) modules.add(new WaterMediaBinaries());
        modules.add(new NetworkAPI());
        modules.add(new CodecsAPI());
        modules.add(new PlayerAPI());

        // PUBLISHED BEFORE THE FIRST MODULE STARTS, SO THE STATIC ACCESSORS ALREADY WORK FOR THE MODULES
        WaterMedia.name = name;
        WaterMedia.tmp = (tmp == null ? DEFAULT_TMP : tmp).toAbsolutePath().normalize();
        WaterMedia.cwd = (cwd == null ? DEFAULT_CWD : cwd).toAbsolutePath().normalize();
        WaterMedia.clientSide = clientSide;
        steps = modules.size();
        started = true;
        LOGGER.info(IT, "Starting {} {} for {} ({})", NAME, VERSION, name, clientSide ? "client" : "server");
        LOGGER.info(IT, "Working directory: {}", WaterMedia.cwd);
        LOGGER.info(IT, "Temporary directory: {}", WaterMedia.tmp);

        try {
            for (final Module module: modules) {
                loading = module.getClass().getSimpleName();
                LOGGER.info(IT, "Loading {} ({}/{})", loading, done + 1, steps);
                module.start(WaterMedia.name, WaterMedia.tmp, WaterMedia.cwd, WaterMedia.clientSide);
                done++; // SINGLE WRITER: ONLY start() TOUCHES IT, UNDER THE CLASS LOCK
            }
        } catch (final Exception | LinkageError failure) {
            final String module = loading;
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.error(IT, DataTool.errCode(ERR_CORE_LOAD_MODULE_LOG) + "Failed to load {} due to module {}", NAME, module, failure);
            throw new IllegalStateException(DataTool.errCode(ERR_CORE_LOAD_MODULE) + "Failed to load WaterMedia module " + module, failure);
        } finally {
            loading = null;
        }
        LOGGER.info(IT, "{} loaded {} module(s)", NAME, done);
    }

    /** True once every module loaded; a part a module lives without (FFmpeg, the file server) does not change it. */
    public static boolean loaded() { return started && done == steps; }
    /** Returns the name of the module being loaded, or null outside a load; loading screens pair it with {@link #step()} and {@link #steps()}. */
    public static String loading() { return loading; }
    /** Returns how many modules finished loading so far. */
    public static int step() { return done; }
    /** Returns how many modules this process loads; zero before {@link #start}. */
    public static int steps() { return started ? steps : 0; }

    /** Prefixes a resource path with this library's identifier, as {@code watermedia:path}. */
    @Deprecated
    public static String toId(final String path) { return ID + ":" + path; }

    /** Creates a project logger that redacts secrets from messages, parameters and traces before appenders see them. */
    public static Logger logger(final String name) {
        final ExtendedLogger logger = LogManager.getContext(false).getLogger(name);
        return new ExtendedLoggerWrapper(logger, name, logger.getMessageFactory()) {
            @Override
            public void logMessage(final String caller, final Level level, final Marker marker, final Message message, final Throwable failure) {
                // 1. THE FORMATTED TEXT, REDACTED AS A WHOLE
                final String originalText = message == null ? "null" : message.getFormattedMessage();
                String text = DataTool.redact(originalText);
                boolean changed = message == null || !Objects.equals(text, originalText);

                // 2. THE PARAMETERS: AN APPENDER MAY FORMAT THEM AGAIN, SO A SECRET IN ONE OF THEM ALSO REPLACES THE MESSAGE
                final Object[] parameters = message == null ? null : message.getParameters();
                if (parameters != null) {
                    for (final Object parameter: parameters) {
                        final String value = ParameterizedMessage.deepToString(parameter);
                        changed |= !Objects.equals(value, DataTool.redact(value));
                    }
                }

                // 3. THE THROWABLES: THE EXPLICIT ONE AND THE ONE EMBEDDED IN THE MESSAGE, EACH PRINTED AND CHECKED ONCE
                final Throwable embedded = message == null ? null : message.getThrowable();
                Throwable cause = failure != null ? failure : embedded;
                for (final Throwable thrown: new Throwable[] { cause, embedded == cause ? null : embedded }) {
                    if (thrown == null) continue;
                    final StringWriter trace = new StringWriter();
                    thrown.printStackTrace(new PrintWriter(trace));
                    final String safe = DataTool.redact(trace.toString());
                    if (safe.contentEquals(trace.getBuffer())) continue;
                    // AN UNSAFE TRACE BECOMES TEXT: TYPES, FRAMES AND CAUSES STAY READABLE, THE THROWABLE NEVER REACHES APPENDERS
                    text += System.lineSeparator() + safe;
                    if (thrown == cause) cause = null;
                    changed = true;
                }
                super.logMessage(caller, level, marker, changed ? new SimpleMessage(text) : message, cause);
            }
        };
    }

    /** Returns the environment name given to {@link #start}; throws before it. */
    public static String name() { requireStarted(); return name; }
    /** Returns the process's absolute working directory; throws before {@link #start}. */
    public static Path cwd() { requireStarted(); return cwd; }
    /** Returns the process's absolute temporary directory; throws before {@link #start}. */
    public static Path tmp() { requireStarted(); return tmp; }
    /** Whether WaterMedia runs on a client; throws before {@link #start}. */
    public static boolean clientSide() { requireStarted(); return clientSide; }

    /** Rejects a client-only operation of {@code type} before {@link #start} or on a server; engines, players and client-only tools call it from their constructors. */
    public static void checkIsClientSideOrThrow(final Class<?> type) {
        if (!clientSide()) throw new IllegalStateException(DataTool.errCode(ERR_CORE_CLIENT_ONLY) + "Failed to create " + type.getSimpleName() + ", it is client-only and this is a server environment");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // THE ONE FAILURE EVERY ACCESSOR GIVES BEFORE start(); ITS VOLATILE READ MUST COME BEFORE THE ENVIRONMENT READ
    private static void requireStarted() {
        if (!started) throw new IllegalStateException(DataTool.errCode(ERR_CORE_NOT_STARTED) + "Failed to read the environment, " + NAME + " has not started");
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /**
     * One unit of WaterMedia's load: an API or a companion module that {@link WaterMedia#start} creates and starts once
     * per process. Each subclass knows by itself whether it is loaded: its constructor flips its own flag on the first
     * call and throws on the second. It decides whether this side needs it and handles the parts it can live without;
     * a failure that escapes {@link #start} ends the load.
     */
    public abstract static class Module {
        /** Starts the module, once, on the thread that called {@link WaterMedia#start}, with the environment it received. */
        protected abstract void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) throws Exception;
    }
}
