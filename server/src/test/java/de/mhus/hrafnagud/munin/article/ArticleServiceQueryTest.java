package de.mhus.hrafnagud.munin.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * How the queries are assembled — no database needed, because the bug this pins
 * happened during construction.
 *
 * <p>{@code new Criteria().andOperator(…)} yields a criteria whose key is
 * {@code null}, and {@code Query} keys its criteria map by exactly that. A
 * second keyless criteria added afterwards therefore threw
 * {@code InvalidMongoDbApiUsageException} („you can't add a second 'null'
 * criteria") — which meant that any <em>filtered</em> stream returned its first
 * page and then failed on every page after it. Unfiltered worked, which is why
 * it went unnoticed.
 */
class ArticleServiceQueryTest {

    private MongoTemplate mongoTemplate;
    private ArticleService service;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        MuninProperties properties = new MuninProperties();
        service = new ArticleService(
                mock(ArticleRepository.class),
                mock(ArticleContentRepository.class),
                mock(EnrichmentService.class),
                mongoTemplate,
                new de.mhus.hrafnagud.munin.place.PlaceRegistry(),
                mock(de.mhus.hrafnagud.munin.category.CategoryMappingService.class),
                mock(de.mhus.hrafnagud.munin.filter.ArticleFilterService.class),
                properties);
        when(mongoTemplate.find(any(Query.class), eq(ArticleDocument.class)))
                .thenReturn(List.of());
    }

    @Test
    void pageByPublished_withFilterAndCursor_buildsOneQuery() {
        ArticleQuery filter = ArticleQuery.builder().sourceName("tagesschau").build();
        ArticleCursor cursor = new ArticleCursor(
                Instant.parse("2026-08-19T09:00:00Z"), "6543210fedcba98765432100");

        // The call itself is the assertion: this used to throw.
        service.pageByPublished(filter, cursor, false, 20);

        // Three conditions, all folded into one $and: the source filter, the
        // "has a publishedAt" guard and the cursor bound.
        assertThat(andParts(capturedQuery())).hasSize(3);
    }

    @Test
    void pageByPublished_withSinceAndCursor_buildsOneQuery() {
        // The same collision through the other door: `since` alone makes the
        // filter half non-empty, so `selector=all` was affected too as soon as a
        // time bound was set.
        ArticleQuery filter = ArticleQuery.builder()
                .publishedSince(Instant.parse("2026-08-01T00:00:00Z"))
                .build();
        ArticleCursor cursor = new ArticleCursor(
                Instant.parse("2026-08-19T09:00:00Z"), "6543210fedcba98765432100");

        service.pageByPublished(filter, cursor, true, 20);

        assertThat(andParts(capturedQuery())).hasSize(3);
    }

    @Test
    void pageByPublished_withoutFilter_stillCarriesTheCursorBound() {
        ArticleCursor cursor = new ArticleCursor(
                Instant.parse("2026-08-19T09:00:00Z"), "6543210fedcba98765432100");

        service.pageByPublished(ArticleQuery.builder().build(), cursor, false, 20);

        // No filter, so two: the guard and the cursor bound.
        assertThat(andParts(capturedQuery())).hasSize(2);
    }

    @Test
    void pageByPublished_withoutCursor_startsAtTheEnd() {
        service.pageByPublished(
                ArticleQuery.builder().sourceName("tagesschau").build(), null, false, 20);

        assertThat(andParts(capturedQuery())).hasSize(2);
    }

    /**
     * The {@code $and} entries of a query.
     *
     * <p>Asserted structurally rather than as JSON: rendering the query to a
     * string needs a codec for {@link Instant}, which only a real template
     * registers — and the thing under test is the shape, not the encoding.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> andParts(Query query) {
        Object and = query.getQueryObject().get("$and");
        assertThat(and).isInstanceOf(List.class);
        return (List<Object>) and;
    }

    private Query capturedQuery() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(captor.capture(), eq(ArticleDocument.class));
        return captor.getValue();
    }
}
