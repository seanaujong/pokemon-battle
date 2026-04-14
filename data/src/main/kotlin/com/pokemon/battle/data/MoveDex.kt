package com.pokemon.battle.data

import com.pokemon.battle.data.MoveDex.register
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.MoveEffect
import com.pokemon.battle.model.MoveTarget
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Volatile

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

    val TACKLE = register(Move("Tackle", Type.NORMAL, MoveCategory.PHYSICAL, 40, contact = true))

    val MACH_PUNCH = register(Move("Mach Punch", Type.FIGHTING, MoveCategory.PHYSICAL, 40, priority = 1, contact = true))

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

    val ROCK_BLAST = register(Move("Rock Blast", Type.ROCK, MoveCategory.PHYSICAL, 25, hitCount = 2..5, contact = true))

    val DOUBLE_SLAP = register(Move("Double Slap", Type.NORMAL, MoveCategory.PHYSICAL, 15, hitCount = 2..5, contact = true))

    val U_TURN =
        register(
            Move(
                "U-turn",
                Type.BUG,
                MoveCategory.PHYSICAL,
                70,
                effects = listOf(MoveEffect.SelfSwitch),
                contact = true,
            ),
        )

    val VOLT_SWITCH =
        register(
            Move(
                "Volt Switch",
                Type.ELECTRIC,
                MoveCategory.SPECIAL,
                70,
                effects = listOf(MoveEffect.SelfSwitch),
            ),
        )

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

    val QUIVER_DANCE =
        register(
            Move(
                "Quiver Dance",
                Type.BUG,
                MoveCategory.STATUS,
                0,
                target = MoveTarget.SELF,
                effects =
                    listOf(
                        MoveEffect.StatBoost(StatType.SPECIAL_ATTACK, 1),
                        MoveEffect.StatBoost(StatType.SPECIAL_DEFENSE, 1),
                        MoveEffect.StatBoost(StatType.SPEED, 1),
                    ),
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

    val TRICK_ROOM =
        register(
            Move(
                "Trick Room",
                Type.PSYCHIC,
                MoveCategory.STATUS,
                0,
                priority = -7,
                target = MoveTarget.SELF,
                effects = listOf(MoveEffect.SetTrickRoom(turns = 5)),
            ),
        )

    val TAILWIND =
        register(
            Move(
                "Tailwind",
                Type.FLYING,
                MoveCategory.STATUS,
                0,
                target = MoveTarget.SELF,
                effects =
                    listOf(
                        MoveEffect.SetSideConditionOnUserSide(
                            com.pokemon.battle.model.SideCondition.TAILWIND,
                            turns = 4,
                        ),
                    ),
            ),
        )

    val FAKE_OUT =
        register(
            Move(
                "Fake Out",
                Type.NORMAL,
                MoveCategory.PHYSICAL,
                40,
                priority = 3,
                target = MoveTarget.ONE_OPPONENT,
                effects = listOf(MoveEffect.SetVolatile(Volatile.Flinch)),
                requiresJustSwitchedIn = true,
                contact = true,
            ),
        )

    val STEALTH_ROCK =
        register(
            Move(
                "Stealth Rock",
                Type.ROCK,
                MoveCategory.STATUS,
                0,
                target = MoveTarget.ONE_OPPONENT,
                effects = listOf(MoveEffect.SetHazardOnOpposingSide(com.pokemon.battle.model.SideHazard.STEALTH_ROCK, maxLayers = 1)),
            ),
        )

    val SPIKES =
        register(
            Move(
                "Spikes",
                Type.GROUND,
                MoveCategory.STATUS,
                0,
                target = MoveTarget.ONE_OPPONENT,
                effects = listOf(MoveEffect.SetHazardOnOpposingSide(com.pokemon.battle.model.SideHazard.SPIKES, maxLayers = 3)),
            ),
        )

    val TOXIC_SPIKES =
        register(
            Move(
                "Toxic Spikes",
                Type.POISON,
                MoveCategory.STATUS,
                0,
                target = MoveTarget.ONE_OPPONENT,
                effects = listOf(MoveEffect.SetHazardOnOpposingSide(com.pokemon.battle.model.SideHazard.TOXIC_SPIKES, maxLayers = 2)),
            ),
        )

    val STICKY_WEB =
        register(
            Move(
                "Sticky Web",
                Type.BUG,
                MoveCategory.STATUS,
                0,
                target = MoveTarget.ONE_OPPONENT,
                effects = listOf(MoveEffect.SetHazardOnOpposingSide(com.pokemon.battle.model.SideHazard.STICKY_WEB, maxLayers = 1)),
            ),
        )

    val RAPID_SPIN =
        register(
            Move(
                "Rapid Spin",
                Type.NORMAL,
                MoveCategory.PHYSICAL,
                power = 50,
                // Gen 8+: damages an opponent, clears hazards on user's side, +1 Speed to user.
                effects =
                    listOf(
                        MoveEffect.ClearHazardsOnUserSide,
                        MoveEffect.UserStatBoost(StatType.SPEED, 1),
                    ),
                contact = true,
            ),
        )

    val DEFOG =
        register(
            Move(
                "Defog",
                Type.FLYING,
                MoveCategory.STATUS,
                power = 0,
                target = MoveTarget.SELF,
                // Scope: clears user-side hazards only. Mainline Defog also clears opposing-side
                // hazards, lowers target Evasion, and removes terrain — deferred (diary 044).
                effects = listOf(MoveEffect.ClearHazardsOnUserSide),
            ),
        )

    val PROTECT =
        register(
            Move(
                "Protect",
                Type.NORMAL,
                MoveCategory.STATUS,
                0,
                priority = 4,
                target = MoveTarget.SELF,
                effects = listOf(MoveEffect.SetVolatile(Volatile.Protect)),
            ),
        )

    /** All registered moves by name. */
    val all: Map<String, Move> get() = _moves

    operator fun get(name: String): Move = _moves[name] ?: error("Unknown move: $name")
}
