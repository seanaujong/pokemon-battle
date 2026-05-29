package com.pokemon.battle.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden tests for [EvolutionDelayAdvisor]. Expected values are read from the committed
 * line bundles (`dex/evolution-lines/`), not from recollection — the repo's "let the
 * code tell you" rule. The data was ingested from PokeAPI; the assertions below were
 * confirmed by running the advisor, then frozen here.
 */
class EvolutionDelayAdvisorTest {
    private val lines = EvolutionLineDex.loadFromClasspath()

    private fun line(base: String): EvolutionLine = lines.getValue(base)

    private fun flag(
        base: String,
        versionGroup: String,
        move: String,
    ): DelayFlag? = EvolutionDelayAdvisor.adviseDelays(line(base), versionGroup).firstOrNull { it.move == move }

    // ---------------------------------------------------------------------------------
    // Mainline Pokemon mechanics — reachable in normal play
    // ---------------------------------------------------------------------------------

    @Test
    fun `kricketot must delay for bug bite in gen IV — kricketune has no level-up route`() {
        val flags = EvolutionDelayAdvisor.adviseDelays(line("kricketot"), "platinum")
        assertEquals(1, flags.size, "Kricketot only learns Bug Bite above its evolution level")
        val f = flags.single()
        assertEquals("bug-bite", f.move)
        assertEquals("kricketot", f.edgeFrom)
        assertEquals("kricketune", f.edgeTo)
        assertEquals(16, f.holdToLevel)
        assertEquals(10, f.evolveAtLevel)
        assertEquals(AlternativeAccess.NONE, f.alternativeAccess)
    }

    @Test
    fun `kricketot bug bite alternative route shifts across generations`() {
        // Same loss every gen, but the fallback differs: unobtainable early, TM-able by gen IX.
        assertEquals(AlternativeAccess.NONE, flag("kricketot", "platinum", "bug-bite")?.alternativeAccess)
        assertEquals(AlternativeAccess.NONE, flag("kricketot", "black-white", "bug-bite")?.alternativeAccess)
        assertEquals(AlternativeAccess.MACHINE, flag("kricketot", "scarlet-violet", "bug-bite")?.alternativeAccess)
    }

    @Test
    fun `hoothoot is never delay-worthy in any generation — noctowl relearns everything`() {
        // The canonical negative case: every move Hoothoot learns, Noctowl relearns by
        // reachable level-up (a few levels later). "Saves levels" is not a loss.
        val hoothoot = line("hoothoot")
        for (versionGroup in hoothoot.versionGroups()) {
            assertTrue(
                EvolutionDelayAdvisor.adviseDelays(hoothoot, versionGroup).isEmpty(),
                "expected no delay flags for Hoothoot in $versionGroup",
            )
        }
    }

    @Test
    fun `caterpie bug bite is a loss in every gen — never level-up on butterfree`() {
        val genV = flag("caterpie", "black-white", "bug-bite")
        assertNotNull(genV)
        assertEquals("caterpie", genV.edgeFrom)
        assertEquals("metapod", genV.edgeTo)
        assertEquals(AlternativeAccess.NONE, genV.alternativeAccess)
        // Butterfree relearns it at level 1 by gen VIII — still a level-up loss, but recoverable.
        assertEquals(AlternativeAccess.RELEARN_ONLY, flag("caterpie", "sword-shield", "bug-bite")?.alternativeAccess)
    }

    @Test
    fun `pansage stone evolution flags crunch simisage can never learn`() {
        val f = flag("pansage", "black-white", "crunch")
        assertNotNull(f)
        assertEquals("simisage", f.edgeTo)
        assertEquals(43, f.holdToLevel)
        assertNull(f.evolveAtLevel, "stone evolutions have no level gate")
        assertEquals(AlternativeAccess.NONE, f.alternativeAccess)
    }

    @Test
    fun `skitty into delcatty flags charm — gutted stone learnset`() {
        assertEquals(AlternativeAccess.NONE, flag("skitty", "black-white", "charm")?.alternativeAccess)
    }

    // ---------------------------------------------------------------------------------
    // Custom-format / extensibility — synthetic lines, hand-built to isolate the rule
    // ---------------------------------------------------------------------------------

    @Test
    fun `level-up evolution flags exactly the moves the evolved form loses`() {
        // Artificial line (made-up moves) so each of the rule's four branches is hit in
        // isolation, independent of any real species' learnset. Verifies the advisor's
        // genuine-level-up-loss rule directly.
        val synthetic =
            EvolutionLine(
                base = "alpha",
                edges = listOf(EvolutionEdge("alpha", "beta", EvolutionTrigger.LEVEL_UP, 10, null)),
                learnsets =
                    mapOf(
                        "alpha" to
                            mapOf(
                                "test" to
                                    listOf(
                                        MoveAcquisition("base", LearnMethod.LEVEL_UP, 1),
                                        MoveAcquisition("keeper", LearnMethod.LEVEL_UP, 20),
                                        MoveAcquisition("lost", LearnMethod.LEVEL_UP, 25),
                                        MoveAcquisition("relearn", LearnMethod.LEVEL_UP, 30),
                                    ),
                            ),
                        "beta" to
                            mapOf(
                                "test" to
                                    listOf(
                                        MoveAcquisition("keeper", LearnMethod.LEVEL_UP, 40),
                                        MoveAcquisition("relearn", LearnMethod.LEVEL_UP, 1),
                                    ),
                            ),
                    ),
            )
        val byMove = EvolutionDelayAdvisor.adviseDelays(synthetic, "test").associateBy { it.move }

        assertEquals(setOf("lost", "relearn"), byMove.keys, "only genuine level-up losses are flagged")
        assertFalse("base" in byMove, "a level-1 base move carries through evolution")
        assertFalse("keeper" in byMove, "beta relearns it by level-up >= 2, so it is not a loss")
        assertEquals(AlternativeAccess.NONE, byMove.getValue("lost").alternativeAccess)
        assertEquals(AlternativeAccess.RELEARN_ONLY, byMove.getValue("relearn").alternativeAccess)
    }

    @Test
    fun `stone evolution has no level gate so any unretained level-up move is flagged`() {
        // Stone/trade evolutions are player-timed: even a low-level move counts, because
        // you choose when to evolve. Contrasted with the level-up case above (gated).
        val synthetic =
            EvolutionLine(
                base = "alpha",
                edges = listOf(EvolutionEdge("alpha", "beta", EvolutionTrigger.USE_ITEM, null, "thunder-stone")),
                learnsets =
                    mapOf(
                        "alpha" to mapOf("test" to listOf(MoveAcquisition("early", LearnMethod.LEVEL_UP, 5))),
                        "beta" to mapOf("test" to emptyList()),
                    ),
            )
        val flags = EvolutionDelayAdvisor.adviseDelays(synthetic, "test")
        assertEquals(1, flags.size)
        assertEquals("early", flags.single().move)
        assertEquals(5, flags.single().holdToLevel)
        assertNull(flags.single().evolveAtLevel)
        assertEquals(AlternativeAccess.NONE, flags.single().alternativeAccess)
    }
}
