plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":engine-provider-api"))
    implementation(project(":engine-model"))
    implementation(project(":engine-utils"))
    implementation("net.newpipe:extractor:v0.26.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation(kotlin("test"))
}
