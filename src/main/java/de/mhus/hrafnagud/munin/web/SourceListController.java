package de.mhus.hrafnagud.munin.web;

import de.mhus.hrafnagud.api.source.SourceListCreateRequest;
import de.mhus.hrafnagud.api.source.SourceListDto;
import de.mhus.hrafnagud.api.source.SourceListRefreshReport;
import de.mhus.hrafnagud.api.source.SourceListUpdateRequest;
import de.mhus.hrafnagud.munin.sourcelist.SourceListService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD over source lists, plus an immediate refresh.
 *
 * <p>Not paged: an installation has a handful of lists, not thousands, and
 * a paging envelope over five rows is ceremony.
 */
@RestController
@RequestMapping("/api/v1/source-lists")
@RequiredArgsConstructor
public class SourceListController {

    private final SourceListService listService;

    @GetMapping
    public List<SourceListDto> list() {
        return listService.listAll().stream().map(MuninMapper::toDto).toList();
    }

    @GetMapping("/{name}")
    public SourceListDto get(@PathVariable("name") String name) {
        return MuninMapper.toDto(listService.requireByName(name));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SourceListDto create(@Valid @RequestBody SourceListCreateRequest request) {
        return MuninMapper.toDto(listService.create(request, Instant.now()));
    }

    @PutMapping("/{name}")
    public SourceListDto update(@PathVariable("name") String name,
            @Valid @RequestBody SourceListUpdateRequest request) {
        return MuninMapper.toDto(listService.update(name, request, Instant.now()));
    }

    /**
     * Deletes the list. Sources it imported are kept and simply become
     * unmanaged — dropping a chunk of the archive's provenance as a side
     * effect of a configuration change would be the wrong default.
     */
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("name") String name) {
        listService.delete(name);
    }

    /**
     * Re-reads the list now and reconciles the registry against it.
     *
     * <p>Synchronous, and can take a while on a large directory — it may
     * create a thousand sources. That is the honest cost of the operation
     * and the caller asked for it.
     */
    @PostMapping("/{name}/refresh")
    public SourceListRefreshReport refreshNow(@PathVariable("name") String name) {
        return listService.refresh(name, Instant.now());
    }
}
