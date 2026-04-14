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

// Bridge Smogon top-sets → PokeAPI targets (diary 041 Phase 3, "use Smogon to
// recommend what to pull from PokeAPI"). Runs after :ingestSmogon; updates
// targets/species.txt with the union of Smogon-recommended species.
tasks.register<JavaExec>("smogonToTargets") {
    group = "application"
    description = "Extend targets/species.txt with species from data/smogon/*-top-sets.json."
    mainClass.set("com.pokemon.battle.ingest.smogon.SmogonToTargetsMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

// Audit gap between PokeAPI's item/ability shape and our enum model. Outputs
// a markdown table; useful when motivating an enum → data-class refactor.
tasks.register<JavaExec>("auditModelGap") {
    group = "verification"
    description = "Print a gap report comparing PokeAPI item/ability fields to our enums."
    mainClass.set("com.pokemon.battle.ingest.cli.ModelGapAuditMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    standardOutput = System.out
}

// Generate `engine/.../data/PokedexCatalog.kt` from the ingested species JSON
// (diary 064: data as code vs data as resource). Run after :data-ingestion:run.
tasks.register<JavaExec>("codegenSpecies") {
    group = "application"
    description = "Generate PokedexCatalog.kt from ingested species JSON."
    mainClass.set("com.pokemon.battle.ingest.codegen.PokedexCodegenMainKt")
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
    implementation(project(":data"))
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
