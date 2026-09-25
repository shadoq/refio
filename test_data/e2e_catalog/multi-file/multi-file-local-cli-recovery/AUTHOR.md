# multi-file-local-cli-recovery - notatki autora

Plik dla autorów scenariusza. Nie trafia do fixture i nie jest pokazywany ocenianemu agentowi.

## Pochodzenie

Zadanie terminalowe inspirowane Terminal-Bench (praca z lokalnym narzędziem według jego dokumentacji, ocena przez niezależny weryfikator), nie oficjalne zadanie tego benchmarku. Mierzy, czy lokalny model przeczyta `--help` i README, rozpozna nieaktualny stan rejestru i przejdzie udokumentowaną ścieżkę odtworzenia zamiast pisać wynik ręcznie. Dane syntetyczne, bez sieci.

## Układ

- `bin/packctl.cjs` - narzędzie: `--help`, `reset`, `verify`, `publish <name>`, `export <path>`. Katalog roboczy ustala względem własnego pliku; `export` rozwiązuje ścieżkę względem bieżącego katalogu. Chronione.
- `README.md` - opis przepływu i kodów wyjścia (0 ok, 1 użycie, 2 nieznany pakiet, 3 rejestr niezgodny z manifestem, 4 zła suma kontrolna artefaktu, 5 brak zależności, 6 pakiet już opublikowany). Chronione.
- `manifests/release.json` - wydanie `2026.09`; pakiety celowo w kolejności `ui`, `app`, `base` (nie w kolejności zależności). Chronione.
- `artifacts/base-1.2.0.pkg`, `ui-2.4.1.pkg`, `app-3.0.0.pkg` - jednoliniowe pliki tekstowe bez końcowego znaku nowej linii, więc konwersja końców linii przez Git (`core.autocrlf`) nie zmienia ich bajtów ani sum. Chronione.
- `.state/registry.json` - nieaktualny stan: `app` opublikowany przed zależnościami, `base` w starej wersji 1.1.0 ze starą sumą, brak `ui`. Modyfikowalny.
- `out/` - brak w fixture; tworzy go `export`. Modyfikowalny.
- `test/release.test.js` - weryfikator. Chroniony.
- `package.json` - chroniony.

## Niezależny test narzędzia

`packctl-cli.test.cjs` w tym katalogu katalogu scenariusza (poza fixture) sprawdza samo narzędzie na świeżej kopii fixture w katalogu tymczasowym, zanim jakikolwiek agent będzie oceniany:

```
node --test test_data/e2e_catalog/multi-file/multi-file-local-cli-recovery/packctl-cli.test.cjs
```

Pokrycie: pomoc i nieznane polecenie, wykrycie nieaktualnego stanu (kod 3), odmowa ponownej publikacji bez `reset` (6), odmowa pakietu z nieaktualną zależnością (5), nieznany pakiet (2), eksport nieaktualnego stanu daje `verified:false` i oblewa weryfikator, nietknięte fixture oblewa weryfikator, pełny udokumentowany przepływ przechodzi weryfikator, podmieniony artefakt (4), zła kolejność w rejestrze (3), tworzenie katalogów przy `export`. Wynik: `# pass 12`, `# fail 0`.

Uwaga techniczna: przy zagnieżdżonym `node --test` trzeba usunąć zmienną `NODE_TEST_CONTEXT`, inaczej proces potomny raportuje do rodzica i zawsze kończy się kodem 0.

## Wersje narzędzi

- Node.js 22.23.2, tylko moduły wbudowane (`node:crypto`, `node:fs`, `node:path`, `node:test`).
- `buildCmd`: `node --test test/release.test.js`.

## Weryfikator (testy)

Oczekiwane wersje, ścieżki artefaktów, sumy SHA-256 (policzone `sha256sum` przy przygotowaniu) i zależności są literałami w teście. Weryfikator nie czyta rejestru ani pola `verified` eksportu.

FAIL_TO_PASS (oblewają na fixture, bo brak `out/release.json`):
- `the release document names the manifest release`
- `every package is exported exactly once and nothing else`
- `each exported package has its release version, artifact and a recomputed checksum`
- `dependencies are published before their dependents`

PASS_TO_PASS:
- `the artifacts on disk still match the release checksums`

Faktyczny wynik: fixture `build_cmd exit=1`; golden przechodzi.

## Rozwiązanie wzorcowe

Wytworzone narzędziem, nie ręcznie: `verify` (kod 3), `reset`, `publish base`, `publish ui`, `publish app`, `verify` (kod 0), `export out/release.json`. W `test_data/e2e/golden/multi-file-local-cli-recovery/` są wynikowe `out/release.json` (kolejność `base`, `ui`, `app`, `verified: true`) i `.state/registry.json`.

## Celowo błędne rozwiązania

Zapisane jako kontrole negatywne w `test_data/e2e/negative/multi-file-local-cli-recovery/<nazwa>/`, nakładane przez `validate-scenarios.sh` na fixture z nałożonym golden (krok (d)). Wszystkie odrzucone (`controls rejected: 5`).

1. `stale-checksum` - w `out/release.json` `base.sha256` = stara suma `b5c6181e2752092913a848a102c0807c4e479d8b4e517a92a752afd5d5ca8800`. Odrzucone przez test sum kontrolnych.
2. `missing-ui` - `out/release.json` bez wpisu `ui`. Odrzucone przez test zbioru pakietów.
3. `app-before-ui` - kolejność `base`, `app`, `ui`. Odrzucone przez test kolejności zależności.
4. `export-only` - sam eksport nieaktualnego rejestru: `.state/registry.json` z fixture i `out/release.json` z pakietami `app 3.0.0`, `base 1.1.0` (stara suma), `verified: false`. Odrzucone przez testy zbioru, wersji i sum.
5. `manifest-changed` - z `manifests/release.json` usunięty `ui` (a `app.dependsOn` = `["base"]`), wynik zgodny z tak zmienionym manifestem. Odrzucone przez `fileUnchanged` (manifest) i przez test zbioru pakietów.

## Ograniczenia

- Poprawny stan plików nie dowodzi użycia CLI: agent mógłby napisać `out/release.json` ręcznie z literałami z manifestu i przejść twardą bramkę. Użycie narzędzia trzeba oceniać osobno ze śladu terminala (kryterium sędziego). `toolOrder` nie sprawdza argumentów poleceń, więc go nie ustawiono.
- `noImmediateRepeat` wyłączone: dwa kolejne `verify` (przed i po naprawie) albo ponowienie po błędzie narzędzia mogą być uzasadnione.
- Brak sieci nie jest wymuszany przez scenariusz; zależy od ustawień przebiegu (np. `--no-egress`).
- Testy są widoczne i chronione, nie ukryte. `packctl-cli.test.cjs` to dodatkowy plik w katalogu scenariusza, poza trzema plikami z karty zadania; generator katalogu go ignoruje (czyta tylko `*.case.json` i `*.prompt.md`).
