package org.watermedia.api.player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.Platform.Quality;
import org.watermedia.api.platform.Platform.Source;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the site lists of {@code MediaPlayer.checkSite}: switches, whitelist, trusted local links, water://global, blacklist, entry matching, disguised hosts, file shares, jar links, the MRL refusal before any request, Windows share strings and the player revalidation. */
@DisplayName("Site lists")
public class SiteListsTest {

    private static final long TIMEOUT_MS = 5_000L;
    private static final long PLAY_TIMEOUT_MS = 10_000L;

    // THE LISTS ARE PROCESS-WIDE CONFIG SHARED WITH EVERY OTHER TEST CLASS: EACH CASE PUTS THEM BACK AS IT FOUND THEM
    private boolean whitelistEnable;
    private String[] whitelistSites;
    private boolean whitelistTrust;
    private boolean blacklistEnable;
    private String[] blacklistSites;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void clientBootstrap() { MediaBootstrap.client(); }

    @BeforeEach
    void snapshot() {
        final WaterMediaConfig.Player.Whitelist white = WaterMediaConfig.player.whitelist;
        final WaterMediaConfig.Player.Blacklist black = WaterMediaConfig.player.blacklist;
        this.whitelistEnable = white.enable;
        this.whitelistSites = white.sites.clone();
        this.whitelistTrust = white.trustLocal;
        this.blacklistEnable = black.enable;
        this.blacklistSites = black.sites.clone();
        // EVERY CASE STARTS WITH BOTH LISTS OFF AND TURNS ON ONLY WHAT IT JUDGES
        white.enable = false;
        black.enable = false;
    }

    @AfterEach
    void restore() {
        final WaterMediaConfig.Player.Whitelist white = WaterMediaConfig.player.whitelist;
        final WaterMediaConfig.Player.Blacklist black = WaterMediaConfig.player.blacklist;
        white.enable = this.whitelistEnable;
        white.sites = this.whitelistSites;
        white.trustLocal = this.whitelistTrust;
        black.enable = this.blacklistEnable;
        black.sites = this.blacklistSites;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static void whitelist(final boolean trustLocal, final String... sites) {
        WaterMediaConfig.player.whitelist.enable = true;
        WaterMediaConfig.player.whitelist.trustLocal = trustLocal;
        WaterMediaConfig.player.whitelist.sites = sites;
    }

    private static void blacklist(final String... sites) {
        WaterMediaConfig.player.blacklist.enable = true;
        WaterMediaConfig.player.blacklist.sites = sites;
    }

    // EVERY LINK IS JUDGED, SO ONE RUN REPORTS ALL THE LINKS THAT BREAK THE EXPECTATION
    private static void plays(final String... links) {
        assertAll(Arrays.stream(links).<Executable>map(link -> () -> assertDoesNotThrow(() -> MediaPlayer.checkSite(URI.create(link)), link)));
    }

    private static void notAllowed(final String... links) {
        assertAll(Arrays.stream(links).<Executable>map(link -> () -> assertThrowsExactly(WhitelistException.class, () -> MediaPlayer.checkSite(URI.create(link)), link)));
    }

    private static void forbidden(final String... links) {
        assertAll(Arrays.stream(links).<Executable>map(link -> () -> assertThrowsExactly(BlacklistException.class, () -> MediaPlayer.checkSite(URI.create(link)), link)));
    }

    // A LOOPBACK ORIGIN SERVING THE STATIC PICTURE ON EVERY PATH AND COUNTING THE REQUESTS THAT REACH IT
    private static LocalHttp picture(final AtomicInteger requests) {
        final byte[] body = Fixtures.readAll(Fixtures.PNG_STATIC);
        return LocalHttp.start("/", exchange -> {
            requests.incrementAndGet();
            LocalHttp.respond(exchange, "image/png", body, 0L);
        });
    }

    // A HANDLER THAT COUNTS THE TIMES IT IS ASKED FOR claimed; IT RESOLVES THE ENTRIES, OR PASSES WHEN THERE ARE NONE
    private static Platform platform(final URI claimed, final AtomicInteger asked, final Source... entries) {
        return new Platform() {
            @Override
            public String name() {
                return "Site Lists Test";
            }

            @Override
            public Resolution resolve(final URI uri) {
                if (!claimed.equals(uri)) return null;
                asked.incrementAndGet();
                return entries.length == 0 ? null : new Resolution(null, List.of(entries));
            }
        };
    }

    private static Source image(final URI target) {
        return new Source(MediaType.IMAGE, null, null, HttpHeaders.defaults(target), List.of(new Quality(target, 0, 0)), null, null);
    }

    // THE MRL CACHE LIVES AS LONG AS THE JVM, SO EVERY CASE COPIES THE PICTURE UNDER ITS OWN NAME (file:// AND water://local)
    private static Path copy(final String name) throws IOException {
        final Path file = WaterMedia.cwd().resolve("build/site-lists/" + name);
        Files.createDirectories(file.getParent());
        Files.copy(Fixtures.PNG_STATIC, file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    private static MRL settled(final URI link) {
        final MRL mrl = PlayerAPI.mrl(link);
        assertTrue(mrl.await(TIMEOUT_MS), () -> link + " is still fetching");
        return mrl;
    }

    private static MRL loaded(final URI link) {
        final MRL mrl = settled(link);
        assertSame(MRL.Status.LOADED, mrl.status(), () -> link + " did not load: " + mrl.exception());
        return mrl;
    }

    // A PLAYER BUILT DESPITE THE LISTS IS RELEASED BEFORE THE ASSERTION FAILS
    private static void noPlayer(final MRL mrl) {
        final MediaPlayer player = PlayerAPI.player(mrl, PlayerAPI::headlessEngine, () -> null);
        if (player != null) player.release();
        assertNull(player, () -> "a player was built for " + mrl.uri());
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Switches")
    class Switches {

        @Test
        @DisplayName("Both lists ship off: the whitelist with its documented sites and trustLocal, the blacklist empty")
        void shippedDefaults() {
            final WaterMediaConfig.Player.Whitelist white = new WaterMediaConfig.Player.Whitelist();
            final WaterMediaConfig.Player.Blacklist black = new WaterMediaConfig.Player.Blacklist();
            assertFalse(white.enable, "the whitelist ships off");
            assertArrayEquals(new String[] { "youtube.com", "youtu.be", "twitch.tv", "imgur.com", "kick.com" }, white.sites);
            assertTrue(white.trustLocal, "trustLocal ships on");
            assertFalse(black.enable, "the blacklist ships off");
            assertEquals(0, black.sites.length, "the blacklist ships without sites");
        }

        @Test
        @DisplayName("Disabled lists judge nothing, whatever their entries")
        void disabledListsJudgeNothing() {
            WaterMediaConfig.player.whitelist.sites = new String[] { "youtube.com" };
            WaterMediaConfig.player.whitelist.trustLocal = false;
            WaterMediaConfig.player.blacklist.sites = new String[] { "vimeo.com", "localhost" };
            plays("https://vimeo.com/76979871", "https://example.com/", "http://localhost/a.png", "http://2130706433/",
                    "data:image/png;base64,AAAA", "file://server/share/a.mp4", "water://remote/abc");
        }

        @Test
        @DisplayName("An enabled whitelist without sites and without trustLocal is ignored")
        void emptyWhitelistWithoutTrustIsIgnored() {
            whitelist(false);
            plays("https://vimeo.com/76979871", "http://8.8.8.8/", "data:image/png;base64,AAAA", "water://local/a.png", "http://2130706433/");
        }

        @Test
        @DisplayName("An enabled whitelist without sites but with trustLocal plays only local links")
        void emptyWhitelistWithTrustPlaysOnlyLocal() {
            whitelist(true);
            plays("water://local/a.png", "http://localhost/a.png", "http://192.168.0.2/a.png", "file:///C:/a.mp4");
            notAllowed("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://vimeo.com/76979871");
        }

        @Test
        @DisplayName("An enabled blacklist without sites is off, disguised addresses included")
        void emptyBlacklistIsOff() {
            blacklist();
            plays("https://pornhub.com/", "http://2130706433/", "http://0x7f.0.0.1/");
            // ONLY THE WHITELIST JUDGES: THE DISGUISED LOOPBACK IS NOT ALLOWED INSTEAD OF FORBIDDEN
            whitelist(true);
            notAllowed("http://127.1/");
        }

        @Test
        @DisplayName("The default whitelist plays its sites, their subdomains and local links once enabled, and refuses the rest")
        void defaultWhitelistPlaysItsSites() {
            final WaterMediaConfig.Player.Whitelist defaults = new WaterMediaConfig.Player.Whitelist();
            whitelist(defaults.trustLocal, defaults.sites);
            plays("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://m.youtube.com/watch?v=x", "https://music.youtube.com/watch?v=x",
                    "https://youtu.be/dQw4w9WgXcQ", "https://www.twitch.tv/x", "https://clips.twitch.tv/x", "https://i.imgur.com/a.png",
                    "https://kick.com/x", "http://localhost/a.png");
            notAllowed("https://vimeo.com/76979871", "https://www.youtube-nocookie.com/embed/x", "https://googlevideo.com/x",
                    "https://www.dailymotion.com/video/x");
        }
    }

    @Nested
    @DisplayName("Whitelist")
    class Allowing {

        @Test
        @DisplayName("Only the listed sites and their subdomains play")
        void listedSitesPlay() {
            whitelist(false, "youtube.com", "kick.com");
            plays("https://youtube.com/", "https://www.youtube.com/watch?v=x", "https://music.youtube.com/x", "https://kick.com/x");
            notAllowed("https://vimeo.com/76979871", "https://twitch.tv/x", "http://localhost/a.png");
        }

        @Test
        @DisplayName("A whitelist refusal carries its code and the link")
        void refusalCarriesCodeAndLink() {
            whitelist(false, "youtube.com");
            final WhitelistException refusal = assertThrowsExactly(WhitelistException.class,
                    () -> MediaPlayer.checkSite(URI.create("https://vimeo.com/76979871")));
            assertEquals("(wm_err:1150) Failed to resolve https://vimeo.com/76979871, the site is not allowed", refusal.getMessage());
        }

        @Test
        @DisplayName("A listed name in the user info, fragment, query, path or as a label of another site does not pass")
        void spoofedLinksAreRefused() {
            whitelist(false, "youtube.com");
            notAllowed("http://youtube.com@evil.example/", "http://youtube.com:443@evil.example/", "http://evil.example#@youtube.com/",
                    "http://evil.example/?u=youtube.com", "http://evil.example/youtube.com", "http://youtube.com.evil.example/",
                    "http://evilyoutube.com/");
        }

        @Test
        @DisplayName("A link without a host that is not local is refused")
        void hostlessLinksAreRefused() {
            whitelist(true, "youtube.com");
            notAllowed("data:image/png;base64,AAAA", "http:///a.mp4", "urn:example:media");
        }
    }

    @Nested
    @DisplayName("Trusted local links")
    class TrustLocal {

        @Test
        @DisplayName("trustLocal plays stores, local files, local names and private addresses")
        void localShapesPlay() {
            whitelist(true, "youtube.com");
            plays("water://local/a.png", "WATER://LOCAL/a.png", "water://remote/abc", "file:///C:/videos/a.mp4", "file:/C:/a.mp4",
                    Fixtures.fileUri(Fixtures.PNG_STATIC).toString(), "http://localhost:8080/a.mp4", "http://LOCALHOST./",
                    "http://media.localhost/a", "http://printer.local/a", "http://127.0.0.1/", "http://127.255.255.254/",
                    "http://10.0.0.1/", "http://172.16.0.1/", "http://172.31.255.255/", "http://192.168.1.10/", "http://169.254.10.20/",
                    "http://0.0.0.0/", "http://[::1]/", "http://[::]/", "http://[fe80::1]/", "http://[febf::1]/", "http://[fe80::1%eth0]/",
                    "http://[fc00::1]/", "http://[fd12:3456::1]/", "http://[::ffff:127.0.0.1]/", "http://[::ffff:192.168.0.1]/");
        }

        @Test
        @DisplayName("Public neighbours of the local ranges, lookalike names, disguised loopbacks and disguised local names are not trusted")
        void lookalikesAreNotTrusted() {
            whitelist(true, "youtube.com");
            notAllowed("http://172.15.255.255/", "http://172.32.0.1/", "http://11.0.0.1/", "http://192.169.0.1/", "http://169.255.0.1/",
                    "http://8.8.8.8/", "http://[2001:4860:4860::8888]/", "http://[fbff::1]/", "http://[fe00::1]/", "http://[::ffff:8.8.8.8]/",
                    "http://localhost.evil.example/", "http://evillocalhost/", "http://evil.example/localhost", "http://local/",
                    "http://evil.local.example/", "http://127.0.0.1.nip.io/", "http://127.1/", "http://2130706433/", "http://0x7f000001/");
            // THESE HOSTS END IN .local OR .localhost, SO ONLY THEIR DISGUISE (AN ESCAPE, A SECOND @) KEEPS THEM FROM BEING LOCAL
            notAllowed("http://evil.com%2f.local/", "http://a@b@printer.local/", "http://a@b@media.localhost/");
        }

        @Test
        @DisplayName("Without trustLocal a local link needs its own entry")
        void localNeedsAnEntryWithoutTrust() {
            whitelist(false, "youtube.com");
            notAllowed("http://localhost/a.png", "http://127.0.0.1/", "http://192.168.1.10/", "http://[::1]/", "http://printer.local/",
                    "file:///C:/a.mp4", "water://local/a.png", "water://remote/abc");
            whitelist(false, "localhost", "127.0.0.1", "[::1]", "printer.local", "192.168.1.10");
            // A NAME ENTRY COVERS ITS SUBDOMAINS HERE TOO (media.localhost), NEVER ITS SIBLINGS (scanner.local)
            plays("http://localhost/a.png", "http://media.localhost/", "http://127.0.0.1/", "http://[::1]/", "http://printer.local/", "http://192.168.1.10/");
            notAllowed("http://10.0.0.1/", "http://scanner.local/", "http://127.0.0.2/");
        }

        @Test
        @DisplayName("Stores and files without a host can never be listed")
        void storesAndFilesCannotBeListed() {
            whitelist(false, "local", "remote", "water", "file", "water://local", "water://remote/abc", "file:///C:/a.mp4");
            notAllowed("water://local/a.png", "water://remote/abc", "file:///C:/a.mp4", "file:/C:/a.mp4");
        }
    }

    @Nested
    @DisplayName("water://global")
    class GlobalStore {

        @Test
        @DisplayName("water://global plays under any whitelist and any blacklist")
        void globalAlwaysPlays() {
            whitelist(false, "youtube.com");
            blacklist("global", "water", "watermedia.srrapero720.me", "srrapero720.me");
            plays("water://global/a.png", "WATER://GLOBAL/a.png", "water://Global/x?y=1", "water://global");
            whitelist(true);
            plays("water://global/a.png");
        }
    }

    @Nested
    @DisplayName("Blacklist")
    class Forbidding {

        @Test
        @DisplayName("A blacklist refusal carries its code and the link")
        void refusalCarriesCodeAndLink() {
            blacklist("pornhub.com");
            final BlacklistException refusal = assertThrowsExactly(BlacklistException.class,
                    () -> MediaPlayer.checkSite(URI.create("https://www.pornhub.com/view_video.php?viewkey=1")));
            assertEquals("(wm_err:1149) Failed to resolve https://www.pornhub.com/view_video.php?viewkey=1, the site is forbidden", refusal.getMessage());
        }

        @Test
        @DisplayName("The blacklist wins over the whitelist")
        void blacklistWinsOverWhitelist() {
            whitelist(true, "pornhub.com", "youtube.com");
            blacklist("pornhub.com");
            forbidden("https://pornhub.com/", "https://www.pornhub.com/");
            plays("https://youtube.com/");
            notAllowed("https://vimeo.com/76979871");
        }

        @Test
        @DisplayName("The blacklist wins over trustLocal")
        void blacklistWinsOverTrustLocal() {
            whitelist(true, "youtube.com");
            blacklist("192.168.0.5", "localhost", "[::1]", "printer.local");
            forbidden("http://192.168.0.5/", "http://localhost:8080/", "http://[::1]/", "http://[0:0:0:0:0:0:0:1]/", "http://printer.local/");
            plays("http://192.168.0.6/", "http://127.0.0.1/", "http://scanner.local/");
        }

        @Test
        @DisplayName("Stores, files without a host and data links can never be forbidden")
        void hostlessLinksCannotBeForbidden() {
            blacklist("local", "remote", "localhost", "file", "water");
            plays("water://local/a.png", "water://remote/abc", "file:///C:/a.mp4", "data:image/png;base64,AAAA");
        }
    }

    @Nested
    @DisplayName("Matching")
    class Matching {

        @Test
        @DisplayName("A site covers itself and its subdomains on a dot boundary")
        void siteCoversSubdomains() {
            blacklist("website.com");
            forbidden("https://website.com/", "https://www.website.com/", "https://a.b.website.com/");
            plays("https://notwebsite.com/", "https://wwwwebsite.com/", "https://website.com.evil.net/", "https://website.co/", "https://ebsite.com/");
        }

        @Test
        @DisplayName("A subdomain covers only itself and deeper names")
        void subdomainCoversOnlyItself() {
            blacklist("some.website.com");
            forbidden("https://some.website.com/", "https://deep.some.website.com/");
            plays("https://website.com/", "https://www.website.com/", "https://other.website.com/", "https://awesome.website.com/");
        }

        @Test
        @DisplayName("Case, trailing dots, ports, user info, percent encoding and Unicode forms do not evade")
        void spellingsDoNotEvade() {
            blacklist("pornhub.com");
            forbidden("http://PornHub.COM./", "HTTPS://WWW.PORNHUB.COM:443/", "http://x@pornhub.com:8080/", "http://user:pw@cdn.pornhub.com:8443/x",
                    "http://pornhub.com:/", "http://pornhub.com../", "http://pornhub.com:abc/");
            // A SECOND @ OR A PERCENT ESCAPE STAYS IN THE HOST AND DISGUISES IT, WHICH THE BLACKLIST FORBIDS AS WELL
            forbidden("http://a@b@pornhub.com/", "http://%70ornhub.com/", "http://%50ORNHUB.com/", "http://pornhub%2ecom/", "http://pornhub.com%2e/", "http://pornhub.com%20/",
                    "http://%EF%BD%90ornhub.com/");
            // FULLWIDTH LETTERS, FULLWIDTH AND IDEOGRAPHIC DOTS, A SOFT HYPHEN AND A ZERO WIDTH SPACE ALL FOLD INTO THE SAME NAME
            forbidden("http://\uFF50ornhub.com/", "http://pornhub\u3002com/", "http://pornhub\uFF0Ecom/", "http://pornhub\uFF61com/",
                    "http://\uFF30\uFF2F\uFF32\uFF2E\uFF28\uFF35\uFF22.\uFF23\uFF2F\uFF2D/", "http://porn\u00ADhub.com/", "http://porn\u200Bhub.com/");
        }

        @Test
        @DisplayName("Unicode and punycode spellings of an entry match each other")
        void unicodeAndPunycodeMatch() {
            blacklist("b\u00FCcher.example");
            forbidden("http://xn--bcher-kva.example/", "http://b\u00FCcher.example/", "http://www.B\u00DCCHER.example/");
            blacklist("xn--bcher-kva.example");
            forbidden("http://b\u00FCcher.example/");
            plays("http://bucher.example/");
        }

        @Test
        @DisplayName("Entries pasted as links, with a port, user info, spaces or a trailing dot still name their site")
        void entrySpellingsNameTheirSite() {
            whitelist(false, "https://Imgur.com/gallery", "kick.com:443", "user@youtu.be", "  TWITCH.tv.  ", "http://[::1]:8080/");
            plays("https://i.imgur.com/a.png", "https://kick.com/x", "https://youtu.be/x", "https://www.twitch.tv/x", "http://[::1]/");
            notAllowed("https://vimeo.com/76979871");
            blacklist("PornHub.COM.");
            forbidden("https://pornhub.com/");

            // A LEADING *. OR DOT IS DROPPED: THE ENTRY NAMES THE APEX AND EVERY SUBDOMAIN, NEVER A LOOKALIKE
            WaterMediaConfig.player.blacklist.enable = false;
            whitelist(false, "*.twitch.tv", ".kick.com", "*.*.imgur.com", " *.YOUTU.be ");
            plays("https://twitch.tv/", "https://www.twitch.tv/x", "https://kick.com/x", "https://www.kick.com/x", "https://imgur.com/",
                    "https://i.imgur.com/a.png", "https://youtu.be/x");
            notAllowed("https://nottwitch.tv/", "https://twitch.tv.evil.example/", "https://vimeo.com/76979871");
            blacklist("*.pornhub.com");
            forbidden("https://pornhub.com/", "https://www.pornhub.com/");
        }

        @Test
        @DisplayName("Junk entries match nothing and never throw")
        void junkEntriesMatchNothing() {
            final String[] junk = { "", "   ", "*", "*.", ".", "..", "https://", "x://", "a b.com", "[", "%", "@", ":443" };
            whitelist(false, junk);
            notAllowed("https://www.twitch.tv/x", "https://twitch.tv/", "https://vimeo.com/76979871");
            WaterMediaConfig.player.whitelist.enable = false;
            blacklist(junk);
            plays("https://www.twitch.tv/x", "https://twitch.tv/", "https://vimeo.com/76979871");
        }

        @Test
        @DisplayName("IP entries match exactly")
        void ipEntriesMatchExactly() {
            blacklist("192.168.0.5");
            forbidden("http://192.168.0.5/", "http://192.168.0.5:8080/x", "http://192.168.0.5./", "http://[::ffff:192.168.0.5]/");
            plays("http://192.168.0.50/", "http://192.168.0.4/", "http://92.168.0.5/");
            blacklist("168.0.5");
            plays("http://192.168.0.5/");
            WaterMediaConfig.player.blacklist.enable = false;
            whitelist(false, "168.0.5");
            notAllowed("http://192.168.0.5/");
        }

        @Test
        @DisplayName("Bracketed IPv6 entries match any spelling of the address, mapped IPv4 and zones included")
        void ipv6SpellingsMatch() {
            blacklist("[2001:db8::1]");
            forbidden("http://[2001:db8::1]/", "http://[2001:DB8:0:0:0:0:0:1]/", "http://[2001:0db8:0000:0000:0000:0000:0000:0001]:8080/");
            plays("http://[2001:db8::2]/");
            blacklist("[2001:DB8:0:0::1]");
            forbidden("http://[2001:db8::1]/");
            blacklist("127.0.0.1");
            forbidden("http://127.0.0.1/", "http://[::ffff:127.0.0.1]/", "http://[::ffff:7f00:1]/");
            blacklist("[fe80::1]");
            forbidden("http://[fe80::1%eth0]/", "http://[FE80:0:0:0:0:0:0:1]/");
        }

        @Test
        @DisplayName("IPv6 entries without brackets match too")
        void ipv6EntriesWithoutBracketsMatch() {
            blacklist("2001:db8::1", "::1", "fe80:0:0:0:0:0:0:1");
            forbidden("http://[2001:db8::1]/", "http://[::1]/", "http://[fe80::1]/");
        }
    }

    @Nested
    @DisplayName("Disguised addresses")
    class Disguised {

        @Test
        @DisplayName("With the blacklist on an IPv4 written as one number, in hex, octal, shortened or with leading zeros is forbidden")
        void disguisedAddressesAreForbidden() {
            blacklist("unrelated.example");
            forbidden("http://2130706433/", "http://3232235521/", "http://0x7f.0.0.1/", "http://0x7F.0x0.0x0.0x1/", "http://0x7f000001/",
                    "http://127.1/", "http://127.0.1/", "http://010.0.0.1/", "http://0177.0.0.1/", "http://2130706433:8080/x");
        }

        @Test
        @DisplayName("Plain addresses that are not listed play")
        void plainAddressesPlay() {
            blacklist("unrelated.example");
            plays("http://8.8.8.8/", "http://93.184.216.34:8080/", "http://127.0.0.1/", "http://0.0.0.0/", "http://255.255.255.255/",
                    "http://[::1]/", "http://[2001:db8::1]/");
        }

        @Test
        @DisplayName("The whitelist never allows a disguised address, even one written as an entry or ending in one")
        void disguisedIsNeverAllowed() {
            whitelist(true, "2130706433", "127.1", "192.168.0.5");
            notAllowed("http://2130706433/", "http://127.1/", "http://0x7f.0.0.1/", "http://x.192.168.0.5/", "http://evil.192.168.0.5:8080/");
            plays("http://192.168.0.5/", "http://127.0.0.1/");
        }

        @Test
        @DisplayName("A host that keeps an @, a percent escape or the Windows IPv6 alias is forbidden by any active blacklist")
        void markedHostsAreForbidden() {
            blacklist("unrelated.example");
            forbidden("http://a@b@youtube.com/", "http://evil.com%40youtube.com/", "http://%70ornhub.com/", "http://%79outube.com/",
                    "http://you%74ube.com/", "http://youtube.com%3F/", "http://--1.ipv6-literal.net/", "http://fe80--1s4.ipv6-literal.net/",
                    "http://2001-db8--1.IPV6-LITERAL.NET/");
            // FULLWIDTH @ FOLDS INTO A PLAIN ONE, SO THE ASCII FORM IS DISGUISED TOO
            forbidden("http://evil.com＠youtube.com/");
            plays("http://ipv6-literal.net/", "https://youtube.com/");
        }

        @Test
        @DisplayName("The whitelist never allows a marked host, not even its own entry or a local alias")
        void markedHostsAreNeverAllowed() {
            whitelist(false, "youtube.com");
            notAllowed("http://a@b@youtube.com/", "http://evil.com%40youtube.com/", "http://%79outube.com/", "http://www.%79outube.com/",
                    "http://evil.com＠youtube.com/");
            whitelist(true, "%79outube.com", "b@youtube.com", "evil.com%40youtube.com", "--1.ipv6-literal.net", "ipv6-literal.net");
            notAllowed("http://%79outube.com/", "http://a@b@youtube.com/", "http://evil.com%40youtube.com/", "http://--1.ipv6-literal.net/",
                    "http://127.0.0.1%2e/");
            plays("http://ipv6-literal.net/");
        }

        @Test
        @DisplayName("The user info is cut at the first @, so a name java.net.URI rejects is still judged by its host")
        void userInfoStillNamesTheHost() {
            blacklist("my_host", "pornhub.com");
            forbidden("http://user@my_host:80/", "http://user:pw@cdn.my_host/x", "http://user@ｐornhub.com/");
            plays("http://my_host@other_host/");
            WaterMediaConfig.player.blacklist.enable = false;
            whitelist(false, "my_host");
            plays("http://user@my_host:80/", "http://user:pw@www.my_host:8080/x");
            notAllowed("http://my_host@other_host/");
        }
    }

    @Nested
    @DisplayName("File links")
    class FileLinks {

        @Test
        @DisplayName("A file link with a host is a network share judged by that host")
        void networkShareIsJudgedByHost() {
            whitelist(true, "youtube.com");
            notAllowed("file://server/share/a.mp4", "file://fileserver.corp.example/share/a.mp4");
            plays("file://192.168.1.5/share/a.mp4", "file://nas.local/share/a.mp4", "file://localhost/C:/a.mp4");
            whitelist(false, "server");
            plays("file://server/share/a.mp4", "file://SERVER/share/b.mp4");
            whitelist(true, "server");
            blacklist("server");
            forbidden("file://server/share/a.mp4");
        }

        @Test
        @DisplayName("A file path that starts with two separators is a Windows share judged by its server")
        void sharePathIsJudgedByServer() {
            final String[] shares = { "file:////server/share/a.mp4", "file://///server/share/a.mp4", "file://localhost//server/share/a.mp4",
                    "file:///%5C%5Cserver%5Cshare%5Ca.mp4", "file:%5C%5Cserver%5Cshare%5Ca.mp4" };
            whitelist(true, "youtube.com");
            notAllowed(shares);
            plays("file:///C:/media/a.mp4", "file:/C:/media/a.mp4", "file:C:/media/a.mp4", "file:///home/user/a.mp4", "file:////192.168.1.5/share/a.mp4");
            whitelist(false, "server");
            plays(shares);
            blacklist("server");
            forbidden(shares);
        }

        @Test
        @DisplayName("The server of a share path is judged only when Java reads the link host as none (missing, localhost or ~)")
        void shareServerOnlyWithoutLinkHost() {
            blacklist("127.0.0.1");
            forbidden("file://127.0.0.1//allowed.example/s/x.mp4");
            WaterMediaConfig.player.blacklist.enable = false;
            whitelist(false, "allowed.example");
            notAllowed("file://127.0.0.1//allowed.example/s/x.mp4", "file://server//allowed.example/s/x.mp4");
            whitelist(false, "server");
            plays("file://server//other.example/s/x.mp4", "file://~//server/share/x", "file://LOCALHOST//server/share/x");
            whitelist(true, "youtube.com");
            notAllowed("file://~//server/share/x", "file://LOCALHOST//server/share/x");
            blacklist("server");
            forbidden("file://~//server/share/x");
        }

        @Test
        @DisplayName("A device prefix, an empty server or a WebDAV mark in a share path is disguised")
        void deviceSharesAreDisguised() {
            final String[] devices = { "file:////./UNC/server/share/x", "file:///%5C%5C.%5CUNC%5Cserver%5Cshare%5Cx",
                    "file:////./GLOBALROOT/Device/Mup/server/share/x", "file:////%3F/UNC/server/share/x", "file:////",
                    "file:////server@SSL/share/x", "file:////server@8080/share/x" };
            // LISTING THE SERVER DOES NOT HELP: NO ENTRY CAN NAME A DISGUISED HOST
            whitelist(true, "youtube.com", "server");
            notAllowed(devices);
            blacklist("unrelated.example");
            forbidden(devices);
        }

        @Test
        @DisplayName("A device prefix written as the link host is disguised too")
        void deviceHostIsDisguised() {
            // JAVA OPENS file://./UNC/server/share/x AS \\.\UNC\server\share\x, THE DEVICE SPELLING OF \\server\share\x
            final String[] devices = { "file://./UNC/server/share/x.mp4", "file://./GLOBALROOT/Device/Mup/server/share/x.mp4",
                    "file://..//server/share/x.mp4", "http://./x.mp4", "http://@/x.mp4", "http://:80/x.mp4" };
            whitelist(true, "youtube.com");
            notAllowed(devices);
            blacklist("unrelated.example");
            forbidden(devices);
        }

        @Test
        @DisplayName("A link host left empty by its user info or port is no host, so the share server is judged")
        void emptyLinkHostIsJudgedByServer() {
            // JAVA READS THE HOST OF file://@//server/x AS EMPTY AND OPENS \\server\x
            final String[] shares = { "file://@//server/share/x.mp4", "file://user@//server/share/x.mp4", "file://:80//server/share/x.mp4" };
            whitelist(true, "youtube.com");
            notAllowed(shares);
            blacklist("server");
            forbidden(shares);
        }
    }

    @Nested
    @DisplayName("jar: links")
    class JarLinks {

        @Test
        @DisplayName("A jar link is judged by the link it wraps, nested ones included")
        void jarIsJudgedByTheWrappedLink() {
            blacklist("evil.com");
            forbidden("jar:http://evil.com/x.jar!/a.png", "jar:jar:http://evil.com/a.jar!/b.jar!/c.png", "JAR:HTTP://EVIL.COM/x.jar!/a.png",
                    "jar:https://cdn.evil.com/x.jar!/dir/a.png", "jar:http://evil.com/x.jar", "jar:http://a@b@youtube.com/x.jar!/a.png");
            plays("jar:https://youtube.com/x.jar!/a.png", "jar:jar:https://youtube.com/a.jar!/b.jar!/c.png");
            WaterMediaConfig.player.blacklist.enable = false;
            whitelist(false, "youtube.com");
            plays("jar:https://youtube.com/x.jar!/a.png", "jar:https://www.youtube.com/x.jar!/dir/a.png", "jar:jar:https://youtube.com/a.jar!/b.jar!/c.png");
            notAllowed("jar:https://other.com/x.jar!/a.png", "jar:https://youtube.com@other.com/x.jar!/a.png", "jar:jar:https://other.com/a.jar!/b.jar!/c.png",
                    "jar:!/a.png");
        }

        @Test
        @DisplayName("A jar on this machine follows trustLocal and a jar on a share is judged by its server")
        void jarFilesFollowTheirLink() {
            final String[] local = { "jar:file:/C:/mods/pack.jar!/a.png", "jar:file:///home/u/pack.jar!/a.png" };
            whitelist(true, "youtube.com");
            plays(local);
            notAllowed("jar:file:////server/share/x.jar!/a.png");
            whitelist(false, "youtube.com");
            notAllowed(local);
            whitelist(false, "server");
            plays("jar:file:////server/share/x.jar!/a.png");
            blacklist("server");
            forbidden("jar:file:////server/share/x.jar!/a.png");
        }

        @Test
        @DisplayName("A jar wrapping water://global always plays")
        void jarGlobalAlwaysPlays() {
            whitelist(false, "youtube.com");
            blacklist("global", "watermedia.srrapero720.me");
            plays("jar:water://global/x!/a", "jar:water://global/x.jar!/dir/a.png");
        }

        @Test
        @DisplayName("A jar whose inner text is no link is rejected with IllegalArgumentException under either list")
        void malformedJarIsRejected() {
            // THE TEXT BEFORE !/ IS NO URI (AN UNCLOSED BRACKET, A SCHEME WITHOUT A PART), SO UNWRAPPING IT FAILS IN URI.create
            final String[] broken = { "jar:http://[::1!/a.png", "jar:x:!/a.png" };
            blacklist("unrelated.example");
            assertAll(Arrays.stream(broken).<Executable>map(link -> () -> assertThrowsExactly(IllegalArgumentException.class,
                    () -> MediaPlayer.checkSite(URI.create(link)), link + " under the blacklist")));
            WaterMediaConfig.player.blacklist.enable = false;
            whitelist(true, "youtube.com");
            assertAll(Arrays.stream(broken).<Executable>map(link -> () -> assertThrowsExactly(IllegalArgumentException.class,
                    () -> MediaPlayer.checkSite(URI.create(link)), link + " under the whitelist")));
        }

        @Test
        @DisplayName("A jar link is judged by the file Java fetches, which ends at the first !/")
        void jarIsJudgedByTheFetchedFile() {
            // JarURLConnection CUTS AT THE FIRST !/: jar:http://evil.com!/x.jar!/a.png FETCHES http://evil.com
            blacklist("evil.com", "server");
            forbidden("jar:http://evil.com!/x.jar!/a.png", "jar:file:////server!/x.jar!/a.png");
        }
    }

    @Nested
    @DisplayName("MRL resolution")
    class Resolving {

        @Test
        @DisplayName("A link the whitelist does not allow ends BLOCKED before any platform or request touches it")
        void whitelistRefusalComesFirst() {
            final AtomicInteger requests = new AtomicInteger();
            final AtomicInteger asked = new AtomicInteger();
            try (final LocalHttp origin = picture(requests)) {
                final URI link = origin.uri("/site-lists/not-allowed.png");
                final Platform spy = platform(link, asked);
                whitelist(false, "youtube.com");
                PlatformAPI.register(spy);
                try {
                    final MRL mrl = settled(link);
                    assertSame(MRL.Status.BLOCKED, mrl.status());
                    assertTrue(mrl.status().failed(), "a BLOCKED MRL counts as failed");
                    final WhitelistException refusal = assertInstanceOf(WhitelistException.class, mrl.exception());
                    assertEquals("(wm_err:1150) Failed to resolve " + link + ", the site is not allowed", refusal.getMessage());
                    assertEquals(0, mrl.sourceCount());
                } finally {
                    PlatformAPI.unregister(spy);
                }
                assertEquals(0, asked.get(), "no platform was asked for the refused link");
                assertEquals(0, requests.get(), "no request reached the origin");
            }
        }

        @Test
        @DisplayName("A forbidden LAN link ends BLOCKED before any platform or request, even under trustLocal")
        void blacklistRefusalComesFirst() {
            final AtomicInteger requests = new AtomicInteger();
            final AtomicInteger asked = new AtomicInteger();
            try (final LocalHttp origin = picture(requests)) {
                final URI link = origin.uri("/site-lists/forbidden.png");
                final Platform spy = platform(link, asked);
                whitelist(true, "youtube.com");
                blacklist(link.getHost());
                PlatformAPI.register(spy);
                try {
                    final MRL mrl = settled(link);
                    assertSame(MRL.Status.BLOCKED, mrl.status());
                    assertTrue(mrl.status().failed(), "a BLOCKED MRL counts as failed");
                    final BlacklistException refusal = assertInstanceOf(BlacklistException.class, mrl.exception());
                    assertEquals("(wm_err:1149) Failed to resolve " + link + ", the site is forbidden", refusal.getMessage());
                } finally {
                    PlatformAPI.unregister(spy);
                }
                assertEquals(0, asked.get(), "no platform was asked for the refused link");
                assertEquals(0, requests.get(), "no request reached the origin");
            }
        }

        @Test
        @DisplayName("A disguised address ends BLOCKED before its request reaches the loopback it hides, which it reaches with the lists off")
        void disguisedAddressNeverConnects() {
            final AtomicInteger requests = new AtomicInteger();
            try (final LocalHttp origin = picture(requests)) {
                // 2130706433 IS 127.0.0.1 WRITTEN AS ONE NUMBER
                final String alias = "http://2130706433:" + origin.uri("/").getPort();
                blacklist("unrelated.example");
                final MRL mrl = settled(URI.create(alias + "/site-lists/disguised.png"));
                assertSame(MRL.Status.BLOCKED, mrl.status());
                assertInstanceOf(BlacklistException.class, mrl.exception());
                assertEquals(0, requests.get(), "no request reached the origin");

                // THE CONTROL PROVES THE ALIAS REALLY LEADS TO THE ORIGIN; ITS OWN PATH, SINCE THE MRL CACHE KEEPS THE BLOCKED ONE
                WaterMediaConfig.player.blacklist.enable = false;
                loaded(URI.create(alias + "/site-lists/disguised-control.png"));
                assertTrue(requests.get() > 0, "the alias reached the loopback origin");
            }
        }

        @Test
        @DisplayName("Only the link is judged: the player plays a source the platform put on a forbidden host")
        void resolvedHostsPlay() {
            final AtomicInteger requests = new AtomicInteger();
            try (final LocalHttp origin = picture(requests)) {
                final URI link = URI.create("https://allowed.example/site-lists/played-elsewhere");
                final URI target = origin.uri("/site-lists/played-elsewhere.png");
                final Platform platform = platform(link, new AtomicInteger(), image(target));
                whitelist(false, "allowed.example");
                blacklist(target.getHost());
                PlatformAPI.register(platform);
                final MRL mrl;
                try {
                    mrl = loaded(link);
                } finally {
                    PlatformAPI.unregister(platform);
                }
                final MediaPlayer player = PlayerAPI.player(mrl, PlayerAPI::headlessEngine, () -> null);
                assertNotNull(player, "the player judges the link, not the host of its source");
                try {
                    assertTrue(player.start(), "the player starts");
                    assertTrue(PlayerWait.awaitStatus(player, PLAY_TIMEOUT_MS, MediaPlayer.Status.PLAYING, MediaPlayer.Status.PAUSED),
                            () -> "the player did not play, it is " + player.status());
                    assertTrue(requests.get() > 0, "the picture came from the forbidden host");
                } finally {
                    player.release();
                }
            }
        }

        @Test
        @DisplayName("Only the link is judged: a redirect to a forbidden host is followed")
        void redirectsAreNotJudged() {
            final AtomicInteger requests = new AtomicInteger();
            final byte[] body = Fixtures.readAll(Fixtures.PNG_STATIC);
            // THE LINK NAMES THE ORIGIN BY ITS ADDRESS AND THE REDIRECT BY ITS NAME, SO EACH HOP CARRIES ANOTHER HOST
            try (final LocalHttp origin = LocalHttp.start("/", exchange -> {
                requests.incrementAndGet();
                if (exchange.getRequestURI().getPath().endsWith("/start")) {
                    exchange.getResponseHeaders().set("Location", "http://localhost:" + exchange.getLocalAddress().getPort() + "/site-lists/redirected.png");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                }
                LocalHttp.respond(exchange, "image/png", body, 0L);
            })) {
                final URI link = origin.uri("/site-lists/start");
                whitelist(false, link.getHost());
                blacklist("localhost");
                assertEquals(MediaType.IMAGE, loaded(link).source(0).type());
                assertTrue(requests.get() >= 2, "the redirect was followed to the forbidden host");
            }
        }

        @Test
        @DisplayName("Playlist channels on a forbidden host stay in the list")
        void playlistChannelsAreNotJudged() {
            final byte[] playlist = Fixtures.readAll(Fixtures.M3U_IPTV);
            try (final LocalHttp origin = LocalHttp.start("/", exchange -> LocalHttp.respond(exchange, "application/vnd.apple.mpegurl", playlist, 0L))) {
                whitelist(true, "youtube.com");
                blacklist("stream.example", "logo.example");
                assertEquals(3, loaded(origin.uri("/site-lists/channels.m3u8")).sourceCount(), "every channel of the fixture stays");
            }
        }

        @Test
        @DisplayName("water://global resolves under lists that refuse everything else")
        void globalResolves() {
            final URI link = URI.create("water://global/site-lists-resolved.png");
            final AtomicInteger asked = new AtomicInteger();
            final Platform platform = platform(link, asked, image(URI.create("https://watermedia.srrapero720.me/site-lists-resolved.png")));
            whitelist(false, "youtube.com");
            blacklist("global", "water", "watermedia.srrapero720.me");
            PlatformAPI.register(platform);
            try {
                loaded(link);
                assertEquals(1, asked.get(), "the platform resolved the link once");
            } finally {
                PlatformAPI.unregister(platform);
            }
        }

        @Test
        @DisplayName("water://local loads under trustLocal and is BLOCKED without it, even when listed")
        void waterLocalFollowsTrustLocal() throws IOException {
            copy("water-local-trusted.png");
            copy("water-local-untrusted.png");
            whitelist(true, "youtube.com");
            loaded(URI.create("water://local/build/site-lists/water-local-trusted.png"));

            final URI untrusted = URI.create("water://local/build/site-lists/water-local-untrusted.png");
            final AtomicInteger asked = new AtomicInteger();
            final Platform spy = platform(untrusted, asked);
            whitelist(false, "youtube.com", "local");
            PlatformAPI.register(spy);
            try {
                final MRL mrl = settled(untrusted);
                assertSame(MRL.Status.BLOCKED, mrl.status());
                assertInstanceOf(WhitelistException.class, mrl.exception());
            } finally {
                PlatformAPI.unregister(spy);
            }
            assertEquals(0, asked.get(), "no platform was asked for the refused link");
        }

        @Test
        @DisplayName("reload() judges the link again and loads it once the list is off")
        void reloadLoadsOnceTheListIsOff() {
            final AtomicInteger requests = new AtomicInteger();
            try (final LocalHttp origin = picture(requests)) {
                whitelist(false, "youtube.com");
                final MRL mrl = settled(origin.uri("/site-lists/reloaded.png"));
                assertSame(MRL.Status.BLOCKED, mrl.status());

                assertSame(mrl, mrl.reload());
                assertTrue(mrl.await(TIMEOUT_MS), "the refused reload settles");
                assertSame(MRL.Status.BLOCKED, mrl.status(), "a reload under the same list is refused again");
                assertEquals(0, requests.get(), "no request reached the origin");

                WaterMediaConfig.player.whitelist.enable = false;
                assertSame(mrl, mrl.reload());
                assertTrue(mrl.await(TIMEOUT_MS), "the allowed reload settles");
                assertSame(MRL.Status.LOADED, mrl.status(), () -> "the link did not load: " + mrl.exception());
                assertNull(mrl.exception());
                assertTrue(requests.get() > 0, "the reload reached the origin");
            }
        }
    }

    // EVERY CASE REFUSES ITS SHARE AND NAMES ITS OWN .invalid SERVER, SO NO SMB CONNECTION CAN EVER BE ATTEMPTED
    @Nested
    @DisplayName("Windows share strings")
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "only a Windows path reads a share string as the root of a server")
    class ShareStrings {

        @Test
        @DisplayName("A backslash share string becomes a file link of its server, which the blacklist forbids")
        void backslashShareIsForbidden() {
            blacklist("site-lists-share-a.invalid");
            final MRL mrl = PlayerAPI.mrl("\\\\site-lists-share-a.invalid\\share\\a b.png");
            assertEquals(URI.create("file://site-lists-share-a.invalid/share/a%20b.png"), mrl.uri());
            assertTrue(mrl.await(TIMEOUT_MS), "the share settles");
            assertSame(MRL.Status.BLOCKED, mrl.status());
            assertInstanceOf(BlacklistException.class, mrl.exception());
        }

        @Test
        @DisplayName("A slash share string becomes a file link of its server, which the whitelist does not allow")
        void slashShareIsNotAllowed() {
            whitelist(false, "youtube.com");
            final MRL mrl = PlayerAPI.mrl("//site-lists-share-b.invalid/share/a b.png");
            assertEquals(URI.create("file://site-lists-share-b.invalid/share/a%20b.png"), mrl.uri());
            assertTrue(mrl.await(TIMEOUT_MS), "the share settles");
            assertSame(MRL.Status.BLOCKED, mrl.status());
            assertInstanceOf(WhitelistException.class, mrl.exception());
        }

        @Test
        @DisplayName("A share string is judged by its server even under trustLocal")
        void shareIsNeverTrusted() {
            whitelist(true, "youtube.com");
            final MRL mrl = PlayerAPI.mrl("\\\\site-lists-share-c.invalid\\share\\a.png");
            assertEquals(URI.create("file://site-lists-share-c.invalid/share/a.png"), mrl.uri());
            assertTrue(mrl.await(TIMEOUT_MS), "the share settles");
            assertSame(MRL.Status.BLOCKED, mrl.status());
            assertInstanceOf(WhitelistException.class, mrl.exception());
        }
    }

    @Nested
    @DisplayName("Player revalidation")
    class Revalidation {

        @Test
        @DisplayName("A whitelist turned on after the load refuses the player and blocks only the judged MRL")
        void whitelistRefusesLoadedLinks() {
            final AtomicInteger requests = new AtomicInteger();
            try (final LocalHttp origin = picture(requests)) {
                final MRL first = loaded(origin.uri("/site-lists/revalidated-api.png"));
                final MRL second = loaded(origin.uri("/site-lists/revalidated-ctor.png"));
                whitelist(false, "youtube.com");

                // THROUGH THE API: NO PLAYER, AND THE ENGINE MADE FOR IT IS RELEASED
                final HeadlessGFXEngine[] made = new HeadlessGFXEngine[1];
                final MediaPlayer player = PlayerAPI.player(first, () -> made[0] = PlayerAPI.headlessEngine(), () -> null);
                if (player != null) player.release();
                assertNull(player, "no player for the refused link");
                assertSame(MRL.Status.BLOCKED, first.status());
                assertTrue(first.status().failed(), "a BLOCKED MRL counts as failed");
                final WhitelistException refusal = assertInstanceOf(WhitelistException.class, first.exception());
                assertEquals("(wm_err:1150) Failed to resolve " + first.uri() + ", the site is not allowed", refusal.getMessage());
                assertNull(first.source(0));
                assertEquals(0, first.sourceCount());
                assertTrue(made[0] == null || made[0].released(), "the engine made for the refused player is released");
                assertSame(MRL.Status.LOADED, second.status(), "a refusal blocks only the MRL it judged");

                // THROUGH THE CONSTRUCTOR: A CODED IllegalStateException CAUSED BY THE REFUSAL
                final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine();
                try {
                    final IllegalStateException failure = assertThrowsExactly(IllegalStateException.class, () -> new TxMediaPlayer(second, 0, gfx));
                    assertEquals("(wm_err:1151) Failed to create the MediaPlayer due to a blocked site: " + second.uri(), failure.getMessage());
                    final WhitelistException cause = assertInstanceOf(WhitelistException.class, failure.getCause());
                    assertEquals("(wm_err:1150) Failed to resolve " + second.uri() + ", the site is not allowed", cause.getMessage());
                    assertSame(MRL.Status.BLOCKED, second.status());
                    assertSame(cause, second.exception());
                    assertNull(second.source(0));
                } finally {
                    gfx.release();
                }
            }
        }

        @Test
        @DisplayName("A blacklist turned on after the load refuses the player of a LAN link even under trustLocal")
        void blacklistRefusesLoadedLinks() {
            final AtomicInteger requests = new AtomicInteger();
            try (final LocalHttp origin = picture(requests)) {
                final MRL mrl = loaded(origin.uri("/site-lists/revalidated-forbidden.png"));
                whitelist(true, "youtube.com");
                blacklist(mrl.uri().getHost());
                noPlayer(mrl);
                assertSame(MRL.Status.BLOCKED, mrl.status());
                final BlacklistException refusal = assertInstanceOf(BlacklistException.class, mrl.exception());
                assertEquals("(wm_err:1149) Failed to resolve " + mrl.uri() + ", the site is forbidden", refusal.getMessage());
                assertNull(mrl.source(0));
            }
        }

        @Test
        @DisplayName("A blocked MRL loads and gets its player again once the list is off and it reloads")
        void blockedMrlComesBack() {
            final AtomicInteger requests = new AtomicInteger();
            try (final LocalHttp origin = picture(requests)) {
                final MRL mrl = loaded(origin.uri("/site-lists/revalidated-back.png"));
                blacklist(mrl.uri().getHost());
                noPlayer(mrl);
                assertSame(MRL.Status.BLOCKED, mrl.status());

                WaterMediaConfig.player.blacklist.enable = false;
                assertSame(mrl, mrl.reload());
                assertTrue(mrl.await(TIMEOUT_MS), "the reload settles");
                assertSame(MRL.Status.LOADED, mrl.status(), () -> "the link did not load: " + mrl.exception());
                final MediaPlayer player = PlayerAPI.player(mrl, PlayerAPI::headlessEngine, () -> null);
                assertNotNull(player, "the reloaded MRL gets its player");
                player.release();
            }
        }

        @Test
        @DisplayName("A local file keeps its player while trusted and is refused once trustLocal turns off")
        void localFileFollowsTrustLocal() throws IOException {
            final MRL trusted = loaded(Fixtures.fileUri(copy("revalidated-trusted.png")));
            final MRL untrusted = loaded(Fixtures.fileUri(copy("revalidated-untrusted.png")));
            whitelist(true, "youtube.com");
            blacklist("pornhub.com");
            final MediaPlayer player = PlayerAPI.player(trusted, PlayerAPI::headlessEngine, () -> null);
            assertNotNull(player, "the trusted local file gets its player");
            player.release();
            assertSame(MRL.Status.LOADED, trusted.status());

            WaterMediaConfig.player.whitelist.trustLocal = false;
            noPlayer(untrusted);
            assertSame(MRL.Status.BLOCKED, untrusted.status());
            assertInstanceOf(WhitelistException.class, untrusted.exception());
        }

        @Test
        @DisplayName("water://global gets its player under lists that refuse everything else")
        void globalGetsItsPlayer() {
            final URI link = URI.create("water://global/site-lists-player.png");
            final Platform platform = platform(link, new AtomicInteger(), image(URI.create("https://watermedia.srrapero720.me/site-lists-player.png")));
            PlatformAPI.register(platform);
            final MRL mrl;
            try {
                mrl = loaded(link);
            } finally {
                PlatformAPI.unregister(platform);
            }
            whitelist(false, "youtube.com");
            blacklist("global", "watermedia.srrapero720.me");
            final MediaPlayer player = PlayerAPI.player(mrl, PlayerAPI::headlessEngine, () -> null);
            assertNotNull(player, "water://global gets its player");
            player.release();
            assertSame(MRL.Status.LOADED, mrl.status());
        }
    }
}
