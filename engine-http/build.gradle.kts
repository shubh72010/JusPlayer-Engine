plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.0.21"
    application
}

application {
    mainClass.set("org.jusplayer.engine.http.JusPlayerHttpEngineKt")
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-api"))
    implementation(project(":engine-autoplay"))
    implementation(project(":engine-queue"))
    implementation(project(":engine-events"))
    implementation(project(":engine-model"))
    implementation(project(":engine-provider-api"))
    implementation(project(":engine-playback-api"))
    implementation(project(":engine-provider-newpipe"))
    implementation(project(":engine-provider-lrclib"))
    implementation(project(":engine-provider-coverartarchive"))
    implementation("io.ktor:ktor-server-netty:2.3.11")
    implementation("io.ktor:ktor-server-cors:2.3.11")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.11")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(kotlin("test"))
}
