package de.mhus.hrafnagud.munin.settings;

import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The on/off switch of a background worker, asked once per round.
 *
 * <p>These switches used to be {@code @ConditionalOnProperty} on the tick
 * itself, which is the strongest form of "off" there is — the bean does not
 * exist, so nothing can run. It also made turning a worker on a restart, and a
 * restart of a collector means poll times bunched up and leases waiting to
 * expire. Asking the setting at the start of each round costs one map lookup
 * and moves the decision to where an operator can reach it.
 *
 * <p>The state change is logged, once per change. A worker that stopped
 * collecting is exactly the kind of thing that has to appear in the log rather
 * than be inferred from a graph going flat — and logging it per round would
 * bury the log of a service that is deliberately idle.
 */
public final class WorkerSwitch {

    private static final Logger log = LoggerFactory.getLogger(WorkerSwitch.class);

    /** What to call this worker in the log — "Feed ingest", "Translation". */
    private final String worker;

    private final SettingBoolean setting;

    /** Last state written to the log; {@code null} until the first round. */
    private final AtomicReference<@Nullable Boolean> announced = new AtomicReference<>();

    public WorkerSwitch(String worker, SettingBoolean setting) {
        this.worker = worker;
        this.setting = setting;
    }

    /** Whether this round should run, announcing the answer when it has changed. */
    public boolean isOn() {
        boolean on = setting.value();
        Boolean previous = announced.getAndSet(on);
        if (previous == null || previous != on) {
            if (on) {
                log.info("{} is on ({}=true)", worker, setting.key());
            } else {
                log.info("{} is off ({}=false) — no round runs until it is switched back on",
                        worker, setting.key());
            }
        }
        return on;
    }
}
