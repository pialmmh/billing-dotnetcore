package com.telcobright.billing.data;

import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.CdrSummaryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Regression pins for ultrareview PR#2 findings.
 *
 * <p>Finding 1 (was a live production time bomb): {@code MySqlSummaryStore.MapRow} had no cases for the
 * SG15 tables ({@code sum_voice_day_05} / {@code sum_voice_hr_05}) — the first time an international
 * hour-bucket needed to merge into an EXISTING _05 row, the rollup threw, rolled back, and the pinned
 * offset stalled that tenant's rollup forever. {@link #every_summary_type_has_a_mapped_entity()} walks
 * the ENUM, so adding a new sum_voice_* type without wiring the store fails the build.</p>
 *
 * <p>Finding 2: {@code AbstractCdrSummary.CloneWithFakeId} always constructed a {@code sum_voice_day_03}
 * regardless of the source's concrete type (harmless today only because the cache substitutes the table
 * name — a latent trap for any code that switches on the clone's class).</p>
 */
class SummaryEntityMappingTests {

    /** The types every ROUTED service group folds into (SG10 -> _03, SG11 -> _02, SG15 -> _05). */
    private static final CdrSummaryType[] ROUTED = {
            CdrSummaryType.sum_voice_day_02, CdrSummaryType.sum_voice_hr_02,
            CdrSummaryType.sum_voice_day_03, CdrSummaryType.sum_voice_hr_03,
            CdrSummaryType.sum_voice_day_05, CdrSummaryType.sum_voice_hr_05,
    };

    /** EVERY routed CdrSummaryType must map to an entity whose class name equals the table name. */
    @Test
    void every_routed_summary_type_has_a_mapped_entity() {
        for (CdrSummaryType t : ROUTED) {
            AbstractCdrSummary s = MySqlSummaryStore.NewEntity(t);
            assertEquals(t.toString(), s.getClass().getSimpleName(),
                    () -> "entity mapped for " + t + " is the wrong concrete type");
        }
    }

    /** A clone must BE the source's concrete type — sum_voice_hr_05 clones to sum_voice_hr_05, not day_03. */
    @Test
    void clone_with_fake_id_preserves_concrete_type() {
        for (CdrSummaryType t : ROUTED) {
            AbstractCdrSummary src = MySqlSummaryStore.NewEntity(t);
            AbstractCdrSummary clone = src.CloneWithFakeId();
            assertNotSame(src, clone);
            assertEquals(src.getClass(), clone.getClass(),
                    () -> "CloneWithFakeId for " + t + " returned " + clone.getClass().getSimpleName());
            assertEquals(-1L, clone.id, "clone id must be the -1 sentinel (set externally)");
        }
    }
}
