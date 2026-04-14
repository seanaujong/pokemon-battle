package com.pokemon.battle.model

enum class Side {
    SIDE_1,
    SIDE_2,
    ;

    fun opposite(): Side =
        when (this) {
            SIDE_1 -> SIDE_2
            SIDE_2 -> SIDE_1
        }
}
