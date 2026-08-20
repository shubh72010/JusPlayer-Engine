rootProject.name = "jusplayer-engine"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Locally-built NewPipeExtractor v0.26.4 (JitPack cannot build the recent
        // tags); the extractor's official coordinates are net.newpipe:extractor.
        mavenLocal()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

include(
    "engine-model",
    "engine-events",
    "engine-provider-api",
    "engine-playback-api",
    "engine-queue",
    "engine-api",
    "engine-core",
    "engine-provider-newpipe",
    "engine-provider-lrclib",
    "engine-provider-coverartarchive",
    "engine-provider-canvas",
    "engine-provider-lastfm",
    "engine-provider-kugou",
    "engine-provider-simpmusic",
    "engine-provider-paxsenix",
    "engine-provider-betterlyrics",
    "engine-provider-unison",
    "engine-provider-youlyplus",
    "engine-autoplay",
    "engine-utils",
    "engine-http",
    "sample-console",
)
