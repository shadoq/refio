# multi-file-offline-log-report - notatki autora

Plik dla autorów scenariusza. Nie trafia do fixture i nie jest pokazywany ocenianemu agentowi.

## Pochodzenie

Zadanie terminalowe inspirowane Terminal-Bench (narzędzie wiersza poleceń z kontraktem, ocena przez uruchomienie), nie oficjalne zadanie tego benchmarku i nie historyczna naprawa SWE-bench. Wartość: lokalne przetwarzanie wrażliwych danych bez sieci i kluczy. Wszystkie dane są syntetyczne.

## Układ

- `bin/report.js` - gotowy punkt wejścia: parsuje `--input`/`--output`, przy braku argumentu kończy kodem 2, potem woła `writeReport`. Modyfikowalny (golden go nie zmienia).
- `src/summarize.js` - zaślepki `summarize(text)` i `writeReport(inputPath, outputPath)` rzucające "not implemented". Plik do implementacji (`deliverable`).
- `README.md` - kontrakt (reguły poprawności, format wyjścia, kody wyjścia). Chroniony.
- `data/requests.jsonl`, `data/invalid-then-valid.jsonl`, `data/latency-20.jsonl`, `data/empty.jsonl` (0 bajtów) - chronione.
- `test/report.test.js` - testy przez CLI (`spawnSync(process.execPath, ...)`), katalogi tymczasowe z spacją w nazwie, sprzątane w `test.after`. Chroniony.
- `package.json` - chroniony.

## Wersje narzędzi

- Node.js 22.23.2, tylko moduły wbudowane.
- `buildCmd`: `node --test test/report.test.js`.

## Dane i oczekiwane wartości (policzone ręcznie)

`data/requests.jsonl` (16 linii):
- zachowane: checkout `r1(200,120)`, `r3(500,300)`, `r6(404,80)`; auth `r2(503,40)`, `r5(201,15)`;
- odrzucone (7): ucięty JSON (`r4`), `status` jako tekst (`r5`), pusty `id`, `latency_ms` = -1, `status` 99, `latency_ms` 1.5, linia `null`;
- duplikaty (2): późniejszy `r1` (latencja 999) i późniejszy `r2`;
- pomijane: pusta linia i linia z samymi spacjami.
- Wynik: auth `count 2, errors 1, p95 40` (posortowane [15,40], ranga ceil(1.9)=2); checkout `count 3, errors 1, p95 300` ([80,120,300], ranga ceil(2.85)=3); `rejected 7`, `duplicates 2`.

`data/invalid-then-valid.jsonl`: `q1` ze statusem 700 (odrzucony), potem poprawny `q1` (70 ms) -> `search count 1, errors 0, p95 70`, `rejected 1`, `duplicates 0`.

`data/latency-20.jsonl`: 20 rekordów `search`, opóźnienia 1..20 w pomieszanej kolejności, dwa ze statusem 500 -> `count 20, errors 2, p95 19` (ranga ceil(19)=19). Średnia wynosiłaby 10.5.

`data/empty.jsonl` -> `{ services: [], rejected: 0, duplicates: 0 }`.

## Testy

FAIL_TO_PASS:
- `two services, duplicates and rejected lines are summarised`
- `the first valid record of a duplicated id is the one kept`
- `an invalid record does not claim the id of a later valid record`
- `p95 of 20 latencies uses nearest rank ceil(0.95 * n)`
- `an empty input produces an empty report`
- `missing parent directories of the output are created`
- `input and output paths may contain spaces`

PASS_TO_PASS (przechodzą już na zaślepce, bo zaślepka kończy się błędem i niczego nie zapisuje; pilnują, by implementacja tego nie zepsuła):
- `a missing input fails and leaves an existing output untouched`
- `a missing input does not create the output`

Faktyczny wynik na fixture: `# pass 2`, `# fail 7`. Golden: `# pass 9`, `# fail 0`.

## Rozwiązanie wzorcowe

`test_data/e2e/golden/multi-file-offline-log-report/src/summarize.js` - pełna implementacja: podział na linie, `trim()` do pomijania pustych (obsługuje też CRLF), `JSON.parse` w `try`, walidacja obiektu (`Number.isInteger`, zakresy), `Set` widzianych `id` tylko dla poprawnych rekordów, grupowanie po `service`, sortowanie nazw, p95 jako `sorted[ceil(0.95*n)-1]`; `writeReport` najpierw czyta wejście (błąd -> komunikat na stderr, kod 1, brak zapisu), potem `mkdirSync(..., { recursive: true })` i zapis.

## Celowo błędne rozwiązania

Zapisane jako kontrole negatywne w `test_data/e2e/negative/multi-file-offline-log-report/<nazwa>/`, nakładane przez `validate-scenarios.sh` na fixture z nałożonym golden (krok (d)). Wszystkie odrzucone (`controls rejected: 3`).

1. `average-instead-of-p95`:
   ```diff
   -  const sorted = [...latencies].sort((a, b) => a - b);
   -  return sorted[Math.ceil(0.95 * sorted.length) - 1];
   +  return Math.round(latencies.reduce((s, v) => s + v, 0) / latencies.length);
   ```
   Oblewa testy 1, 2 i 4.
2. `last-duplicate-wins` (`seen` jako `Map`, późniejszy duplikat zastępuje wcześniejszy):
   ```diff
   -    if (seen.has(record.id)) {
   -      duplicates++;
   -      continue;
   -    }
   -    seen.add(record.id);
   +    if (seen.has(record.id)) {
   +      duplicates++;
   +      const prev = seen.get(record.id);
   +      const list = byService.get(prev.service);
   +      list.splice(list.indexOf(prev), 1);
   +      if (list.length === 0) byService.delete(prev.service);
   +    }
   +    seen.set(record.id, record);
   ```
   Oblewa testy 1 i 2.
3. `overwrite-on-missing-input`:
   ```diff
        console.error(`cannot read input ${inputPath}: ${err.message}`);
   +    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
   +    fs.writeFileSync(outputPath, JSON.stringify(summarize(""), null, 2) + "\n");
        return 1;
   ```
   Oblewa testy 8 i 9.

## Ograniczenia

- Testy są widoczne i chronione, nie ukryte.
- Wymóg "tylko moduły wbudowane Node" nie jest twardą bramką: brak `node_modules` w projekcie powoduje, że zewnętrzny `require` by się nie powiódł, ale schemat nie ma asercji na zawartość `package.json` poza jego ochroną (`fileUnchanged`), więc dopisanie zależności jest wykluczone tylko pośrednio.
- Brak sieci nie jest wymuszany przez scenariusz; zależy od ustawień przebiegu (np. `--no-egress`).
- Format wydruku JSON (wcięcia) jest dowolny; testy porównują sparsowaną strukturę.
