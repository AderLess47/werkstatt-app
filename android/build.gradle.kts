plugins {
    // AGP 9 bringt Kotlin selbst mit (built-in Kotlin): `org.jetbrains.kotlin.android`
    // wird nicht mehr angewendet, sonst bricht die Konfiguration mit „extension
    // with name 'kotlin' already registered“ ab. Die Kotlin-Fassung kommt aus dem
    // Compose-Plugin unten, das auf derselben Fassung sitzt und die
    // Kotlin-Gradle-Plugin-Abhängigkeit von AGP (2.2.10) mit anhebt.
    //
    // AGP 9.4.1 liegt über der von Kotlin 2.4.21 zugesagten Obergrenze (9.3.1):
    // die Matrix endet dort, nicht der Bau. Der Sprung ist gemessen grün —
    // Bau, 186 Tests, Abhängigkeitsprüfung und CI —, und die Zusage deckt nur
    // ab, was JetBrains getestet hat, nicht was funktioniert. Bleibt er einmal
    // rot, ist 9.3.1 der Rückweg; AGP 9.5 bleibt draußen, weil dessen Abstand
    // zur Matrix größer ist und er nichts bringt, was dieser Bau braucht.
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // :bindings ist ein reines JVM-Modul ohne Android; dort bleibt das Kotlin-JVM-Plugin.
    kotlin("jvm") version "2.4.21" apply false
}
