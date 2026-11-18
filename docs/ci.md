# CI — was läuft, und wo sie gerade steht

`.github/workflows/ci.yml` läuft bei jedem Push und jedem Pull Request. Zusätzlich
lässt er sich von Hand starten (`workflow_dispatch`): ein Lauf ist damit
wiederholbar, ohne dafür einen leeren Commit zu brauchen.

| Job | Was er prüft |
| --- | --- |
| `c` | `make -C j2534 test` (165 Prüfungen) und dass `core/tests/corpus/isotp.txt` unverändert aus dem Erzeuger hervorgeht |
| `rust` | `cargo test` für `core` (224 Tests, dazu 4 Korpus-Tests aus `core/tests/corpus/isotp.txt`) und für `core-ffi` (22 Tests) |
| `clippy` | `cargo clippy --all-targets -- -D warnings` für `core` und `core-ffi`; `--all-targets` schließt die Tests ein, und genau dort lagen die zwei Warnungen, die den Job sonst nie erreicht hätten |
| `fmt` | `cargo fmt --check` für `core`, `core-ffi` und `core/fuzz` — eigener Job, weil ein Formatfehler nichts über das Verhalten sagt |
| `deny` | `cargo deny` für `core`, `core-ffi` und `core/fuzz`: Lizenzen und bekannte Sicherheitslücken (RustSec) — Regeln in `deny.toml`, ein Schritt je Manifest |
| `deps` | der aufgelöste Gradle-Graph der beiden Android-Module, die Kopfddateien des C-Kerns und die bekannten Sicherheitslücken zu beiden: jedes Fremdmodul braucht eine Zeile mit Lizenz in `android/abhaengigkeiten.txt`, jede Kopfddatei eine Zeile in `j2534/abhaengigkeiten.txt`, offene Lücken eine Zeile in `android/osv-ausnahmen.txt` — Einzelheiten unten |
| `bindings` | erzeugt Kotlin- und Swift-Bindings neu, lädt sie als Artefakt hoch (`needs: rust`) |
| `jvm-tests` | die 186 Tests der App ohne Gerät über `sh scripts/jvm-tests.sh`, gegen den Host-Build der Bibliothek |
| `android` | nimmt die SDK-Lizenzen selbst an und installiert NDK 26.1, Plattform 37.0 und Build-Tools 36.0.0; Bindings und Host-Bibliothek; NDK-Bibliothek für `arm64-v8a` und `x86_64`; `./android/gradlew -p android :bindings:test`; dann `:app:testDebugUnitTest :app:assembleDebug` und eine Prüfung, dass beide ABIs in der APK liegen |

Der Android-Job baut über den Wrapper, nicht über ein vom Runner gestelltes
Gradle: die Version steht allein in
`android/gradle/wrapper/gradle-wrapper.properties`. `setup-gradle` bekommt
bewusst kein `gradle-version` — ohne diese Angabe nimmt die Aktion den Wrapper
des Projekts an und richtet nur den Cache ein. Damit gibt es genau einen Pin und
keine zweite Gradle-Distribution, die niemand benutzt.

### SDK-Lizenzen: warum der Job sie selbst annimmt

`android-actions/setup-android@v3` akzeptiert die Lizenzen standardmäßig selbst
(`sdkmanager --licenses`). Auf dem Runner stellte dieser Aufruf die Rückfrage
„Review licenses that have not been accepted (y/N)?“, und der Job brach dort ab —
alle folgenden Schritte wurden übersprungen (Lauf #319, 17.11.2026). Die Aktion
setzt deshalb nur noch das cmdline-tools-Paket
(`accept-android-sdk-licenses: false`, `packages: ""`); ein eigener Schritt nimmt
die Lizenzen ohne Rückfrage an und installiert das NDK:

```sh
yes | sdkmanager --licenses > /dev/null
yes | sdkmanager "ndk;26.1.10909125" > /dev/null
```

`yes |` beantwortet die Lizenzfrage vorab; ohne das kann der Schritt auf einem
frischen Runner hängen statt zu scheitern.

### Skripte werden mit `bash`/`sh` gestartet

Die Helfer unter `scripts/` ruft die CI nicht über `./…` auf, sondern mit
`bash scripts/gen-bindings.sh …` bzw. `sh scripts/jvm-tests.sh`. Der Modus im
Repository ist inzwischen `100755` (Commit `2bf8698`), aber der Aufruf soll nicht
davon abhängen: mit `./` endet ein fehlendes Ausführungsbit sofort in
„Permission denied“. Genau das traf am 17.11.2026 drei Jobs gleichzeitig, und im
Log sah es nach einem Fehler des Skripts aus.

Die Zahlen in den Jobnamen sind eine Ankündigung, keine Messung. Damit sie nicht
abdriften, zählt `sh scripts/zahlen-pruefen.sh` alle fünf lokal nach —
`make -C j2534 test`, beide `cargo test`, `scripts/jvm-tests.sh` — und bricht ab,
sobald eine Zahl von der im Workflow genannten abweicht (Exit 1). Es zählt, was
die Suiten melden, nicht was im Quelltext deklariert ist. Das Skript prüft den
lokalen Stand; im Workflow ist es nicht eingebunden, die Zahlen in den Jobnamen
gegenzuprüfen bleibt also Handarbeit.

## Formatierung

`cargo fmt --check` läuft als eigener Job `fmt` für die drei Crates (`core`,
`core-ffi`, `core/fuzz`). Eigener Job und nicht ein Schritt in `rust`: ein
Formatfehler sagt nichts über das Verhalten, und der Lauf soll zeigen, ob nur
die Form abweicht — ohne die Tests dafür erneut zu fahren.

Der Weg dahin war der schwierige Teil. Das Crate ist über die Zeit von Hand
formatiert worden; ein Lauf mit rustfmt änderte am 17.11.2026 **zwölf Dateien
mit rund 235 KB** Quelltext.

Ein eigener Versuch, die Handformatierung per `rustfmt.toml` als Soll zu erklären
(`max_width = 110`, `use_small_heuristics = "Max"`, `reorder_imports = false`),
ist am 17.11.2026 gemessen gescheitert: rustfmt löst `=> { Ausdruck }` in einem
Match-Arm immer zu `=> Ausdruck,` auf, und dafür gibt es keine stabile Option
(`match_arm_blocks = false` ändert daran nichts). Genau diese Form steht in
`sfd.rs` und `isotp.rs`; außerdem sind Signaturen im Repo 101 Zeichen lang und
liegen damit schon über dem Vorgabewert 100. Eine Konfiguration, die den alten
Stand durchwinkt, gibt es also nicht — es bleibt beim Reformat.

Die Änderung selbst ist rein mechanisch gefahren — der Kern danach weiter grün
(damals 184 Tests, Clippy sauber). Der Haken war der Weg ins Repository: es gibt von
dieser Maschine keinen `git push`, jede Datei geht einzeln durch die API, und
`sfd.rs` allein hat 39 KB. Ein einzelnes verrutschtes Zeichen wäre ein
Programmierfehler in getestetem Code — deshalb stand der Job erst im Workflow,
als alle Dateien übertragen und jede gegen `git hash-object` geprüft war. Ein
Job, der beim ersten Lauf rot ist, prüft nichts.

Bei `core/src/uds.rs` hat diese Prüfung gegriffen: der Blob im Repository
(`20309ff3`) wich vom lokal formatierten (`856f4d5a`) ab, weil die
typografischen Schlusszeichen („…“) als gerade Anführungszeichen übertragen
worden waren. Commit `7a1c601` korrigiert das; erst danach stimmte der Blob.

**Stand am 17.11.2026.** Alle acht vorher offenen Dateien sind formatiert und im
Repository, jede per Blob-Hash bestätigt:

| Datei | Größe |
| --- | --- |
| `core/src/audit.rs` | 20 KB |
| `core/src/content.rs` | 28 KB |
| `core/src/function.rs` | 32 KB |
| `core/src/function/tests.rs` | 16 KB |
| `core/src/isotp.rs` | 17 KB |
| `core/src/lib.rs` | 1,5 KB |
| `core/src/sfd.rs` | 39 KB |
| `core/src/uds.rs` | 75 KB |

`cargo fmt --check` ist danach für alle drei Crates lokal grün (Exit 0), und
`sh scripts/kern-tests.sh` läuft durch (224 Rust-Tests).

## Lizenzen und CVEs: `cargo deny`

Der Job `deny` prüft alle drei Rust-Pakete gegen `deny.toml`: ob jede
Abhängigkeit unter einer erlaubten Lizenz steht, und ob eine Fassung im
`Cargo.lock` eine bekannte Sicherheitslücke aus der RustSec-Datenbank ist.
Eigener Job wie `fmt`, weil das nichts über das Verhalten des Kerns sagt.

Drei Läufe, nicht einer. `core`, `core-ffi` und `core/fuzz` hängen nicht an
einer gemeinsamen Workspace-Wurzel; jedes hat sein eigenes `Cargo.lock`, und
cargo-deny liest den Graph aus dem Lockfile, das zu seinem Manifest gehört. Ein
einzelner Lauf über alle würde einen der Stände verschweigen — deshalb je ein
Schritt mit `--manifest-path`, damit im Log steht, welcher Teil gemeint ist. Die
Regeln stehen in `deny.toml` im Wurzelverzeichnis.

Die erlaubten Lizenzen sind eine Aufzählung und kein Muster: was nicht drinsteht,
bricht den Lauf. In den Bäumen von `core-ffi` (82 Pakete) und `core/fuzz` (12
Pakete) sind das am 18.11.2026 nur MIT, Apache-2.0 (teils als `OR`),
`Apache-2.0 WITH LLVM-exception`, MPL-2.0, Unicode-3.0, Unlicense und NCSA;
`LGPL-2.1-or-later` steht bei `r-efi` nur als dritte Alternative eines `OR`,
gewählt ist MIT. `core` hat keine Abhängigkeiten.

`NCSA` kam erst mit dem dritten Manifest dazu: `libfuzzer-sys` steht unter „(MIT
OR Apache-2.0) AND NCSA“, also unter der Lizenz der LLVM-Fuzzer selbst — die
erste Lizenz, die allein die Fuzz-Ziele hereinbringen.

### Das eigene Crate ist „proprietary“ — mit Absicht

`core` und `core-ffi` tragen `license = "proprietary"`. Das ist kein gültiger
SPDX-Ausdruck, und es gibt keinen: der Quelltext steht unter AGPL-3.0 (siehe
`LICENSE`), nicht unter einer Krate-Lizenz. cargo-deny wertet das als
„unlicensed“ und bricht — zu Recht, denn es kann die Frage nicht beantworten.
Beide Manifeste tragen deshalb `publish = false` (sie gehen nicht an crates.io),
und `[licenses.private] ignore = true` nimmt genau solche Crates aus. Fremde
Abhängigkeiten berührt das nicht; sie werden weiter gegen die Allow-Liste geprüft.

### Die eine Ausnahme in `[advisories]`

`basic-toml 0.1.10` ist seit dem 3.3.2025 ohne Betreuung — RUSTSEC-2026-0374,
„unmaintained“. Es kommt über `uniffi_bindgen → askama → askama_derive` herein,
also über das Werkzeug, das die Kotlin- und Swift-Bindings erzeugt, nicht über
Code, der im Betrieb fremde Daten auswertet. Das Advisory meldet keine Lücke und
stellt selbst fest „No safe upgrade is available“; `uniffi` ist per ADR-001 §5 auf
`=0.32.2` gepinnt. Der Eintrag steht deshalb mit Begründung in `deny.toml`, und
das Advisory bleibt im Log sichtbar. Beim nächsten `uniffi`-Upgrade gehört er
gelöscht — sonst wird aus einer befristeten Ausnahme ein Dauerzustand.

### Das Lockfile muss im Repository liegen

Der erste Lauf mit dem dritten Schritt (Lauf #333, Commit `d3922be`, 18.11.2026)
war rot, und zwar nur im Schritt `Fuzz-Ziele`:

```
error: cannot create the lock file /github/workspace/core/fuzz/Cargo.lock
       because --locked was passed to prevent this
```

`core/fuzz/Cargo.lock` lag nie im Repository — `core/` und `core-ffi/` haben
ihres eingecheckt, die Fuzz-Crate nicht (`.gitignore` ignoriert dort nur Korpus
und Abstürze). Lokal fiel das nicht auf, weil das Lockfile aus früheren
Fuzz-Läufen im Arbeitsbaum lag. Ohne Lockfile kann `--locked` nicht greifen:
cargo müsste die Abhängigkeiten erst auflösen, und genau das verbietet das Flag.
Commit `be0a069` checkt es ein; damit prüfen lokal und Runner denselben Graph.

**Stand am 18.11.2026**, lokal mit cargo-deny 0.21.0 gemessen:

| Manifest | `check licenses advisories` | Treffer |
| --- | --- | --- |
| `core/Cargo.toml` | grün (Exit 0) | keine — das Crate hat keine Abhängigkeiten |
| `core-ffi/Cargo.toml` | grün (Exit 0) | `RUSTSEC-2026-0374`, wie oben begründet |
| `core/fuzz/Cargo.toml` | grün (Exit 0) | keine — `libfuzzer-sys` und seine Abhängigkeiten sind sauber |

Auf dem Runner geben zwei Läufe zusammen den Beleg:

| Lauf | Commit | Ergebnis |
| --- | --- | --- |
| #330 | `3ca875c` | zwei Schritte, alle acht Jobs grün |
| #335 | `be0a069` | drei Schritte, `deny` nach 23 s grün (Kern, FFI-Schicht, Fuzz-Ziele) |

Das Image der Aktion bringt cargo-deny 0.21.0 mit, also dieselbe Fassung wie die
lokale Messung, und das Log zeigt den Aufruf
`cargo-deny --log-level warn --manifest-path core/Cargo.toml --locked` mit dem
Unterbefehl `check licenses advisories`. Dass die Regeln aus dem Wurzelverzeichnis
gelesen werden, sieht man im Kern-Schritt an den Warnungen
`license-not-encountered` mit Verweis auf `deny.toml:48`–`54`: bei einem Crate
ohne Abhängigkeiten trifft keine der erlaubten Lizenzen auf etwas. Ein Fehler
wäre das nicht, aber ein Hinweis darauf, dass die Liste mit dem Baum wachsen
muss.

**Was der Job nicht prüft:** ob eine Lizenz für die Auslieferung *passt*. Das
entscheidet die Allow-Liste, und die ist eine menschliche Entscheidung, keine
Messung. Ebenso wenig sagt er etwas über die Kotlin- und Java-Abhängigkeiten der
App oder über die C-Seite (`j2534/`) — dafür ist bis zum 18.11.2026 kein Job
gelaufen. Seitdem prüft `deps` deren Herkunft, Lizenzen und bekannte
Sicherheitslücken (nächstes Kapitel). Die C-Seite hat nichts zu befragen — sie
bindet keine Bibliothek ein, sondern zehn Kopfddateien; was dort nach einem
Advisory aussähe, gibt es nicht.

Denselben Befehl fährt `scripts/ci-lokal.sh` als Job `deny`, und der
Pre-Push-Hook wählt ihn mit aus — Einzelheiten unter „Lokaler Ersatz“ weiter
unten.

## Abhängigkeiten jenseits von Cargo: `deps`

`deny` liest nur Cargo-Lockfiles. Alles, was die Android-Module aus Google,
Maven Central und JitPack ziehen, und jede fremde Kopfddatei, die der C-Kern
einbindet, blieb damit ungeprüft. Der Job `deps` fährt
`scripts/abhaengigkeiten-pruefen.sh` und prüft beide Seiten: Herkunft, Lizenz und
bekannte Sicherheitslücken.

### Kotlin/Java: der aufgelöste Graph, nicht die Erklärung

Die Erklärungen in `android/app/build.gradle.kts` sagen nichts über transitive
Module. Geprüft wird deshalb der Graph, den Gradle daraus auflöst — ein
Init-Skript hängt dafür eine Aufgabe an `:bindings` und `:app` (die Build-Dateien
bleiben unverändert) und gibt je Konfiguration `<group>:<module>:<version>` aus:
`:bindings runtimeClasspath`, `:bindings testRuntimeClasspath`,
`:app releaseRuntimeClasspath` und `:app debugUnitTestRuntimeClasspath`.

Jedes Modul muss in `android/abhaengigkeiten.txt` stehen, mit Lizenz; fehlt eins,
endet der Job rot und nennt es. Das ist dieselbe Bauart wie `deny.toml`: die
Entscheidung ist menschlich, die Prüfung mechanisch. Werkzeuge, die die Lizenz
selbst ermitteln, gibt es für Gradle nicht — Gradle kennt kein Lizenzmodell. Die
Angaben in der Liste sind deshalb aus den POMs der Module abgelesen (18.11.2026),
nicht behauptet. Zwei POMs führen keine Lizenz (`listenablefuture`,
`hamcrest-core`); bei ihnen steht in der Zeile, worauf die Angabe beruht.

Gemessen am 18.11.2026 in dieser VM (OpenJDK 17.0.21, Gradle 8.14.4 aus dem
Wrapper, Android SDK mit Plattform 34) — 104 Fremdmodule:

| Lizenz | Module |
| --- | --- |
| Apache-2.0 | 94 |
| EPL-2.0 | 6 (JUnit 5 im Testgraph) |
| MIT | 1 (`usb-serial-for-android`) |
| EPL-1.0 | 1 (`junit:junit` 4.13.2) |
| BSD-3-Clause | 1 (`hamcrest-core`) |
| Apache-2.0 OR LGPL-2.1-or-later | 1 (`jna`) |

Nach dem Sprung auf AGP 9.3.1, Gradle 9.5.0 und `compileSdk 37` (dieselbe
Messung, 18.11.2026) sind es **117 Module**: 13 neue AndroidX-Module
(`navigationevent`, `savedstate-compose`, `window`, `runtime-retain`,
`core-viewtree`) und `kotlinx-serialization-core` kamen dazu, entfallen sind
`compose.material`, `material-icons-core` und die alten
`kotlin-stdlib-jdk7/jdk8`-Brücken. Die neuen Zeilen sind aus den POMs abgelesen,
nicht aus dem Gedächtnis: alle Apache-2.0.

### C-Seite: keine Fremdbibliothek, und das bleibt so

`j2534/` bindet zehn Kopfddateien ein: sieben aus ISO C (`stddef.h`, `stdint.h`,
`stdio.h`, `stdlib.h`, `stdarg.h`, `string.h`, `time.h`) und drei aus Win32
(`windows.h`, `winusb.h`, `setupapi.h`, alle nur unter `#ifdef _WIN32`
übersetzt). Keine libusb, kein libudev, kein Vendoring — das ist eine Zusage des
Projekts, und `j2534/abhaengigkeiten.txt` hält sie fest. Geprüft wird zweimal:
die Liste der eckigen Includes (was der Quelltext nennt, auch hinter `#ifdef`)
und über `cc -M` der Include-Graph, den der Compiler tatsächlich auflöst — jede
Abhängigkeit muss in `j2534/` oder in einem Systempfad liegen. Am 18.11.2026:
zehn Kopfddateien, alle in der Liste, Include-Graph nur `j2534/` und `/usr`.

### Sicherheitslücken: OSV

Die Frage, die bis zum 18.11.2026 offen blieb, beantwortet der dritte Teil des
Skripts. Gefragt wird OSV (`api.osv.dev`): offen, ohne Konto, ohne Schlüssel, mit
Maven-Koordinaten — und mit der Fassung, in der eine Lücke behoben ist. Gefragt
werden die Fassungen aus dem aufgelösten Graphen **und** die Plugins aus
`android/build.gradle.kts`, denn das Kotlin-Gradle-Plugin ist genau der Ort, an
dem die erste gefundene Lücke saß. Plugins löst Gradle über einen eigenen
Klassenpfad auf, der im Projektgraph nicht auftaucht (gemessen: der
`buildscript.classpath` bleibt bei Plugins aus dem `plugins`-Block leer); die
Fassungen werden deshalb dort gelesen, wo sie entschieden werden, und über eine
feste Zuordnung auf Maven-Koordinaten abgebildet. Ein Plugin ohne Zuordnung
macht den Lauf rot, statt still aus der Abfrage zu fallen.

Ein Treffer ist rot, es sei denn er steht mit Begründung in
`android/osv-ausnahmen.txt` — dieselbe Bauart wie die Ausnahme in `deny.toml`.
Ein Lauf ohne Netz ist kein grüner Lauf: antwortet OSV nicht, endet er mit 1 und
sagt das. Am 18.11.2026 sind so **108 Fassungen** (104 Fremdmodule, dazu AGP,
Kotlin-Gradle-Plugin und Compose-Compiler-Plugin) abgefragt worden.

Nach dem Sprung auf den Stand von Ende 2026 fragt derselbe Lauf **121
Fassungen** ab (117 Module, dazu AGP 9.3.1, Kotlin-Gradle-Plugin 2.4.20 und das
Compose-Compiler-Plugin 2.4.20) — Ergebnis: keine bekannte Lücke. Die
`osv-ausnahmen.txt` bleibt leer.

**Der eine Treffer und was er gekostet hat.** `org.jetbrains.kotlin:
kotlin-gradle-plugin:2.0.21` stand auf GHSA-r937-wjx7-w2jp (CVE-2026-53914,
„Unsafe Deserialization in Kotlin Build Cache Enables Code Execution“,
MODERATE): unsichere Deserialisierung der Metadaten im Build-Cache, behoben erst
in 2.4.20-Beta1. Es gab also keinen Fix in der 2.0er-Reihe. Der Sprung auf
**Kotlin 2.4.20** (stabil, vom 18.11.2026) zog drei Dinge nach sich, die unter
„nur eine Versionsnummer“ nicht sichtbar sind:

| Was | vorher | nachher | warum |
| --- | --- | --- | --- |
| Kotlin (AGP-Plugin, Compose-Plugin, `jvm`-Plugin) | 2.0.21 | 2.4.20 | die Lücke |
| Gradle (Wrapper) | 8.9 | 8.14.4 | Kotlin 2.4 verlangt mindestens 8.14.4 |
| `kotlinOptions { jvmTarget = "17" }` | im `android`-Block | `kotlin { compilerOptions { jvmTarget.set(JVM_17) } }` | Kotlin 2.4 hat das alte DSL entfernt, `jvmTarget` als String ist dort ein Fehler |
| `kotlinc` in `scripts/jvm-tests.sh` | 2.0.21 | 2.4.20 | das Skript sagt selbst, die Fassungen gehören zu `build.gradle.kts` |

Lokal belegt nach dem Sprung: `:bindings:test` grün (95 s, der erste Lauf holt
die neuen Artefakte), `:app:assembleDebug` grün (57 s, `BUILD SUCCESSFUL`),
danach alle sieben Jobs zusammen grün in 44 s. Der Wrapper-Jar selbst blieb der
von 8.9: er lädt nur die Distribution, und das tat er mit 8.14.4 nachweislich.

**Was die Prüfung nicht ist:** ein Beweis der Abwesenheit. OSV sammelt, was
andere melden (GHSA und Verwandte); eine Lücke, die nur im NVD steht und keine
GHSA hat, fehlt hier. Und geprüft wird der Graph, nicht das, was im APK landet:
`libjnidispatch.so` aus JNA und die native Rust-Bibliothek haben keine
Maven-Fassung und damit keinen Eintrag, den OSV befragen könnte.

#### AndroidX und Compose: gemessen, nicht vermutet

Genau der Fall, in dem „kein OSV-Treffer“ zu wenig wäre: die 84
AndroidX-/Compose-Fassungen im Graph sind in OSV kaum abgedeckt. Deshalb am
18.11.2026 zusätzlich das NVD befragt (offene API, ohne Schlüssel):

| Suche im NVD | Treffer | Ergebnis |
| --- | --- | --- |
| `androidx` | 0 | keine CVE nennt „androidx“ |
| `androidx.core` | 0 | keine |
| `material3` | 0 | keine |
| `compose ui` | 5 | fremde Projekte (Livewire, Docker-UI, Ditto, Nuxt UI) |
| `jetpack` | 44 | WordPress-Plugin und NVIDIA JetPack |

Der einzige Faden, der Compose überhaupt mit einem CVE verbindet, ist
**CVE-2024-7254** (Denial of Service in protobuf): im Google-Issuetracker
(401068466) melden Scanner ihn gegen `androidx.compose.ui:ui:1.7.x`, und
Google antwortet dort, die Ursache sei protobuf 3.22.3 und seit dem Sprung auf
4.28.2 (Compose 1.8+) behoben. Für dieses Projekt heißt das: **nichts zu heben**
— im aufgelösten Graphen kommt `com.google.protobuf` null Mal vor, und die POMs
von `androidx.compose.ui:ui-android:1.7.3`, `androidx.core:core:1.13.1` und
`androidx.compose.material3:material3:1.3.0` nennen es nicht. Wo kein Protobuf
im APK ist, ist auch die Lücke nicht darin. Ein Sprung auf Compose 1.8+ ginge
ohnehin nur mit `compileSdk 35` und damit einem AGP-Wechsel.

**Die Grenze bleibt:** Google veröffentlicht Sicherheitskorrekturen in den
Release Notes auch ohne CVE — „kein CVE“ heißt „keine gemeldete Lücke“, nicht
„keine Fehlerkorrektur“. Die Fassungen hier waren am 18.11.2026 die vom
September 2024 (Compose 1.7.3, core 1.13.1), der Stand Ende 2026 ist Compose
1.12.1, core 1.19.1. Dieser Abstand ist derselbe Tag geschlossen worden:

#### Der Sprung auf den Stand von Ende 2026

Er war kein Nummernwechsel in der Abhängigkeitsliste — Compose 1.12 verlangt
API 37, und das kann erst AGP 9.1 oder neuer. Was daran hing:

| Was | vorher | nachher | warum |
| --- | --- | --- | --- |
| Compose BOM | 2024.09.03 (Compose 1.7.3, material3 1.3.0) | 2026.09.00 (Compose 1.12.1, material3 1.4.0) | der Zweck des Sprungs |
| `activity-compose` | 1.9.2 | 1.13.0 | Stand Ende 2026 |
| `lifecycle-viewmodel-compose` | 2.8.5 | 2.11.0 | ebenso |
| `compileSdk` | 34 | 37 | Compose 1.12 setzt API 37 voraus; die Plattform heißt seit API 36 mit Unterfassung `android-37.0` |
| AGP | 8.5.2 | 9.3.1 | API 37 können erst AGP 9.1+; 9.3.1 ist die letzte Linie, die Kotlin 2.4.20 offiziell unterstützt (9.4/9.5 liegen darüber) |
| Gradle (Wrapper) | 8.14.4 | 9.5.0 | AGP 9.3 verlangt mindestens 9.5.0 |
| Build-Tools | 34.0.0 | 36.0.0 | Minimum von AGP 9 |
| `usb-serial-for-android` | 3.10.0 | 3.12.0 | die Sperre („3.11.0 verlangt `compileSdk 35`“) fiel mit dem Sprung |
| Kotlin-Plugin `android` | 2.4.20 | entfällt | AGP 9 übersetzt Kotlin selbst (built-in Kotlin); die KGP-Fassung kommt über das Compose-Plugin 2.4.20 (AGP bringt 2.2.10 mit, Gradle hebt auf 2.4.20) |
| `targetSdk` | 34 | **34** | bleibt bewusst: eine Verhaltenszusage ans System (Rechte, Hintergrundarbeit), kein Bauzwang — gehört erst nach einem Gerätetest angehoben |

Zwei Dinge daran waren Arbeit statt Tippen:

1. **Die Katalog-Assets.** `sourceSets["main"].assets.srcDir(layout.buildDirectory.dir(…))`
   lehnt AGP 9 ab („You cannot add Provider instances to the Android SourceSet
   API“). Der Kopier-Task beschreibt seinen Ausgang jetzt selbst als
   `DirectoryProperty`, und `androidComponents.onVariants { …
   sources.assets?.addGeneratedSourceDirectory(…) }` hängt ihn an die Variante —
   damit trägt die API die Task-Abhängigkeit, die vorher an `preBuild` hing.
2. **Die SDK-Pakete.** AGP 9 braucht die Plattform `android-37.0` schon beim
   Konfigurieren. `scripts/vorbereiten.sh` und der `android`-Job installieren sie
   (und Build-Tools 36.0.0) jetzt mit; ohne sie scheitert nicht der Code,
   sondern die Konfiguration.

Lokal belegt am 18.11.2026 (JDK 17, warm): `:app:assembleDebug` grün in 59 s,
die APK meldet `compileSdkVersion='37'` und `targetSdkVersion:'34'`, die fünf
YAML-Dateien liegen unter `assets/funktionen/`, `deps` ist mit 117 Modulen und
121 abgefragten Fassungen grün. Neu ist eine Abschreibungs-Warnung:
`LocalClipboardManager` (in `SfdAbschnitt.kt`) gilt seit Compose 1.12 als
veraltet zugunsten von `LocalClipboard`, das suspendierende Funktionen kennt.
Sie bleibt stehen — der Umbau ändert das Verhalten der Zwischenablage und
gehört nicht in einen Versionssprung.

**Nicht geprüft:** die Anzeige unter Compose 1.12 und `material3` 1.4.0. In
dieser VM gibt es kein Gerät und keinen Emulator (`/dev/kvm` nicht zugänglich);
belegt ist die Übersetzung, die Paketierung und der Asset-Pfad, nicht das
Bild.

### Was der Job nicht prüft

- **Keine Lücke ohne Meldung.** Siehe oben: OSV ist eine Sammlung von Meldungen,
  kein Beweis. Für die C-Seite gibt es nichts zu befragen, für native
  Bestandteile (JNA, Rust) auch nicht — die deckt `deny` für Rust ab, für JNA
  bleibt nur die Fassung in der Liste.
- **Keine Eignung einer Fassung.** Ob 2.4.20 mit AGP 9.3.1 auf Dauer zusammen
  arbeitet, sagt kein Advisory; das zeigt nur der Bau, und der ist oben datiert.
- **Keine Eignung der Lizenz.** Ob Apache-2.0 für die Auslieferung passt,
  entscheidet die Liste, nicht das Werkzeug.
- **Keine Fassungen in der Liste.** Aufgenommen ist das Modul, nicht seine
  Version; die aufgelösten Fassungen stehen im Log des Laufs. Die Fassungen sind
  in den Build-Dateien fest verdrahtet (kein `+`, kein `latest`), damit ein
  Versionssprung innerhalb einer erlaubten Lizenz nicht als Zeilenumbau in der
  Liste endet. Ein Sprung bleibt dadurch aber auch unbemerkt — der Preis dafür,
  dass die Liste bei jedem Update nicht neu geschrieben werden muss.

Ein neues Modul aufnehmen heißt: Zeile in die Liste, Lizenz beim Modul
nachschlagen (POM), eintragen, Lauf wiederholen. Fehlt der Eintrag, ist der Job
rot — nicht still.

## Fuzzing — lokal belegt, nicht in der CI

Punkt 4 der Definition of Done ([implementierungsplan.md](implementierungsplan.md)
§6) verlangt Fuzzing des Parser-Codes. Ein Job dafür steht bewusst nicht im
Workflow: er bräuchte nightly und `cargo-fuzz` auf dem Runner, und ein Lauf von
zehn Minuten je Ziel passt nicht in den Durchlauf. Gefahren wird lokal, mit
fester Dauer:

```
sh scripts/fuzz.sh        # 300 s je Ziel
sh scripts/fuzz.sh 30     # Rauchprobe nach einer Änderung
```

Zwei Ziele in `core/fuzz/fuzz_targets/`: `isotp` (Empfangsweg über `Loopback`,
Sendeweg gegen ein Gerät mit Blockgröße 0–8, `dlc` absichtlich auch falsch) und
`uds` (die Parser für Sitzung, Kennung und Fehlerspeicher sowie die
NRC-Erkennung).

| Ziel | Dauer | Läufe | Korpus | Absturz |
| --- | --- | --- | --- | --- |
| `isotp` | 302 s | 33 494 974 | 66 Einheiten | keiner |
| `uds` | 302 s | 55 643 504 | 18 Einheiten | keiner |

Gemessen am 17.11.2026 in dieser VM (nightly 1.102, cargo-fuzz 0.13.2); zusammen
604 s, also die geforderten zehn Minuten. Gefahren wurde in Abschnitten, weil
ein einzelner Befehl hier nach rund 295 s abgebrochen wird — die Dauer ist die
Summe der Abschnitte, nicht ein Lauf am Stück.

**Was der Lauf nicht sagt:** kein Absturz heißt nicht keine Fehler. Belegt ist
die Abwesenheit eines Absturzes auf den erreichten Pfaden, nicht die
Korrektheit der Auswertung. Der Korpus liegt nicht im Repository
(`core/fuzz/corpus/` ist ignoriert) — jeder Lauf ohne ihn beginnt bei null.

## Bis zum 17.11.2026: kein Lauf wurde gestartet

Bis dahin war kein einziger Lauf erfolgreich. Gemessen am 15.11.2026 über die
GitHub-API:

| Frage | Antwort |
| --- | --- |
| Läufe insgesamt | 147 (gemessen 15.11.2026, 16:56 UTC; jeder Push zählt weiter) |
| davon erfolgreich | **0** |
| die fünf Läufe zu den SFD- und Doku-Commits (`11948610`…`e0ad5607`) | je `conclusion: failure`, Laufzeit 3–5 s |
| die acht Läufe zu den Commits dieser Sitzung (`f5ea5eb`…`41a81222`, Lauf 129–136) | je `conclusion: failure`, Laufzeit 4–5 s |
| letzter Lauf (`43737325391`, Commit `e0ad5607`, Lauf 147) | 5 Jobs: vier mit `conclusion: failure`, je `runner_id: 0`, `steps: []`; gestartet 16:46:11, beendet 16:46:14/15; `bindings` `skipped`, weil `rust` nicht gelaufen ist — dasselbe Bild wie beim vorigen Stand |
| Repository | **privat** (`private: true`) — so der Stand der Messung; seit dem 17.11.2026 öffentlich |
| Actions im Repository | `enabled: true`, `allowed_actions: all` |
| Actions-Minuten im Monat (Konto) | 978 Linux + 388 macOS, brutto rund 30 $, im Nutzungsbericht mit 0 $ netto ausgewiesen |
| Lauf des Commits, der diese Seite hinzufügte (#126) | `created_at` 13:44:10, `updated_at` 13:44:14, `conclusion: failure` |
| erneuter Lauf, Versuch 2 (#126, „Re-run failed jobs“) | 13:49:00 → 13:49:04, wieder `steps: []`, `runner_id: 0`, `failure` |

Die Zahl der Läufe wächst mit jedem Push; erfolgreich war bisher keiner — auch
der Lauf, der diese Seite ins Repository brachte, endete nach vier Sekunden
ohne Runner. Ein erneuter Lauf (Versuch 2) ändert daran nichts: gleiche Dauer,
gleiches Bild, kein einziger Schritt. Solange das so aussieht, war die Sperre
nicht gefallen — unabhängig davon, was in den Abrechnungseinstellungen geändert
wurde. Nach einer Korrektur kann es dauern, bis sie greift; der nächste Beweis
war ein Lauf mit `runner_id ≠ 0`.

Ein Job **ohne Schritte und ohne Runner** ist nicht an einer Prüfung gescheitert
— er wurde nicht gestartet. Die Meldung, die GitHub dazu anzeigt, lautet:

> The job was not started because recent account payments have failed or your
> spending limit needs to be increased. Please check the 'Billing & plans'
> section in your settings.

Das Bild ist in sich stimmig: die Rechte im Repository sind offen, die
Workflow-Datei ist gültig (lokal mit einem YAML-Parser geladen, sieben Jobs,
`android` mit 13 Schritten), und es wurden in diesem Monat Minuten verbraucht.
Es fehlt also nicht an der Konfiguration, sondern an der Abrechnung.

### Seit dem 17.11.2026 läuft die CI

Das Repository ist seit dem 17.11.2026, 19:17 UTC **öffentlich**. Standard-Runner
sind in öffentlichen Repositories kostenlos und verbrauchen kein Kontingent
(GitHub-Abrechnungsdoku: „Standard GitHub-hosted runners in public repositories“),
damit greift die Abrechnung dieses Pfads nicht mehr. Belegt an drei Läufen
desselben Abends:

| Lauf | Commit | Ergebnis |
| --- | --- | --- |
| #319 | `c6b1bc5` (workflow_dispatch ergänzt) | erste Läufe mit echtem Runner (`runner_id: 1000003054` ff.); der Android-Job scheiterte an der Lizenzabfrage |
| #322 | `850471b` (Skripte per `bash` aufgerufen) | `conclusion: success`, alle sieben Jobs grün |
| #323 | `2bf8698` (Skripte ausführbar) | `conclusion: success`, alle sieben Jobs grün |

Damit ist auch das auf dem Runner belegt, was vorher nur lokal galt: `cargo ndk`
für beide ABIs, die `readelf`-Prüfung, `setup-gradle` ohne `gradle-version`, der
Kotlin-Linktest, der APK-Bau mit beiden `libvci_ffi.so` und die Artefakt-Uploads.

## Was die Sperre löst — und wer es kann

Das kann nur der Kontoinhaber (oder ein Billing-Manager einer Organisation); von
dieser Maschine aus ist es nicht erreichbar. Drei Wege:

1. **Zahlungsmittel reparieren oder Budget anheben.** `github.com/settings/billing`
   → *Payment information* (Karte erneuern, abgelehnte Zahlung wiederholen) und
   *Budgets and alerts*: ein Budget mit „Stop usage when budget limit is reached“
   blockiert, sobald es aufgebraucht ist — auch ein 0-$-Budget. Budgets gibt es
   pro Produkt, pro Runner-Typ und pro Repository; eines davon genügt, um zu
   blockieren. Nach einer Zahlung kann es einige Zeit dauern, bis die Sperre
   fällt.
2. **Repository öffentlich machen.** Standard-Runner in öffentlichen
   Repositories sind kostenlos, die Abrechnung dieses Pfads greift dort nicht.
   Das ist eine Entscheidung über den Code, nicht über die Technik — diesen Weg
   ist das Repository am 17.11.2026 gegangen (siehe oben).
3. **Selbst gehosteter Runner.** `runs-on` auf einen eigenen Runner zeigen; der
   zählt nicht zur Abrechnung. Braucht eine dauerhaft erreichbare Maschine, die
   es hier nicht gibt.

Was **nicht** hilft: die Workflow-Datei ändern, Rechte im Repository anpassen,
Jobs erneut starten („Re-run jobs“ scheitert genauso) oder die Trigger einschränken.

## Woran man erkennt, dass es wieder läuft

Ein Lauf, der die Sperre überwunden hat, bekommt einen Runner und Schritte.
Prüfbar über `GET /repos/AderLess47/werkstatt-app/actions/runs/{id}/jobs`:

- `runner_id` ist nicht 0,
- `steps` ist gefüllt (mindestens `Set up job`),
- die Laufzeit liegt über ein paar Sekunden.

Danach ist der erste grüne Lauf die eigentliche Prüfung — er kam mit #322
(17.11.2026, 19:37 UTC), zwei Läufe später folgte #323.

## Lokaler Ersatz: `scripts/ci-lokal.sh`

Für den Fall, dass auf dem Runner nichts durchgeht (oder ohne Netz), fährt ein
Skript dieselben sieben Jobs mit denselben Befehlen auf dieser Maschine:

```
sh scripts/ci-lokal.sh
```

Gemessen am 17.11.2026, 16:39 UTC in dieser VM (rustc 1.100.0, cargo 1.100.0,
OpenJDK 17.0.21, Linux 6.1.102 x86_64) — alle fünf Jobs grün in zusammen 37 s.
Gegenüber dem Lauf um 16:15 UTC sind sechs Tests dazugekommen, alle in der App:
die Ebenen-Prüfung des neuen SecurityAccess-Abschnitts (Hex, gerade Zahl,
ein Byte), der Anbieter, der Ebene und Seed mitschreibt, die Seed-Texte (kein
Seed, leer, nur Nullbytes, Hex) und die Meldung bei fehlendem Schlüssel —
damit 186. Dazu kam der SecurityAccess-Vorgang an die Audit-Kette: drei Tests
in der FFI-Schicht (19 → 22) und einer in der App, der prüft, dass der Eintrag
Ebene und Ergebnis nennt, aber weder Seed noch Schlüssel. Der `rust`-Job brauchte
diesmal 0 s, weil die Übersetzung schon stand; Dauern und Umfang der Läufe sind
deshalb nur bedingt vergleichbar. Die Läufe davor sind die Stationen dieser
Sitzung: `0x27` im Kern und über die Sprachgrenze, `0x2E` und `0x31` im Kern.

| Job | Ergebnis | Dauer | Was im Log steht |
| --- | --- | --- | --- |
| `c` | grün | 0 s | 165 `PASS`-Zeilen, Korpus byteweise unverändert |
| `rust` | grün | 0 s | 224 Tests im Kern, 4 Korpus-Tests, 22 in der FFI-Schicht |
| `clippy` | grün | 1 s | keine Warnung in `core` und `core-ffi` |
| `fmt` | grün | 0 s | `--check` für `core`, `core-ffi`, `core/fuzz` |
| `jvm-tests` | grün | 36 s | `OK (186 tests)` |

Die Dauern sind **mit warmem Build-Verzeichnis** gemessen — `target/` war vom
vorigen Lauf gefüllt. Ein kalter Lauf braucht Minuten; die Zahl sagt deshalb
nichts über die Laufzeit auf einem Runner, nur dass dieselben Prüfungen hier
durchlaufen.

### `deny` kam am 18.11.2026 dazu

Der sechste Job kam mit der Aufnahme der Fuzz-Crate. Er fährt je Manifest
denselben Aufruf wie die CI (`cargo-deny --log-level warn --manifest-path …
--locked check licenses advisories`), also drei Läufe für `core`, `core-ffi` und
`core/fuzz` — ein Manifest weniger wäre ein Stand, den das Log verschweigt.

Gemessen am 18.11.2026 in dieser VM (cargo-deny 0.21.0, dieselbe Fassung wie im
Aktions-Image), warm:

| Job | Ergebnis | Dauer | Was im Log steht |
| --- | --- | --- | --- |
| `deny` | grün | 2–3 s | dreimal `advisories ok, licenses ok`, dazu `license-not-encountered` für die Lizenzen, die in einem Baum ohne passende Abhängigkeit nichts treffen |

Der erste Lauf holt die RustSec-Datenbank in den Cache des Benutzers: ohne Netz
und ohne Cache endet der Job rot, und das ist dann kein Lizenzbefund. Fehlt
`cargo-deny` (es liegt nicht bei cargo bei), steht der Job als „nicht gelaufen“
in der Übersicht und das Skript endet mit 1. Der Pre-Push-Hook wählt `deny` mit
aus und lässt ihn aus, wenn das Werkzeug fehlt — benannt, nicht als grün.

### `deps` kam am 18.11.2026 dazu

Der siebte Job schließt die Lücke, die im `deny`-Kapitel benannt war: er fährt
dasselbe Skript wie die CI (`sh scripts/abhaengigkeiten-pruefen.sh`) und hält
die Erlaubnislisten `android/abhaengigkeiten.txt` und `j2534/abhaengigkeiten.txt`
gegen den aufgelösten Gradle-Graphen bzw. den Include-Graph des C-Kerns, dazu die
Fassungen gegen OSV.

Gemessen am 18.11.2026 in dieser VM (OpenJDK 17.0.21, Gradle 8.14.4 aus dem
Wrapper, Android SDK mit Plattform 34), warm — der Gradle-Daemon lief schon:

| Job | Ergebnis | Dauer | Was im Log steht |
| --- | --- | --- | --- |
| `deps` | grün | 2 s | zehn Kopfddateien, alle in `j2534/abhaengigkeiten.txt`, Include-Graph nur `j2534/` und `/usr`; 104 Fremdmodule im Gradle-Graph, alle in `android/abhaengigkeiten.txt`, dazu die Lizenzübersicht (94 Apache-2.0, 6 EPL-2.0, je eins MIT, EPL-1.0, BSD-3-Clause, Apache-2.0 OR LGPL-2.1-or-later) und `108 Fassungen bei api.osv.dev abgefragt` mit dem Ergebnis „keine bekannte Lücke“ |

Nach dem Sprung auf AGP 9.3.1, Gradle 9.5.0 und Plattform 37.0 (18.11.2026,
warm) steht derselbe Lauf auf **117 Fremdmodulen** und **121 abgefragten
Fassungen** — weiter grün, Lizenzübersicht 107 Apache-2.0, 6 EPL-2.0, je eins
MIT, EPL-1.0, BSD-3-Clause, Apache-2.0 OR LGPL-2.1-or-later. Der erste Lauf
nach dem Sprung holt zusätzlich die Gradle-9.5.0-Distribution und die neuen
Artefakte; getrennt gemessen wurde das nicht.

Kalt ist der Job teuer: der erste Lauf holt Gradle-Distribution und
Modul-Metadaten und braucht Netz. Gemessen wurde in dieser VM ein kalter
Gradle-Lauf von 61 s (`:bindings:dependencies`, inklusive Download der
Distribution) — warm steht der Daemon und es sind Sekunden. Fehlt das JDK oder
das Android SDK, steht `deps` als „nicht gelaufen“ in der Übersicht und das
Skript endet mit 1. Der Pre-Push-Hook lässt ihn in diesem Fall aus und benennt
den Grund. Seit dem 18.11.2026 gilt dasselbe für `python3`: fehlt es, steht
`deps` als „nicht gelaufen“ in der Übersicht, und der Hook lässt ihn mit dem
Grund aus. Das **Netz** ist der andere Fall — es wird nicht vorab geprüft.
Fehlt es, läuft der Job und wird rot mit `OSV nicht befragbar`; das ist ein
Befund am Werkzeug, kein übersprungener Lauf.

Dass der Job anschlägt, ist am 18.11.2026 geprüft worden: ein
`#include <libusb.h>` in `j2534/src/core.c`, ein aus
`android/abhaengigkeiten.txt` gelöschtes Modul (`junit:junit`) und — für die
Advisory-Prüfung — eine Fassung mit bekannter Lücke in der Abfrageliste
(`kotlin-gradle-plugin:2.0.21`) machen ihn rot, jeweils mit Nennung des Fundes;
nach dem Zurücksetzen ist er wieder grün. Dieselbe Lücke mit Eintrag in
`android/osv-ausnahmen.txt` läuft durch und sagt „als Ausnahme eingetragen“; ein
nicht erreichbarer OSV-Host endet rot mit `OSV nicht befragbar`, nicht als
übersprungen.

Alle sieben Jobs zusammen sind am 18.11.2026 einmal komplett grün gelaufen:
106 s mit warmem `target/`, warmem Gradle-Daemon und einem JDK, das in dieser VM
sonst nicht liegt (`c` 0 s, `rust` 40 s, `clippy` 25 s, `fmt` 1 s, `deny` 2 s,
`deps` 1 s, `jvm-tests` 37 s). Ohne JDK steht `jvm-tests` weiter als „nicht
gelaufen“ da — der Lauf von `deps` ändert daran nichts.

Nach dem Kotlin-Sprung (siehe `deps`-Kapitel) ist derselbe Lauf am 18.11.2026
erneut gefahren: alle sieben Jobs grün in **44 s** (`c` 0 s, `rust` 1 s, `clippy`
1 s, `fmt` 0 s, `deny` 2 s, `deps` 2 s, `jvm-tests` 38 s, mit `OK (186 tests)`).
Die kürzere Zeit ist kein Fortschritt: `target/` war warm, und der `rust`-Job
hatte nichts zu übersetzen.

Was der Lauf **nicht** belegt, damit die Aussage klein bleibt: geprüft wird der
Arbeitsbaum, kein frischer Klon; es laufen die Werkzeuge dieser Maschine, nicht
die der Runner-Images; ohne Artefakt-Upload und ohne Cache. `android` (SDK,
NDK) und `bindings` (Artefakt-Upload, Swift-Bindings brauchen macOS) fehlen
ganz und stehen in der Übersicht nicht als grün. Fehlt das JDK, steht
`jvm-tests` als „nicht gelaufen“ da und das Skript endet mit 1 — ein nicht
gefahrener Job ist kein bestandener. Dasselbe gilt für `deny` ohne
`cargo-deny` und für `deps` ohne JDK, Android SDK, `python3` oder Netz.

Die Ausgaben liegen je Job in `dist/ci-lokal/<Zeitstempel>/` (ignoriert), die
Übersicht daneben in `uebersicht.txt`. Der Korpus wird dort byteweise gegen die
neu erzeugte Fassung verglichen statt über `git diff`: der Arbeitsbaum kann vom
Index abweichen, und ein Vergleich gegen den Index meldete dann eine Änderung,
die es nicht gibt.

## Was das für den Stand der Verifikation heißt

Seit dem 17.11.2026 stützt sich die Doku auch auf die CI: die Läufe #322 und #323
sind grün, inklusive Android-Job. Belegt ist damit zusätzlich zu den lokalen
Läufen (`make -C j2534 test`, beide `cargo test`, `sh scripts/jvm-tests.sh` mit
186 Tests, `sh scripts/fuzz.sh` über 604 s, siehe oben): `cargo ndk` für beide
ABIs, das Verhalten von `setup-gradle` ohne `gradle-version`, der APK-Bau mit
beiden ABIs, die Artefakt-Uploads und die Abhängigkeitsauflösung über JitPack
auf einem frischen Rechner.

Ungeprüft bleibt, was kein Lauf zeigen kann: die Anzeige und der Ablauf gegen
ein echtes Steuergerät. Dafür fehlen hier Gerät und Fahrzeug; die App läuft
bisher nur im Test und im Emulator-freien JVM-Lauf.
