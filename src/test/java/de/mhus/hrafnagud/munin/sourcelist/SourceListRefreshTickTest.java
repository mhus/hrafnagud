package de.mhus.hrafnagud.munin.sourcelist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.settings.TestSettings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How much one round gets through.
 *
 * <p>This is the behaviour a catalogue import depends on. When it was a fixed
 * five lists per five-minute tick, sixty-five lists arriving at once took over
 * an hour to become feeds — during which a fresh instance shows a full list
 * registry and no sources at all, which reads as broken rather than busy.
 */
class SourceListRefreshTickTest {

    private SourceListService listService;
    private MuninProperties properties;
    private SourceListRefreshTick tick;

    @BeforeEach
    void setUp() {
        listService = mock(SourceListService.class);
        properties = new MuninProperties();
        tick = new SourceListRefreshTick(listService, properties, TestSettings.of(properties));
    }

    @Test
    void a_round_keeps_claiming_until_nothing_is_due() {
        // 12 lists due, leased 5 at a time.
        stubClaims(5, 5, 2);

        assertThat(tick.runRound(Instant.now())).isEqualTo(12);
        // Four calls: three that returned work and one that found none.
        verify(listService, times(4)).claimDue(any(), anyInt());
        verify(listService, times(12)).refresh(any(SourceListDocument.class), any());
    }

    @Test
    void a_round_stops_at_the_cap_and_leaves_the_rest_for_the_next_one() {
        properties.getSourceList().setBatchSize(5);
        properties.getSourceList().setMaxPerRound(10);
        stubClaims(5, 5, 5, 5);

        assertThat(tick.runRound(Instant.now())).isEqualTo(10);
        verify(listService, times(2)).claimDue(any(), anyInt());
    }

    /** The last claim must not overshoot the cap. */
    @Test
    void the_final_claim_asks_only_for_what_the_cap_still_allows() {
        properties.getSourceList().setBatchSize(5);
        properties.getSourceList().setMaxPerRound(7);
        stubClaims(5, 2);

        tick.runRound(Instant.now());

        verify(listService).claimDue(any(), eq(5));
        verify(listService).claimDue(any(), eq(2));
    }

    @Test
    void nothing_due_is_one_claim_and_no_work() {
        stubClaims(0);

        assertThat(tick.runRound(Instant.now())).isZero();
        verify(listService, times(1)).claimDue(any(), anyInt());
    }

    /** One list that throws must not end the round for the other sixty-four. */
    @Test
    void a_failing_list_does_not_stop_the_round() {
        stubClaims(3, 0);
        doThrow(new IllegalStateException("boom"))
                .when(listService).refresh(any(SourceListDocument.class), any());

        assertThat(tick.runRound(Instant.now())).isEqualTo(3);
        verify(listService, times(3)).refresh(any(SourceListDocument.class), any());
    }

    /** Each claim returns the next batch size in the sequence. */
    private void stubClaims(int... sizes) {
        Queue<Integer> remaining = new LinkedList<>();
        for (int size : sizes) {
            remaining.add(size);
        }
        when(listService.claimDue(any(), anyInt())).thenAnswer(invocation -> {
            Integer size = remaining.poll();
            if (size == null) {
                return List.of();
            }
            List<SourceListDocument> batch = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                batch.add(SourceListDocument.builder().name("list-" + i).build());
            }
            return batch;
        });
    }
}
