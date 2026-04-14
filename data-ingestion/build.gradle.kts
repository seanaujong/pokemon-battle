plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    jacoco
}

application {
    mainClass.set("com.pokemon.battle.ingest.cli.IngestMainKt")
}

// CLI resolves paths (targets/, data/raw/, engine/src/main/resources/) from repo root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

// Smogon chaos-stats ingestion (diary 041 Phase 3) — second entrypoint, reads
// `targets/smogon.txt`, writes `.cache/smogon/` (gitignored) + `data/raw/smogon/`
// + `data/smogon/` (both committed).
tasks.register<JavaExec>("ingestSmogon") {
    group = "application"
    description = "Fetch Smogon monthly chaos stats and transform to top-sets JSON."
    mainClass.set("com.pokemon.battle.ingest.smogon.SmogonIngestMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
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

kotlin {
    jvmToolchain(17)
}
