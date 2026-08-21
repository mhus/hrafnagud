package de.mhus.hrafnagud.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything tunable about collecting and storing — Munin's half of the
 * service.
 *
 * <p>What spends model time rather than somebody's bandwidth lives in
 * {@link HuginProperties}, and what belongs to neither half in
 * {@link HrafnagudProperties}. Three roots, so a property name never claims a
 * layer it does not belong to.
 *
 * <p>The defaults are chosen for politeness rather than for throughput. A
 * worldwide crawl talks to thousands of publishers who did not ask to be
 * crawled; the fastest configuration that still works is not the right one,
 * because the cost of being wrong is a block that no retry policy recovers
 * from.
 *
 * <h2>This is the default layer, not the value</h2>
 * Most of what is below is read through
 * {@link de.mhus.hrafnagud.settings.Settings}, which puts whatever
 * an operator has stored in the database in front of it. So these fields are
 * what a value <em>falls back to</em>: they still come from
 * {@code application.yml} and the {@code HRAFNAGUD_*} environment as before,
 * and removing an override is what returns a setting to them.
 *
 * <p>Read straight from here — because nothing could read them any later — are
 * the values consumed while the service starts: the tick cadences, the proxy
 * and connect timeout behind the shared HTTP client, the language detector's
 * model configuration, {@link Catalog#installBundled}, {@link Api} in full, and
 * {@link Feed#profiles}. {@code specs/settings.md} §3 is that boundary written
 * down.
 */
@ConfigurationProperties(prefix = "munin")
@Data
public class MuninProperties {

    private final Http http = new Http();
    private final Feed feed = new Feed();
    private final Content content = new Content();
    private final SourceList sourceList = new SourceList();
    private final Catalog catalog = new Catalog();
    private final Language language = new Language();
    private final Category category = new Category();
    private final Filter filter = new Filter();
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

        /**
         * Named interval policies, one per class of source.
         *
         * <p>The fields above are the unnamed default and stay the fallback
         * for anything a profile leaves unset — so adding profiles changes
         * nothing until a source, list or catalogue names one.
         *
         * <p>This exists because the adaptive policy can only move within the
         * bounds it is given. A blog that posts monthly is still polled at the
         * ceiling, and a global ceiling that suits news (12 h) is two orders of
         * magnitude too eager for a blog. See {@code FetchProfile}.
         */
        private Map<String, Profile> profiles = new LinkedHashMap<>();
    }

    /**
     * One named interval policy. Every field is optional and falls back to the
     * {@link Feed} defaults — a profile that only widens the ceiling says only
     * that.
     */
    @Data
    public static class Profile {

        private @Nullable Duration defaultInterval;

        private @Nullable Duration minInterval;

        private @Nullable Duration maxInterval;
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

        /**
         * Entries accepted from one list document.
         *
         * <p>A guard against a pathological document, not a policy: published
         * collections of five figures exist (one text list of indie-web feeds
         * has 36,000 lines), and truncating one silently at ten thousand
         * would look like a broken import rather than a limit.
         *
         * <p>What actually bounds the cost of a large list is not this number
         * but the poll interval and the per-host limiter — a hundred thousand
         * feeds polled daily are cheaper than a thousand polled every five
         * minutes.
         */
        private int maxEntries = 50_000;

        /**
         * Lists leased at a time within a round. Lease granularity, not a
         * ceiling — the round keeps claiming until nothing is due or
         * {@link #maxPerRound} is reached.
         */
        private int batchSize = 5;

        /**
         * Ceiling on one round, so that a round ends.
         *
         * <p>A catalogue delivers its lists all due at once, and a fixed
         * handful per five-minute tick would spread a fresh instance's first
         * import over an hour. Draining instead means the first round after an
         * import does the work — paced by the per-host limiter, which for a
         * directory served from one CDN is the real clock.
         *
         * <p>200 is roughly three catalogues' worth: enough that a normal
         * import finishes in one round, low enough that a round stays minutes
         * rather than hours and its leases are released again.
         */
        private int maxPerRound = 200;

        private Duration claimLease = Duration.ofMinutes(15);
    }

    /**
     * Catalogue refresh — the layer that discovers source lists.
     *
     * <p>Slow by design: a directory of feed lists changes on the timescale
     * of somebody editing a repository, and re-reading it more often than
     * daily only spends somebody else's rate limit.
     */
    @Data
    public static class Catalog {

        private boolean enabled = true;

        private Duration tickInterval = Duration.ofMinutes(15);

        private Duration defaultInterval = Duration.ofHours(24);

        private Duration claimLease = Duration.ofMinutes(30);

        /**
         * Whether a fresh database gets the bundled {@code awesome-rss-feeds}
         * catalogues — one for news, one for blogs, both disabled. Once
         * installed they are ordinary catalogues; switching this off later
         * changes nothing.
         *
         * <p>There is deliberately no "narrow the bundled selection" property
         * any more. Running less now means enabling one of the two rather than
         * both, and a property that re-filtered them would fight the split it
         * is meant to complement.
         */
        private boolean installBundled = true;

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

    /**
     * Category normalisation: mapping what publishers call their sections onto
     * IPTC Media Topics.
     *
     * <p>Only the matching stage is here. It is string comparison against a
     * bundled table, runs at ingest and costs nothing, so it is Munin's work.
     * The stage that asks a brain about what matching could not settle is
     * {@code hugin.category.*} — see {@link HuginProperties.Category}.
     */
    @Data
    public static class Category {

        /**
         * Confidence at which the matching stage's answer counts as resolved
         * rather than as a guess.
         *
         * <p>0.9 by design: an exact label match in any of thirteen languages
         * scores 1.0 and a token-set match 0.9, while the single-word rule
         * scores 0.4 and therefore never resolves on its own — it reaches a
         * third of all uses by mapping any one-word category to any label
         * containing that word, which is also how "standard" becomes a topic.
         */
        private double acceptConfidence = 0.9;
    }

    /**
     * Filter rules — which articles are worth a body fetch and a translation.
     *
     * <p>There is no {@code enabled} here, and that is not an omission. The
     * engine is a gate rather than a worker: with no rules written every article
     * is accepted, which is exactly what an off switch would achieve. The two
     * numbers below bound the only expensive operation, re-evaluating articles
     * that are already stored.
     *
     * <p>Design: specs/filter.md.
     */
    @Data
    public static class Filter {

        /**
         * Cap on articles examined by one re-evaluation run.
         *
         * <p>The archive is millions of rows, so a run is bounded even when the
         * time window is not. Reaching the cap is reported rather than hidden,
         * and the next run continues where this one stopped — the progress
         * marker is {@code policyAt} on the article.
         */
        private int maxPerRun = 50_000;

        /** Articles read per batch while walking. */
        private int batchSize = 500;
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
