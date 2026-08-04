import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("jvm") version "2.0.21" apply false
}

// Group is the JitPack-derived coordinate (`com.github.<owner>.<repo>`) rather
// than an org namespace: JitPack serves the root module as
// `com.github.shubh72010:JusPlayer-Engine`, and only harvests submodules from
// ~/.m2 when they carry this same group. Consumers depend on
// `com.github.shubh72010.JusPlayer-Engine:<module>:<version>`.
allprojects {
    group = "com.github.shubh72010.JusPlayer-Engine"
}
subprojects {
    apply(plugin = "maven-publish")

    afterEvaluate {
        if (plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
            val javaComponent = components["java"]
            val publishing = extensions.getByName("publishing") as PublishingExtension
            publishing.publications.create("maven", MavenPublication::class.java) {
                from(javaComponent)
            }
        }
    }
}