package com.pokemon.battle.data

import com.pokemon.battle.data.MoveDex.register
import com.pokemon.battle.model.*

/**
 * Move definitions. Each move is fully specified in one place:
 * base data (type, power, priority, target) and effects (stat boosts, etc.).
 *
 * All moves are automatically registered via [register] — defining a move
 * and registering it for lookup are the same step.
 */
object MoveDex {
    private val _moves = mutableMapOf<String, Move>()

    private fun register(move: Move): Move {
        _moves[move.name] = move
        return move
    }

    // --- Physical moves ---

    val TACKLE = register(Move("Tackle", Type.NORMAL, MoveCategory.PHYSICAL, 40))

    val MACH_PUNCH = register(Move("Mach Punch", Type.FIGHTING, MoveCategory.PHYSICAL, 40, priority = 1))

    val EARTHQUAKE =
        register(
            Move(
                "Earthquake",
                Type.GROUND,
                MoveCategory.PHYSICAL,
                100,
                target = MoveTarget.ALL_OTHER,
            ),
        )

    val MUD_SLAP = register(Move("Mud-Slap", Type.GROUND, MoveCategory.SPECIAL, 20))

    // --- Special moves ---

    val FLAMETHROWER = register(Move("Flamethrower", Type.FIRE, MoveCategory.SPECIAL, 90))

    val SLUDGE_BOMB = register(Move("Sludge Bomb", Type.POISON, MoveCategory.SPECIAL, 90))

    val HYPER_VOICE =
        register(
            Move(
                "Hyper Voice",
                Type.NORMAL,
                MoveCategory.SPECIAL,
                90,
                target = MoveTarget.ALL_OPPONENTS,
            ),
        )

    val THUNDERBOLT = register(Move("Thunderbolt", Type.ELECTRIC, MoveCategory.SPECIAL, 90))

    val ICE_BEAM = register(Move("Ice Beam", Type.ICE, MoveCategory.SPECIAL, 90))

    val SHADOW_BALL = register(Move("Shadow Ball", Type.GHOST, MoveCategory.SPECIAL, 80))

    val AURA_SPHERE = register(Move("Aura Sphere", Type.FIGHTING, MoveCategory.SPECIAL, 80))

    // --- Status moves ---

    val SWORDS_DANCE =
        register(
            Move(
                "Swords Dance",
                Type.NORMAL,
                MoveCategory.STATUS,
                0,
                target = MoveTarget.SELF,
                effects = listOf(MoveEffect.StatBoost(StatType.ATTACK, 2)),
            ),
        )

    val NASTY_PLOT =
        register(
            Move(
                "Nasty Plot",
                Type.DARK,
                MoveCategory.STATUS,
                0,
                target = MoveTarget.SELF,
                effects = listOf(MoveEffect.StatBoost(StatType.SPECIAL_ATTACK, 2)),
            ),
        )

    val GROWL =
        register(
            Move(
                "Growl",
                Type.NORMAL,
                MoveCategory.STATUS,
                0,
                target = MoveTarget.ONE_OPPONENT,
                effects = listOf(MoveEffect.StatBoost(StatType.ATTACK, -1)),
            ),
        )

    /** All registered moves by name. */
    val all: Map<String, Move> get() = _moves

    operator fun get(name: String): Move = _moves[name] ?: error("Unknown move: $name")
}
