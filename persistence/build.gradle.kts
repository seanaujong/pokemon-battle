// :persistence — owns the on-disk format for completed battles. Depends only on
// :engine (for BattleResult and event DTOs). Consumers (:cli, :server, :analytics)
// inject a BattleRecorder at the loop/session layer. Diary 078.

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
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
    // api — PersistedBattle / BattleMetadata are types that downstream consumers
    // (:analytics batch aggregations) need to see. Engine's event DTOs are
    // transitively visible through :engine's public surface.
    api(project(":engine"))
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

kotlin {
    jvmToolchain(17)
}
