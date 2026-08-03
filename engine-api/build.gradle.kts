plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-model"))
    implementation(project(":engine-events"))
    implementation(project(":engine-provider-api"))
    implementation(project(":engine-playback-api"))
    implementation(project(":engine-queue"))
    implementation(project(":engine-utils"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation(kotlin("test"))
}