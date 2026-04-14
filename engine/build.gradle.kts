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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    // Engine tests use catalogs (MoveDex.FLAMETHROWER, PokedexCatalog.CHARIZARD).
    // Engine main is data-free per diary 065.
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
    // detekt.yml stays at the repo root — shared across any future modules.
    config.setFrom(rootProject.files("detekt.yml"))
    buildUponDefaultConfig = true
}

kotlin {
    jvmToolchain(17)
}
