plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
    `maven-publish`
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    jacoco
}

group = "com.pokemon.battle"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    // Engine tests use catalogs (MoveDex.FLAMETHROWER, Pokedex loader).
    // Engine main is data-free per diary 065.
    testImplementation(project(":data"))
    // Engine tests also use render/AI utilities (e.g. ScenarioTest's TypeAI fixture,
    // SecondGenTest's renderBattle output check). Engine main has neither per diary 066.
    testImplementation(project(":render"))
    testImplementation(project(":ai"))
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
