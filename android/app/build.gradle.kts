plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.werkstatt.vci"
    compileSdk = 34

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

    kotlinOptions {
        jvmTarget = "17"
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
    // **Feste Version, nicht „latest“:** ab 3.11.0 verlangt das aar
    // `compileSdk 35` (dieses Projekt: 34, AGP 8.5.2 kann nicht mehr), und es
    // zieht kotlin-stdlib 2.2 mit, das zu Kotlin 2.0.21 nicht passt — der
    // Übersetzer bricht mit „metadata is 2.2.0, expected 2.0.0“ ab. Ein
    // Versionssprung hier ist deshalb ein eigener Schritt: AGP und Kotlin
    // zusammen heben, nicht nur `compileSdk` hochzählen.
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")

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
