package com.pokemon.battle.engine

import com.pokemon.battle.model.GimmickKind
import com.pokemon.battle.model.UsedGimmick

/**
 * Format-specific policy hook. Today covers gimmick budgets; will grow to include legal
 * moves, banlists, type-chart swaps (Inverse), win conditions, format-gated calc tweaks.
 *
 * The principle (diary 030 addendum): the engine holds raw state ([BattleState]); the
 * ruleset decides legality. Different formats plug in different rulesets without any
 * engine code changes.
 */
interface Ruleset {
    /**
     * True if the holder on [priorUsage]'s side can activate [kind] right now. Default
     * rejects everything (matches [NoGimmicksRuleset]: no gimmicks exist in this
     * ruleset). Concrete rulesets override this with their budget policy.
     */
    fun canUseGimmick(
        kind: GimmickKind,
        priorUsage: List<UsedGimmick>,
    ): Boolean = false
}

/** No gimmicks permitted — the safe default matching the engine's current behavior. */
object NoGimmicksRuleset : Ruleset

/** Pokemon Champions / modern VGC: one gimmick of any kind per side per battle. */
object PokemonChampionsRuleset : Ruleset {
    override fun canUseGimmick(
        kind: GimmickKind,
        priorUsage: List<UsedGimmick>,
    ): Boolean = priorUsage.isEmpty()
}

/** Smogon National Dex: one of each kind per side per battle (Mega + Z + Dynamax all legal). */
object NationalDexRuleset : Ruleset {
    override fun canUseGimmick(
        kind: GimmickKind,
        priorUsage: List<UsedGimmick>,
    ): Boolean = priorUsage.none { it.kind == kind }
}

/** Gen 9 VGC Reg H / similar: Tera only, once per side per battle. */
object Gen9VgcTeraRuleset : Ruleset {
    override fun canUseGimmick(
        kind: GimmickKind,
        priorUsage: List<UsedGimmick>,
    ): Boolean = kind == GimmickKind.TERA && priorUsage.isEmpty()
}
