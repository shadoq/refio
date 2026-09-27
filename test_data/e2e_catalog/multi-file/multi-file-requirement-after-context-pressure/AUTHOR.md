# multi-file-requirement-after-context-pressure - notatki autora

Plik dla autorów scenariusza. Nie trafia do fixture i nie jest pokazywany ocenianemu agentowi.

## Pochodzenie

Syntetyczne zadanie inspirowane metodą SWE-bench (FAIL_TO_PASS / PASS_TO_PASS), nie oficjalne zadanie. Sprawdza, czy wymagania formatu podane na początku polecenia przetrwają pracę w kilku powiązanych modułach i odczyt większego pliku danych, a reguła anulowania zostanie zastosowana w obu niezależnych agregatorach.

Ta wersja mierzy funkcję. Odporność na utratę historii (32k/64k) to osobny eksperyment na tej samej, zamrożonej wersji zadania. Bez śladu kompakcji albo zbliżenia do limitu kontekstu wynik raportować jako "odporność kontekstu niezmierzona".

## Układ

- `src/regionTotals.js` - sumy regionów; każdy region z co najmniej jednym zamówieniem jest na liście. Błąd: `canceled` liczone jak `paid`. Do zmiany.
- `src/overallTotal.js` - suma całkowita, celowo osobna implementacja (`switch`). Ten sam błąd. Do zmiany.
- `src/report.js` - `buildReport` (kontrakt CSV: `;`, nagłówek `region;total_cents`, sortowanie alfabetyczne, wiersz `TOTAL`, LF na końcu każdej linii) i `parseOrders`. Chroniony (format raportu).
- `bin/report.js` - `node bin/report.js <orders.csv>` na stdout. Chroniony.
- `data/sample.csv` (przykład z karty zadania), `data/orders.csv` (240 zamówień + 2 zwroty regionu SE), `data/empty.csv` (sam nagłówek). Chronione.
- `test/report.test.js`, `package.json` - chronione.

`data/orders.csv` wygenerowano jednorazowo deterministycznym generatorem (LCG z ziarnem 20260925, regiony PL/DE/FR/IT/NL/CZ/ES, statusy 60% paid / 20% canceled / 20% refunded, kwoty 199-49998) i zamrożono. Rozkład: 146 paid, 55 canceled, 41 refunded; SE ma wyłącznie zwroty. Nie dokładać danych do chwili porażki wybranego modelu.

## Wersje narzędzi

- Node.js 22.23.2, tylko moduły wbudowane.
- `buildCmd`: `node --test test/report.test.js`.

## Oczekiwane wartości

Przykład z karty (`data/sample.csv`): `region;total_cents` / `DE;300` / `PL;1000` / `TOTAL;1300` z końcowym LF.

Pełny eksport policzony niezależnie od kodu projektu (`awk`, liczą się tylko `paid`): `CZ;561664`, `DE;402457`, `ES;441440`, `FR;538309`, `IT;720783`, `NL;811587`, `PL;376734`, `SE;0`, `TOTAL;3852974`. Z błędem (canceled liczone) suma wynosiłaby 5154688.

## Testy

FAIL_TO_PASS:
- `regional totals ignore canceled orders`
- `the overall total ignores canceled orders`
- `the report keeps the semicolon CSV contract for the documented example`
- `the CLI prints the documented example exactly, ending with LF`
- `the CLI report for the full order export`

PASS_TO_PASS:
- `paid orders contribute their amount and refunded orders contribute zero`
- `a region whose total is zero is still listed`
- `an empty order list gives the header and a zero total`
- `the CLI prints only the header and a zero total for an empty export`

Faktyczny wynik na fixture: `# pass 4`, `# fail 5`. Golden: 9/9.

Region wyłącznie z zamówieniami anulowanymi nie jest testowany celowo: polecenie nie rozstrzyga, czy ma pozostać na liście z zerem, czy zniknąć.

## Rozwiązanie wzorcowe

```diff
 // src/regionTotals.js
 //   refunded  - contributes zero (the money went back to the customer)
+//   canceled  - contributes zero (the order never completed)
...
-    if (order.status === "refunded") continue;
+    if (order.status === "refunded" || order.status === "canceled") continue;

 // src/overallTotal.js
       case "refunded":
+      case "canceled":
         break;
```

## Celowo błędne rozwiązania

Zapisane jako kontrole negatywne w `test_data/e2e/negative/multi-file-requirement-after-context-pressure/<nazwa>/`, nakładane przez `validate-scenarios.sh` na fixture z nałożonym golden (krok (d)). Wszystkie odrzucone (`controls rejected: 5`).

1. `only-region-fixed` - `src/overallTotal.js` przywrócony z fixture (poprawiony tylko agregator regionów). FAIL: `build_cmd exit=1`, 4 testy.
2. `only-overall-fixed` - `src/regionTotals.js` przywrócony z fixture. FAIL: `build_cmd exit=1`, 4 testy.
3. `comma-separator` - w `src/report.js` `const SEPARATOR = ",";`. FAIL: `src/report.js changed`, `build_cmd exit=1`, 6 testów.
4. `header-removed` - w `src/report.js` `const lines = [];` zamiast `[HEADER.join(SEPARATOR)]`. FAIL: `src/report.js changed`, `build_cmd exit=1`, 6 testów.
5. `data-changed` - z `data/orders.csv` usunięte wiersze `canceled` (przy poprawnym kodzie). Testy przechodzą, odrzuca wyłącznie `fileUnchanged` (`data/orders.csv changed`).

## Ograniczenia

- Scenariusz nie wymusza presji kontekstu: przy domyślnym oknie 64k dane (ok. 8 KB) i moduły mieszczą się bez kompakcji. Wariant 32k/64k wymaga osobnych ustawień przebiegu; schemat nie ma asercji "zaszła kompakcja", trzeba ją odczytać ze śladu (np. znaczniki `ConversationSummaryService`/`[CTX]` w logu) i raportować osobno.
- Testy są widoczne i chronione, nie ukryte.
- Kodowanie: pliki są ASCII; osobnej asercji na kodowanie wyjścia nie ma (porównanie dokładnego tekstu stdout obejmuje LF i brak BOM).
