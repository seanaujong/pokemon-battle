package com.pokemon.battle.server.team

import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Nature
import com.pokemon.battle.model.StatBlock

/**
 * Parses Smogon-format team text into a list of [SmogonSet]. One set per blank-line-
 * separated block. Minimal support for v1: species + item + ability + level + nature +
 * IVs + EVs + up to 4 moves. Nickname, gender, shiny, and Tera type are not yet parsed;
 * they're dropped on the floor.
 *
 * Example input (one set):
 * ```
 * Charizard @ Life Orb
 * Ability: Blaze
 * Level: 50
 * EVs: 252 SpA / 4 SpD / 252 Spe
 * Timid Nature
 * - Flamethrower
 * - Air Slash
 * - Focus Blast
 * - Roost
 * ```
 */
object SmogonParser {
    fun parseTeam(text: String): List<SmogonSet> =
        text.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::parseSet)

    private fun parseSet(block: String): SmogonSet {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.isNotEmpty()) { "Empty set block" }

        val (species, item) = parseHeader(lines[0])
        var ability: Ability? = null
        var level = 100
        var nature = Nature.HARDY
        var evs = StatBlock.uniform(0)
        var ivs = StatBlock.uniform(31)
        val moves = mutableListOf<String>()

        for (line in lines.drop(1)) {
            when {
                line.startsWith("Ability:") -> ability = parseAbility(line.substringAfter(":").trim())
                line.startsWith("Level:") -> level = line.substringAfter(":").trim().toInt()
                line.startsWith("EVs:") -> evs = parseStatLine(line.substringAfter(":"), default = 0)
                line.startsWith("IVs:") -> ivs = parseStatLine(line.substringAfter(":"), default = 31)
                line.endsWith(" Nature") -> nature = parseNature(line.removeSuffix(" Nature").trim())
                line.startsWith("-") -> moves.add(line.removePrefix("-").trim())
                else -> {} // ignore nickname/gender/shiny/tera for v1
            }
        }

        requireNotNull(ability) { "Set for $species missing Ability line" }
        require(moves.isNotEmpty()) { "Set for $species has no moves" }

        return SmogonSet(
            species = species,
            item = item,
            ability = ability,
            level = level,
            nature = nature,
            ivs = ivs,
            evs = evs,
            moves = moves,
        )
    }

    private fun parseHeader(line: String): Pair<String, Item?> {
        // "Charizard @ Life Orb" or "Charizard" (no item)
        // Nickname forms "Nick (Charizard) @ Life Orb" deferred to later; for v1 assume
        // the first segment is the species.
        val (namePart, itemPart) =
            if ("@" in line) {
                val (l, r) = line.split("@", limit = 2).map { it.trim() }
                l to r
            } else {
                line.trim() to null
            }
        val species = namePart.substringAfter("(").substringBefore(")").ifBlank { namePart }
        val item = itemPart?.let(::parseItem)
        return species to item
    }

    private fun parseStatLine(
        raw: String,
        default: Int,
    ): StatBlock {
        var hp = default
        var atk = default
        var def = default
        var spa = default
        var spd = default
        var spe = default
        for (piece in raw.split("/").map { it.trim() }.filter { it.isNotEmpty() }) {
            val (amountStr, statStr) = piece.split(" ", limit = 2).map { it.trim() }
            val amount = amountStr.toInt()
            when (statStr.uppercase()) {
                "HP" -> hp = amount
                "ATK" -> atk = amount
                "DEF" -> def = amount
                "SPA" -> spa = amount
                "SPD" -> spd = amount
                "SPE" -> spe = amount
                else -> error("Unknown stat token '$statStr' in '$raw'")
            }
        }
        return StatBlock(hp, atk, def, spa, spd, spe)
    }

    private fun parseItem(name: String): Item = Item.valueOf(toEnumName(name))

    private fun parseAbility(name: String): Ability = Ability.valueOf(toEnumName(name))

    private fun parseNature(name: String): Nature = Nature.valueOf(name.uppercase())

    private fun toEnumName(displayName: String): String = displayName.trim().replace(" ", "_").replace("-", "_").uppercase()
}
