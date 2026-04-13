package com.pokemon.battle

class MoveExecutionPhase(
    private val roll: (IntRange) -> Int = { range -> range.random() }
) : Phase {
    override fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent> {
        val order = resolveMoveOrder(state, choices)
        val players = listOf(order.first, order.first.opponent())

        return players.fold(state to emptyList<BattleEvent>()) { (currentState, events), player ->
            val choice = choices.choiceFor(player)
            if (choice !is TurnChoice.UseMove) return@fold currentState to events

            val attacker = currentState.pokemonFor(player)
            if (attacker.isFainted) return@fold currentState to events

            val newEvents = executeMove(currentState, player, choice.move)
            val newState = newEvents.fold(currentState) { s, event -> event.apply(s) }
            newState to events + newEvents
        }.second
    }

    private fun executeMove(state: BattleState, player: Player, move: Move): List<BattleEvent> {
        val attacker = state.pokemonFor(player)
        val defender = state.pokemonFor(player.opponent())

        val events = mutableListOf<BattleEvent>(MoveAttempted(player, move))

        if (defender.isFainted) return events

        val result = calculateDamage(attacker, defender, move, roll)
        val damageEvent = DamageDealt(
            target = player.opponent(),
            amount = result.damage,
            effectiveness = result.effectiveness,
            critical = false // TODO: critical hit logic
        )
        events.add(damageEvent)

        val stateAfterDamage = damageEvent.apply(state)
        if (stateAfterDamage.pokemonFor(player.opponent()).isFainted) {
            events.add(PokemonFainted(player.opponent()))
        }

        return events
    }
}
