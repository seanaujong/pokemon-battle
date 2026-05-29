plugins {
    kotlin("jvm")
    application
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    jacoco
}

application {
    mainClass.set("com.pokemon.battle.cli.PlayMainKt")
}

// Forward stdin so humans (and scripted agents) can interact with the CLI.
// Without this, Gradle's daemon swallows input and prompts block forever.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Run the AI-vs-AI demo battle."
    mainClass.set("com.pokemon.battle.cli.DemoMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

// Batch runner — plays N AI-vs-AI battles, records each to `./battles/`, and
// prints aggregate stats. Usage: `./gradlew :cli:batchDemo --args="200"`.
// See diary 078 for the motivation.
tasks.register<JavaExec>("batchDemo") {
    group = "application"
    description = "Run N AI-vs-AI battles, persist to ./battles/, print aggregates."
    mainClass.set("com.pokemon.battle.cli.BatchDemoMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

// Head-to-head AI eval matrix. Forces playerTags + matchup aggregations.
tasks.register<JavaExec>("matrixEval") {
    group = "application"
    description = "Run every AI matchup N times, persist, print win-rate matrix."
    mainClass.set("com.pokemon.battle.cli.MatrixEvalMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

// Evolution-delay advisor (diary 103). Pass the species via -Pargs, e.g.
// `./gradlew adviseDelays -Pargs="kricketot"` or `-Pargs="hoothoot --game platinum"`.
tasks.register<JavaExec>("adviseDelays") {
    group = "application"
    description = "Advise which evolutions are worth delaying for a species' learnset."
    mainClass.set("com.pokemon.battle.cli.AdviseDelaysMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    args = (project.findProperty("args") as String?)?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
}

// Engine resources (species JSON) are loaded from the classpath, so no workingDir
// override is needed here — unlike :data-ingestion which reads repo-root files.

group = "com.pokemon.battle"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":data"))
    implementation(project(":data-ingestion"))
    implementation(project(":render"))
    implementation(project(":ai"))
    implementation(project(":persistence"))
    implementation(project(":analytics"))
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
