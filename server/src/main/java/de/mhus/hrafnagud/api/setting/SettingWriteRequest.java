package de.mhus.hrafnagud.api.setting;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of a settings write: the new value as text.
 *
 * <p>Text rather than a typed union, because the declared {@link SettingType}
 * already says how to read it and a JSON number would arrive as a double for
 * an {@code INT} setting anyway. The value is parsed against the type before
 * anything is stored.
 *
 * <p>There is no type field. The type belongs to the key and is decided in the
 * code that reads it; letting a caller supply one would allow a duration to be
 * declared a string and the mismatch to surface in a worker.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SettingWriteRequest {

    /**
     * The new value. Blank means "no override" and is refused — the way back
     * to the default is {@code DELETE}, so that "reset" and "set to empty" do
     * not share one gesture.
     */
    private @Nullable String value;
}
