package de.mhus.hrafnagud.munin.web;

import de.mhus.hrafnagud.api.place.PlaceDto;
import de.mhus.hrafnagud.munin.place.PlaceRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The containment hierarchy, so a client can read the ids an article carries.
 *
 * <p>The whole table in one response — 279 rows, a few kilobytes, unchanged
 * between releases. Paging it, or offering a lookup per id, would cost a
 * request per article shown for no benefit; a client fetches this once and
 * keeps it.
 */
@RestController
@RequestMapping("/api/v1/places")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceRegistry registry;

    @GetMapping
    public List<PlaceDto> list() {
        return registry.all().stream()
                .map(place -> PlaceDto.builder()
                        .id(place.id())
                        .parentId(place.parentId())
                        .kind(place.kind().name())
                        .name(place.name())
                        .path(List.copyOf(place.path()))
                        .build())
                .toList();
    }
}
