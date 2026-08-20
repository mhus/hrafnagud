package de.mhus.hrafnagud.api.category;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One Media Topic, without its labels.
 *
 * <p>Served for the same reason places are ({@code PlaceDto}): an article
 * carries ids, and a client needs names to show them and a tree to offer a
 * choice. The ten thousand match labels stay on the server — they are the
 * matcher's working set, not information a reader wants.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TopicDto {

    /** Qcode, e.g. {@code medtop:15000000}. */
    private String id = "";

    private @Nullable String parentId;

    /** English name. Display only. */
    private String name = "";

    /** This topic and everything containing it, outermost first. */
    @Builder.Default
    private List<String> path = new ArrayList<>();
}
