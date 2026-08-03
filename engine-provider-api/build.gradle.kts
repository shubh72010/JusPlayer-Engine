plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":engine-model"))
    testImplementation(kotlin("test"))
}