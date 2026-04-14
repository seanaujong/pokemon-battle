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

// Engine resources (species JSON) are loaded from the classpath, so no workingDir
// override is needed here — unlike :data-ingestion which reads repo-root files.

group = "com.pokemon.battle"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":engine"))
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
