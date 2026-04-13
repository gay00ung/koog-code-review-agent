plugins {
    kotlin("jvm") version "2.3.0"
    application
}

group = "net.lateinit"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("ai.koog:koog-agents:0.7.1")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("net.lateinit.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
