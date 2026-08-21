package de.mhus.hrafnagud.kit;

import de.mhus.vance.ode.kit.KitSource;
import de.mhus.vance.ode.kit.OdeKitBuildRequest;
import de.mhus.vance.ode.kit.OdeKitBundle;
import de.mhus.vance.ode.kit.OdeKitDeclaration;
import de.mhus.vance.ode.kit.StaticKitSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * The kit that turns a project into a reader of this archive: the files from
 * the classpath, with this service's own API keys filled in.
 *
 * <h2>Why this is not a plain {@link StaticKitSource}</h2>
 * The endpoint keys are runtime configuration — environment variables in a
 * deployment — so they cannot be baked into a file in the jar. The interface
 * exists for exactly this: the files come from the classpath, the secrets come
 * from the running configuration, and the caller gets a kit that works without
 * anybody copying a key by hand.
 *
 * <p>Which is not the same as substituting the <em>addresses</em>. Those travel
 * as {@code {{ accessUrl }}} and are filled in by the reader, because only the
 * reader knows which of this service's addresses it actually reached — and a
 * host that answered with an address of its own choosing could point a project
 * somewhere else entirely.
 *
 * <h2>An unset key is omitted, not shipped empty</h2>
 * A delivered {@code PASSWORD} is written when the project has none and never
 * touched again. Shipping an empty one would therefore install a permanent
 * blank that no later configuration can correct — worse than shipping nothing,
 * because the operator has no way to see that it happened.
 *
 * <h2>The revision covers the keys</h2>
 * {@link KitSource} requires the revision to move exactly when the bytes move.
 * The bytes here are the classpath tree <em>plus</em> the keys, so a rotated
 * key has to change it — otherwise the reader's periodic check would report
 * "nothing to do" forever and the project would keep a key that no longer
 * opens anything.
 */
@Slf4j
public class ArchiveKitSource implements KitSource {

    /** Setting file per surface, and the configured key that fills it. */
    private static final Map<String, String> KEY_FILES = Map.of(
            "settings/centauri.endpoint.hrafnagud.apiKey.yaml", "centauri",
            "settings/research.endpoint.hrafnagud.apiKey.yaml", "zarniwoop",
            "settings/jaglan.mount.hrafnagud.apiKey.yaml", "jaglan");

    private final StaticKitSource files;
    private final Map<String, String> keys;

    /**
     * @param id   how the kit is addressed; must be stable across restarts
     * @param keys surface name to API key, blank where none is configured
     */
    public ArchiveKitSource(String id, String basePath, String version, Map<String, String> keys) {
        this.files = StaticKitSource.fromClasspath(id, basePath, version,
                "Configures a project to read hrafnagud's news archive");
        this.keys = new LinkedHashMap<>(keys);

        long shipped = keys.values().stream().filter(StringUtils::isNotBlank).count();
        if (shipped == 0) {
            log.info("Kit '{}' ships no API keys — none are configured, so the surfaces "
                    + "it points at are unguarded anyway", id);
        } else {
            log.info("Kit '{}' ships {} of {} API keys; the rest are omitted rather than "
                    + "shipped empty", id, shipped, keys.size());
        }
    }

    @Override
    public OdeKitDeclaration declare() {
        OdeKitDeclaration base = files.declare();
        return new OdeKitDeclaration(
                base.id(),
                base.version(),
                // Folded, not replaced: the tree's own content hash still has
                // to be in here, or an edited kit file would go unnoticed.
                fold(base.revision()),
                base.description());
    }

    @Override
    public OdeKitBundle build(OdeKitBuildRequest request) {
        Map<String, byte[]> bundle = new TreeMap<>(files.build(request).files());
        for (var entry : KEY_FILES.entrySet()) {
            String key = keys.get(entry.getValue());
            if (StringUtils.isBlank(key)) {
                continue;
            }
            bundle.put(entry.getKey(), settingFile(entry.getValue(), key));
        }
        return new OdeKitBundle(bundle);
    }

    /**
     * A {@code PASSWORD} setting document.
     *
     * <p>Written here rather than kept as a template file with a placeholder,
     * because a template would sit in the jar looking like a configured
     * setting with an empty value — and the whole point of §"unset is omitted"
     * is that such a file must not exist.
     */
    private static byte[] settingFile(String surface, String key) {
        String yaml = """
                type: PASSWORD
                value: "%s"
                description: "Shared secret for hrafnagud's %s surface. Delivered by the \
                archive itself; a value already present here is never overwritten, so a \
                rotated key stays rotated."
                """.formatted(key.replace("\\", "\\\\").replace("\"", "\\\""), surface);
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    /** Tree revision and key material in one token. */
    private String fold(String treeRevision) {
        StringBuilder material = new StringBuilder(treeRevision);
        // Sorted, so the same configuration always folds to the same value —
        // a map iteration order that varied would make every check report a
        // change.
        new TreeMap<>(keys).forEach((surface, key) -> material.append('\0').append(surface)
                .append('=')
                .append(StringUtils.isBlank(key) ? "" : sha256(key)));
        return sha256(material.toString());
    }

    /**
     * Hashed rather than folded in verbatim: a revision is handed out on a
     * cheap, cacheable call, and a key belongs in the bundle behind a token —
     * not in the answer that says whether the bundle changed.
     */
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
