# 107 — Doc-router cleanup, round 3

**Status:** Complete

## Goal

Close the three remaining doc-router findings from the 2026-05-30 `new-contributor`
pass-3 re-audit (router graded a strong pass / top-decile; these are the small
residual gaps), and commit the already-applied round-2 orphan-routing fixes.

The through-line: a newcomer should be routed to every committed doc and every
non-obvious top-level directory from a routing doc, and our self-containment
convention should say — and *enforce* — what it actually means for module READMEs.

## Findings addressed

1. **README module list omits `:persistence`** — `README.md`'s "How it's built"
   block lists 8 of 9 modules. Now that `CLAUDE.md` routes to `README.md` first,
   this is the first module map a newcomer reads. Add the `:persistence` row and
   the "`settings.gradle.kts` is authoritative on disagreement" one-liner that
   `architecture.md` already carries.

2. **Top-level dirs `battles/`, `targets/`, `scripts/` are inventoried nowhere** —
   referenced in no routing doc. Add them to `docs/index.md` (the hub), each
   pointing at the doc that explains it: `targets/` → `data-ingestion.md`,
   `battles/` → `corpus-format.md`, `scripts/` → `server/README.md`.

3. **DECISION — does the self-contained-docs convention cover *module* READMEs?**
   `server/README.md` body has diary refs ("See **diary 069**", 067/070/071) and a
   `## Related` section. `DocConventionTest` doesn't catch it because it only scans
   `docs/**`.

   **Sean's call:** *"READMEs are allowed to act like indexes but probably
   shouldn't reference diaries."* So module READMEs are a distinct tier — they sit
   next to their code and may cross-link to sibling code/scripts (index behavior),
   but the no-diary-reference rule still applies (a diary pointer rots the same way
   regardless of which file it lives in). Resolution:
   - Strip the diary pointers from `server/README.md`; keep `## Related` as a
     code-only index (the smoke-test client link stays).
   - Codify the module-README tier in `docs/index.md`'s convention text.
   - Extend `DocConventionTest` to enforce the no-diary rule across every
     `README.md` outside `docs/` — promoting the prose rule to a falsifiable guard,
     which is exactly the gap the finding named.

## Plan

- [x] `README.md`: add `:persistence` row (ordered as in `architecture.md`) +
      authoritative `settings.gradle.kts` one-liner. (#1)
- [x] `docs/index.md`: add a "Top-level directories" inventory pointing each dir at
      its explaining doc. (#2)
- [x] `server/README.md`: strip the 5 diary pointers (intro paragraph, the
      protocol-version aside, the team-validator aside, the multiplexing aside, and
      the 4 `## Related` diary bullets); keep `## Related` as a code-only index
      (Messages.kt + the smoke-test client). (#3)
- [x] `docs/index.md`: add the module-README clause to the convention list. (#3)
- [x] `DocConventionTest`: add a test asserting no `README.md` outside `docs/`
      references a diary number; update the class doc; factor the shared scan into
      `diaryRefsIn`. (#3)
- [ ] Commit round-2 (orphan routing, already in tree) + these as separate logical
      commits — awaiting Sean's go-ahead.

## Validation

- `./gradlew test ktlintCheck detekt` green — in particular the new
  `DocConventionTest` case passes (server/README.md cleaned) and the existing one
  stays green.
- `grep -nEi "diar(y|ies)\s+[0-9]|diaries/[0-9]" README.md server/README.md` returns
  nothing.
- README module block lists all 9 modules from `settings.gradle.kts`.

## Code review

Walked the diagnostic checklist; the substantive points:

- **Testable in isolation? / Auditable?** Yes — the no-diary rule is now a
  falsifiable guard, not prose. I injected `see diary 069` into `server/README.md`,
  watched `module READMEs never reference a diary entry` go red at the assertion,
  then reverted. A guard you haven't watched fail isn't protecting anything; this
  one's been watched.
- **Right layer / does it depend on things it shouldn't?** The new test lives
  beside the existing canonical-docs guard in `:engine` (which has zero project
  deps and already owns `DocConventionTest`). It shells out to `git ls-files`
  rather than walking the filesystem, specifically so it scans *maintained* files
  and never descends into the gitignored `scripts/analyst-env` venv (which could
  hold vendored READMEs). That's the correct dependency direction: the rule keys on
  "what we commit," and git is the authority on that.
- **Duplicated logic?** The two tests shared an identical scan-and-format loop; I
  factored it into `diaryRefsIn(base, files)` so the regex and the offender-message
  format live in one place. Each test still owns its own file-selection and its own
  failure message (canonical-doc wording vs. README wording), which is the part that
  legitimately differs.
- **Can it represent an illegal state? / invariants enforced or in our heads?** The
  whole point of this change: the module-README half of the convention was prose in
  `docs/index.md` that nothing enforced (`DocConventionTest` only scanned `docs/**`).
  A drifted `server/README.md` was a representable-but-wrong state. It now can't pass
  CI. The invariant moved from our heads into a predicate.
- **Names match the domain?** `diaryRefsIn`, `trackedFiles`, `DIARY_NUMBER_REF` read
  as what they are. The convention text names the new tier ("module READMEs are
  local indexes, not diary logs") in the same vocabulary the rest of the index uses.
- **Failure modes visible, not silent?** `trackedFiles` does `check(exit == 0)` with
  the captured stderr in the message, so a broken `git` invocation fails loudly
  rather than silently scanning zero files (which would make the guard vacuously
  pass — the dangerous failure mode for a linter).
- **Hard to reverse?** No. Each edit is independent and removable; the test is
  additive. The one judgment call baked in — "module READMEs may index but not cite
  diaries" — is Sean's stated decision, recorded above and in `docs/index.md`.

**Process note (worth surfacing):** mid-task I ran `git checkout -- server/README.md`
to revert a *test injection*, but that file also held my real, uncommitted edits, so
the checkout blew them away and I had to re-apply all five. Lesson for the
parallel/iteration playbook: don't use `git checkout --` to undo a scratch edit on a
file that carries uncommitted work — append/remove the scratch line surgically
instead. No lasting damage (re-applied + re-verified green), but it cost a cycle.

**Industry comparison.** This is a docs-lint guard in the family of
markdownlint / Vale / a custom `link-check` CI step. Where we differ: rather than a
generic "no broken links" rule, the predicate encodes a *project-specific temporal
invariant* — "current-truth docs must not point at the append-only event log" —
which off-the-shelf linters have no concept of. Driving file selection off
`git ls-files` rather than a glob is the same instinct as `pre-commit`'s
tracked-files model and `ripgrep`'s gitignore awareness: lint what you version,
not what happens to be on disk. Small surface, so no new module or format — the
comparison is "a bespoke lint rule," and that's exactly what it is.

**No further findings.**
