package de.mhus.hrafnagud.settings;

import de.mhus.hrafnagud.config.HuginProperties;
import de.mhus.hrafnagud.config.MuninProperties;
import lombok.Getter;
import org.springframework.stereotype.Component;

/**
 * Every setting that can be changed while the service runs, declared once.
 *
 * <p>This is the list, for both halves of the service. A key exists because it
 * is written here, its type and its default come from here, and the sentence an operator reads next to it in
 * the console is the one written here — so there is no second place where a
 * setting could be half-defined.
 *
 * <p>The sections mirror the properties classes field for field, and the keys
 * are the property names from {@code application.yml} unchanged. That is the
 * whole trick to the cascade: {@code munin.feed.batchSize} in the YAML is the
 * default for the setting called {@code munin.feed.batchSize}, and an operator
 * reading either one is looking at the same name.
 *
 * <p>Which root a key carries follows the layer that owns the value:
 * {@code munin.*} for collecting and storing, {@code hugin.*} for what hands
 * text to a model. The one seam runs through {@link Category}, and it is
 * deliberate — {@code munin.category.acceptConfidence} tunes the local
 * table match at ingest, while the rest of that section is the worker that
 * spends model time on what the match could not settle.
 *
 * <h2>What is deliberately not here</h2>
 * A value is missing from this list for one of three reasons, and none of them
 * is an oversight:
 *
 * <ul>
 *   <li><b>It is read while the service starts.</b> The tick cadences
 *       ({@code tickInterval}, {@code initialDelay}) are baked into
 *       {@code @Scheduled}, the proxy and the connect timeout into the shared
 *       {@code HttpClient}, {@code munin.catalog.installBundled} only ever
 *       applies to an empty database, and {@code munin.api.consoleEnabled}
 *       decides whether an HTTP surface exists at all. Storing those here would
 *       be a value nothing reads.</li>
 *   <li><b>It is a secret.</b> {@code munin.api.token} and the two Ode keys
 *       stay in the environment; see {@code specs/settings.md} §5.</li>
 *   <li><b>It is structure rather than a number.</b>
 *       {@code munin.feed.profiles} defines interval classes and
 *       {@code munin.language.languages} restricts the detector's model — both
 *       are shapes an operator changes with a deployment, not a knob.</li>
 * </ul>
 *
 * <p>Where a change takes effect is worth knowing per group: a worker switch
 * and a batch size are read at the start of the next round, while the interval
 * bounds are applied the next time a source is <em>rescheduled</em> — the
 * poll times already written stay as they are until each source comes round.
 */
@Component
@Getter
public class Settings {

    private final Http http;
    private final Feed feed;
    private final Content content;
    private final SourceList sourceList;
    private final Catalog catalog;
    private final Translation translation;
    private final Language language;
    private final Category category;
    private final Filter filter;
    private final Gemini gemini;

    /** Shared HTTP behaviour, for whatever is decided per request. */
    public record Http(
            SettingString userAgent,
            SettingDuration readTimeout,
            SettingLong maxBodyBytes,
            SettingDuration minHostInterval) {
    }

    /** Feed polling and the adaptive interval. */
    public record Feed(
            SettingBoolean enabled,
            SettingInt batchSize,
            SettingDuration defaultInterval,
            SettingDuration minInterval,
            SettingDuration maxInterval,
            SettingInt busyThreshold,
            SettingDuration maxFailureInterval,
            SettingDuration claimLease,
            SettingInt maxItemsPerFeed,
            SettingInt maxSummaryChars,
            SettingDuration maxFutureSkew) {
    }

    /** Full-article fetching. */
    public record Content(
            SettingBoolean enabled,
            SettingInt batchSize,
            SettingInt maxAttempts,
            SettingDuration retryDelay,
            SettingDuration claimLease,
            SettingInt minWordCount,
            SettingInt maxTextChars,
            SettingBoolean respectRobots,
            SettingDuration robotsCacheTtl) {
    }

    /** Source-list refresh. */
    public record SourceList(
            SettingBoolean enabled,
            SettingDuration defaultInterval,
            SettingInt maxEntries,
            SettingInt batchSize,
            SettingInt maxPerRound,
            SettingDuration claimLease) {
    }

    /** Catalogue refresh. */
    public record Catalog(
            SettingBoolean enabled,
            SettingDuration defaultInterval,
            SettingDuration claimLease) {
    }

    /** The translation queue. */
    public record Translation(
            SettingBoolean enabled,
            SettingString provider,
            SettingString pivotLanguage,
            SettingLanguages readableLanguages,
            SettingBoolean translateSummary,
            SettingInt batchSize,
            SettingInt maxAttempts,
            SettingDuration retryDelay,
            SettingDuration claimLease,
            SettingInt maxSourceChars) {
    }

    /** Language detection, minus what the detector is built from. */
    public record Language(
            SettingBoolean enabled,
            SettingInt minChars) {
    }

    /** Category normalisation. */
    public record Category(
            SettingBoolean enabled,
            SettingInt batchSize,
            SettingInt maxAttempts,
            SettingDuration retryDelay,
            SettingDuration claimLease,
            SettingDouble acceptConfidence) {
    }

    /** Direct model access for the Gemini provider. */
    public record Gemini(
            SettingString model,
            SettingDouble temperature,
            SettingDuration timeout) {
    }

    /** Filter re-evaluation. */
    public record Filter(
            SettingInt maxPerRun,
            SettingInt batchSize) {
    }

    public Settings(SettingsService store, MuninProperties defaults, HuginProperties hugin) {
        MuninProperties.Http httpDefaults = defaults.getHttp();
        this.http = new Http(
                store.text("munin.http.userAgent", httpDefaults::getUserAgent,
                        "Identifies the crawler to publishers. Name the project and give "
                                + "somebody a way to make contact; impersonating a browser "
                                + "is how a crawler ends up blocked."),
                store.duration("munin.http.readTimeout", httpDefaults::getReadTimeout,
                        "How long one request may take to answer."),
                store.number("munin.http.maxBodyBytes", httpDefaults::getMaxBodyBytes,
                        "Hard cap on a downloaded body, in bytes. The stream is cut rather "
                                + "than buffered, so this bounds a URL that turns out to be a "
                                + "video file."),
                store.duration("munin.http.minHostInterval", httpDefaults::getMinHostInterval,
                        "Minimum spacing between two requests to the same host, across all "
                                + "workers. The single most important politeness control."));

        MuninProperties.Feed feedDefaults = defaults.getFeed();
        this.feed = new Feed(
                store.bool("munin.feed.enabled", feedDefaults::isEnabled,
                        "Whether feeds are polled at all. Checked at the start of each "
                                + "round, so switching it off stops the next round rather "
                                + "than the one in flight."),
                store.integer("munin.feed.batchSize", feedDefaults::getBatchSize,
                        "Sources claimed per round, which is also how many polls are in "
                                + "flight at once."),
                store.duration("munin.feed.defaultInterval", feedDefaults::getDefaultInterval,
                        "Interval a newly created source starts with. Applies to sources "
                                + "added from now on."),
                store.duration("munin.feed.minInterval", feedDefaults::getMinInterval,
                        "Floor for the adaptive interval — no source is polled more often "
                                + "than this. Applied the next time a source is rescheduled."),
                store.duration("munin.feed.maxInterval", feedDefaults::getMaxInterval,
                        "Ceiling for the adaptive interval. Applied the next time a source "
                                + "is rescheduled."),
                store.integer("munin.feed.busyThreshold", feedDefaults::getBusyThreshold,
                        "New articles in one poll at or above which the interval is halved, "
                                + "on the assumption that the feed window may have rolled "
                                + "over and entries were missed."),
                store.duration("munin.feed.maxFailureInterval",
                        feedDefaults::getMaxFailureInterval,
                        "Cap on the backoff a failing source reaches. Failures stretch the "
                                + "interval but never disable the source."),
                store.duration("munin.feed.claimLease", feedDefaults::getClaimLease,
                        "How long a claimed source stays leased — the time a source is "
                                + "stuck if a worker dies mid-fetch."),
                store.integer("munin.feed.maxItemsPerFeed", feedDefaults::getMaxItemsPerFeed,
                        "Entries read from a single feed document."),
                store.integer("munin.feed.maxSummaryChars", feedDefaults::getMaxSummaryChars,
                        "Teaser length kept from a feed entry."),
                store.duration("munin.feed.maxFutureSkew", feedDefaults::getMaxFutureSkew,
                        "How far a publication date may lie in the future before it is "
                                + "treated as wrong and dropped."));

        MuninProperties.Content contentDefaults = defaults.getContent();
        this.content = new Content(
                store.bool("munin.content.enabled", contentDefaults::isEnabled,
                        "Whether article bodies are fetched. Ingest queues every article "
                                + "either way, so switching this on works through whatever "
                                + "has accumulated."),
                store.integer("munin.content.batchSize", contentDefaults::getBatchSize,
                        "Articles claimed per round."),
                store.integer("munin.content.maxAttempts", contentDefaults::getMaxAttempts,
                        "Attempts before an article is marked FAILED."),
                store.duration("munin.content.retryDelay", contentDefaults::getRetryDelay,
                        "Delay before the first retry; doubles per attempt."),
                store.duration("munin.content.claimLease", contentDefaults::getClaimLease,
                        "Lease held on a claimed article."),
                store.integer("munin.content.minWordCount", contentDefaults::getMinWordCount,
                        "Below this word count the extraction counts as failed — a consent "
                                + "page extracts to a few dozen words, and storing that is "
                                + "worse than storing nothing."),
                store.integer("munin.content.maxTextChars", contentDefaults::getMaxTextChars,
                        "Characters kept from an extracted body."),
                store.bool("munin.content.respectRobots", contentDefaults::isRespectRobots,
                        "Whether robots.txt is fetched and obeyed."),
                store.duration("munin.content.robotsCacheTtl",
                        contentDefaults::getRobotsCacheTtl,
                        "How long a parsed robots.txt is reused."));

        MuninProperties.SourceList listDefaults = defaults.getSourceList();
        this.sourceList = new SourceList(
                store.bool("munin.source-list.enabled", listDefaults::isEnabled,
                        "Whether source lists are re-read."),
                store.duration("munin.source-list.defaultInterval",
                        listDefaults::getDefaultInterval,
                        "Refresh interval a newly created list starts with."),
                store.integer("munin.source-list.maxEntries", listDefaults::getMaxEntries,
                        "Entries accepted from one list document — a guard against a "
                                + "pathological file, not a policy."),
                store.integer("munin.source-list.batchSize", listDefaults::getBatchSize,
                        "Lists leased at a time within a round."),
                store.integer("munin.source-list.maxPerRound", listDefaults::getMaxPerRound,
                        "Ceiling on one round, so that a round ends. A fresh import is "
                                + "drained in one round rather than spread over an hour."),
                store.duration("munin.source-list.claimLease", listDefaults::getClaimLease,
                        "Lease held on a claimed list."));

        MuninProperties.Catalog catalogDefaults = defaults.getCatalog();
        this.catalog = new Catalog(
                store.bool("munin.catalog.enabled", catalogDefaults::isEnabled,
                        "Whether catalogues are re-read. Individual catalogues have their "
                                + "own switch in the console; this is the layer's."),
                store.duration("munin.catalog.defaultInterval",
                        catalogDefaults::getDefaultInterval,
                        "Refresh interval a newly created catalogue starts with."),
                store.duration("munin.catalog.claimLease", catalogDefaults::getClaimLease,
                        "Lease held on a claimed catalogue."));

        HuginProperties.Translation translationDefaults = hugin.getTranslation();
        this.translation = new Translation(
                store.bool("hugin.translation.enabled", translationDefaults::isEnabled,
                        "Whether the worker drains the translation backlog. Off leaves the "
                                + "queue filling, which is the reason this is separate from "
                                + "the pivot language."),
                store.text("hugin.translation.provider",
                        translationDefaults::getProvider,
                        "Which provider translates: 'vance-ode' calls a brain, 'gemini' calls "
                                + "Google's API directly. Empty picks the only one that is "
                                + "wired, and is an error state when several are. Resolved per "
                                + "article, so switching it is a setting rather than a deploy."),
                store.text("hugin.translation.pivotLanguage",
                        translationDefaults::getPivotLanguage,
                        "The one language everything is normalised into, as a BCP-47 "
                                + "primary subtag. Empty queues nothing. Decided at ingest, "
                                + "so a change applies to articles arriving from now on; "
                                + "for the ones already stored, re-evaluate the filter."),
                store.languages("hugin.translation.readableLanguages",
                        () -> SettingLanguages.normalise(
                                translationDefaults.getReadableLanguages()),
                        "Languages that need no translation, comma-separated — an article in "
                                + "one of these is marked SKIPPED at ingest instead of queued. "
                                + "The pivot language always counts as one of them. Decided at "
                                + "ingest, so a change applies to articles arriving from now "
                                + "on; re-evaluate the filter to apply it to stored ones."),
                store.bool("hugin.translation.translateSummary",
                        translationDefaults::isTranslateSummary,
                        "Whether the teaser is translated alongside the title. Both travel "
                                + "in one call, so this saves tokens rather than requests."),
                store.integer("hugin.translation.batchSize",
                        translationDefaults::getBatchSize,
                        "Articles claimed per round."),
                store.integer("hugin.translation.maxAttempts",
                        translationDefaults::getMaxAttempts,
                        "Attempts before an article is dropped from the backlog."),
                store.duration("hugin.translation.retryDelay",
                        translationDefaults::getRetryDelay,
                        "Delay before the first retry; doubles per attempt."),
                store.duration("hugin.translation.claimLease",
                        translationDefaults::getClaimLease,
                        "Lease held on a claimed article."),
                store.integer("hugin.translation.maxSourceChars",
                        translationDefaults::getMaxSourceChars,
                        "Longest source text handed to a provider in one go."));

        MuninProperties.Language languageDefaults = defaults.getLanguage();
        this.language = new Language(
                store.bool("munin.language.enabled", languageDefaults::isEnabled,
                        "Whether the language of an article is detected when the feed does "
                                + "not state it."),
                store.integer("munin.language.minChars", languageDefaults::getMinChars,
                        "Below this many characters of title plus teaser, detection is "
                                + "skipped and the language stays unknown — a confident "
                                + "wrong answer on a headline is worse than none."));

        HuginProperties.Category categoryDefaults = hugin.getCategory();
        MuninProperties.Category matchingDefaults = defaults.getCategory();
        this.category = new Category(
                store.bool("hugin.category.enabled", categoryDefaults::isEnabled,
                        "Whether unresolved category mappings are sent to a brain. The "
                                + "table-matching stage runs either way and costs nothing."),
                store.integer("hugin.category.batchSize", categoryDefaults::getBatchSize,
                        "Mappings resolved per round."),
                store.integer("hugin.category.maxAttempts", categoryDefaults::getMaxAttempts,
                        "Attempts before a mapping is left FAILED for a person to settle."),
                store.duration("hugin.category.retryDelay", categoryDefaults::getRetryDelay,
                        "Delay before the first retry; doubles per attempt."),
                store.duration("hugin.category.claimLease", categoryDefaults::getClaimLease,
                        "Lease held on a claimed mapping."),
                store.fraction("munin.category.acceptConfidence",
                        matchingDefaults::getAcceptConfidence,
                        "Confidence at which the matching stage's answer counts as resolved "
                                + "rather than as a guess."));

        HuginProperties.Gemini geminiDefaults = hugin.getGemini();
        this.gemini = new Gemini(
                store.text("hugin.gemini.model", geminiDefaults::getModel,
                        "The model, as Google's API names it — e.g. gemini-3.5-flash-lite for "
                                + "the cheap end. Which one answered is recorded per "
                                + "translation, so a change here stays visible afterwards."),
                store.fraction("hugin.gemini.temperature", geminiDefaults::getTemperature,
                        "Low on purpose: the same text coming back differently on every call "
                                + "would make two runs incomparable."),
                store.duration("hugin.gemini.timeout", geminiDefaults::getTimeout,
                        "Budget for one request to the model."));

        MuninProperties.Filter filterDefaults = defaults.getFilter();
        this.filter = new Filter(
                store.integer("munin.filter.maxPerRun", filterDefaults::getMaxPerRun,
                        "Cap on articles examined by one re-evaluation run. Reaching it is "
                                + "reported, and the next run continues where this one "
                                + "stopped."),
                store.integer("munin.filter.batchSize", filterDefaults::getBatchSize,
                        "Articles read per batch while walking the archive."));
    }
}
