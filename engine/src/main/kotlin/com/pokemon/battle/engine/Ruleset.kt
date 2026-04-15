package com.pokemon.battle.engine

import com.pokemon.battle.model.FailReason
import com.pokemon.battle.model.GimmickKind
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.UsedGimmick
import com.pokemon.battle.model.Volatile

/**
 * Format-specific policy hook. Today covers gimmick budgets and move legality; will grow
 * to include banlists, type-chart swaps (Inverse), win conditions, format-gated calc
 * tweaks.
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

    /**
     * Decide whether [userSlot] may use [move] right now. Default allows every move —
     * concrete rulesets that enforce choice-lock / disable / taunt / etc. override this
     * (typically by delegating to [volatileBasedMoveLegality]).
     *
     * The engine consults this early in [com.pokemon.battle.phase.MoveExecutionPhase];
     * a [MoveLegality.Forbidden] result produces a [MoveFailed] event and the move is
     * skipped (same shape as status-induced move failures). See diary 039.
     */
    fun canUseMove(
        state: BattleState,
        userSlot: Slot,
        move: Move,
    ): MoveLegality = MoveLegality.Allowed
}

/**
 * Shared helper used by every concrete ruleset: checks volatile-based restrictions that
 * are gen-stable (Choice-lock today; Disable / Encore / Taunt / Torment / Heal Block
 * when their setter moves arrive — see diary 039 Deferred section).
 */
internal fun volatileBasedMoveLegality(
    state: BattleState,
    userSlot: Slot,
    move: Move,
): MoveLegality {
    val user = state.pokemonFor(userSlot)
    val lock = user.volatiles.filterIsInstance<Volatile.ChoiceLocked>().firstOrNull()
    if (lock != null && lock.move != move) {
        return MoveLegality.Forbidden(FailReason.CHOICE_LOCKED)
    }
    // Future: Disable, Encore, Taunt, Torment, HealBlock
    return MoveLegality.Allowed
}

/** No gimmicks permitted — the safe default matching the engine's current behavior. */
internal object NoGimmicksRuleset : Ruleset

/** Pokemon Champions / modern VGC: one gimmick of any kind per side per battle. */
internal object PokemonChampionsRuleset : Ruleset {
    override fun canUseGimmick(
        kind: GimmickKind,
        priorUsage: List<UsedGimmick>,
    ): Boolean = priorUsage.isEmpty()

    override fun canUseMove(
        state: BattleState,
        userSlot: Slot,
        move: Move,
    ): MoveLegality = volatileBasedMoveLegality(state, userSlot, move)
}

/** Smogon National Dex: one of each kind per side per battle (Mega + Z + Dynamax all legal). */
internal object NationalDexRuleset : Ruleset {
    override fun canUseGimmick(
        kind: GimmickKind,
        priorUsage: List<UsedGimmick>,
    ): Boolean = priorUsage.none { it.kind == kind }

    override fun canUseMove(
        state: BattleState,
        userSlot: Slot,
        move: Move,
    ): MoveLegality = volatileBasedMoveLegality(state, userSlot, move)
}

/** Gen 9 VGC Reg H / similar: Tera only, once per side per battle. */
object Gen9VgcTeraRuleset : Ruleset {
    override fun canUseGimmick(
        kind: GimmickKind,
        priorUsage: List<UsedGimmick>,
    ): Boolean = kind == GimmickKind.TERA && priorUsage.isEmpty()

    override fun canUseMove(
        state: BattleState,
        userSlot: Slot,
        move: Move,
    ): MoveLegality = volatileBasedMoveLegality(state, userSlot, move)
}
