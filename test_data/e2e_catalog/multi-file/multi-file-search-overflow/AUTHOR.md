# multi-file-search-overflow - notatki autora

Plik dla autorów scenariusza. Nie trafia do fixture i nie jest pokazywany ocenianemu agentowi.

## Pochodzenie

Syntetyczne zadanie inspirowane metodą SWE-bench (naprawa w istniejącym repozytorium, ocena przez testy FAIL_TO_PASS i PASS_TO_PASS). Nie jest oficjalnym zadaniem SWE-bench. Mierzy, czy lokalny model znajdzie aktywny walidator, gdy zwykłe wyszukiwanie nazwy `isValidId` albo `MAX_ID_LENGTH` zwraca ok. 600 trafień z archiwum, czyli więcej niż typowy limit wyników narzędzia wyszukiwania.

Zadanie ocenia funkcjonalną naprawę. Nie dowodzi samo w sobie poprawności limitu trafień `GrepSearchTool`; tę hipotezę trzeba sprawdzić osobnym testem jednostkowym Refio z ponad 500 trafieniami. Scenariusz nie zakłada kolejności przechodzenia katalogów (`Files.walk`).

## Układ

- `src/api.js` - `createRecord(id)` zwraca `{ ok: true, id }` albo `{ ok: false, error: "INVALID_ID" }`. Chroniony.
- `src/validation.js` - aktywny walidator, początkowo `MAX_ID_LENGTH = 32`. Jedyny plik do zmiany.
- `archive/rel-<rok>-q<n>/validation.js` - 30 plików, w każdym 20 historycznych wersji walidatora w jednej linii (każda linia zawiera `isValidId` i `MAX_ID_LENGTH`, limity 16-64). Nic ich nie importuje. Wszystkie chronione.
- `test/api.test.js` - testy publicznego API. Chronione.
- `package.json` - chroniony.

Archiwum wygenerowano jednorazowo deterministycznym skryptem (lata 2015-2022, kwartały, pierwsze 30 kombinacji; limit = `[16,20,24,32,40,48,64][k % 7]`, wzorzec = jeden z pięciu wariantów klas znaków, `k = nr_pliku*20 + nr_wersji`). Pliki są zamrożone; nie generować ich ponownie przy zmianach scenariusza.

## Wersje narzędzi

- Node.js 22.23.2 (sprawdzone lokalnie), tylko moduły wbudowane (`node:test`, `node:assert`).
- `buildCmd`: `node --test test/api.test.js`. Jawna ścieżka, żeby wykrywanie plików testowych przez `node --test` nie zależało od nazw w archiwum.

## Testy

Oczekiwane wartości są literałami w teście, niezależnymi od implementacji.

FAIL_TO_PASS (oblewają na fixture, przechodzą na golden):
- `an identifier of 25 characters is over the documented limit`
- `an identifier of 32 characters is over the documented limit`

PASS_TO_PASS (przechodzą na fixture i golden):
- `a one-character identifier is accepted`
- `an identifier of exactly 24 characters is accepted`
- `letters, digits and underscore are all allowed`
- `an empty identifier is rejected`
- `an identifier containing a space is rejected`
- `an identifier with a non-ASCII letter is rejected`
- `null is rejected`
- `a number is rejected even when its digits would be valid`

Faktyczny wynik na fixture: `# pass 8`, `# fail 2` (dokładnie testy 25 i 32).

## Rozwiązanie wzorcowe

`test_data/e2e/golden/multi-file-search-overflow/src/validation.js`:

```diff
-const MAX_ID_LENGTH = 32;
+const MAX_ID_LENGTH = 24;
```

## Celowo błędne rozwiązania

Zapisane jako kontrole negatywne w `test_data/e2e/negative/multi-file-search-overflow/<nazwa>/` (nakładane przez `validate-scenarios.sh` na fixture z już nałożonym golden, krok (d)). Wszystkie zostały odrzucone (`controls rejected: 4`).

1. `archive-edited` - zmiana archiwum, `archive/rel-2015-q1/validation.js`, `rev01`:
   ```diff
   -exports.rev01 = function isValidId(value) { const MAX_ID_LENGTH = 16; ...
   +exports.rev01 = function isValidId(value) { const MAX_ID_LENGTH = 24; ...
   ```
   Wynik: FAIL (`changed:archive/rel-2015-q1/validation.js`).
2. `limit-23` - `src/validation.js`:
   ```diff
   -const MAX_ID_LENGTH = 32;
   +const MAX_ID_LENGTH = 23;
   ```
   Wynik: FAIL (`build_exit=1`, oblewa test 24 znaków).
3. `unicode-allowed` - dopuszczenie Unicode, w `src/validation.js`:
   ```diff
   -const ID_CHARS = /^[A-Za-z0-9_]+$/;
   +const ID_CHARS = /^[\p{L}\p{N}_]+$/u;
   ```
   Wynik: FAIL (`build_exit=1`, `not ok 8 - an identifier with a non-ASCII letter is rejected`).
4. `error-format` - zmiana formatu błędu, w `src/api.js`:
   ```diff
   -    return { ok: false, error: "INVALID_ID" };
   +    return { ok: false, error: { code: "INVALID_ID", message: "identifier must be 1-24 characters" } };
   ```
   Wynik: FAIL (`changed:src/api.js`, `build_exit=1`).

## Ograniczenia

- Testy są widoczne dla agenta i chronione przez `fileUnchanged`, nie ukryte.
- Liczbę odczytów plików i przypadki ucięcia wyników wyszukiwania trzeba odczytać ze śladu przebiegu; schemat nie ma asercji dla ucięcia wyszukiwania. `toolBudget` nie jest ustawiony celowo, żeby nie wymuszać jednej nazwy narzędzia.
- Needle `[^0-9]24[^0-9]` to tylko sygnał zgodności; właściwą bramką są testy.
