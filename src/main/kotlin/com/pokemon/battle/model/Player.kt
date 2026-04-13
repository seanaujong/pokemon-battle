package com.pokemon.battle.model

enum class Player { P1, P2 }

fun Player.opponent(): Player = when (this) {
    Player.P1 -> Player.P2
    Player.P2 -> Player.P1
}
