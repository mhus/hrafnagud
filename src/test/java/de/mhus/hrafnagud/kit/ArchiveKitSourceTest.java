package de.mhus.hrafnagud.kit;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.ode.kit.KitSource;
import de.mhus.vance.ode.kit.OdeKitBuildRequest;
import de.mhus.vance.ode.kit.StaticKitSource;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The kit as a caller receives it.
 *
 * <p>Reads the real files from the classpath rather than a fixture: what is
 * shipped is the thing worth testing, and a fixture would let the kit and its
 * test drift apart while both stayed green.
 */
class ArchiveKitSourceTest {

    private static final Map<String, String> KEYS = Map.of(
            "centauri", "centauri-secret",
            "zarniwoop", "research-secret",
            "jaglan", "mount-secret");

    private static ArchiveKitSource source(Map<String, String> keys) {
        return new ArchiveKitSource("hrafnagud-archive", "kits/archive", "1.0", keys);
    }

    private static OdeKitBuildRequest request() {
        return request("hrafnagud-archive");
    }

    /** kit, instance, tenant, project, accessUrl, installId, params. */
    private static OdeKitBuildRequest request(String kit) {
        return new OdeKitBuildRequest(kit, null, "acme", "test1", null, null, Map.of());
    }

    private static String file(Map<String, byte[]> bundle, String path) {
        byte[] content = bundle.get(path);
        return content == null ? null : new String(content, StandardCharsets.UTF_8);
    }

    // ─── The bundle ───

    @Test
    void theKitShipsItsDescriptorAndTheThreeSurfaces() {
        Map<String, byte[]> bundle = source(KEYS).build(request()).files();

        assertThat(bundle).containsKeys(
                "kit.yaml",
                "settings/centauri.endpoint.hrafnagud.protocol.yaml",
                "settings/centauri.endpoint.hrafnagud.baseUrl.yaml",
                "settings/research.endpoint.hrafnagud.protocol.yaml",
                "settings/research.endpoint.hrafnagud.baseUrl.yaml",
                "settings/jaglan.mount.hrafnagud.protocol.yaml",
                "settings/jaglan.mount.hrafnagud.baseUrl.yaml",
                "documents/skills/hrafnagud-archive/SKILL.md");
    }

    @Test
    void addressesTravelAsAPlaceholder() {
        // Filled in by the reader, using the address it actually reached us
        // on. A host answering with an address of its own choosing could point
        // a project somewhere else entirely.
        Map<String, byte[]> bundle = source(KEYS).build(request()).files();

        assertThat(file(bundle, "settings/jaglan.mount.hrafnagud.baseUrl.yaml"))
                .contains("{{ accessUrl }}/ode/files");
        assertThat(file(bundle, "settings/centauri.endpoint.hrafnagud.baseUrl.yaml"))
                .contains("{{ accessUrl }}/ode/feed");
        assertThat(file(bundle, "settings/research.endpoint.hrafnagud.baseUrl.yaml"))
                .contains("{{ accessUrl }}/ode/search");
    }

    @Test
    void everySurfaceIsPinnedToTheOdeProtocol() {
        // Without .protocol the reader's factory skips the endpoint — silently,
        // which is the worst way for this kit to fail.
        Map<String, byte[]> bundle = source(KEYS).build(request()).files();

        for (String path : new String[] {
                "settings/centauri.endpoint.hrafnagud.protocol.yaml",
                "settings/research.endpoint.hrafnagud.protocol.yaml",
                "settings/jaglan.mount.hrafnagud.protocol.yaml"}) {
            assertThat(file(bundle, path)).as(path).contains("value: \"ode\"");
        }
    }

    // ─── Keys ───

    @Test
    void configuredKeys_travelAsPasswordSettings() {
        Map<String, byte[]> bundle = source(KEYS).build(request()).files();

        assertThat(file(bundle, "settings/jaglan.mount.hrafnagud.apiKey.yaml"))
                .contains("type: PASSWORD")
                .contains("value: \"mount-secret\"");
        assertThat(file(bundle, "settings/centauri.endpoint.hrafnagud.apiKey.yaml"))
                .contains("value: \"centauri-secret\"");
        assertThat(file(bundle, "settings/research.endpoint.hrafnagud.apiKey.yaml"))
                .contains("value: \"research-secret\"");
    }

    @Test
    void anUnsetKey_isOmittedRatherThanShippedEmpty() {
        // The one that matters. A delivered PASSWORD is written when the
        // project has none and never touched again, so an empty one installs a
        // permanent blank that no later configuration corrects.
        Map<String, byte[]> bundle = source(Map.of(
                "centauri", "centauri-secret",
                "zarniwoop", "",
                "jaglan", "   ")).build(request()).files();

        assertThat(bundle).containsKey("settings/centauri.endpoint.hrafnagud.apiKey.yaml");
        assertThat(bundle).doesNotContainKeys(
                "settings/research.endpoint.hrafnagud.apiKey.yaml",
                "settings/jaglan.mount.hrafnagud.apiKey.yaml");
    }

    @Test
    void noKeysAtAll_stillYieldsAUsableKit() {
        // An installation with unguarded surfaces is a real configuration, and
        // the kit still carries the addresses and the skill.
        Map<String, byte[]> bundle = source(Map.of(
                "centauri", "", "zarniwoop", "", "jaglan", "")).build(request()).files();

        assertThat(bundle).containsKey("settings/jaglan.mount.hrafnagud.baseUrl.yaml");
        assertThat(bundle.keySet().stream().filter(k -> k.contains("apiKey"))).isEmpty();
    }

    @Test
    void aQuoteInAKey_doesNotBreakTheYaml() {
        Map<String, byte[]> bundle = source(Map.of(
                "centauri", "a\"b\\c", "zarniwoop", "", "jaglan", "")).build(request()).files();

        assertThat(file(bundle, "settings/centauri.endpoint.hrafnagud.apiKey.yaml"))
                .contains("value: \"a\\\"b\\\\c\"");
    }

    // ─── The revision ───

    @Test
    void theRevisionIsStableForTheSameConfiguration() {
        // A reader compares it on a schedule; a value that moved on its own
        // would refetch the kit forever.
        assertThat(source(KEYS).declare().revision())
                .isEqualTo(source(KEYS).declare().revision());
    }

    @Test
    void aRotatedKeyMovesTheRevision() {
        // Otherwise the periodic check reports "nothing to do" forever and the
        // project keeps a key that no longer opens anything.
        String before = source(KEYS).declare().revision();
        String after = source(Map.of(
                "centauri", "centauri-secret",
                "zarniwoop", "research-secret",
                "jaglan", "rotated")).declare().revision();

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void theRevisionStillCoversTheFiles() {
        // Folded with the tree hash rather than replacing it, so an edited kit
        // file is noticed as well.
        String kitOnly = StaticKitSource
                .fromClasspath("hrafnagud-archive", "kits/archive").declare().revision();

        assertThat(source(KEYS).declare().revision()).isNotEqualTo(kitOnly).isNotBlank();
    }

    @Test
    void theDeclaredIdIsWhatBuildIsRoutedBy() {
        assertThat(source(KEYS).declare().id()).isEqualTo("hrafnagud-archive");
    }

    // ─── The translation kit beside it ───

    @Test
    void theTranslationKitLoadsFromTheClasspath() {
        // It is bundled by the build rather than living under
        // src/main/resources, so this is also a test of that wiring.
        KitSource translation = StaticKitSource.fromClasspath(
                "hrafnagud-translation", "kits/translation");

        assertThat(translation.build(request("hrafnagud-translation")).files())
                .containsKeys(
                        "kit.yaml",
                        "documents/_vance/events/translate-article.yaml",
                        "documents/_vance/scripts/translate-article.js",
                        "documents/_vance/recipes/article-translate.yaml",
                        "settings/translation.defaultTargetLang.yaml");
    }
}
