package de.mhus.hrafnagud.munin.web;

import de.mhus.hrafnagud.api.catalog.CatalogCreateRequest;
import de.mhus.hrafnagud.api.catalog.CatalogDto;
import de.mhus.hrafnagud.api.catalog.CatalogRefreshReport;
import de.mhus.hrafnagud.api.catalog.CatalogUpdateRequest;
import de.mhus.hrafnagud.munin.catalog.SourceCatalogService;
import de.mhus.hrafnagud.munin.sourcelist.SourceListService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Catalogues: where source lists come from.
 *
 * <p>Not paged, like the lists below them — an installation has a couple of
 * catalogues, and a paging envelope over two rows is ceremony.
 */
@RestController
@RequestMapping("/api/v1/catalogs")
@RequiredArgsConstructor
public class CatalogController {

    private final SourceCatalogService catalogService;
    private final SourceListService listService;

    @GetMapping
    public List<CatalogDto> list() {
        return catalogService.listAll().stream()
                .map(catalog -> MuninMapper.toDto(catalog,
                        listService.countByCatalog(catalog.getName())))
                .toList();
    }

    /**
     * Which readers this build has.
     *
     * <p>Worth an endpoint of its own: the reader id is the one field of a
     * catalogue that cannot be guessed, and a form offering a free-text box
     * for it produces a 400 per typo.
     */
    @GetMapping("/readers")
    public Map<String, String> readers() {
        return catalogService.availableReaders();
    }

    @GetMapping("/{name}")
    public CatalogDto get(@PathVariable("name") String name) {
        return MuninMapper.toDto(catalogService.requireByName(name),
                listService.countByCatalog(name));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogDto create(@Valid @RequestBody CatalogCreateRequest request) {
        return MuninMapper.toDto(catalogService.create(request, Instant.now()), 0);
    }

    @PutMapping("/{name}")
    public CatalogDto update(@PathVariable("name") String name,
            @RequestBody CatalogUpdateRequest request) {
        return MuninMapper.toDto(catalogService.update(name, request, Instant.now()),
                listService.countByCatalog(name));
    }

    /**
     * Deletes the catalogue and leaves its lists behind, unmanaged — the same
     * rule the layer below follows, so that removing a directory never
     * silently removes an archive.
     */
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("name") String name) {
        catalogService.delete(name);
    }

    /**
     * Reads the catalogue now, synchronously, and answers with what changed.
     *
     * <p>The schedule does this by itself; this endpoint exists for the case
     * where waiting for it is the wrong answer — a filter was just widened, a
     * new catalogue was just registered, or something is being debugged. It
     * runs whether or not the catalogue is enabled: the flag governs the
     * schedule, and an explicit request has already decided.
     */
    @PostMapping("/{name}/refresh")
    public CatalogRefreshReport refreshNow(@PathVariable("name") String name) {
        return catalogService.refresh(name, Instant.now());
    }
}
