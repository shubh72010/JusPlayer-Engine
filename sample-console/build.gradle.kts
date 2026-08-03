plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("org.jusplayer.sample.MainKt")
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-provider-newpipe"))
    implementation(project(":engine-model"))
    implementation(project(":engine-events"))
    implementation(project(":engine-provider-api"))
    implementation(project(":engine-playback-api"))
    implementation(project(":engine-queue"))
    implementation(project(":engine-api"))
    implementation(project(":engine-utils"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation(kotlin("test"))
}