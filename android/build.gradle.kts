// Top-level build script for the Android module. AGP 8.7 / Kotlin 2.0 /
// Compose BOM 2024.10 — same toolchain we settled on in the other
// worktree. The module under :app is the only one in this project.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
