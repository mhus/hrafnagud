package de.mhus.hrafnagud.munin.web;

import de.mhus.hrafnagud.api.article.ArticleContentDto;
import de.mhus.hrafnagud.api.article.ArticleDto;
import de.mhus.hrafnagud.api.article.ArticleImageDto;
import de.mhus.hrafnagud.api.article.ArticleTranslationDto;
import de.mhus.hrafnagud.api.catalog.CatalogDto;
import de.mhus.hrafnagud.api.enrichment.EnrichmentDto;
import de.mhus.hrafnagud.api.source.SourceDto;
import de.mhus.hrafnagud.api.source.SourceListDto;
import de.mhus.hrafnagud.munin.article.ArticleContentDocument;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleImage;
import de.mhus.hrafnagud.munin.catalog.SourceCatalogDocument;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentDocument;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import de.mhus.hrafnagud.munin.sourcelist.SourceListDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

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
                .fetchProfile(source.getFetchProfile())
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
                .fetchProfile(list.getFetchProfile())
                .missingSourcePolicy(list.getMissingSourcePolicy())
                .refreshIntervalSeconds(list.getRefreshIntervalSeconds())
                .nextRefreshAt(list.getNextRefreshAt())
                .lastRefreshAt(list.getLastRefreshAt())
                .lastOutcome(list.getLastOutcome())
                .lastError(list.getLastError())
                .consecutiveFailures(list.getConsecutiveFailures())
                .originCatalogName(list.getOriginCatalogName())
                .lastSeenInCatalogAt(list.getLastSeenInCatalogAt())
                .lastReport(list.getLastReport())
                .createdAt(list.getCreatedAt())
                .updatedAt(list.getUpdatedAt())
                .build();
    }

    /**
     * @param listCount lists this catalogue owns, counted by the caller — the
     *                  document does not carry it, because a denormalised
     *                  count that only a refresh updates would be wrong for
     *                  most of a day.
     */
    public static CatalogDto toDto(SourceCatalogDocument catalog, long listCount) {
        return CatalogDto.builder()
                .name(catalog.getName())
                .title(catalog.getTitle())
                .type(catalog.getType())
                .url(catalog.getUrl())
                .params(new LinkedHashMap<>(catalog.getParams()))
                .enabled(catalog.isEnabled())
                .include(new ArrayList<>(catalog.getInclude()))
                .exclude(new ArrayList<>(catalog.getExclude()))
                .listRefreshIntervalSeconds(catalog.getListRefreshIntervalSeconds())
                .fetchProfile(catalog.getFetchProfile())
                .sourceFetchIntervalSeconds(catalog.getSourceFetchIntervalSeconds())
                .missingListPolicy(catalog.getMissingListPolicy())
                .refreshIntervalSeconds(catalog.getRefreshIntervalSeconds())
                .nextRefreshAt(catalog.getNextRefreshAt())
                .lastRefreshAt(catalog.getLastRefreshAt())
                .lastOutcome(catalog.getLastOutcome())
                .lastError(catalog.getLastError())
                .consecutiveFailures(catalog.getConsecutiveFailures())
                .listCount(listCount)
                .lastReport(catalog.getLastReport())
                .createdAt(catalog.getCreatedAt())
                .updatedAt(catalog.getUpdatedAt())
                .build();
    }

    public static ArticleDto toDto(ArticleDocument article) {
        return toDto(article, null);
    }

    /**
     * @param translation newest {@code TRANSLATION} enrichment for this
     *                    article, or {@code null} when there is none or
     *                    the caller did not ask for it
     */
    public static ArticleDto toDto(ArticleDocument article,
            @Nullable EnrichmentDocument translation) {
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
                .topicIds(new ArrayList<>(article.getTopicIds()))
                .originCountry(article.getOriginCountry())
                .originPlaceIds(new ArrayList<>(article.getOriginPlaceIds()))
                .sources(new ArrayList<>(article.getSourceNames()))
                .firstSource(article.getFirstSourceName())
                .publishedAt(article.getPublishedAt())
                .firstSeenAt(article.getFirstSeenAt())
                .lastSourceAddedAt(article.getLastSourceAddedAt())
                .contentStatus(article.getContentStatus())
                .contentFetchedAt(article.getContentFetchedAt())
                .contentWordCount(article.getContentWordCount())
                .contentError(article.getContentError())
                .translationStatus(article.getTranslationStatus())
                .translationError(article.getTranslationError())
                .contentPolicy(article.getContentPolicy())
                .contentPolicyRule(article.getContentPolicyRule())
                .translationPolicy(article.getTranslationPolicy())
                .translationPolicyRule(article.getTranslationPolicyRule())
                .translation(translation == null ? null : toTranslationDto(translation))
                .build();
    }

    /** Flattens a translation enrichment for the article view. */
    private static ArticleTranslationDto toTranslationDto(EnrichmentDocument enrichment) {
        Map<String, Object> content = enrichment.getContent();
        return ArticleTranslationDto.builder()
                .title(str(content.get("title")))
                .summary(StringUtils.trimToNull(str(content.get("summary"))))
                .language(enrichment.getLanguage())
                .producer(enrichment.getProducer())
                .model(enrichment.getModel())
                .translatedAt(enrichment.getCreatedAt())
                .build();
    }

    /** Full record of one processing run. */
    public static EnrichmentDto toDto(EnrichmentDocument enrichment) {
        return EnrichmentDto.builder()
                .id(StringUtils.defaultString(enrichment.getId()))
                .articleId(enrichment.getArticleId())
                .type(enrichment.getType())
                .producer(enrichment.getProducer())
                .model(enrichment.getModel())
                .language(enrichment.getLanguage())
                .createdAt(enrichment.getCreatedAt())
                .content(new LinkedHashMap<>(enrichment.getContent()))
                .build();
    }

    private static String str(@Nullable Object value) {
        return value instanceof String s ? s : "";
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
