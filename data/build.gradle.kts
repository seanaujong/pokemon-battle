// :data — catalogs of specific things (species, moves) that the engine consumes
// but doesn't ship. Diary 065 split this out of :engine. Items / abilities
// stay coupled to engine internals (registries called from MoveExecutionPhase
// etc.); revisit them in a later diary if/when DI plumbing is added.

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    jacoco
}

group = "com.pokemon.battle"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
    }
}

detekt {
    config.setFrom(rootProject.files("detekt.yml"))
    buildUponDefaultConfig = true
}

// Generated catalog (diary 064) — its format is owned by the codegen template,
// not ktlint. The PokedexCodegenTest in :data-ingestion enforces format drift.
ktlint {
    filter {
        exclude { it.file.path.endsWith("PokedexCatalog.kt") }
    }
}

kotlin {
    jvmToolchain(17)
}
