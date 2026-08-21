package de.mhus.hrafnagud.munin.web;

import de.mhus.hrafnagud.api.setting.SettingDto;
import de.mhus.hrafnagud.api.setting.SettingSource;
import de.mhus.hrafnagud.api.setting.SettingWriteRequest;
import de.mhus.hrafnagud.munin.settings.Setting;
import de.mhus.hrafnagud.munin.settings.SettingsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The values an operator can change without a restart.
 *
 * <p>Three verbs and no create: the set of settings is what this build declares
 * — {@code MuninSettings} is that list — so writing is always overriding
 * something that already exists and has a default. A key nothing declares is a
 * 404 rather than a new row, which is what keeps the collection from filling
 * with typos that look like configuration.
 *
 * <p>{@code DELETE} is the way back to the configured default, and it is the
 * only way: a blank {@code PUT} is refused so that "reset" and "store an empty
 * value" cannot be confused for one another.
 *
 * <p>Start-up values are deliberately absent from this surface — the tick
 * cadences, the proxy, the API token. They are read once while the service
 * boots, so a value stored here would be one nothing reads. See
 * {@code specs/settings.md} §3.
 */
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settings;

    /** Every declared setting, with its effective value, its default and which is in force. */
    @GetMapping
    public List<SettingDto> list() {
        return settings.declared().stream().map(this::toDto).toList();
    }

    @GetMapping("/{key}")
    public SettingDto get(@PathVariable("key") String key) {
        return toDto(settings.require(key));
    }

    /** Stores an override. The value is parsed against the declared type first. */
    @PutMapping("/{key}")
    public SettingDto set(@PathVariable("key") String key,
            @RequestBody SettingWriteRequest request) {
        settings.set(key, request.getValue());
        return toDto(settings.require(key));
    }

    /** Removes the override, returning the setting to its configured default. */
    @DeleteMapping("/{key}")
    public SettingDto reset(@PathVariable("key") String key) {
        settings.reset(key);
        return toDto(settings.require(key));
    }

    private SettingDto toDto(Setting<?> setting) {
        return SettingDto.builder()
                .key(setting.key())
                .type(setting.type())
                .value(setting.render())
                .defaultValue(setting.renderDefault())
                .source(setting.overridden() ? SettingSource.DATABASE : SettingSource.CONFIG)
                .description(setting.description())
                .updatedAt(settings.updatedAt(setting.key()).orElse(null))
                .build();
    }
}
