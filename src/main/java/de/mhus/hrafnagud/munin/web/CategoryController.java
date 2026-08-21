package de.mhus.hrafnagud.munin.web;

import de.mhus.hrafnagud.api.category.CategoryMappingDto;
import de.mhus.hrafnagud.api.category.CategoryMappingStatus;
import de.mhus.hrafnagud.api.category.TopicDto;
import de.mhus.hrafnagud.api.common.PageDto;
import de.mhus.hrafnagud.munin.category.CategoryMappingDocument;
import de.mhus.hrafnagud.munin.category.CategoryMappingService;
import de.mhus.hrafnagud.munin.category.Topic;
import de.mhus.hrafnagud.munin.category.TopicRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The mapping table, readable and correctable.
 *
 * <p>This exists because the table is the only place the archive's category
 * mess is written down. Stage one guesses, stage two decides, and both can be
 * wrong in ways that are obvious to a person and invisible to a query: without
 * a way to look at the list most-used-first and say "no, that is a football
 * club, not a sport", the learning half of the design is unobservable.
 *
 * <p>Correction is the only write. There is deliberately no endpoint that
 * re-runs stage two on demand or edits a topic path by hand — the first would
 * spend tokens to get the same answer, the second would put a path in the
 * table that the vocabulary does not agree with.
 */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryMappingService mappings;
    private final TopicRegistry topics;

    /**
     * Lists mappings, most-used first.
     *
     * <p>Most-used rather than alphabetical, and it is the whole point of the
     * endpoint: the useful question is "what is still unresolved and appears on
     * a lot of articles", and alphabetical order buries that under one-off tags
     * such as an author's name.
     */
    @GetMapping
    public PageDto<CategoryMappingDto> list(
            @RequestParam(value = "status", required = false)
                    @Nullable CategoryMappingStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {

        List<CategoryMappingDto> items = mappings.list(status, page, size).stream()
                .map(this::toDto)
                .toList();
        return PageDto.<CategoryMappingDto>builder()
                .items(items)
                .page(page)
                .size(size)
                // Cheap here, unlike on articles: the table is bounded by the
                // vocabulary, thousands of rows rather than millions.
                .total(mappings.count(status))
                .build();
    }

    /** How many mappings sit in each state — the backlog at a glance. */
    @GetMapping("/summary")
    public Map<String, Long> summary() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (CategoryMappingStatus status : CategoryMappingStatus.values()) {
            counts.put(status.name(), mappings.count(status));
        }
        counts.put("TOTAL", mappings.count(null));
        return counts;
    }

    @GetMapping("/{key}")
    public CategoryMappingDto get(@PathVariable("key") String key) {
        return toDto(mappings.requireByKey(key));
    }

    /**
     * Settles one mapping by hand. Terminal: nothing asks again.
     *
     * <p>A null or blank {@code topicId} means "this is not a topic" rather
     * than "clear the decision" — a person looking at *René Habermann* is
     * answering the question, not declining to.
     */
    @PostMapping("/{key}/confirm")
    public CategoryMappingDto confirm(@PathVariable("key") String key,
            @RequestBody ConfirmRequest request) {

        String topicId = request.topicId() == null || request.topicId().isBlank()
                ? null
                : request.topicId().trim();
        return toDto(mappings.confirm(key, topicId, Instant.now()));
    }

    /**
     * The vocabulary as a tree, names only.
     *
     * <p>Whole thing in one response, like {@code /api/v1/places}: 1,393 rows
     * that change when a standards committee meets. A client fetches it once
     * and uses it both to label the ids on an article and to offer the choice
     * when correcting a mapping.
     */
    @GetMapping("/topics")
    public List<TopicDto> topicTree() {
        return topics.all().stream()
                .map(topic -> TopicDto.builder()
                        .id(topic.id())
                        .parentId(topic.parentId())
                        .name(topic.name())
                        .path(List.copyOf(topic.path()))
                        .build())
                .toList();
    }

    private CategoryMappingDto toDto(CategoryMappingDocument document) {
        List<String> names = new ArrayList<>();
        for (String id : document.getTopicPath()) {
            names.add(topics.find(id).map(Topic::name).orElse(id));
        }
        return CategoryMappingDto.builder()
                .key(document.getKey())
                .raw(document.getRaw())
                .status(document.getStatus())
                .topicId(document.getTopicId())
                .topicName(topics.find(document.getTopicId())
                        .map(Topic::name)
                        .orElse(null))
                .topicPath(List.copyOf(document.getTopicPath()))
                .topicPathNames(names)
                .confidence(document.getConfidence())
                .decidedBy(document.getDecidedBy())
                .note(document.getNote())
                .useCount(document.getUseCount())
                .attempts(document.getAttempts())
                .lastError(document.getLastError())
                .lastSeenAt(document.getLastSeenAt())
                .build();
    }

    /** @param topicId a Media Topic qcode, or null/blank for "not a topic". */
    public record ConfirmRequest(@Nullable String topicId) { }
}
