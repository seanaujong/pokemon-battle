// Root build file. Plugin *versions* are declared here with `apply false` so each
// subproject applies without restating the version. This matches Gradle's guidance
// and silences the "Kotlin Gradle plugin was loaded multiple times" warning that
// fires when two subprojects declare the same plugin version independently.
//
// Module layout:
//   :engine          — the battle engine
//   :data-ingestion  — PokeAPI ingestion (diary 041)
//   :cli             — interactive CLI (diary 056)

plugins {
    kotlin("jvm") version "2.2.10" apply false
    kotlin("plugin.serialization") version "2.2.10" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
}
