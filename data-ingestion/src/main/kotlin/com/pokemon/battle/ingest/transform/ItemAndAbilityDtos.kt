package com.pokemon.battle.ingest.transform

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal DTOs for PokeAPI item / ability responses. Mirrors the pattern of
 * [com.pokemon.battle.ingest.fetch.PokeApiPokemon]: only the fields we actually
 * project into committed raw files. See diary 064's update on the model gap
 * — the fields we *don't* model in our domain (category, attributes,
 * generation, etc.) are exactly what the audit shows as missing.
 */
@Serializable
data class PokeApiItem(
    val name: String,
    val category: NamedRef,
    val attributes: List<NamedRef> = emptyList(),
    @SerialName("fling_power") val flingPower: Int? = null,
    @SerialName("effect_entries") val effectEntries: List<EffectEntry> = emptyList(),
)

@Serializable
data class PokeApiAbility(
    val name: String,
    val generation: NamedRef,
    @SerialName("is_main_series") val isMainSeries: Boolean = true,
    @SerialName("effect_entries") val effectEntries: List<EffectEntry> = emptyList(),
)

@Serializable
data class NamedRef(val name: String)

@Serializable
data class EffectEntry(
    @SerialName("short_effect") val shortEffect: String = "",
    val effect: String = "",
    val language: NamedRef,
)

/** First English short_effect, or empty if none. */
fun List<EffectEntry>.englishShort(): String = firstOrNull { it.language.name == "en" }?.shortEffect ?: ""
