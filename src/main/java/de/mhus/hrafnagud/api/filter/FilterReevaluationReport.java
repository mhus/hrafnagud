package de.mhus.hrafnagud.api.filter;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * What a re-evaluation run did.
 *
 * <p>Numbers rather than a bare "ok": the run is bounded, so the operator has
 * to be able to see whether it reached the end of the window or stopped at the
 * cap, and how much actually changed. A pass that silently examined half the
 * window and reported success is the failure mode this exists against.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FilterReevaluationReport {

    /** Oldest article considered; null means the whole archive. */
    private @Nullable Instant since;

    /** How many articles were looked at. */
    private long examined;

    /** Of those, how many had a decision change — the only ones written. */
    private long changed;

    /** Newly denied, so taken out of a queue. */
    private long denied;

    /** Newly accepted, so put back into one. */
    private long accepted;

    /**
     * True when the cap was reached before the window ran out. The next run
     * continues where this one stopped, oldest first.
     */
    private boolean capped;

    /** Rules in effect for this run, after which the answers are what they are. */
    private int rulesApplied;
}
