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
