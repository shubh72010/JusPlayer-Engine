plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":engine-model"))
    implementation(project(":engine-events"))
    testImplementation(kotlin("test"))
}