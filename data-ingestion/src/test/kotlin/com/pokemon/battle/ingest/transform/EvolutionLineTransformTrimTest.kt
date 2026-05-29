package com.pokemon.battle.ingest.transform

import com.pokemon.battle.data.AlternativeAccess
import com.pokemon.battle.data.EvolutionDelayAdvisor
import com.pokemon.battle.data.EvolutionEdgeJson
import com.pokemon.battle.data.EvolutionLineJson
import com.pokemon.battle.data.MoveAcquisitionJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the one cross-layer assumption in the ingestion pipeline: the build-time
 * TM-trim ([EvolutionLineTransform.trimToAdvisorRelevant]) is *lossless for the advisor*.
 *
 * The trim drops acquisitions the [EvolutionDelayAdvisor] cannot currently read. If the
 * advisor's rule ever changes to read what the trim discards, the committed bundles
 * would be silently missing that data — the failure mode the diary-103 review flagged
 * as "an invariant only in our heads." This test makes it falsifiable: it asserts the
 * advisor produces *identical* flags on full vs trimmed learnsets, and (so the check
 * isn't vacuous) that the trim genuinely removes data.
 */
class EvolutionLineTransformTrimTest {
    private companion object {
        const val VG = "black-white"
        const val PRE = "pre"
        const val EVO = "evo"
    }

    /**
     * A line exercising every category the trim reasons about:
     *  - `keeper`  — pre-evo level-up move (a delay candidate); evo has it only by tutor,
     *                which the advisor reads for `alternativeAccess`, so it MUST survive.
     *  - `dropped` — TM-only on both stages, no stage level-learns it: pure dead weight.
     */
    private val fullLearnsets: Map<String, Map<String, List<MoveAcquisitionJson>>> =
        mapOf(
            PRE to
                mapOf(
                    VG to
                        listOf(
                            MoveAcquisitionJson("keeper", "level-up", 20),
                            MoveAcquisitionJson("dropped", "machine", 0),
                        ),
                ),
            EVO to
                mapOf(
                    VG to
                        listOf(
                            MoveAcquisitionJson("keeper", "tutor", 0),
                            MoveAcquisitionJson("dropped", "machine", 0),
                        ),
                ),
        )

    private fun adviseOn(learnsets: Map<String, Map<String, List<MoveAcquisitionJson>>>) =
        EvolutionDelayAdvisor.adviseDelays(
            EvolutionLineJson(
                base = PRE,
                edges = listOf(EvolutionEdgeJson(PRE, EVO, "level-up", minLevel = 10)),
                learnsets = learnsets,
            ).toDomain(),
            VG,
        )

    private fun acquisitionCount(learnsets: Map<String, Map<String, List<MoveAcquisitionJson>>>): Int =
        learnsets.values.sumOf { byVg -> byVg.values.sumOf { it.size } }

    @Test
    fun `trim is lossless for the advisor — identical flags before and after`() {
        val trimmed = EvolutionLineTransform.trimToAdvisorRelevant(fullLearnsets)

        assertTrue(
            acquisitionCount(trimmed) < acquisitionCount(fullLearnsets),
            "trim must actually drop data, else this guard is vacuous",
        )
        assertEquals(
            adviseOn(fullLearnsets),
            adviseOn(trimmed),
            "advisor output must not depend on data the trim removes",
        )
    }

    @Test
    fun `the surviving tutor still feeds alternativeAccess on the kept move`() {
        // Confirms the trim keeps non-level-up acquisitions of a level-up move: keeper is
        // a loss on the evo (no level-up there), and its tutor route must still register.
        val flag = adviseOn(EvolutionLineTransform.trimToAdvisorRelevant(fullLearnsets)).single()
        assertEquals("keeper", flag.move)
        assertEquals(AlternativeAccess.TUTOR, flag.alternativeAccess)
    }
}
