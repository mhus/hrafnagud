package de.mhus.hrafnagud.settings;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One stored override.
 *
 * <p>Only overrides are rows. A setting left at its configured default has no
 * document, which is what makes "back to the default" a delete rather than a
 * second copy of the value that would then have to be kept in step with
 * {@code application.yml}.
 *
 * <p>The value is text and the type is not stored. The type belongs to the
 * declaration in the code that reads the setting — storing it here would
 * create a second opinion about what {@code munin.feed.batchSize} is, and the
 * two could disagree after a release.
 */
@Document(collection = "settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettingDocument {

    @Id
    private @Nullable String id;

    /** Dotted key, identical to the {@code application.yml} property. */
    @Indexed(unique = true)
    private String key = "";

    /** The override, as text. Parsed against the declared type on write. */
    private String value = "";

    /** Set by the service on every write, like the other collections here. */
    private @Nullable Instant updatedAt;
}
