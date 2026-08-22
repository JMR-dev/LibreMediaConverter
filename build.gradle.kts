// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// AGP 9 uses "built-in Kotlin": it compiles Kotlin itself, with the KGP it bundles (2.2.10 for
// AGP 9.3.1, per its POM). That version caps jvmTarget at 24. Putting a newer KGP on this single
// buildscript classpath is what raises the ceiling -- AGP's built-in Kotlin then compiles with
// 2.4.10 instead. The Compose compiler plugin must match KGP exactly, so it moves in lockstep.
//
// This is why the module below applies these two by id() rather than alias(): the plugins come
// from here, not from the version catalog's plugin resolution. The catalog still carries the
// version numbers, so there is exactly one place to edit.
//
// We still do NOT apply org.jetbrains.kotlin.android -- that plugin is incompatible with AGP 9's
// built-in-Kotlin DSL and fails the build.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:${libs.versions.agp.get()}")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}
