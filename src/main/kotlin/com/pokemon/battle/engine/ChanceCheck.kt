package com.pokemon.battle.engine

import com.pokemon.battle.model.FailReason

/** Returns true [percentChance]% of the time. [reason] identifies which mechanic is rolling. */
typealias ChanceCheck = (percentChance: Int, reason: FailReason) -> Boolean

val defaultChanceCheck: ChanceCheck = { percent, _ -> (1..100).random() <= percent }
