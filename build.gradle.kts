import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("jvm") version "2.0.21" apply false
}

allprojects {
    group = "org.jusplayer"
}

// Publish every module as a Maven artifact so consumers can depend on
// `org.jusplayer:<module>:<version>` (e.g. via JitPack). The version lives in
// gradle.properties — bump it there and tag the release.
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