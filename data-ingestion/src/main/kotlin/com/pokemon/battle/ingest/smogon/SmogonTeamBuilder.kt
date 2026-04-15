package com.pokemon.battle.ingest.smogon

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.Type
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Materializes engine [Pokemon]/[PokemonState] from a [SmogonFormatSets] JSON
 * (e.g. `data/smogon/gen5ou-1760-top-sets.json`). For each requested species,
 * picks the top-rank ability/item and the top-N moves *that we actually
 * implement*, falling back per `OnUnsupported`.
 *
 * Diary 099: replaces the hardcoded matrix team pool with Smogon-informed sets
 * so matrix outcomes carry meaningful play signal.
 *
 * Name canonicalization: Smogon writes ability/item/move names as lowercase no
 * whitespace ("ironbarbs", "choicescarf", "powerwhip"). Our enums and move
 * names retain casing/spaces. The matcher canonicalizes by lowercasing and
 * stripping non-alphanumerics on both sides.
 */
@Suppress("TooManyFunctions") // Public API: parse + build (Pokemon/State) + per-axis pickers + lookups
object SmogonTeamBuilder {
    private val json = Json { ignoreUnknownKeys = true }

    /** What to do when a requested species/ability/item/move isn't available. */
    enum class OnUnsupported {
        /** Throw an [IllegalStateException] — fail loudly. Default for tests / matrix-eval. */
        FAIL,

        /** Drop the species (or fall back to "no item / no ability / fewer moves"). */
        SKIP,
    }

    /** Parse a [SmogonFormatSets] JSON from disk. Exposed so CLI callers don't need
     * a kotlinx-serialization dependency just to read a Smogon top-sets file. */
    fun loadSets(path: Path): SmogonFormatSets = json.decodeFromString(SmogonFormatSets.serializer(), Files.readString(path))

    /** Build a team from a path on disk. Convenience for CLI/test callers. */
    fun loadFromFile(
        path: Path,
        speciesNames: List<String>,
        pokedex: Map<String, Species>,
        teraTypes: Map<String, Type> = emptyMap(),
        onUnsupported: OnUnsupported = OnUnsupported.FAIL,
    ): List<Pokemon> {
        val sets = json.decodeFromString(SmogonFormatSets.serializer(), Files.readString(path))
        return build(sets, speciesNames, pokedex, teraTypes, onUnsupported)
    }

    /** Build a list of [Pokemon] from a parsed [SmogonFormatSets]. */
    fun build(
        sets: SmogonFormatSets,
        speciesNames: List<String>,
        pokedex: Map<String, Species>,
        teraTypes: Map<String, Type> = emptyMap(),
        onUnsupported: OnUnsupported = OnUnsupported.FAIL,
    ): List<Pokemon> {
        val byName = sets.topSpecies.associateBy { it.name }
        return speciesNames.mapNotNull { name ->
            val set = byName[name] ?: return@mapNotNull failOrSkip("species not in Smogon set", name, onUnsupported)
            val species = pokedex[name] ?: return@mapNotNull failOrSkip("species not in Pokedex", name, onUnsupported)
            Pokemon(species = species, level = LEVEL, teraType = teraTypes[name])
        }
    }

    /**
     * Build a [PokemonState] from the Smogon set: top ability + top item we support, current HP =
     * max HP. Returns null when fail/skip rules apply.
     */
    fun buildState(
        sets: SmogonFormatSets,
        speciesName: String,
        pokedex: Map<String, Species>,
        teraTypes: Map<String, Type> = emptyMap(),
        onUnsupported: OnUnsupported = OnUnsupported.FAIL,
    ): PokemonState? {
        val set =
            sets.topSpecies.firstOrNull { it.name == speciesName }
                ?: return failOrSkip("species not in Smogon set", speciesName, onUnsupported)?.let { null }
        val species =
            pokedex[speciesName]
                ?: return failOrSkip("species not in Pokedex", speciesName, onUnsupported)?.let { null }
        val ability = pickAbility(set.topAbilities, speciesName, onUnsupported)
        val item = pickItem(set.topItems, speciesName, onUnsupported)
        val pokemon = Pokemon(species = species, level = LEVEL, teraType = teraTypes[speciesName])
        return PokemonState(
            pokemon = pokemon,
            currentHp = pokemon.maxHp,
            ability = ability,
            item = item,
        )
    }

    /**
     * Resolve the Smogon move list to engine [Move]s — top 4 we support, in declaration order.
     * Returns the list (possibly shorter than 4) so callers can warn / pad / fail.
     */
    fun pickMoves(
        speciesName: String,
        smogonMoves: List<String>,
        onUnsupported: OnUnsupported = OnUnsupported.FAIL,
    ): List<Move> {
        val resolved = smogonMoves.mapNotNull { lookupMove(it) }
        if (resolved.size < smogonMoves.size && onUnsupported == OnUnsupported.FAIL) {
            val unresolved = smogonMoves.filter { lookupMove(it) == null }
            error("$speciesName: unsupported moves $unresolved (have ${smogonMoves.size}, resolved ${resolved.size})")
        }
        return resolved.take(MOVES_PER_POKEMON)
    }

    private fun pickAbility(
        smogonAbilities: List<String>,
        speciesName: String,
        onUnsupported: OnUnsupported,
    ): Ability? {
        val match = smogonAbilities.firstNotNullOfOrNull { lookupAbility(it) }
        if (match == null) {
            failOrSkip("no supported ability among $smogonAbilities", speciesName, onUnsupported)
        }
        return match
    }

    private fun pickItem(
        smogonItems: List<String>,
        speciesName: String,
        onUnsupported: OnUnsupported,
    ): Item? {
        val match = smogonItems.firstNotNullOfOrNull { lookupItem(it) }
        if (match == null) {
            failOrSkip("no supported item among $smogonItems", speciesName, onUnsupported)
        }
        return match
    }

    private fun lookupAbility(smogonName: String): Ability? = Ability.entries.firstOrNull { canonical(it.name) == canonical(smogonName) }

    private fun lookupItem(smogonName: String): Item? = Item.entries.firstOrNull { canonical(it.name) == canonical(smogonName) }

    private fun lookupMove(smogonName: String): Move? = MoveDex.all.values.firstOrNull { canonical(it.name) == canonical(smogonName) }

    private fun canonical(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun failOrSkip(
        reason: String,
        context: String,
        mode: OnUnsupported,
    ): Nothing? {
        if (mode == OnUnsupported.FAIL) error("Smogon team builder: $reason ($context)")
        return null
    }

    private const val LEVEL = 50
    private const val MOVES_PER_POKEMON = 4
}
