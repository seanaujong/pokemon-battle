package com.pokemon.battle.cli

/**
 * AI strategies exposed by the matrix evaluator. Adding a new value here and
 * wiring it in `MatrixEvalMain.buildSidedAI` is the entire cost of extending
 * the matrix. The enum replaces the hardcoded `"TypeAI"` / `"RandomAI"` strings
 * that appeared in `Strategy.entries` and `buildSidedAI`'s `when` branches —
 * a typo in either spot is now a compile error. Finding from diary 082's
 * retroactive code review.
 */
enum class Strategy {
    TypeAI,
    RandomAI,
    HeuristicAI,
}
