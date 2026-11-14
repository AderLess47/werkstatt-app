//! Bindgen-Binary: erzeugt Kotlin- und Swift-Bindings aus der Bibliothek.
//!
//! Aufruf (siehe `scripts/gen-bindings.sh`):
//! `uniffi-bindgen generate --library <lib.so> --language kotlin --out-dir ...`

fn main() {
    uniffi::uniffi_bindgen_main()
}
