package com.pokemon.battle

import com.pokemon.battle.engine.*
import com.pokemon.battle.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IvsEvsNaturesTest {
    private val species =
        Species(
            name = "TestMon",
            types = listOf(Type.NORMAL),
            baseHp = 80,
            baseAttack = 100,
            baseDefense = 80,
            baseSpecialAttack = 80,
            baseSpecialDefense = 80,
            baseSpeed = 100,
        )

    // --- Stat formula ---

    @Test
    fun `default IVs are 31, EVs are 0, nature is neutral`() {
        val pokemon = Pokemon(species, level = 50)
        assertEquals(31, pokemon.ivs.attack)
        assertEquals(0, pokemon.evs.attack)
        assertEquals(Nature.HARDY, pokemon.nature)
    }

    @Test
    fun `HP formula includes IVs and EVs`() {
        // Base 80, level 50, 31 IV, 0 EV: ((160+31)*50)/100 + 60 = 155
        assertEquals(155, calcMaxHp(80, 50, iv = 31, ev = 0))
        // With 252 EVs: ((160+31+63)*50)/100 + 60 = (254*50)/100 + 60 = 127 + 60 = 187
        assertEquals(187, calcMaxHp(80, 50, iv = 31, ev = 252))
        // With 0 IVs, 0 EVs: ((160)*50)/100 + 60 = 80 + 60 = 140
        assertEquals(140, calcMaxHp(80, 50, iv = 0, ev = 0))
    }

    @Test
    fun `stat formula includes IVs, EVs, and nature`() {
        // Base 100, level 50, 31 IV, 0 EV, neutral: ((200+31)*50)/100 + 5 = 120
        assertEquals(120, calcStat(100, 50, iv = 31, ev = 0, natureMod = 1.0))
        // With Adamant (+Atk): 120 * 1.1 = 132
        assertEquals(132, calcStat(100, 50, iv = 31, ev = 0, natureMod = 1.1))
        // With Modest (-Atk): 120 * 0.9 = 108
        assertEquals(108, calcStat(100, 50, iv = 31, ev = 0, natureMod = 0.9))
        // With 252 EVs, neutral: ((200+31+63)*50)/100 + 5 = 147 + 5 = 152
        assertEquals(152, calcStat(100, 50, iv = 31, ev = 252, natureMod = 1.0))
    }

    // --- Nature ---

    @Test
    fun `Adamant nature boosts Attack and lowers Special Attack`() {
        assertEquals(1.1, Nature.ADAMANT.modifier(StatType.ATTACK))
        assertEquals(0.9, Nature.ADAMANT.modifier(StatType.SPECIAL_ATTACK))
        assertEquals(1.0, Nature.ADAMANT.modifier(StatType.DEFENSE))
        assertEquals(1.0, Nature.ADAMANT.modifier(StatType.SPEED))
    }

    @Test
    fun `neutral natures have no modifiers`() {
        for (stat in StatType.entries) {
            assertEquals(1.0, Nature.HARDY.modifier(stat))
        }
    }

    @Test
    fun `Pokemon calcStat uses IVs EVs and nature`() {
        val adamantPokemon =
            Pokemon(
                species,
                level = 50,
                ivs = StatBlock.uniform(31),
                evs = StatBlock(hp = 0, attack = 252, defense = 0, specialAttack = 0, specialDefense = 0, speed = 0),
                nature = Nature.ADAMANT,
            )
        // Attack: base 100, 31 IV, 252 EV, Adamant (1.1x)
        // ((200+31+63)*50)/100 + 5 = 152, * 1.1 = 167.2 → 167
        assertEquals(167, adamantPokemon.calcStat(StatType.ATTACK))

        // Speed: base 100, 31 IV, 0 EV, neutral (Adamant doesn't affect speed)
        // ((200+31)*50)/100 + 5 = 120
        assertEquals(120, adamantPokemon.calcStat(StatType.SPEED))
    }

    // --- IVs/EVs affect damage ---

    @Test
    fun `EV investment increases damage output`() {
        val basePokemon = Pokemon(species, level = 50)
        val investedPokemon =
            Pokemon(
                species,
                level = 50,
                evs = StatBlock(hp = 0, attack = 252, defense = 0, specialAttack = 0, specialDefense = 0, speed = 0),
            )

        val defender = PokemonState(basePokemon, currentHp = basePokemon.maxHp)
        val tackle = Move("Tackle", Type.NORMAL, MoveCategory.PHYSICAL, power = 40)

        val baseDamage =
            calculateDamage(
                PokemonState(basePokemon, currentHp = basePokemon.maxHp),
                defender,
                tackle,
                roll = { 100 },
            )
        val investedDamage =
            calculateDamage(
                PokemonState(investedPokemon, currentHp = investedPokemon.maxHp),
                defender,
                tackle,
                roll = { 100 },
            )

        assertTrue(
            investedDamage.damage > baseDamage.damage,
            "252 Atk EVs ($investedDamage) should deal more than 0 EVs ($baseDamage)",
        )
    }

    @Test
    fun `nature affects damage output`() {
        val neutralPokemon = Pokemon(species, level = 50, nature = Nature.HARDY)
        val adamantPokemon = Pokemon(species, level = 50, nature = Nature.ADAMANT)

        val defender = PokemonState(neutralPokemon, currentHp = neutralPokemon.maxHp)
        val tackle = Move("Tackle", Type.NORMAL, MoveCategory.PHYSICAL, power = 40)

        val neutralDamage =
            calculateDamage(
                PokemonState(neutralPokemon, currentHp = neutralPokemon.maxHp),
                defender,
                tackle,
                roll = { 100 },
            )
        val adamantDamage =
            calculateDamage(
                PokemonState(adamantPokemon, currentHp = adamantPokemon.maxHp),
                defender,
                tackle,
                roll = { 100 },
            )

        assertTrue(
            adamantDamage.damage > neutralDamage.damage,
            "Adamant ($adamantDamage) should deal more physical damage than neutral ($neutralDamage)",
        )
    }
}
