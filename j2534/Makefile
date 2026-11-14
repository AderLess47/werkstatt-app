# J2534-PassThru-Port — Build
#
# Linux/macOS: Shared Library + Tests (Transport, ISO-TP, ABI) mit simuliertem Gerät.
# Windows:     dieselben Quellen mit MSVC/MinGW bauen; usb_winusb.c wird dort aktiv,
#              auf Linux ist die Datei eine leere Übersetzungseinheit.
#              Exportliste: win/j2534.def

CC      ?= gcc
CFLAGS  ?= -std=c99 -O2 -Wall -Wextra -Wpedantic -fPIC -Iinclude
LDFLAGS ?= -shared

SRC  = src/core.c src/transport_usb.c src/usb_sim.c src/usb_winusb.c src/checker.c src/rollout.c
LIB  = build/libj2534.so
T1   = build/test_session
T2   = build/test_transport
T3   = build/test_checker
T4   = build/test_rollout
TOOL = build/vci-check
TOOL2 = build/rollout
GOLD = build/golden

.PHONY: all lib test clean syntax corpus

all: lib test

lib: $(LIB)

$(LIB): $(SRC) include/j2534.h src/transport_usb.h src/usb_backend.h src/checker.h src/rollout.h
	@mkdir -p build
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC)

$(T1): tests/test_session.c $(LIB)
	$(CC) $(CFLAGS) -Isrc -o $@ tests/test_session.c -Lbuild -lj2534 -Wl,-rpath,'$$ORIGIN'

$(T2): tests/test_transport.c $(LIB)
	$(CC) $(CFLAGS) -Isrc -o $@ tests/test_transport.c -Lbuild -lj2534 -Wl,-rpath,'$$ORIGIN'

$(T3): tests/test_checker.c $(LIB)
	$(CC) $(CFLAGS) -Isrc -o $@ tests/test_checker.c -Lbuild -lj2534 -Wl,-rpath,'$$ORIGIN'

$(T4): tests/test_rollout.c $(LIB)
	$(CC) $(CFLAGS) -Isrc -o $@ tests/test_rollout.c -Lbuild -lj2534 -Wl,-rpath,'$$ORIGIN'

$(TOOL): tools/vci_check.c $(LIB)
	$(CC) $(CFLAGS) -Isrc -o $@ tools/vci_check.c -Lbuild -lj2534 -Wl,-rpath,'$$ORIGIN'

$(TOOL2): tools/rollout.c $(LIB)
	$(CC) $(CFLAGS) -Isrc -o $@ tools/rollout.c -Lbuild -lj2534 -Wl,-rpath,'$$ORIGIN'

$(GOLD): tools/golden.c $(LIB)
	$(CC) $(CFLAGS) -Isrc -o $@ tools/golden.c -Lbuild -lj2534 -Wl,-rpath,'$$ORIGIN'

# Korpus für den Rust-Kern neu erzeugen (nach Änderungen am Wire-Format).
corpus: $(GOLD)
	@mkdir -p ../core/tests/corpus
	@./$(GOLD) > ../core/tests/corpus/isotp.txt
	@echo "Korpus geschrieben: core/tests/corpus/isotp.txt"

test: $(T1) $(T2) $(T3) $(T4) $(TOOL) $(TOOL2)
	@echo "=== ABI (J2534-API gegen Simulationsgerät) ==="
	@./$(T1)
	@echo
	@echo "=== Transport (Framing, ISO-TP, Fehlerfälle) ==="
	@./$(T2)
	@echo
	@echo "=== VCI-Checker (Selbsttest) ==="
	@./$(T3)
	@echo
	@echo "=== Rollout (Sammlung und Auswertung) ==="
	@./$(T4)

# Nur Syntax prüfen, ohne zu linken — nützlich für schnelle Iteration.
syntax:
	$(CC) $(CFLAGS) -fsyntax-only $(SRC)

clean:
	rm -rf build
