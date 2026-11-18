plugins {
    // AGP 9 bringt Kotlin selbst mit (built-in Kotlin): `org.jetbrains.kotlin.android`
    // wird nicht mehr angewendet, sonst bricht die Konfiguration mit „extension
    // with name 'kotlin' already registered“ ab. Die Kotlin-Fassung kommt aus dem
    // Compose-Plugin unten, das auf derselben Fassung sitzt und die
    // Kotlin-Gradle-Plugin-Abhängigkeit von AGP (2.2.10) mit anhebt.
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    // :bindings ist ein reines JVM-Modul ohne Android; dort bleibt das Kotlin-JVM-Plugin.
    kotlin("jvm") version "2.4.20" apply false
}
