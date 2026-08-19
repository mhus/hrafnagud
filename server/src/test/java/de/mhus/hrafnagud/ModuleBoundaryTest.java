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
 * <p>Reading source files rather than bytecode is deliberate: an import that
 * only appears in a Javadoc {@code @link} or a comment is still someone
 * starting to think in the wrong direction, and the compiler would have caught
 * the real import either way. Cheap, deterministic, no new dependency.
 */
class ModuleBoundaryTest {

    /** Package roots that must stay ignorant of everything below. */
    private static final List<String> INDEPENDENT = List.of("munin", "api");

    /**
     * What they must not reach for: the Ode libraries, and the three packages
     * that use them. The direction is the point — those three import Munin,
     * never the other way round.
     */
    private static final List<String> FORBIDDEN = List.of(
            "de.mhus.vance",
            "de.mhus.hrafnagud.translate",
            "de.mhus.hrafnagud.centauri",
            "de.mhus.hrafnagud.zarniwoop");

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
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        for (String forbidden : FORBIDDEN) {
                            if (line.contains(forbidden)) {
                                violations.add("%s:%d  %s"
                                        .formatted(file, i + 1, line.trim()));
                            }
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("""
                        Munin reaches outward. Deleting translate/, centauri/ and \
                        zarniwoop/ has to leave a collector that still compiles — \
                        see specs/architecture.md §2.1. Move whatever needs the \
                        brain into one of those three packages instead.""")
                .isEmpty();
    }
}
