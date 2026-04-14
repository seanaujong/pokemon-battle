// :server — JSONL stdin/stdout wrapper around :engine's BattleLoop, so out-of-JVM
// clients (Python, TypeScript) can drive a battle via subprocess. Diary 069.

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    jacoco
}

application {
    mainClass.set("com.pokemon.battle.server.ServerMainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

group = "com.pokemon.battle"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":data"))
    implementation(project(":render"))
    implementation(project(":ai"))
    implementation(project(":persistence"))
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
