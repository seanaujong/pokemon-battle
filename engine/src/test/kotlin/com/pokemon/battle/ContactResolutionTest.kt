package com.pokemon.battle

import com.pokemon.battle.engine.ability.AbilityEffect
import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.item.ItemEffect
import com.pokemon.battle.engine.item.ItemRegistry
import com.pokemon.battle.engine.resolveIsContact
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.Type
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the attacker-side contact override seam introduced in diary 088. The
 * canonical motivating interaction is Gen 9's Punching Glove (item that negates
 * contact on punching moves). We don't implement Punching Glove here — Gen 9 is
 * out of scope — but a stub item/ability shows the seam resolves correctly.
 *
 * If this test breaks, the architecture no longer supports Punching Glove / Long
 * Reach / Protective-Pads-style interactions without touching defender-side code.
 */
class ContactResolutionTest {
    private val species =
        Species(
            name = "Testmon",
            types = listOf(Type.NORMAL),
            baseHp = 100,
            baseAttack = 80,
            baseDefense = 80,
            baseSpecialAttack = 80,
            baseSpecialDefense = 80,
            baseSpeed = 80,
        )
    private val contactMove = Move("Tackle", Type.NORMAL, MoveCategory.PHYSICAL, 40, contact = true)
    private val nonContactMove = Move("Flamethrower", Type.FIRE, MoveCategory.SPECIAL, 90)

    private fun pokemonState(
        ability: Ability? = null,
        item: Item? = null,
    ): PokemonState = PokemonState(Pokemon(species, level = 50), currentHp = 100, ability = ability, item = item)

    /** Stub item that mimics Punching Glove: force contact = false on any move. */
    private object FakePunchingGlove : ItemEffect {
        override val item = Item.LEFTOVERS // piggybacks on an enum we have; irrelevant to the test's assertion

        override fun overridesContact(move: Move): Boolean = false
    }

    /** Stub ability that mimics Long Reach: force contact = false on any move. */
    private object FakeLongReach : AbilityEffect {
        override val ability = Ability.KLUTZ // piggybacks on an enum; irrelevant

        override fun overridesContact(move: Move): Boolean = false
    }

    private val emptyAbilities = AbilityRegistry(emptyList())
    private val emptyItems = ItemRegistry(emptyList(), emptyAbilities)

    @Test
    fun `default resolution returns Move contact flag`() {
        val attacker = pokemonState()
        assertTrue(resolveIsContact(contactMove, attacker, emptyItems, emptyAbilities))
        assertFalse(resolveIsContact(nonContactMove, attacker, emptyItems, emptyAbilities))
    }

    @Test
    fun `item override wins over Move contact flag`() {
        val items = ItemRegistry(listOf(FakePunchingGlove), emptyAbilities)
        val attacker = pokemonState(item = Item.LEFTOVERS)
        // Tackle is a contact move; Punching Glove-stub negates it.
        assertFalse(
            resolveIsContact(contactMove, attacker, items, emptyAbilities),
            "item override should force contact=false",
        )
    }

    @Test
    fun `ability override wins over item override`() {
        val abilities = AbilityRegistry(listOf(FakeLongReach))
        val forcesContactTrueItem =
            object : ItemEffect {
                override val item = Item.LEFTOVERS

                override fun overridesContact(move: Move) = true
            }
        val items = ItemRegistry(listOf(forcesContactTrueItem), abilities)
        val attacker = pokemonState(ability = Ability.KLUTZ, item = Item.LEFTOVERS)
        // Ability says false, item says true → ability wins.
        assertFalse(
            resolveIsContact(nonContactMove, attacker, items, abilities),
            "ability overridesContact(false) should beat item overridesContact(true)",
        )
    }

    @Test
    fun `absent registry entries fall through to Move default`() {
        // Attacker's ability / item aren't registered, so overridesContact returns null
        // and the Move's own flag decides.
        val attacker = pokemonState(ability = Ability.BLAZE, item = Item.LIFE_ORB)
        assertTrue(resolveIsContact(contactMove, attacker, emptyItems, emptyAbilities))
        assertFalse(resolveIsContact(nonContactMove, attacker, emptyItems, emptyAbilities))
    }
}
