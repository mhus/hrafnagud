package de.mhus.hrafnagud.munin.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers for the dedup key and the content fingerprint. */
public final class Hashes {

    private Hashes() {
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of {@code value}. */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK specification.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * First {@code length} hex characters of the SHA-256 — enough entropy to
     * disambiguate names within one installation, short enough to keep a
     * generated source name readable.
     */
    public static String shortHash(String value, int length) {
        String full = sha256(value);
        return full.substring(0, Math.min(length, full.length()));
    }
}
