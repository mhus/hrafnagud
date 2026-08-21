package de.mhus.hrafnagud.api.setting;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One setting, as the operator API describes it.
 *
 * <p>Both the effective value and the default are reported, and so is which of
 * the two is in force. A settings screen that shows only the current number
 * cannot answer the question an operator actually has — "did somebody change
 * this, and what was it before" — and the answer has to survive the person who
 * made the change.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SettingDto {

    /** Dotted key, identical to the {@code application.yml} property. */
    private String key = "";

    private SettingType type = SettingType.STRING;

    /** The value in force, rendered as text. */
    private String value = "";

    /** What the value would be with no override — the configured default. */
    private String defaultValue = "";

    /**
     * Where {@link #value} comes from: {@code DATABASE} when an override is
     * stored, {@code CONFIG} when it falls through to {@code application.yml}
     * or the code default.
     */
    private SettingSource source = SettingSource.CONFIG;

    /** What the setting does, for whoever is about to change it. */
    private String description = "";

    /** When the override was last written; absent when there is none. */
    private @Nullable Instant updatedAt;
}
