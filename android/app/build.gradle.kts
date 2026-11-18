import java.io.File

plugins {
    id("com.android.application")
    // Kein `org.jetbrains.kotlin.android` mehr: seit AGP 9 übersetzt das
    // Android-Plugin Kotlin selbst (built-in Kotlin). Der Compose-Compiler
    // bleibt ein eigenes Plugin, weil er an die Kotlin-Fassung gebunden ist.
    id("org.jetbrains.kotlin.plugin.compose")
}

// Die Kataloginhalte liegen einmal im Repo (`content/funktionen`); die App
// liest sie aus den Assets. Eine zweite Abschrift in `android/app/src/main/
// assets` wäre der Anfang von zwei Wahrheiten — was der Kern prüft, muss das
// sein, was im Katalog steht. Deshalb kopiert der Bau sie zur Bauzeit.
//
// Eigener Task statt `Copy`: AGP 9 nimmt in der SourceSet-API keine Provider
// mehr an („You cannot add Provider instances to the Android SourceSet API“).
// `addGeneratedSourceDirectory` verlangt ein `DirectoryProperty` als Ausgang —
// das hat `Copy` nicht, also beschreibt dieser Task seinen Ausgang selbst.
abstract class KatalogKopieren : DefaultTask() {
    @get:InputDirectory
    abstract val quelle: DirectoryProperty

    @get:OutputDirectory
    abstract val ziel: DirectoryProperty

    @TaskAction
    fun kopieren() {
        val nach = File(ziel.get().asFile, "funktionen")
        nach.deleteRecursively()
        nach.mkdirs()
        quelle.get().asFile
            .listFiles { datei -> datei.isFile && datei.name.endsWith(".yaml") }
            ?.forEach { datei -> datei.copyTo(File(nach, datei.name), overwrite = true) }
    }
}

val katalogKopieren by tasks.registering(KatalogKopieren::class) {
    quelle.set(rootProject.file("../content/funktionen"))
    ziel.set(layout.buildDirectory.dir("generated/katalog"))
}

android {
    namespace = "de.werkstatt.vci"
    // 37 statt 34: Compose 1.12 (BOM 2026.09.00) verlangt es, und ohne diesen
    // Sprung bleibt der ganze AndroidX-Stand auf September 2024 stehen.
    // `targetSdk` bleibt bewusst auf 34 — das ist eine Verhaltenszusage ans
    // System (Rechte, Hintergrundarbeit), keine Bauvoraussetzung, und gehört
    // erst nach einem Gerätetest angehoben.
    compileSdk = 37

    defaultConfig {
        applicationId = "de.werkstatt.vci"
        minSdk = 29          // Android 10: USB-Host und BLE-Stack ausreichend stabil
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // JNA bringt eigene native Bibliotheken mit; ohne diese Regel kollidieren
        // sie beim Zusammenpacken mit den jniLibs.
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Ziel-JVM als `compilerOptions` statt `kotlinOptions`: Kotlin 2.4 hat das alte
// DSL entfernt, und `jvmTarget` als String ist dort ein Fehler. Voll qualifiziert,
// damit die Build-Datei ohne zusätzlichen Import lesbar bleibt.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Der Task hängt an den Assets der Variante, nicht an `preBuild`: damit kennt
// `mergeDebugAssets` ihn selbst, und die Task-Abhängigkeit geht nicht verloren.
androidComponents {
    onVariants { variante ->
        variante.sources.assets?.addGeneratedSourceDirectory(katalogKopieren, KatalogKopieren::ziel)
    }
}

dependencies {
    implementation(project(":bindings")) {
        // :bindings zieht JNA als jar (Host-Test). Auf Android braucht es das
        // aar mit den mitgelieferten .so-Dateien — sonst doppelte Klassen.
        exclude(group = "net.java.dev.jna", module = "jna")
    }

    // Der Kern spricht über JNA mit libvci_ffi.so (kommt aus jniLibs).
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // FTDI-Wandler für Klasse-1-Dongles am Kabel (OBDLink EX/SX): Baudrate,
    // Leitungsparameter und Statusbytes macht diese Bibliothek (MIT-Lizenz).
    // Begrenzt wird der Einsatz in FtdiStnVerbindung: nur FTDI-VID und nur
    // Geräte, deren STI-Kennung STN-Firmware bestätigt.
    //
    // **Feste Version, nicht „latest“:** 3.12.0 ist der Stand vom 18.11.2026.
    // Die Sperre auf 3.10.0 fiel mit dem Sprung auf `compileSdk 37`: ab 3.11.0
    // verlangt das aar `compileSdk 35`, und das kann AGP 9.3.1.
    implementation("com.github.mik3y:usb-serial-for-android:3.12.0")

    // Compose 1.12.1, material3 1.4.0 (BOM-Zuordnung vom September 2026).
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Das aar bringt nur die Android-Dispatcher mit; für den JVM-Test braucht
    // JNA den Host-Dispatcher (com/sun/jna/linux-x86-64/libjnidispatch.so).
    testImplementation("net.java.dev.jna:jna:5.14.0")
}

// VciCheck kommt ohne Android-APIs aus und lässt sich deshalb auf der JVM gegen
// die echte Rust-Bibliothek prüfen — ohne Emulator.
tasks.withType<Test>().configureEach {
    val libDir = providers.environmentVariable("VCI_LIB_DIR")
        .orElse(rootProject.file("../core-ffi/target/release").absolutePath)
        .get()
    systemProperty("jna.library.path", libDir)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
