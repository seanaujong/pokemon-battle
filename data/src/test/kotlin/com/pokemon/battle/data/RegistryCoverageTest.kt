package com.pokemon.battle.data

import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Item
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Catches the "I added an enum value and forgot to register a behavior" bug — without
 * over-firing on enum values that legitimately have no battle behavior (Run Away,
 * Pickup, a plain held berry we only track for team-construction). The rule:
 *
 * > Every [Item] and [Ability] enum value must either have a [GenVRegistries]
 * > entry, *or* be listed in [IDENTITY_ONLY_ITEMS] / [IDENTITY_ONLY_ABILITIES] below.
 *
 * Adding a new enum value then becomes a *decision* — register it or acknowledge
 * it's identity-only — rather than a silent drop. The test fails with a pointer to
 * which bucket the new value needs to go into.
 *
 * Moves are deliberately excluded: a `MoveEffect` registry (diary 029) would only
 * hold entries for moves with secondary effects, and the vast majority of moves just
 * deal damage. The identity-only set would dominate the registry set for moves —
 * see the in-progress diary for a move-registry design that keeps the signal/noise
 * ratio useful.
 */
class RegistryCoverageTest {
    /**
     * Items present in the enum but deliberately without a registered behavior.
     * Empty today because every `Item` we model has behavior. Add a value here when
     * introducing an identity-only item (e.g. a berry we only care about for team
     * imports, or an item whose behavior we haven't implemented yet).
     */
    private val identityOnlyItems: Set<Item> = emptySet()

    /**
     * Abilities present in the enum but deliberately without a registered behavior.
     * Blaze/Overgrow/Torrent *are* registered (pinch type boosts); identity-only
     * abilities would be e.g. Run Away (no battle effect at all).
     */
    private val identityOnlyAbilities: Set<Ability> = emptySet()

    @Test
    fun `every Item enum value is either registered in GenVRegistries or explicitly identity-only`() {
        val allItems = Item.values().toSet()
        val registered = Item.values().filter { GenVRegistries.items.effectFor(it) != null }.toSet()
        val covered = registered + identityOnlyItems

        val missing = allItems - covered
        val overlap = registered intersect identityOnlyItems

        assertEquals(
            emptySet<Item>(),
            missing,
            "Item enum values with no behavior and not in identityOnlyItems: $missing. " +
                "Either register an ItemEffect in GenVRegistries or add to the identity-only set.",
        )
        assertEquals(
            emptySet<Item>(),
            overlap,
            "Items present in both the registry AND identityOnlyItems — remove from the identity-only set: $overlap",
        )
    }

    @Test
    fun `every Ability enum value is either registered in GenVRegistries or explicitly identity-only`() {
        val allAbilities = Ability.values().toSet()
        val registered = Ability.values().filter { GenVRegistries.abilities.effectFor(it) != null }.toSet()
        val covered = registered + identityOnlyAbilities

        val missing = allAbilities - covered
        val overlap = registered intersect identityOnlyAbilities

        assertEquals(
            emptySet<Ability>(),
            missing,
            "Ability enum values with no behavior and not in identityOnlyAbilities: $missing. " +
                "Either register an AbilityEffect in GenVRegistries or add to the identity-only set.",
        )
        assertEquals(
            emptySet<Ability>(),
            overlap,
            "Abilities present in both the registry AND identityOnlyAbilities — remove from the identity-only set: $overlap",
        )
    }
}
