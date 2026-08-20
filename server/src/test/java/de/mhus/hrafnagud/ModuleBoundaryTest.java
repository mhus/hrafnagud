package de.mhus.hrafnagud;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The one hard rule of specs/architecture.md §2.1: <b>Munin has no dependency
 * on Vancetope.</b> The archive must be collectable and queryable without a
 * brain anywhere near it.
 *
 * <p>This used to be enforced by the module graph — {@code hrafnagud-munin}
 * simply did not have {@code vance-ode-*} on its classpath, so a wrong import
 * failed the build. Collapsing the six modules into one traded that for a
 * simpler project, and a rule nobody checks is a rule that decays. So it is
 * checked here instead.
 *
 * <p>Reading source files rather than bytecode is deliberate: it catches a
 * fully-qualified reference that never became an import, and it needs no new
 * dependency to do it.
 *
 * <p><b>Comments are exempt</b>, and that exemption was earned the hard way:
 * the first version flagged {@code ApiTokenInterceptor}, whose Javadoc names
 * {@code de.mhus.vance} precisely to explain why Munin duplicates thirty lines
 * rather than reuse the Ode guard. A rule that cannot be written down next to
 * the code it governs is one people work around instead of reading.
 */
class ModuleBoundaryTest {

    /** Package roots that must stay ignorant of everything below. */
    private static final List<String> INDEPENDENT = List.of("munin", "api");

    /**
     * What they must not reach for: the Ode libraries, and the packages that
     * use them. The direction is the point — those import Munin, never the
     * other way round.
     */
    private static final List<String> FORBIDDEN = List.of(
            "de.mhus.vance",
            "de.mhus.hrafnagud.translate",
            "de.mhus.hrafnagud.classify",
            "de.mhus.hrafnagud.centauri",
            "de.mhus.hrafnagud.zarniwoop",
            // Not one of the four, and still outward-facing: it declares
            // Munin's fields as Ode facets and therefore imports vance-ode. An
            // import of it from munin would smuggle the whole contract in
            // through a package whose name does not say so.
            "de.mhus.hrafnagud.facet");

    private static final Path SOURCE_ROOT =
            Path.of("src", "main", "java", "de", "mhus", "hrafnagud");

    @Test
    void munin_and_api_never_mention_vancetope_or_the_packages_that_face_it()
            throws IOException {
        List<String> violations = new ArrayList<>();

        for (String pkg : INDEPENDENT) {
            Path root = SOURCE_ROOT.resolve(pkg);
            assertThat(root).as("package %s exists — has it been renamed?", pkg).exists();

            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    List<String> lines = Files.readAllLines(file);
                    boolean inBlockComment = false;
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        String trimmed = line.trim();

                        // Deliberately naive: this tracks whole-line block
                        // comments and nothing cleverer. Code sharing a line
                        // with the end of a block comment would be missed,
                        // and that is a shape nobody writes here.
                        if (inBlockComment) {
                            if (trimmed.contains("*/")) {
                                inBlockComment = false;
                            }
                            continue;
                        }
                        if (trimmed.startsWith("/*")) {
                            inBlockComment = !trimmed.contains("*/");
                            continue;
                        }
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                            continue;
                        }

                        String code = line.split("//", 2)[0];
                        for (String forbidden : FORBIDDEN) {
                            if (code.contains(forbidden)) {
                                violations.add("%s:%d  %s"
                                        .formatted(file, i + 1, trimmed));
                            }
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("""
                        Munin reaches outward. Deleting translate/, classify/, \
                        centauri/ and zarniwoop/ has to leave a collector that \
                        still compiles — see specs/architecture.md §2.1. Move \
                        whatever needs the brain into one of those four \
                        packages instead.""")
                .isEmpty();
    }
}
