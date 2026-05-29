# Skill: Add an ability to the engine

**Scope:** `:engine` (enum + interface), `:data` (concrete effect + registry entry),
`:render` (optional flavor text), `engine/src/test` (behavior test).
**Level:** user goal.
**Primary actor:** contributor adding a new ability behavior.

Mirrors the item skill almost step-for-step — abilities are the same shape with
different hooks. Read `add-item.md` first if you haven't added an item before.

## Stakeholders and interests

- **Contributor** — wants ability-specific hooks (on-switch-in, weather-damage
  suppression, item suppression) without digging for where they get called.
- **Engine maintainers** — want `:engine` to stay free of per-ability
  conditionals. The `AbilityRegistry` is the only dispatch site.

## Preconditions

- Your ability maps to an existing hook on `AbilityEffect`. Check the source
  for the current hook inventory — this doc drifts otherwise. The hooks
  currently include `onSwitchIn`, `onSwitchOut`, `blocksMove`,
  `onMoveAbsorbed`, `blocksWeatherDamage`, `attackerDamageModifier`,
  `defenderDamageModifier`, `interceptIncomingDamage`, `suppressesHeldItem`,
  `speedModifier`, `onHpThresholdCrossed`. If your ability needs a new
  hook, see *Extensions*.
- Green `./gradlew test` as a baseline.

## Minimum guarantees

- Partial work (enum only, no effect yet) is caught by `RegistryCoverageTest`.
  The build fails loudly rather than silently accepting an unregistered
  ability.

## Success guarantees

- The ability is usable via `PokemonState(pokemon, ability = Ability.YOUR_ABILITY)`.
- At least one test asserts the behavior under a deterministic pipeline.
- `./gradlew test ktlintCheck detekt` stays green.

## Trigger

You want a Pokemon to have a passive battle effect the engine doesn't model.

## Main success scenario

1. **Confirm the hook you need already exists** on
   `engine/src/main/kotlin/com/pokemon/battle/engine/ability/AbilityEffect.kt`.
   If not, jump to *Extension 1a*.
2. **Add the enum value** in
   `engine/src/main/kotlin/com/pokemon/battle/model/Ability.kt`.
3. **Create the effect file** at
   `data/src/main/kotlin/com/pokemon/battle/data/ability/<Name>Effect.kt`.
   Implement `com.pokemon.battle.engine.ability.AbilityEffect`. Override only
   the hooks you need.
4. **Register** the effect in `GenVRegistries` (same file as for items —
   `data/src/main/kotlin/com/pokemon/battle/data/Registries.kt`).
5. **Optional: custom render text** at
   `render/src/main/kotlin/com/pokemon/battle/render/ability/<Name>Text.kt`.
   The renderer falls back to a generic `"X's AbilityName!"` if you skip this.
6. **Write a behavior test.** Most abilities get grouped under
   `engine/src/test/kotlin/com/pokemon/battle/AbilityTest.kt` with a
   `// --- AbilityName ---` section header; look at the existing sections
   for the template. A standalone `<Name>Test.kt` is fine if your ability
   has rich multi-scenario coverage (e.g. `KlutzTest`, `PinchTypeBoostTest`).
7. **Validate:** `./gradlew test ktlintCheck detekt`. Commit.

## Extensions

**1a. The hook you need doesn't exist.** Add a defaulted method to
   `AbilityEffect` and wire it in at *every* place that triggers the
   mechanic — it may be more than one. Switch-out is the canonical
   example: `onSwitchOut` fires from both `SwitchPhase` (voluntary
   switches) *and* `MoveExecutionPhase.doSelfSwitch` (U-turn / Volt
   Switch). Missing the second site silently breaks half the trigger.
   And note the *deliberate exclusions*: faint replacement runs through
   `BattleLoop.handleFaintReplacements` and is semantically distinct —
   a fainted Pokemon is replaced, not switched out. Document these in
   the hook's docstring so the next reader doesn't have to re-derive.

   Also: when adding the N-th hook to `AbilityEffect`, detekt's
   `TooManyFunctions` threshold (11) will fire. Land an inline
   `@Suppress("TooManyFunctions")` on the interface with a one-line
   rationale (e.g. "hooks grow as mechanics are added; each ability
   overrides only what applies").

   Do not add `when (ability)` dispatch at the call site — the registry
   is the dispatch.

**2a. Your ability suppresses another effect.** Klutz is the worked example —
   see `KlutzEffect` and the `suppressesHeldItem` hook. The `ItemRegistry`
   already respects suppression via `effectForHolder`. The rule for
   cross-registry suppression: put the suppression check on the *lookup*
   (context-aware registry), not on every caller.

**3a. Identity-only ability (no battle effect).** Run Away, Pickup, etc.
   Add to `identityOnlyAbilities` in `RegistryCoverageTest.kt` instead of
   registering a no-op effect. Note the intent in a one-line comment.

**5a. Your ability's flavor text depends on weather, target, or other state.**
   `AbilityText.render...(...)` takes the relevant arguments — don't reach for
   `BattleState` in the text layer. If the information isn't already passed,
   add it to the `AbilityText` hook signature at the same time you add the
   corresponding `AbilityEffect` hook.

## Related information

- **Worked examples in repo:** `IntimidateEffect`, `LevitateEffect`,
  `SturdyEffect`, `WeatherImmunityEffects` (shared delegation pattern for
  abilities that differ only in which weather they immunize).
- **Gotcha:** the `effectiveAbility` vs `ability` distinction on
  `PokemonState` — effects mid-battle may be overridden (Mega Evolution,
  Gastro Acid). All registry lookups should consult `effectiveAbility`; see
  `AbilityEffect.kt`'s docstring.
