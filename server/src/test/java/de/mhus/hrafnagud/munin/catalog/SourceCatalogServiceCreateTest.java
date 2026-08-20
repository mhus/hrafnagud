package de.mhus.hrafnagud.munin.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.api.catalog.CatalogCreateRequest;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.sourcelist.SourceListService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * What a newly registered catalogue starts as.
 *
 * <p>Worth pinning down rather than trusting: a catalogue is a standing
 * instruction to crawl somebody else's list of publishers, several ship with
 * the application, and a default that flips back to "on" would make a fresh
 * installation start all of them at once — a change nobody would notice in a
 * review and everybody would notice in their outbound traffic.
 */
class SourceCatalogServiceCreateTest {

    private SourceCatalogRepository repository;
    private SourceCatalogService service;

    @BeforeEach
    void setUp() {
        repository = mock(SourceCatalogRepository.class);
        when(repository.findByName(any())).thenReturn(Optional.empty());
        when(repository.findByUrl(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service = new SourceCatalogService(repository, mock(MongoTemplate.class),
                mock(SourceListService.class),
                List.of(new GithubOpmlReader(null, null)),
                new MuninProperties());
    }

    @Test
    void a_catalogue_is_disabled_unless_the_request_asks_for_it() {
        SourceCatalogDocument created = service.create(request(null), Instant.now());

        assertThat(created.isEnabled()).isFalse();
    }

    @Test
    void asking_for_enabled_is_honoured() {
        assertThat(service.create(request(true), Instant.now()).isEnabled()).isTrue();
        assertThat(service.create(request(false), Instant.now()).isEnabled()).isFalse();
    }

    /**
     * Disabled governs the schedule, not the data: the catalogue is still due,
     * so switching it on later starts it at the next tick rather than needing a
     * second nudge.
     */
    @Test
    void a_disabled_catalogue_is_still_created_due() {
        SourceCatalogDocument created = service.create(request(null), Instant.now());

        assertThat(created.getNextRefreshAt()).isNotNull();
    }

    private static CatalogCreateRequest request(Boolean enabled) {
        return CatalogCreateRequest.builder()
                .name("repo")
                .type(GithubOpmlReader.TYPE)
                .url("https://github.com/o/r")
                .enabled(enabled)
                .build();
    }
}
