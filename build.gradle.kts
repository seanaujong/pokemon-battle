plugins {
    kotlin("jvm") version "2.2.10"
    application
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

application {
    mainClass.set("MainKt")
}

group = "com.pokemon.battle"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
