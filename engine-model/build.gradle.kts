plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.0.21"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3")
    testImplementation(kotlin("test"))
}