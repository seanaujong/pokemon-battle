package com.pokemon.battle.model

import kotlinx.serialization.Serializable

@Serializable
data class Slot(val side: Side, val position: Int = 0) {
    companion object {
        /** Singles shorthand: side 1's only slot. */
        fun p1(position: Int = 0) = Slot(Side.SIDE_1, position)

        /** Singles shorthand: side 2's only slot. */
        fun p2(position: Int = 0) = Slot(Side.SIDE_2, position)
    }
}
