package de.mhus.hrafnagud.munin.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything tunable about collection, in one place.
 *
 * <p>The defaults are chosen for politeness rather than for throughput. A
 * worldwide crawl talks to thousands of publishers who did not ask to be
 * crawled; the fastest configuration that still works is not the right one,
 * because the cost of being wrong is a block that no retry policy recovers
 * from.
 */
@ConfigurationProperties(prefix = "munin")
@Data
public class MuninProperties {

    private final Http http = new Http();
    private final Feed feed = new Feed();
    private final Content content = new Content();
    private final SourceList sourceList = new SourceList();
    private final Language language = new Language();
    private final Translation translation = new Translation();
    private final Api api = new Api();

    /** Shared HTTP behaviour for feed, list and article requests. */
    @Data
    public static class Http {

        /** Optional outbound proxy. Unset means direct connections. */
        private final Proxy proxy = new Proxy();

        /**
         * Identifies the crawler and gives publishers somebody to contact.
         * An anonymous or browser-impersonating agent is how a crawler ends
         * up on a blocklist, so the default names the project and expects
         * the operator to append a contact URL.
         */
        private String userAgent =
                "Hrafnagud/0.1 (+https://github.com/mhus/hrafnagud; news aggregator)";

        private Duration connectTimeout = Duration.ofSeconds(10);

        private Duration readTimeout = Duration.ofSeconds(30);

        /**
         * Hard cap on a downloaded body. Protects against a misconfigured
         * URL pointing at a video file; the stream is cut, not buffered
         * first.
         */
        private long maxBodyBytes = 8L * 1024 * 1024;

        /**
         * Minimum spacing between two requests to the same host, across all
         * workers. This is the single most important politeness control: a
         * directory import can easily put two hundred feeds of one publisher
         * into the registry, and without spacing they would all be polled in
         * the same second.
         */
        private Duration minHostInterval = Duration.ofSeconds(2);
    }

    /**
     * Outbound HTTP proxy.
     *
     * <p>Opt-in by presence: leaving {@code host} unset means every request
     * goes out directly, which is what an installation without a proxy
     * wants and needs no configuration to express.
     *
     * <p>A configured host with no usable port is rejected at startup rather
     * than ignored. Silently falling back to direct connections would be the
     * worst outcome of the three — an environment that requires a proxy
     * would fail every fetch, and the reason would be nowhere near the
     * mistake.
     */
    @Data
    public static class Proxy {

        /** Proxy host or IP. Empty or unset disables proxying. */
        private String host = "";

        private int port;

        public boolean isConfigured() {
            return !host.isBlank();
        }
    }

    /** Feed polling and the adaptive interval. */
    @Data
    public static class Feed {

        /** Master switch for the background poll loop. */
        private boolean enabled = true;

        /** How often the loop looks for sources that are due. */
        private Duration tickInterval = Duration.ofSeconds(30);

        /**
         * Sources claimed per tick. Also bounds how many polls are in
         * flight: a round runs its whole batch concurrently on virtual
         * threads, and the per-host rate limiter — not a pool size — is what
         * keeps any single publisher from being hit hard.
         */
        private int batchSize = 20;

        /** Interval a newly created source starts with. */
        private Duration defaultInterval = Duration.ofMinutes(30);

        private Duration minInterval = Duration.ofMinutes(5);

        private Duration maxInterval = Duration.ofHours(12);

        /**
         * New articles in one poll at or above which the interval is halved.
         * Reaching it suggests the feed's window may have rolled over
         * between polls, which means entries were missed.
         */
        private int busyThreshold = 5;

        /**
         * Cap on the backoff a failing source reaches. Failures stretch the
         * interval geometrically but never disable the source — a feed that
         * is down for a week and returns should resume by itself, and
         * auto-disabling would silently shrink the registry.
         */
        private Duration maxFailureInterval = Duration.ofHours(24);

        /**
         * How long a claimed source stays leased. Bounds how long a source
         * is stuck if the worker dies mid-fetch.
         */
        private Duration claimLease = Duration.ofMinutes(10);

        /** Entries read from a single feed document. */
        private int maxItemsPerFeed = 500;

        /** Teaser length kept from a feed entry. */
        private int maxSummaryChars = 4000;

        /** Tolerance for publication dates in the future before they are dropped. */
        private Duration maxFutureSkew = Duration.ofHours(6);
    }

    /** Full-article fetching. */
    @Data
    public static class Content {

        /**
         * Master switch. Off by default: fetching publisher pages is a
         * qualitatively different activity from reading the feeds they
         * publish for that purpose, and it should be a decision the operator
         * makes rather than one that happens by installing the service.
         */
        private boolean enabled = false;

        private Duration tickInterval = Duration.ofSeconds(15);

        /** Articles claimed per tick, and the in-flight bound as above. */
        private int batchSize = 20;

        /** Attempts before an article is marked {@code FAILED}. */
        private int maxAttempts = 3;

        /** Delay before the first retry; doubles per attempt. */
        private Duration retryDelay = Duration.ofMinutes(10);

        /** Lease held on a claimed article, as above. */
        private Duration claimLease = Duration.ofMinutes(5);

        /**
         * Below this word count the extraction is treated as failed. A
         * paywall interstitial or a consent page extracts to a few dozen
         * words, and storing that as the article body is worse than storing
         * nothing.
         */
        private int minWordCount = 60;

        /** Characters kept from an extracted body. */
        private int maxTextChars = 200_000;

        /** Whether {@code robots.txt} is fetched and obeyed. */
        private boolean respectRobots = true;

        /** How long a parsed {@code robots.txt} is reused. */
        private Duration robotsCacheTtl = Duration.ofHours(6);
    }

    /** Source-list refresh. */
    @Data
    public static class SourceList {

        private boolean enabled = true;

        private Duration tickInterval = Duration.ofMinutes(5);

        private Duration defaultInterval = Duration.ofHours(24);

        /** Entries accepted from one list document. */
        private int maxEntries = 10_000;

        private Duration claimLease = Duration.ofMinutes(15);
    }

    /**
     * Translation queue.
     *
     * <p>Munin owns the queue and the storage; it does not own the
     * engine. Which service performs a translation is decided by whoever
     * supplies a {@code TranslationProvider} — with none on the
     * classpath, articles simply accumulate a backlog and nothing works
     * it, which is a legible state rather than a broken one.
     */
    @Data
    public static class Translation {

        /**
         * The one language everything is normalised into, as a BCP-47
         * primary subtag. Empty — the default — means no article is ever
         * queued and the subsystem stays dormant.
         *
         * <p>One language rather than a list: translation here is the
         * step that lets every later stage work in a single language.
         * Rendering the archive in several display languages is a
         * different job, and one field cannot honestly be both.
         */
        private String pivotLanguage = "";

        /**
         * Whether the teaser is translated alongside the title.
         *
         * <p>Both go in one call either way, so switching this off saves
         * the teaser's tokens but not a request. Titles alone already
         * carry lists, search results and clustering; the teaser is worth
         * its cost when it has to be readable.
         */
        private boolean translateSummary = true;

        private Duration tickInterval = Duration.ofSeconds(20);

        /** Articles claimed per tick. */
        private int batchSize = 10;

        /** Attempts per language before it is dropped from the backlog. */
        private int maxAttempts = 3;

        /** Delay before the first retry; doubles per attempt. */
        private Duration retryDelay = Duration.ofMinutes(5);

        private Duration claimLease = Duration.ofMinutes(5);

        /**
         * Longest source text handed to a provider in one go. A provider
         * is typically a model call, and an article body arriving where a
         * teaser was expected is the case this guards against.
         */
        private int maxSourceChars = 8000;
    }

    /** Language detection. */
    @Data
    public static class Language {

        private boolean enabled = true;

        /**
         * Below this many characters of title plus teaser, detection is
         * skipped and the language stays unknown. Statistical detection on a
         * six-word headline guesses, and a confident wrong answer is worse
         * than an honest {@code UNKNOWN}.
         */
        private int minChars = 40;

        /**
         * Lingua's high-accuracy models hold every n-gram order in memory,
         * which for all languages costs several gigabytes. Low-accuracy mode
         * keeps the highest orders only — a few hundred megabytes — and the
         * difference only shows on very short input, which the threshold
         * above already excludes.
         */
        private boolean lowAccuracyMode = true;

        /**
         * Restricts detection to these BCP-47 primary subtags. Empty means
         * all languages Lingua knows. Narrowing it is both faster and more
         * accurate when the registry is regional.
         */
        private List<String> languages = new ArrayList<>();
    }

    /** The operator API and the console served over it. */
    @Data
    public static class Api {

        /**
         * Bearer token required by {@code /api/v1/**}.
         *
         * <p><b>Empty means no check</b>, which keeps every existing
         * installation working and matches what the Vancetope-facing
         * endpoints already do with their own keys. It is the wrong default
         * for anything reachable from outside: the operator API can delete
         * articles and sources.
         *
         * <p>One token, not accounts. Everyone who has it can do everything,
         * which is honest for a service whose entire user base is the person
         * running it — and small enough to rotate by editing one variable.
         */
        private String token = "";

        /**
         * Whether the bundled console is served at {@code /}.
         *
         * <p>Separate from the token because they answer different
         * questions. The console holds no data and no credential — it asks
         * for the token and keeps it in the browser — so serving it is
         * harmless where the API is guarded, and pointless where the API is
         * not reachable at all.
         */
        private boolean consoleEnabled = true;
    }
}
