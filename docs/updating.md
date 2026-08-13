# Updating

Versions are published to JitPack as **git tags**. Updating is usually a one-line
change.

## Point at the new version

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-api:1.1.0")
}
```

becomes

```kotlin
dependencies {
    implementation("com.github.shubh72010.JusPlayer-Engine:engine-api:1.3.0")
}
```

Sync Gradle. Done.

## What to bump

Every module coordinate uses the same version, so update them all together:

```kotlin
implementation("com.github.shubh72010.JusPlayer-Engine:engine-api:1.3.0")
implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-newpipe:1.3.0")
implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-lrclib:1.3.0")
implementation("com.github.shubh72010.JusPlayer-Engine:engine-provider-coverartarchive:1.3.0")
```

You only need to change the ones you depend on explicitly.

## Which version am I on?

```bash
# from the repo (local checkout)
cat gradle.properties     # version=...

# on JitPack
curl https://jitpack.io/com/github/shubh72010/JusPlayer-Engine/engine-api/maven-metadata.xml
```

## Pre-1.2.0 caveat

Releases before **1.1.0** published only a broken empty root artifact — submodule
coordinates did not resolve. If you're using anything below `1.1.0`, jump straight
to `1.1.0` or later. See [Migration](migration.md).

## Breaking changes

`v1.3.0` introduces breaking API changes — see the [Migration Guide](migration.md).
Before `v1.3.0` there were no released changes to migrate from.
