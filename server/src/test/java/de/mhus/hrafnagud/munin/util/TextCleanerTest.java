package de.mhus.hrafnagud.munin.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class TextCleanerTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    @Test
    void stripHtml_removesMarkupAndDecodesEntities() {
        assertThat(TextCleaner.stripHtml("<p>Caf&eacute; &amp; <b>Bar</b></p>"))
                .isEqualTo("Café & Bar");
    }

    @Test
    void stripHtml_dropsScriptPayloads() {
        // Feeds carry tracking scripts in the description often enough that
        // a regex strip would leak JavaScript into the teaser.
        assertThat(TextCleaner.stripHtml("<p>Story</p><script>var x = 'boom';</script>"))
                .isEqualTo("Story");
    }

    @Test
    void stripHtml_ofBlank_isEmpty() {
        assertThat(TextCleaner.stripHtml(null)).isEmpty();
        assertThat(TextCleaner.stripHtml("   ")).isEmpty();
    }

    @Test
    void truncate_cutsAtAWordBoundary() {
        assertThat(TextCleaner.truncate("one two three four five", 12)).isEqualTo("one two…");
    }

    @Test
    void truncate_leavesShortTextAlone() {
        assertThat(TextCleaner.truncate("short", 100)).isEqualTo("short");
    }

    @Test
    void sanitizePublished_acceptsAPlausibleDate() {
        Instant published = NOW.minus(2, ChronoUnit.HOURS);
        assertThat(TextCleaner.sanitizePublished(published, NOW, 21600)).isEqualTo(published);
    }

    @Test
    void sanitizePublished_rejectsFarFutureDates() {
        // A publisher with a broken clock would otherwise permanently own
        // the top of any date-ordered view.
        Instant published = NOW.plus(30, ChronoUnit.DAYS);
        assertThat(TextCleaner.sanitizePublished(published, NOW, 21600)).isNull();
    }

    @Test
    void sanitizePublished_toleratesModestClockSkew() {
        Instant published = NOW.plus(1, ChronoUnit.HOURS);
        assertThat(TextCleaner.sanitizePublished(published, NOW, 21600)).isEqualTo(published);
    }

    @Test
    void sanitizePublished_rejectsEpochAndPreHistory() {
        assertThat(TextCleaner.sanitizePublished(Instant.EPOCH, NOW, 21600)).isNull();
    }

    @Test
    void sanitizePublished_ofNull_isNull() {
        assertThat(TextCleaner.sanitizePublished(null, NOW, 21600)).isNull();
    }

    @Test
    void normalizeLanguage_reducesToThePrimarySubtag() {
        assertThat(TextCleaner.normalizeLanguage("de-DE")).isEqualTo("de");
        assertThat(TextCleaner.normalizeLanguage("de_AT")).isEqualTo("de");
        assertThat(TextCleaner.normalizeLanguage("EN")).isEqualTo("en");
        assertThat(TextCleaner.normalizeLanguage("zh-Hans-CN")).isEqualTo("zh");
    }

    @Test
    void normalizeLanguage_rejectsNonTags() {
        assertThat(TextCleaner.normalizeLanguage("german")).isNull();
        assertThat(TextCleaner.normalizeLanguage("")).isNull();
        assertThat(TextCleaner.normalizeLanguage(null)).isNull();
        assertThat(TextCleaner.normalizeLanguage("x")).isNull();
    }

    @Test
    void wordCount_countsWhitespaceSeparatedTokens() {
        assertThat(TextCleaner.wordCount("one two  three\nfour")).isEqualTo(4);
        assertThat(TextCleaner.wordCount("  ")).isZero();
        assertThat(TextCleaner.wordCount(null)).isZero();
    }

    @Test
    void wordCount_japaneseText_isNotCountedAsAHandfulOfWords() {
        // Japanese uses no spaces. Counting tokens would score a full
        // article as one "word", and every length threshold downstream
        // would then reject every CJK article as a stub.
        String sentence = "市議会は火曜日の夜、交通計画を賛成多数で可決した。";

        assertThat(TextCleaner.wordCount(sentence)).isGreaterThan(8);
    }

    @Test
    void wordCount_chineseAndThai_areCountedTheSameWay() {
        assertThat(TextCleaner.wordCount("市议会星期二晚上通过了交通计划")).isGreaterThan(5);
        assertThat(TextCleaner.wordCount("สภาเทศบาลอนุมัติแผนการขนส่ง")).isGreaterThan(5);
    }

    @Test
    void wordCount_mixedScripts_countBothHalves() {
        // A Japanese article quoting an English name.
        int mixed = TextCleaner.wordCount("市議会は Daimler Truck について報じた");

        assertThat(mixed).isGreaterThan(TextCleaner.wordCount("Daimler Truck"));
    }

    @Test
    void wordCount_latinText_isUnaffectedByTheScriptHandling() {
        assertThat(TextCleaner.wordCount("The council approved the plan")).isEqualTo(5);
    }
}
