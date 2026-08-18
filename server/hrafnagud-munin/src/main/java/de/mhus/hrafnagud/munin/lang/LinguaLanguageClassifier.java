package de.mhus.hrafnagud.munin.lang;

import com.github.pemistahl.lingua.api.IsoCode639_1;
import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@link LanguageClassifier} backed by Lingua.
 *
 * <p>The detector is built on first use, not at startup. Loading the
 * n-gram models for all 75 languages takes seconds and hundreds of
 * megabytes; paying that during boot would make every restart slow and
 * every test that touches the context slower, for a component an installation
 * with {@code munin.language.enabled=false} never calls.
 *
 * <p>Low-accuracy mode is the default. It keeps only the highest n-gram
 * orders, which cuts the memory cost by an order of magnitude and is
 * indistinguishable on inputs above the configured minimum length — and
 * inputs below it are refused anyway.
 */
@Component
@Slf4j
public class LinguaLanguageClassifier implements LanguageClassifier {

    private final MuninProperties.Language config;

    /** Guarded by {@link #lock}; built on first {@link #detect} call. */
    private volatile @Nullable LanguageDetector detector;

    private final Object lock = new Object();

    public LinguaLanguageClassifier(MuninProperties properties) {
        this.config = properties.getLanguage();
    }

    @Override
    public @Nullable String detect(String text) {
        if (!config.isEnabled()) {
            return null;
        }
        String value = StringUtils.trimToEmpty(text);
        if (value.length() < config.getMinChars()) {
            return null;
        }
        Language detected = detector().detectLanguageOf(value);
        if (detected == Language.UNKNOWN) {
            return null;
        }
        IsoCode639_1 iso = detected.getIsoCode639_1();
        return iso == IsoCode639_1.NONE ? null : iso.toString().toLowerCase(Locale.ROOT);
    }

    private LanguageDetector detector() {
        LanguageDetector current = detector;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            if (detector == null) {
                long start = System.currentTimeMillis();
                detector = build();
                log.info("Language detector ready in {} ms (lowAccuracy={})",
                        System.currentTimeMillis() - start, config.isLowAccuracyMode());
            }
            return detector;
        }
    }

    private LanguageDetector build() {
        LanguageDetectorBuilder builder = builderFor(config.getLanguages());
        if (config.isLowAccuracyMode()) {
            builder = builder.withLowAccuracyMode();
        }
        return builder.build();
    }

    /**
     * Restricting the candidate set both speeds detection up and makes it
     * more accurate, so a configured list is honoured — but Lingua needs at
     * least two candidates to compare, and a one-entry list would be a
     * configuration mistake that silently turns into "everything is
     * {@code de}". Fewer than two falls back to all languages with a warning.
     */
    private LanguageDetectorBuilder builderFor(List<String> configured) {
        Set<IsoCode639_1> codes = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String tag : configured) {
            String normalized = StringUtils.trimToEmpty(tag).toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            try {
                codes.add(IsoCode639_1.valueOf(normalized));
            } catch (IllegalArgumentException e) {
                unknown.add(tag);
            }
        }
        if (!unknown.isEmpty()) {
            log.warn("Ignoring unknown language codes in munin.language.languages: {}", unknown);
        }
        if (codes.size() < 2) {
            if (!codes.isEmpty()) {
                log.warn("munin.language.languages needs at least two usable codes"
                        + " — falling back to all languages");
            }
            return LanguageDetectorBuilder.fromAllLanguages();
        }
        return LanguageDetectorBuilder.fromIsoCodes639_1(codes.toArray(new IsoCode639_1[0]));
    }
}
