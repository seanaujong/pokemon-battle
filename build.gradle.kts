// Root build file. Intentionally minimal — each module owns its own plugins, deps,
// and config. Shared concerns (detekt.yml, CLAUDE.md, docs/) live at this level.
//
// Module layout:
//   :engine — the battle engine (see engine/build.gradle.kts)
//
// Future modules (diaries 041/042): :data-ingestion, :analytics, clients.
