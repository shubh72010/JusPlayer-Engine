plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":engine-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation(kotlin("test"))
}