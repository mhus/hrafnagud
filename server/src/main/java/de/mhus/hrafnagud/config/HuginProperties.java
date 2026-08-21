package de.mhus.hrafnagud.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything tunable about the steps that <em>think</em> — the ones that hand
 * text to a model and store what comes back.
 *
 * <p>Separate from {@link MuninProperties} because the two answer to different
 * budgets. Munin spends requests against publishers who did not ask to be
 * crawled, so its defaults are chosen for politeness; Hugin spends model time
 * somebody pays for, so its workers are <b>off by default</b> and stay off
 * until an operator decides otherwise. Nothing here starts because a service
 * was deployed.
 *
 * <h2>This is the default layer, not the value</h2>
 * As with Munin's properties: what is below is what a setting
 * ({@link de.mhus.hrafnagud.settings.Settings}) falls back to when no override
 * is stored. Read straight from here are only the values consumed while the
 * service starts — the tick cadences, which are baked into {@code @Scheduled}.
 * See {@code specs/settings.md} §3.
 */
@ConfigurationProperties(prefix = "hugin")
@Data
public class HuginProperties {

    private final Translation translation = new Translation();
    private final Category category = new Category();

    /**
     * The translation queue.
     *
     * <p>Munin owns the queue and the storage; it does not own the engine.
     * Which service performs a translation is decided by whoever supplies a
     * {@code TranslationProvider} — with none wired, articles simply
     * accumulate a backlog and nothing works it, which is a legible state
     * rather than a broken one.
     */
    @Data
    public static class Translation {

        /**
         * Whether anything works the translation backlog.
         *
         * <p>Off by default, like the body fetch and unlike the ticks that
         * only talk to feeds: a translation costs somebody else's model time
         * and somebody's money, and that is a decision an operator makes
         * rather than inherits from a deployment.
         *
         * <p>Switching it off stops the <em>worker</em>, not the queue:
         * whether an article is queued at all is decided at ingest by
         * {@link #pivotLanguage} and {@link #readableLanguages}. A pivot set
         * and this off means a backlog that grows with nothing draining it —
         * a legible state, and the startup log says so.
         */
        private boolean enabled = false;

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
         * Languages that need no translation, as BCP-47 primary subtags.
         *
         * <p>An article in one of these is marked {@code SKIPPED} at ingest
         * rather than queued. {@link #pivotLanguage} always counts as one of
         * them — translating German into German costs a call and returns what
         * it was given — so this list is for the <em>other</em> languages a
         * reader here can already read.
         *
         * <p>Empty is the honest default: it says nothing about what anybody
         * reads, and translates everything that is not already the pivot.
         */
        private List<String> readableLanguages = new ArrayList<>();

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

    /**
     * Category resolution — the second stage of deciding what a publisher's
     * section name means, the one that asks a brain.
     *
     * <p>The first stage is string comparison against a bundled table, costs
     * nothing and runs regardless; its one knob is
     * {@code munin.category.acceptConfidence}, because matching stored
     * mappings is Munin's work. This block is only the worker that spends
     * model time on what matching could not settle.
     */
    @Data
    public static class Category {

        /** Whether anything works the mapping backlog. Off by default. */
        private boolean enabled = false;

        private Duration tickInterval = Duration.ofSeconds(30);

        /** Mappings resolved per round. */
        private int batchSize = 10;

        /** Attempts before a mapping is left as {@code FAILED} for a person. */
        private int maxAttempts = 3;

        private Duration retryDelay = Duration.ofMinutes(10);

        private Duration claimLease = Duration.ofMinutes(5);
    }
}
