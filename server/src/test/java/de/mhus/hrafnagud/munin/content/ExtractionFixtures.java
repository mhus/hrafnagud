package de.mhus.hrafnagud.munin.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads the extraction fixtures from {@code src/test/resources/pages}. */
final class ExtractionFixtures {

    private ExtractionFixtures() {
    }

    static String load(String name) {
        String path = "/pages/" + name;
        try (InputStream in = ExtractionFixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
