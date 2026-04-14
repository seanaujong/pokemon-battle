// :render — turns BattleEvents into player-visible text. Pure transform; no I/O.
// Pokemon Showdown puts rendering in client code (separate repo); diary 066
// extracted this from :engine to put presentation outside mechanics.

plugins {
    kotlin("jvm")
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
    // api — TextRenderer.render signatures take BattleEvent / BattleState;
    // consumers of :render need those engine types transitively visible.
    api(project(":engine"))
    testImplementation(kotlin("test"))
    testImplementation(project(":data"))
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
