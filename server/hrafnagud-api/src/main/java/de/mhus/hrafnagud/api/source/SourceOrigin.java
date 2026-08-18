package de.mhus.hrafnagud.api.source;

/**
 * Who created a source — the input to the "may a list re-import overwrite
 * this?" decision.
 *
 * <ul>
 *   <li>{@link #MANUAL} — created through the REST API by a human. A list
 *       refresh never modifies these, even if the same feed URL appears in
 *       a list.</li>
 *   <li>{@link #LIST} — created by a source-list refresh. Fields may be
 *       updated by later refreshes of the owning list, except the ones the
 *       user has since edited (see {@code lockedFields}).</li>
 * </ul>
 */
public enum SourceOrigin {

    /** Created by a human through the API. */
    MANUAL,

    /** Created by a source-list refresh. */
    LIST
}
