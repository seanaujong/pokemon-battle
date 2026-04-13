package com.pokemon.battle.data

import com.pokemon.battle.model.Species
import com.pokemon.battle.model.Type
import java.io.InputStream

object Pokedex {

    fun load(input: InputStream): Map<String, Species> {
        return input.bufferedReader().useLines { lines ->
            lines.drop(1) // skip header
                .filter { it.isNotBlank() }
                .map { parseLine(it) }
                .associateBy { it.name }
        }
    }

    fun loadFromClasspath(path: String = "data/species.csv"): Map<String, Species> {
        val stream = Pokedex::class.java.classLoader.getResourceAsStream(path)
            ?: error("Species CSV not found on classpath: $path")
        return load(stream)
    }

    private fun parseLine(line: String): Species {
        val parts = line.split(",").map { it.trim() }
        require(parts.size >= 9) { "Malformed species line (expected 9 columns): $line" }

        val name = parts[0]
        val type1 = parseType(parts[1], name)
        val type2 = if (parts[2].isNotEmpty()) parseType(parts[2], name) else null
        val types = listOfNotNull(type1, type2)

        return Species(
            name = name,
            types = types,
            baseHp = parts[3].toInt(),
            baseAttack = parts[4].toInt(),
            baseDefense = parts[5].toInt(),
            baseSpecialAttack = parts[6].toInt(),
            baseSpecialDefense = parts[7].toInt(),
            baseSpeed = parts[8].toInt()
        )
    }

    private fun parseType(value: String, speciesName: String): Type {
        return try {
            Type.valueOf(value)
        } catch (e: IllegalArgumentException) {
            error("Unknown type '$value' for species '$speciesName'")
        }
    }
}
