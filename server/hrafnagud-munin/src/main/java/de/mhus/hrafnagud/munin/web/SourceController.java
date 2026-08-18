package de.mhus.hrafnagud.munin.web;

import de.mhus.hrafnagud.api.common.PageDto;
import de.mhus.hrafnagud.api.source.SourceCreateRequest;
import de.mhus.hrafnagud.api.source.SourceDto;
import de.mhus.hrafnagud.api.source.SourceFetchReport;
import de.mhus.hrafnagud.api.source.SourceType;
import de.mhus.hrafnagud.api.source.SourceUpdateRequest;
import de.mhus.hrafnagud.munin.ingest.FeedIngestService;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import de.mhus.hrafnagud.munin.source.SourceService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** CRUD over the source registry, plus an immediate poll. */
@RestController
@RequestMapping("/api/v1/sources")
@RequiredArgsConstructor
public class SourceController {

    /** Cap on page size, so one request cannot ask for the whole registry. */
    private static final int MAX_PAGE_SIZE = 500;

    private final SourceService sourceService;
    private final FeedIngestService ingestService;

    @GetMapping
    public PageDto<SourceDto> list(
            @RequestParam(value = "enabled", required = false) @Nullable Boolean enabled,
            @RequestParam(value = "type", required = false) @Nullable SourceType type,
            @RequestParam(value = "list", required = false) @Nullable String listName,
            @RequestParam(value = "q", required = false) @Nullable String query,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {

        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);
        List<SourceDocument> sources =
                sourceService.list(enabled, type, listName, query, pageIndex, pageSize);
        // The registry is small enough that an exact count is cheap — unlike
        // the article collection, where it is not.
        long total = sourceService.count(enabled, type, listName, query);
        return PageDto.of(sources.stream().map(MuninMapper::toDto).toList(),
                pageIndex, pageSize, total);
    }

    @GetMapping("/{name}")
    public SourceDto get(@PathVariable("name") String name) {
        return MuninMapper.toDto(sourceService.requireByName(name));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SourceDto create(@Valid @RequestBody SourceCreateRequest request) {
        return MuninMapper.toDto(sourceService.create(request, Instant.now()));
    }

    @PutMapping("/{name}")
    public SourceDto update(@PathVariable("name") String name,
            @Valid @RequestBody SourceUpdateRequest request) {
        return MuninMapper.toDto(sourceService.update(name, request, Instant.now()));
    }

    /**
     * Clears the source's locked fields, returning ownership to the source
     * list that imported it.
     */
    @PostMapping("/{name}/unlock")
    public SourceDto unlock(@PathVariable("name") String name) {
        return MuninMapper.toDto(sourceService.unlock(name, Instant.now()));
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("name") String name) {
        sourceService.delete(name);
    }

    /**
     * Polls the source now and returns what happened.
     *
     * <p>Synchronous: the caller asked to see this feed work, and an
     * accepted-202 with the result arriving in the log would not answer
     * that. The per-host rate limiter still applies, so this cannot be used
     * to hammer a publisher.
     */
    @PostMapping("/{name}/fetch")
    public SourceFetchReport fetchNow(@PathVariable("name") String name) {
        SourceDocument source = sourceService.requireByName(name);
        return ingestService.poll(source, Instant.now());
    }
}
