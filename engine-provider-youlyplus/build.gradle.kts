plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.0.21"
}

dependencies {
    implementation(project(":engine-provider-api"))
    implementation(project(":engine-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(kotlin("test"))
}
