package de.mhus.hrafnagud.munin.web;

import de.mhus.hrafnagud.api.article.ArticleContentDto;
import de.mhus.hrafnagud.api.article.ArticleDto;
import de.mhus.hrafnagud.api.article.ArticleImageDto;
import de.mhus.hrafnagud.api.article.ArticleTranslationDto;
import de.mhus.hrafnagud.api.source.SourceDto;
import de.mhus.hrafnagud.api.source.SourceListDto;
import de.mhus.hrafnagud.munin.article.ArticleContentDocument;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleImage;
import de.mhus.hrafnagud.munin.article.ArticleTranslation;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import de.mhus.hrafnagud.munin.sourcelist.SourceListDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * Documents to DTOs.
 *
 * <p>One direction only. Requests are not mapped back into documents here —
 * creating and patching an entity involves defaulting, normalisation and
 * uniqueness rules that belong to the owning service, and a mapper that did
 * it would be that logic in the wrong place.
 */
public final class MuninMapper {

    private MuninMapper() {
    }

    public static SourceDto toDto(SourceDocument source) {
        return SourceDto.builder()
                .name(source.getName())
                .title(source.getTitle())
                .type(source.getType())
                .url(source.getUrl())
                .siteUrl(source.getSiteUrl())
                .enabled(source.isEnabled())
                .language(source.getLanguage())
                .country(source.getCountry())
                .categories(new ArrayList<>(source.getCategories()))
                .origin(source.getOrigin())
                .originListName(source.getOriginListName())
                .lockedFields(new ArrayList<>(source.getLockedFields()))
                .lastSeenInListAt(source.getLastSeenInListAt())
                .fetchIntervalSeconds(source.getFetchIntervalSeconds())
                .nextFetchAt(source.getNextFetchAt())
                .lastFetchAt(source.getLastFetchAt())
                .lastOutcome(source.getLastOutcome())
                .lastError(source.getLastError())
                .consecutiveFailures(source.getConsecutiveFailures())
                .articleCount(source.getArticleCount())
                .lastArticleAt(source.getLastArticleAt())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .build();
    }

    public static SourceListDto toDto(SourceListDocument list) {
        return SourceListDto.builder()
                .name(list.getName())
                .title(list.getTitle())
                .type(list.getType())
                .url(list.getUrl())
                .enabled(list.isEnabled())
                .defaultLanguage(list.getDefaultLanguage())
                .defaultCountry(list.getDefaultCountry())
                .defaultCategories(new ArrayList<>(list.getDefaultCategories()))
                .defaultFetchIntervalSeconds(list.getDefaultFetchIntervalSeconds())
                .missingSourcePolicy(list.getMissingSourcePolicy())
                .refreshIntervalSeconds(list.getRefreshIntervalSeconds())
                .nextRefreshAt(list.getNextRefreshAt())
                .lastRefreshAt(list.getLastRefreshAt())
                .lastOutcome(list.getLastOutcome())
                .lastError(list.getLastError())
                .consecutiveFailures(list.getConsecutiveFailures())
                .lastReport(list.getLastReport())
                .createdAt(list.getCreatedAt())
                .updatedAt(list.getUpdatedAt())
                .build();
    }

    public static ArticleDto toDto(ArticleDocument article) {
        Map<String, ArticleTranslationDto> translations = new LinkedHashMap<>();
        article.getTranslations().forEach((language, translation) ->
                translations.put(language, toDto(translation)));

        return ArticleDto.builder()
                .id(StringUtils.defaultString(article.getId()))
                .url(article.getUrl())
                .originalUrl(article.getOriginalUrl())
                .title(article.getTitle())
                .summary(article.getSummary())
                .author(article.getAuthor())
                .imageUrl(article.getImageUrl())
                .language(article.getLanguage())
                .languageSource(article.getLanguageSource())
                .categories(new ArrayList<>(article.getCategories()))
                .sources(new ArrayList<>(article.getSourceNames()))
                .firstSource(article.getFirstSourceName())
                .publishedAt(article.getPublishedAt())
                .firstSeenAt(article.getFirstSeenAt())
                .lastSourceAddedAt(article.getLastSourceAddedAt())
                .contentStatus(article.getContentStatus())
                .contentFetchedAt(article.getContentFetchedAt())
                .contentWordCount(article.getContentWordCount())
                .contentError(article.getContentError())
                .translations(translations)
                .build();
    }

    private static ArticleTranslationDto toDto(ArticleTranslation translation) {
        return ArticleTranslationDto.builder()
                .title(translation.getTitle())
                .summary(translation.getSummary())
                .engine(translation.getEngine())
                .translatedAt(translation.getTranslatedAt())
                .build();
    }

    public static ArticleContentDto toDto(ArticleContentDocument content) {
        return ArticleContentDto.builder()
                .articleId(content.getArticleId())
                .text(content.getText())
                .wordCount(content.getWordCount())
                .extractedTitle(content.getExtractedTitle())
                .imageUrl(content.getImageUrl())
                .images(content.getImages().stream().map(MuninMapper::toDto).toList())
                .author(content.getAuthor())
                .publishedAt(content.getPublishedAt())
                .language(content.getLanguage())
                .canonicalUrl(content.getCanonicalUrl())
                .finalUrl(content.getFinalUrl())
                .extractor(content.getExtractor())
                .fetchedAt(content.getFetchedAt())
                .translations(new LinkedHashMap<>(content.getTranslations()))
                .build();
    }

    private static ArticleImageDto toDto(ArticleImage image) {
        return ArticleImageDto.builder()
                .url(image.getUrl())
                .caption(image.getCaption())
                .role(image.getRole())
                .width(image.getWidth())
                .height(image.getHeight())
                .build();
    }
}
