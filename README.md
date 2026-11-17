# Werkstatt-App

Diagnose-App für die Werkstatt: ein J2534-Pass-Thru-Port in C, der ISO-TP-/UDS-Kern
in Rust (über UniFFI an Android gebunden) und eine Android-App mit USB- und
BLE-Transport.

## Aufbau

- `core/` — UDS, ISO-TP, Audit-Kette, Funktionskatalog (Rust)
- `core-ffi/` — UniFFI-Bindung für Android
- `j2534/` — J2534-Port in C
- `android/` — App (Kotlin)
- `docs/` — Planung, Übergabe und Nachweise

## Prüfen

```sh
sh scripts/kern-tests.sh
sh scripts/jvm-tests.sh
./android/gradlew -p android :app:compileDebugKotlin
```

Im Repository steht bewusst kein Seed-/Key-Algorithmus, auch nicht als Testhilfe.

## Lizenz

Der Quellcode steht unter der GNU Affero General Public License, Version 3 oder
später (AGPL-3.0-or-later), siehe [LICENSE](LICENSE).

Die Unterlagen in `docs/` (Businessplan, Wirtschaftlichkeitsrechnung,
Investorenunterlagen) sind nicht Teil dieser Lizenz; dafür bleiben alle Rechte
vorbehalten.
