plugins {
    kotlin("jvm")
}

dependencies {
    // Die erzeugten Bindings sprechen über JNA mit libvci_ffi.
    implementation("net.java.dev.jna:jna:5.14.0")
    testImplementation(kotlin("test"))
}

// Die Bindings werden erzeugt (`./scripts/gen-bindings.sh kotlin`) und hier nur
// eingebunden — keine Kopie im Repository, damit sie nicht veralten können.
kotlin.sourceSets["main"].kotlin.srcDir(rootProject.file("../core-ffi/bindings/kotlin"))

tasks.test {
    useJUnitPlatform()
    // JNA sucht libvci_ffi.so in diesem Verzeichnis (Host-Build).
    val libDir = providers.environmentVariable("VCI_LIB_DIR")
        .orElse(rootProject.file("../core-ffi/target/release").absolutePath)
        .get()
    systemProperty("jna.library.path", libDir)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
