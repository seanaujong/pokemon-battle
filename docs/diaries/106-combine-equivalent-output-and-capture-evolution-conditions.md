# 106 — combine equivalent advice + capture evolution conditions

**Status:** Complete

## Goal

Two `adviseDelays` improvements the user asked for:

1. **Combine games/gens with equivalent output.** The per-generation view never
   merges identical advice *across* generations, so a line whose advice is stable
   for gens V–VII prints the same block three times. Group all games whose advice
   is identical into one block, labelled with every game it covers.
2. **Make the evolution-requirement line accurate.** It claimed a single
   version-less requirement modelled as only `{trigger, minLevel, item}`, dropping
   `min_happiness`, `held_item`, and `time_of_day`. So Budew→Roselia (friendship,
   daytime) and Pichu→Pikachu (friendship) read as bare "on level-up", and
   Poliwhirl→Politoed (trade holding King's Rock) as bare "by trade". Capture
   those conditions so the label is complete.

## Questions asked and answers received

- **How to combine?** — "Group by identical advice": every game with the same
  flags shares one block, labelled with all its games (gen noted). Drops the
  strict gen-first layout in favour of compactness.
- **How to handle the requirement line?** — "Capture the conditions": model
  happiness / held-item / time-of-day so the label is accurate (still
  version-less — PokeAPI gives one set of conditions per edge, which is the known
  limit).

## Honest scope note

PokeAPI's evolution chain carries **one** set of conditions per edge, not a
per-game history. Capturing happiness/held-item/time makes the *snapshot*
complete and correct, but cannot reflect a method that genuinely differed in an
older game. For the committed set the methods are gen-stable, so this closes the
real gap; the version-less limitation is documented, not hidden.

## Plan

- [ ] **Data model.** Add `minHappiness: Int?`, `heldItem: String?`,
      `timeOfDay: String?` (defaults null) to `EvolutionEdgeJson` (+ `toDomain`)
      and `EvolutionEdge`. New params last so positional test constructors still
      compile; `encodeDefaults=false` keeps unchanged edges byte-identical.
- [ ] **DTO + transform.** Add `min_happiness` / `held_item` / `time_of_day` to
      `PokeApiEvolutionDetail`; populate them in `EvolutionLineTransform.edgesOf`
      (blank time-of-day → null).
- [ ] **Re-ingest** `./gradlew :data-ingestion:ingestEvolutionLines` (offline —
      cache is warm). Verify the diff is surgical: only friendship/held-item/time
      edges gain fields; 18 bundles unchanged in membership.
- [ ] **Label.** Enrich `edgeLabel`: level-up + happiness → "by friendship"
      (+ daytime/nighttime); trade + held item → "by trade holding <item>";
      level-up keeps "at L<n>"; append time-of-day to level-up forms.
- [ ] **Combine.** Replace the per-generation printing with a pure, testable
      `adviceGroups(line, edge)` that groups available version groups by identical
      advice (excludes games where the evolved form is absent), plus a
      `gamesLabel` ("Black White (V), X Y (VI)" across gens; "…, Platinum (gen IV)"
      within one). Printing becomes a thin loop over the groups.
- [ ] **Tests.** Transform captures the three conditions (crafted chain JSON);
      `adviceGroups` collapses identical gens into one group on the real Roselia
      line and excludes gen-III version groups.
- [x] **Validate** `./gradlew test ktlintCheck detekt` green; `adviseDelays
      roselia`/`poliwag`/`pichu`/`kricketot` eyeballed.

## What the output surfaced (a third bug, fixed)

Combining made an adjacent bug visible: the availability filter checked only the
*evolved* form, so Budew→Roselia listed gen III under "evolve freely" — but Budew
doesn't exist in gen III (only Roselia does). The fix requires **both** stages to
exist in a game (`hasLearnsetIn(from) && hasLearnsetIn(to)`); gen III now drops
from that edge, and the evolve-freely group is correctly gen VIII only. This is
the symmetric partner of diary 105's evolved-form guard — same law, the other end
of the edge. It lives in the CLI's display filter (which games to show), since
the advisor already returns empty when the pre-evo is absent.

## Code review

Walked the checklist.

- **Right layer?** Two distinct concerns landed in two layers. Capturing
  conditions is a data/ingestion concern (DTO + transform + bundle schema); the
  domain `EvolutionEdge` gained pure fields. Combining + the both-stages filter is
  presentation, isolated in `EvolutionAdviceGroups` (pure, no I/O), with
  `AdviseDelaysMain` reduced to a thin print loop.
- **Testable in isolation?** Yes — `adviceGroups`/`gamesLabel`/`fingerprint` are
  `internal` pure functions tested directly (`EvolutionAdviceGroupsTest`), and the
  transform's condition capture is tested from a crafted chain
  (`EvolutionLineConditionsTest`), no network.
- **Readable / names?** `AdviceGroup`, `adviceGroups`, `gamesLabel`,
  `hasLearnsetIn`, `levelUpGate`, `timeSuffix` read in domain terms. The grouping
  file owns the shared `titleCase`/`fingerprint`, removing the duplicates that had
  lived in `AdviseDelaysMain`.
- **Hard to reverse?** The bundle schema grew three optional fields with null
  defaults; `encodeDefaults=false` means old/condition-less edges are byte-
  identical, so the re-ingest diff was three edges. Reverting is removing fields +
  re-ingest. Low cost.
- **Illegal states / invariants?** Conditions are null when the trigger doesn't
  use them; the label `when` only reads happiness/held-item on the matching
  trigger. The both-stages filter is the enforced invariant for "evolution exists
  in this game."
- **Failure modes?** A multi-condition edge we don't model (e.g. location) falls
  back to the generic trigger label, not a crash.
- **Industry comparison (substantial — schema + output-format change):** the
  combining is a *group-by-equivalence* fold (relational `GROUP BY` on an advice
  fingerprint, then label the partition) — standard report compaction. The schema
  growth mirrors **additive, back-compatible columns** with omit-on-default
  encoding (like Protobuf optional fields / Parquet nullable columns): readers of
  old bundles still work, writers only emit present values. Deliberate divergence:
  we *don't* version the conditions per game because the upstream (PokeAPI) is
  itself version-less here — documented as a known limit, not papered over.

No findings to defer.
