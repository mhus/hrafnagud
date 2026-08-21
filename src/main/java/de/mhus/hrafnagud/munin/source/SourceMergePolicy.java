package de.mhus.hrafnagud.munin.source;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.Builder;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Merges what a source list says about a feed into the registry entry,
 * without undoing what a human has said about the same feed.
 *
 * <p>This is the whole reason source lists are usable at all. A directory
 * is a good way to acquire a thousand feeds and a bad way to keep them:
 * sooner or later an operator disables a feed that publishes garbage,
 * corrects a language that the directory has wrong, or narrows a category.
 * If the next refresh reverts that, the operator learns not to bother, and
 * the registry degrades to whatever upstream says.
 *
 * <p>So every field a human writes is recorded in
 * {@link SourceDocument#getLockedFields()} and becomes off-limits to the
 * list. The list keeps ownership of everything else, which is the common
 * case — titles change, feeds move between sections, and those updates
 * should keep flowing.
 *
 * <p>Pure: mutates the document handed in and reports what it changed,
 * with no database and no clock.
 */
public final class SourceMergePolicy {

    public static final String FIELD_TITLE = "title";
    public static final String FIELD_SITE_URL = "siteUrl";
    public static final String FIELD_CATEGORIES = "categories";
    public static final String FIELD_LANGUAGE = "language";
    public static final String FIELD_COUNTRY = "country";
    public static final String FIELD_ENABLED = "enabled";
    public static final String FIELD_URL = "url";
    public static final String FIELD_INTERVAL = "fetchIntervalSeconds";

    private SourceMergePolicy() {
    }

    /** Defaults a source list applies to everything it imports. */
    @Value
    @Builder
    public static class Defaults {

        @Nullable String language;

        @Nullable String country;

        /** Prepended in front of whatever the list document declares. */
        @lombok.Singular
        List<String> categories;
    }

    /**
     * Applies {@code candidate} to {@code target}, skipping locked fields.
     *
     * @return names of the fields actually changed; empty means the refresh
     *         had nothing to do for this source
     */
    public static Set<String> apply(SourceDocument target, SourceCandidate candidate,
            Defaults defaults) {

        Set<String> changed = new LinkedHashSet<>();
        Set<String> locked = target.getLockedFields();

        if (!locked.contains(FIELD_TITLE)
                && StringUtils.isNotBlank(candidate.getTitle())
                && !candidate.getTitle().equals(target.getTitle())) {
            target.setTitle(candidate.getTitle());
            changed.add(FIELD_TITLE);
        }

        if (!locked.contains(FIELD_SITE_URL)
                && StringUtils.isNotBlank(candidate.getSiteUrl())
                && !Objects.equals(candidate.getSiteUrl(), target.getSiteUrl())) {
            target.setSiteUrl(candidate.getSiteUrl());
            changed.add(FIELD_SITE_URL);
        }

        if (!locked.contains(FIELD_CATEGORIES)) {
            List<String> merged = mergeCategories(defaults.getCategories(),
                    candidate.getCategories());
            if (!merged.equals(target.getCategories())) {
                target.setCategories(merged);
                changed.add(FIELD_CATEGORIES);
            }
        }

        if (!locked.contains(FIELD_LANGUAGE)) {
            String language = StringUtils.defaultIfBlank(candidate.getLanguage(),
                    defaults.getLanguage());
            if (StringUtils.isNotBlank(language) && !Objects.equals(language, target.getLanguage())) {
                target.setLanguage(language);
                changed.add(FIELD_LANGUAGE);
            }
        }

        if (!locked.contains(FIELD_COUNTRY)) {
            String country = StringUtils.defaultIfBlank(candidate.getCountry(),
                    defaults.getCountry());
            if (StringUtils.isNotBlank(country) && !Objects.equals(country, target.getCountry())) {
                target.setCountry(country);
                changed.add(FIELD_COUNTRY);
            }
        }

        // A source the list still carries is a source the list wants polled.
        // Re-enabling is how a feed that was dropped and later restored comes
        // back on its own — unless a human disabled it, which is locked.
        if (!locked.contains(FIELD_ENABLED) && !target.isEnabled()) {
            target.setEnabled(true);
            changed.add(FIELD_ENABLED);
        }

        return changed;
    }

    /**
     * List defaults first, then the list document's own categories,
     * deduplicated and order-preserving.
     */
    static List<String> mergeCategories(List<String> defaults, List<String> declared) {
        Set<String> merged = new LinkedHashSet<>();
        for (String value : defaults) {
            if (StringUtils.isNotBlank(value)) {
                merged.add(value.trim());
            }
        }
        for (String value : declared) {
            if (StringUtils.isNotBlank(value)) {
                merged.add(value.trim());
            }
        }
        return new ArrayList<>(merged);
    }
}
