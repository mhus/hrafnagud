package de.mhus.hrafnagud.munin.error;

/** A named entity does not exist. Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String kind, String name) {
        super(kind + " '" + name + "' not found");
    }
}
