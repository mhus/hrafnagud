package de.mhus.hrafnagud.munin.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.api.article.TranslationStatus;
import de.mhus.hrafnagud.config.HuginProperties;
import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.munin.category.CategoryMappingService;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.hrafnagud.munin.filter.ArticleFilterService;
import de.mhus.hrafnagud.munin.place.PlaceRegistry;
import de.mhus.hrafnagud.settings.TestSettings;
import com.mongodb.client.result.UpdateResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * The shape of the translation queue's two queries.
 *
 * <p>No database: what is pinned here is how the claim is <em>built</em> —
 * newest first, still only what is due — and that the expiry sweep touches
 * exactly the articles the claim will never reach. Both are decisions rather
 * than mechanics, and both are invisible in a status field once wrong.
 */
class TranslationQueueOrderTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private MongoTemplate mongoTemplate;
    private HuginProperties hugin;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        hugin = new HuginProperties();
    }

    private ArticleService service(Map<String, String> overrides) {
        return new ArticleService(
                mock(ArticleRepository.class),
                mock(ArticleContentRepository.class),
                mock(EnrichmentService.class),
                mongoTemplate,
                new PlaceRegistry(),
                mock(CategoryMappingService.class),
                mock(ArticleFilterService.class),
                TestSettings.build(new MuninProperties(), hugin, overrides));
    }

    /**
     * A news archive that cannot keep up should be current rather than
     * complete: the newest article is the one somebody is about to read.
     */
    @Test
    void the_claim_takes_the_newest_article_first() {
        ArticleService service = service(Map.of());
        when(mongoTemplate.findAndModify(any(Query.class), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(ArticleDocument.class))).thenReturn(null);

        service.claimTranslationDue(NOW, 5);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findAndModify(query.capture(), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(ArticleDocument.class));
        assertThat(query.getValue().getSortObject().get("firstSeenAt")).isEqualTo(-1);
        // The due predicate stays: a leased or backed-off article is not ready
        // however new it is.
        Document criteria = query.getValue().getQueryObject();
        assertThat(criteria.get("translationStatus")).isEqualTo(TranslationStatus.PENDING);
        assertThat(criteria.get("translationNextAttemptAt", Document.class).get("$lte"))
                .isEqualTo(NOW);
    }

    @Test
    void the_sweep_drops_what_the_claim_will_never_reach() {
        ArticleService service = service(Map.of("hugin.translation.maxAge", "P3D"));
        when(mongoTemplate.updateMulti(any(Query.class), any(UpdateDefinition.class),
                eq(ArticleDocument.class)))
                .thenReturn(UpdateResult.acknowledged(4, 4L, null));

        assertThat(service.expireTranslationBacklog(NOW, Duration.ofDays(3))).isEqualTo(4);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate).updateMulti(query.capture(), update.capture(),
                eq(ArticleDocument.class));

        Document criteria = query.getValue().getQueryObject();
        assertThat(criteria.get("translationStatus")).isEqualTo(TranslationStatus.PENDING);
        // Three days back, not "old" by some other measure.
        assertThat(criteria.get("firstSeenAt", Document.class).get("$lt"))
                .isEqualTo(NOW.minus(Duration.ofDays(3)));

        Document written = ((Update) update.getValue()).getUpdateObject();
        Document set = written.get("$set", Document.class);
        // SKIPPED rather than FAILED: nothing failed, the archive decided.
        assertThat(set.get("translationStatus")).isEqualTo(TranslationStatus.SKIPPED);
        // The reason goes where the console already looks for one.
        assertThat((String) set.get("translationError")).contains("newest first");
        // And out of the partial index, so the backlog number means something.
        assertThat(written.get("$unset", Document.class))
                .containsKey("translationNextAttemptAt");
    }

    @Test
    void switching_the_cutoff_off_touches_nothing() {
        ArticleService service = service(Map.of());

        assertThat(service.expireTranslationBacklog(NOW, Duration.ZERO)).isZero();

        verify(mongoTemplate, never()).updateMulti(any(Query.class), any(UpdateDefinition.class),
                eq(ArticleDocument.class));
    }
}
