# CI — was läuft, und wo sie gerade steht

`.github/workflows/ci.yml` läuft bei jedem Push und jedem Pull Request.

| Job | Was er prüft |
| --- | --- |
| `c` | `make -C j2534 test` (165 Prüfungen) und dass `core/tests/corpus/isotp.txt` unverändert aus dem Erzeuger hervorgeht |
| `rust` | `cargo test` für `core` (48 Tests, dazu 4 Korpus-Tests aus `core/tests/corpus/isotp.txt`) und für `core-ffi` (8 Tests) |
| `bindings` | erzeugt Kotlin- und Swift-Bindings neu, lädt sie als Artefakt hoch (`needs: rust`) |
| `jvm-tests` | die 157 Tests der App ohne Gerät über `sh scripts/jvm-tests.sh`, gegen den Host-Build der Bibliothek |
| `android` | NDK-Bibliothek für `arm64-v8a` und `x86_64`; `./android/gradlew -p android :bindings:test`; dann `:app:testDebugUnitTest :app:assembleDebug` und eine Prüfung, dass beide ABIs in der APK liegen |

Der Android-Job baut über den Wrapper, nicht über ein vom Runner gestelltes
Gradle: die Version steht allein in
`android/gradle/wrapper/gradle-wrapper.properties`. `setup-gradle` bekommt
bewusst kein `gradle-version` — ohne diese Angabe nimmt die Aktion den Wrapper
des Projekts an und richtet nur den Cache ein. Damit gibt es genau einen Pin und
keine zweite Gradle-Distribution, die niemand benutzt.

Die Zahlen in den Jobnamen sind eine Ankündigung, keine Messung. Damit sie nicht
abdriften, zählt `sh scripts/zahlen-pruefen.sh` alle fünf lokal nach —
`make -C j2534 test`, beide `cargo test`, `scripts/jvm-tests.sh` — und bricht ab,
sobald eine Zahl von der im Workflow genannten abweicht (Exit 1). Es zählt, was
die Suiten melden, nicht was im Quelltext deklariert ist. Laufen kann es nur
lokal: die Jobs selbst werden ja nicht gestartet (siehe unten).

## Stand: die Jobs werden nicht gestartet

Gemessen am 15.11.2026 über die GitHub-API:

| Frage | Antwort |
| --- | --- |
| Läufe insgesamt | 147 (gemessen 15.11.2026, 16:56 UTC; jeder Push zählt weiter) |
| davon erfolgreich | **0** |
| die fünf Läufe zu den SFD- und Doku-Commits (`11948610`…`e0ad5607`) | je `conclusion: failure`, Laufzeit 3–5 s |
| die acht Läufe zu den Commits dieser Sitzung (`f5ea5eb`…`41a81222`, Lauf 129–136) | je `conclusion: failure`, Laufzeit 4–5 s |
| letzter Lauf (`43737325391`, Commit `e0ad5607`, Lauf 147) | 5 Jobs: vier mit `conclusion: failure`, je `runner_id: 0`, `steps: []`; gestartet 16:46:11, beendet 16:46:14/15; `bindings` `skipped`, weil `rust` nicht gelaufen ist — dasselbe Bild wie beim vorigen Stand |
| Repository | **privat** (`private: true`) |
| Actions im Repository | `enabled: true`, `allowed_actions: all` |
| Actions-Minuten im Monat (Konto) | 978 Linux + 388 macOS, brutto rund 30 $, im Nutzungsbericht mit 0 $ netto ausgewiesen |
| Lauf des Commits, der diese Seite hinzufügte (#126) | `created_at` 13:44:10, `updated_at` 13:44:14, `conclusion: failure` |
| erneuter Lauf, Versuch 2 (#126, „Re-run failed jobs“) | 13:49:00 → 13:49:04, wieder `steps: []`, `runner_id: 0`, `failure` |

Die Zahl der Läufe wächst mit jedem Push; erfolgreich war bisher keiner — auch
der Lauf, der diese Seite ins Repository brachte, endete nach vier Sekunden
ohne Runner. Ein erneuter Lauf (Versuch 2) ändert daran nichts: gleiche Dauer,
gleiches Bild, kein einziger Schritt. Solange das so aussieht, ist die Sperre
nicht gefallen — unabhängig davon, was in den Abrechnungseinstellungen geändert
wurde. Nach einer Korrektur kann es dauern, bis sie greift; der nächste Beweis
ist ein Lauf mit `runner_id ≠ 0`.

Ein Job **ohne Schritte und ohne Runner** ist nicht an einer Prüfung gescheitert
— er wurde nicht gestartet. Die Meldung, die GitHub dazu anzeigt, lautet:

> The job was not started because recent account payments have failed or your
> spending limit needs to be increased. Please check the 'Billing & plans'
> section in your settings.

Das Bild ist in sich stimmig: die Rechte im Repository sind offen, die
Workflow-Datei ist gültig (lokal mit einem YAML-Parser geladen, fünf Jobs,
`android` mit 13 Schritten), und es wurden in diesem Monat Minuten verbraucht.
Es fehlt also nicht an der Konfiguration, sondern an der Abrechnung.

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
   Das ist eine Entscheidung über den Code, nicht über die Technik — das
   Repository ist derzeit privat.
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

Danach ist der erste grüne Lauf die eigentliche Prüfung — bis dahin ist in
diesem Repository **kein** CI-Ergebnis belegt.

## Was das für den Stand der Verifikation heißt

Solange 0 von 147 Läufen erfolgreich sind, stützt sich keine Aussage in der
Dokumentation auf die CI. Belegt ist, was lokal gelaufen ist: `make -C j2534
test`, `cargo test` für beide Crates, `sh scripts/jvm-tests.sh` (157 Tests) und
der Android-Build über den Wrapper
(`./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug`).
Ungeprüft bleibt alles, was nur auf dem Runner passiert: `cargo ndk` für beide
ABIs, das Verhalten von `setup-gradle` ohne `gradle-version`, die
Artefakt-Uploads und die Abhängigkeitsauflösung über JitPack auf einem frischen
Rechner.
