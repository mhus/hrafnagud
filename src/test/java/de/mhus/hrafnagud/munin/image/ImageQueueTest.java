package de.mhus.hrafnagud.munin.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.munin.article.ArticleImage;
import de.mhus.hrafnagud.settings.Settings;
import de.mhus.hrafnagud.settings.TestSettings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Which images get queued, and which do not.
 *
 * <p>The interesting cases are all refusals: the switch being off, the
 * lead-only filter, and an image that is already known. Together they are what
 * keeps a feature with unbounded storage cost bounded.
 */
class ImageQueueTest {

    private static final Instant NOW = Instant.parse("2026-08-21T18:00:00Z");

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final ImageRepository repository = mock(ImageRepository.class);

    /** Every upsert reports "created", so counting them is counting queue writes. */
    private void upsertsCreate() {
        UpdateResult created = mock(UpdateResult.class);
        when(created.getUpsertedId()).thenReturn(new BsonString("id"));
        when(mongoTemplate.upsert(any(Query.class), any(Update.class),
                eq(ImageDocument.class))).thenReturn(created);
    }

    private ImageService serviceWith(Settings settings) {
        return new ImageService(repository, mongoTemplate, settings);
    }

    private static Settings enabled(boolean leadOnly) {
        MuninProperties properties = new MuninProperties();
        properties.getImage().setEnabled(true);
        properties.getImage().setLeadOnly(leadOnly);
        return TestSettings.of(properties);
    }

    private static ArticleImage image(String url, String role) {
        return ArticleImage.builder().url(url).role(role).build();
    }

    @Test
    void disabled_queuesNothing() {
        // And writes nothing: a queue entry per image for a feature that is
        // off would be storing a decision rather than recording a fact.
        ImageService service = serviceWith(TestSettings.defaults());

        int queued = service.enqueue("a1", List.of(image("https://e.com/a.jpg", "LEAD")), NOW);

        assertThat(queued).isZero();
        verify(mongoTemplate, never()).upsert(any(Query.class), any(Update.class),
                eq(ImageDocument.class));
    }

    @Test
    void leadOnly_skipsInlineImages() {
        upsertsCreate();
        ImageService service = serviceWith(enabled(true));

        int queued = service.enqueue("a1", List.of(
                image("https://e.com/lead.jpg", "LEAD"),
                image("https://e.com/inline1.jpg", "INLINE"),
                image("https://e.com/inline2.jpg", "INLINE")), NOW);

        assertThat(queued).isEqualTo(1);
    }

    @Test
    void withoutLeadOnly_everyImageIsQueued() {
        upsertsCreate();
        ImageService service = serviceWith(enabled(false));

        int queued = service.enqueue("a1", List.of(
                image("https://e.com/lead.jpg", "LEAD"),
                image("https://e.com/inline1.jpg", "INLINE"),
                image("https://e.com/inline2.jpg", "INLINE")), NOW);

        assertThat(queued).isEqualTo(3);
    }

    @Test
    void roleCase_doesNotDecideIt() {
        upsertsCreate();
        ImageService service = serviceWith(enabled(true));

        assertThat(service.enqueue("a1", List.of(image("https://e.com/a.jpg", "lead")), NOW))
                .isEqualTo(1);
    }

    @Test
    void blankUrl_isSkipped() {
        upsertsCreate();
        ImageService service = serviceWith(enabled(false));

        int queued = service.enqueue("a1", List.of(
                image("", "LEAD"), image("   ", "INLINE")), NOW);

        assertThat(queued).isZero();
        verify(mongoTemplate, never()).upsert(any(Query.class), any(Update.class),
                eq(ImageDocument.class));
    }

    @Test
    void noImages_writesNothing() {
        ImageService service = serviceWith(enabled(false));

        assertThat(service.enqueue("a1", List.of(), NOW)).isZero();
        verify(mongoTemplate, never()).upsert(any(Query.class), any(Update.class),
                eq(ImageDocument.class));
    }

    @Test
    void alreadyKnown_isNotCountedAsNewlyQueued() {
        // An upsert that matched an existing record reports no upserted id.
        // That is the whole deduplication mechanism: a second article
        // referencing one image must not reset its state or its retry
        // schedule.
        UpdateResult matched = mock(UpdateResult.class);
        when(matched.getUpsertedId()).thenReturn(null);
        when(mongoTemplate.upsert(any(Query.class), any(Update.class),
                eq(ImageDocument.class))).thenReturn(matched);
        ImageService service = serviceWith(enabled(true));

        assertThat(service.enqueue("a2", List.of(image("https://e.com/a.jpg", "LEAD")), NOW))
                .isZero();
    }

    @Test
    void theSwitch_isReadPerCallNotAtConstruction() {
        // The point of the settings layer: a long-lived singleton must see an
        // operator's change without a restart.
        var fixture = TestSettings.fixture(new MuninProperties(), Map.of());
        ImageService service = new ImageService(repository, mongoTemplate, fixture.settings());
        upsertsCreate();

        assertThat(service.enqueue("a1", List.of(image("https://e.com/a.jpg", "LEAD")), NOW))
                .as("off by default")
                .isZero();

        fixture.store().set("munin.image.enabled", "true");

        assertThat(service.enqueue("a1", List.of(image("https://e.com/a.jpg", "LEAD")), NOW))
                .as("after the operator switched it on")
                .isEqualTo(1);
    }
}
