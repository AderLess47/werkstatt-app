pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android (FTDI-Wandler) wird nur über JitPack verteilt.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "vci-android"

// :bindings  — Linktest: Kotlin kompiliert die UniFFI-Bindings gegen den Rust-Kern
// :app       — erster Bildschirm: Zustand des Kerns, ohne Fahrzeug und ohne Dongle
include(":bindings")
include(":app")
