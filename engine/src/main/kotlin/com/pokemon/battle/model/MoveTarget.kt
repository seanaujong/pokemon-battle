package com.pokemon.battle.model

enum class MoveTarget {
    SELF, // Swords Dance — affects the user
    ONE_OPPONENT, // Flamethrower — player selects one opposing slot
    ALL_OPPONENTS, // Hyper Voice — hits all opposing slots
    ALL_OTHER, // Earthquake — hits all slots except the user
}
