package de.mhus.hrafnagud.munin.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.api.article.LanguageSource;
import de.mhus.hrafnagud.api.filter.FilterOutcome;
import de.mhus.hrafnagud.api.filter.FilterOutcomes;
import de.mhus.hrafnagud.munin.category.CategoryMappingService;
import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.settings.TestSettings;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.hrafnagud.munin.filter.ArticleFilterService;
import de.mhus.hrafnagud.munin.lang.LanguageResolver;
import de.mhus.hrafnagud.munin.place.PlaceRegistry;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import java.time.Instant;
import java.util.List;
import org.bson.BsonString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * When a category is counted.
 *
 * <p>{@code useCount} is documented as "how many articles carry this category"
 * and it orders both the console list and the resolution queue, which is where
 * model spend goes. Ingest runs for every candidate in every feed window on
 * every poll — most of them entries already stored — so counting per call
 * measured how often a feed is polled instead: a five-minute news feed
 * contributed some 288 counts a day per entry against a weekly blog's one.
 */
class ArticleServiceCategoryCountTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    private MongoTemplate mongoTemplate;
    private CategoryMappingService categories;
    private ArticleService service;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        categories = mock(CategoryMappingService.class);
        when(categories.resolve(anyList(), any())).thenReturn(
                new CategoryMappingService.CategoryResolution(
                        List.of("medtop:15000000"), List.of("sport")));

        ArticleFilterService filters = mock(ArticleFilterService.class);
        when(filters.evaluate(any())).thenReturn(
                new FilterOutcomes(FilterOutcome.defaultAccept(),
                        FilterOutcome.defaultAccept()));

        service = new ArticleService(
                mock(ArticleRepository.class),
                mock(ArticleContentRepository.class),
                mock(EnrichmentService.class),
                mongoTemplate,
                new PlaceRegistry(),
                categories,
                filters,
                TestSettings.defaults());
    }

    @Test
    void aStoredArticleCountsItsCategories() {
        stubUpsert(UpdateResult.acknowledged(0, 0L, new BsonString("new-id")));

        assertThat(ingest()).isEqualTo(ArticleService.IngestOutcome.CREATED);
        verify(categories).countUsage(List.of("sport"), NOW);
    }

    @Test
    void aSecondSourceForTheSameArticleCountsToo() {
        // Bounded — once per (article, source) — and it is the moment the story
        // spread, which is a real article carrying the category.
        stubUpsert(UpdateResult.acknowledged(1, 1L, null));

        assertThat(ingest()).isEqualTo(ArticleService.IngestOutcome.DUPLICATE_CROSS_SOURCE);
        verify(categories).countUsage(List.of("sport"), NOW);
    }

    @Test
    void aRedeliveryFromTheSameSourceCountsNothing() {
        // This is the one that repeats on every poll for as long as the entry
        // stays in the feed window.
        stubUpsert(UpdateResult.acknowledged(1, 0L, null));

        assertThat(ingest()).isEqualTo(ArticleService.IngestOutcome.DUPLICATE_SAME_SOURCE);
        verify(categories, never()).countUsage(any(), any());
    }

    @Test
    void resolvingStillHappensForEveryCandidate() {
        // Learning what exists must not wait for a store: the mapping row for
        // an unseen category is created here, and the article being a duplicate
        // does not make its category unknown.
        stubUpsert(UpdateResult.acknowledged(1, 0L, null));

        ingest();

        verify(categories).resolve(List.of("Sport"), NOW);
    }

    private ArticleService.IngestOutcome ingest() {
        ArticleCandidate candidate = ArticleCandidate.builder()
                .url("https://example.org/a")
                .originalUrl("https://example.org/a")
                .title("A title")
                .publishedAt(NOW)
                .category("Sport")
                .build();
        SourceDocument source = SourceDocument.builder()
                .name("example")
                .language("en")
                .build();
        return service.ingest(candidate, source,
                new LanguageResolver.Resolution("en", LanguageSource.FEED),
                ContentStatus.PENDING, NOW);
    }

    private void stubUpsert(UpdateResult result) {
        when(mongoTemplate.upsert(any(Query.class), any(Update.class),
                eq(ArticleDocument.class))).thenReturn(result);
    }
}
