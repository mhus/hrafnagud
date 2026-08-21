package de.mhus.hrafnagud.munin.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a reader found: the entries, plus what it had to ignore to get there.
 *
 * @param entries  the lists offered, deduplicated by URL in the order they
 *                 were found.
 * @param invalid  entries dropped as unusable — a missing URL, an unsupported
 *                 scheme. Counted rather than thrown: one broken line in a
 *                 directory of hundreds is a fact about the directory, not a
 *                 reason to refuse all of it.
 * @param warnings sentences for the operator, e.g. a truncation notice.
 */
public record CatalogReadResult(
        List<CatalogEntry> entries, int invalid, List<String> warnings) {

    public CatalogReadResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static CatalogReadResult of(List<CatalogEntry> entries) {
        return new CatalogReadResult(entries, 0, List.of());
    }

    /**
     * Stable hash over the entry URLs.
     *
     * <p>Sorted first, so that a directory listing that comes back in a
     * different order does not read as a change — the set is what matters,
     * and a spurious change means a full reconciliation pass over every list
     * the catalogue owns.
     *
     * <p>SHA-256 and not {@code String.hashCode()}: a collision here does not
     * produce a wrong page somewhere, it makes a real change to the directory
     * invisible until something else moves. 32 bits is not enough to bet that
     * on, and the cost is a hash of a few kilobytes once a day.
     */
    public String fingerprint() {
        Set<String> urls = new LinkedHashSet<>();
        for (CatalogEntry entry : entries) {
            urls.add(entry.url());
        }
        List<String> sorted = new ArrayList<>(urls);
        sorted.sort(Comparator.naturalOrder());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16) + "-" + sorted.size();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
