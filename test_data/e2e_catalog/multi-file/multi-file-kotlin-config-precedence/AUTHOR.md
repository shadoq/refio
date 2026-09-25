# multi-file-kotlin-config-precedence - notatki autora

Plik dla autorów scenariusza. Nie trafia do fixture i nie jest pokazywany ocenianemu agentowi.

## Pochodzenie

Zadanie wyprowadzone z historycznej usterki Refio, a nie pełne odtworzenie oryginalnego repozytorium. Nie jest to zgłoszenie usterki aktualnego Refio.

- Stan przed poprawką: `5e37f5f26cd98976f37123cec0c22b1633699498`.
- Poprawka: `0576dfc617edcbfccda0450b7070e8ed5867ac7d`.
- Plik: `core/src/main/kotlin/pl/jclab/refio/core/services/ConfigResolver.kt`, metoda `getConfigWithPrecedence`.
- Przed poprawką `get()` sprawdzał `runOverrides` na początku, a `getConfigWithPrecedence()` od razu wołał `configRepository.getWithPrecedence(...)`, więc odbiorcy czytający cały wiersz (wybór modelu, weryfikacja zadania) nie widzieli nadpisania. Poprawka zwraca z `getConfigWithPrecedence()` syntetyczny wiersz `Config` z opisem `"Run-scope override"`, bez zapisu do bazy.

Przeniesiono wyłącznie tę część historycznego commitu. Pozostałe zmiany tego commitu (`setTyped` przez `set`, gałąź `scope == APP` w `get`, parametr `supersedeProjectScope` i kasowanie wiersza projektu przy zapisie) dotyczą innych zachowań i nie weszły do zadania.

Różnice względem oryginału (uproszczenia, bez zmiany kontraktu naprawy):
- Brak SQLite/Exposed, YAML, pamięci podręcznej i `ConfigKey`; baza zastąpiona `InMemoryConfigRepository` z licznikiem zapisów `writeCount` i listą wierszy `allRows()`.
- `get()` zwraca wartość wiersza o najwyższym pierwszeństwie zamiast osobnej gałęzi dla zakresu; zachowuje regułę "nadpisanie najpierw".
- Puste (białe znaki) nadpisanie traktowane jest jako brak nadpisania. W oryginale `runOverrides[key]?.let { return it }` zwracał też pusty tekst; w fixture reguła `takeIf { it.isNotBlank() }` jest jawna w istniejącym `get()`, a test `blank run override counts as absent for every read` wymaga tej samej reguły w odczycie wiersza. To świadome doprecyzowanie "fallback behavior" z promptu, nie zmiana kontraktu historycznej naprawy.
- Odbiorca `ModelSelector.modelFor()` odpowiada miejscom w Refio, które czytały surowy wiersz przy wyborze modelu.

## Układ

- `src/main/kotlin/configdemo/ConfigResolver.kt` - resolver z błędem. Plik do zmiany.
- `src/main/kotlin/configdemo/ModelSelector.kt` - odbiorca czytający wiersz. Modyfikowalny (poprawna naprawa go nie wymaga).
- `src/main/kotlin/configdemo/ConfigRow.kt`, `InMemoryConfigRepository.kt` - chronione (rejestr zapisów musi być wiarygodny).
- `src/test/kotlin/configdemo/ConfigPrecedenceTest.kt`, `RunOverrideTest.kt` - chronione.
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `verify.cjs` - chronione.
- `.gitignore` (build/, .gradle/, .kotlin/, wyjątek dla `gradle-wrapper.jar`, bo główny `.gitignore` repo ignoruje `*.jar`) i `.gitattributes` (`gradlew` z LF) - pomocnicze, niechronione.

## Wersje narzędzi i środowisko

- Gradle 9.4.0 (wrapper skopiowany z głównego repo: `gradle-wrapper.jar`, `gradle-wrapper.properties`, `gradlew`, `gradlew.bat`; `gradlew` znormalizowany do LF).
- Kotlin 2.3.20 (`kotlin("jvm")`), `apiVersion`/`languageVersion` 1.9, `jvmTarget` 17.
- Toolchain Java 17. Na maszynie przygotowania Gradle wybrał Eclipse Temurin 17.0.16+8 z `~/.gradle/jdks` (auto-provisioned). Sam Gradle uruchomiony na Microsoft JDK 21.0.8.
- JUnit Jupiter 5.10.1, `junit-platform-launcher` 1.10.1 (Gradle 9 wymaga go jawnie), `kotlin("test")`. Bez MockK, SQLite i IntelliJ.
- `buildCmd`: `node verify.cjs`. Skrypt wybiera `.\gradlew.bat` (Windows, przez `cmd.exe /d /c`) albo `sh ./gradlew`, uruchamia `test --rerun --offline --no-daemon --console=plain`, sprawdza kod wyjścia, a potem sumuje `tests - skipped` oraz `failures + errors` z `build/test-results/test/TEST-*.xml`. Wymaga co najmniej jednego wykonanego testu i zera porażek.
- Uruchomienie offline sprawdzone: wszystkie zależności (wtyczka Kotlin 2.3.20, kotlin-test, JUnit) rozwiązały się z lokalnej pamięci Gradle (`--offline`). Na innej maszynie brak tych artefaktów albo brak JDK 17 wykrywalnego przez Gradle to błąd przygotowania środowiska, nie porażka modelu. Przygotowanie: jednorazowo `gradlew test` z siecią w kopii fixture, potem usunąć `build/`, `.gradle/`, `.kotlin/`.
- Czas: ok. 70 s na pełne `node verify.cjs` bez demona (kompilacja + 15 testów).
- Kompilator zgłasza ostrzeżenie o przestarzałej wersji języka 1.9 (tak jak w głównym repo); nie wpływa na wynik.

## Testy

Oczekiwane wartości to stałe tekstowe z zasianych danych, niezależne od implementacji.

FAIL_TO_PASS (oblewają na fixture):
- `RunOverrideTest.row read returns the run override over every stored scope`
- `RunOverrideTest.row read returns the run override for a key with no stored value`
- `RunOverrideTest.model selection uses the run override`

PASS_TO_PASS (przechodzą na fixture i golden):
- `ConfigPrecedenceTest`: `task value wins over project and application`, `project value wins over application when the task has no value`, `application value is used for a project without its own value`, `explicit project id takes the place of the default project`, `missing key yields null and the consumer falls back to its default`, `every set is one persistent write`
- `RunOverrideTest`: `ordinary read returns the run override over every stored scope`, `row read carries the key it was asked for`, `run override is never written to the repository`, `next run without the override reads the stored values again`, `blank run override counts as absent for every read`, `run override of one key leaves other keys alone`

Faktyczny wynik na fixture: `15 tests completed, 3 failed` (dokładnie trzy powyższe). Golden: 15/15.

## Rozwiązanie wzorcowe

`test_data/e2e/golden/multi-file-kotlin-config-precedence/src/main/kotlin/configdemo/ConfigResolver.kt`:

```diff
-    fun getConfigWithPrecedence(key: String, taskId: String? = null, projectId: String? = null): ConfigRow? =
-        repository.getWithPrecedence(key = key, taskId = taskId, projectId = resolveProjectId(projectId))
+    fun getConfigWithPrecedence(key: String, taskId: String? = null, projectId: String? = null): ConfigRow? {
+        // Callers that read the row (model selection) must see the same run override as
+        // get(); the row exists only for this run and is never stored.
+        runOverride(key)?.let { raw ->
+            return ConfigRow(key = key, value = raw, scope = ConfigScope.APP, description = RUN_OVERRIDE_DESCRIPTION)
+        }
+        return repository.getWithPrecedence(key = key, taskId = taskId, projectId = resolveProjectId(projectId))
+    }
...
     private fun resolveProjectId(projectId: String?): String? = projectId ?: defaultProjectId
+
+    private companion object {
+        const val RUN_OVERRIDE_DESCRIPTION = "Run-scope override"
+    }
```

## Celowo błędne rozwiązania

Zapisane jako kontrole negatywne w `test_data/e2e/negative/multi-file-kotlin-config-precedence/<nazwa>/`, nakładane przez `validate-scenarios.sh` na fixture z nałożonym golden (krok (d)).

1. `persist-override` - zapis nadpisania; w golden zamiast zwrócenia syntetycznego wiersza:
   ```diff
   -            return ConfigRow(key = key, value = raw, scope = ConfigScope.APP, description = RUN_OVERRIDE_DESCRIPTION)
   +            repository.set(key, raw, ConfigScope.APP, description = RUN_OVERRIDE_DESCRIPTION)
   +            return repository.get(key, ConfigScope.APP)
   ```
2. `consumer-only` - poprawa tylko odbiorcy; `ConfigResolver.kt` przywrócony do wersji z fixture, a w `ModelSelector.kt`:
   ```diff
   -        val row = resolver.getConfigWithPrecedence(MODEL_KEY, taskId = taskId)
   -        return row?.value?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
   +        return resolver.get(MODEL_KEY, taskId = taskId)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
   ```
3. `wrong-scope-order` - projekt przed zadaniem; w golden:
   ```diff
   -        return repository.getWithPrecedence(key = key, taskId = taskId, projectId = resolveProjectId(projectId))
   +        val projectRow = resolveProjectId(projectId)?.let { repository.get(key, ConfigScope.PROJECT, projectId = it) }
   +        return projectRow ?: repository.getWithPrecedence(key = key, taskId = taskId, projectId = resolveProjectId(projectId))
   ```
4. `leak-between-resolvers` - nadpisania w stanie całego procesu; w golden:
   ```diff
   +    init {
   +        activeOverrides.putAll(runOverrides)
   +    }
   +
   -    private fun runOverride(key: String): String? = runOverrides[key]?.takeIf { it.isNotBlank() }
   +    private fun runOverride(key: String): String? = activeOverrides[key]?.takeIf { it.isNotBlank() }
   ...
            const val RUN_OVERRIDE_DESCRIPTION = "Run-scope override"
   +        val activeOverrides = mutableMapOf<String, String>()
   ```

Wyniki (`validate-scenarios.sh`: `controls rejected: 4`, wszystkie przez `build_cmd exit=1`; szczegóły z ręcznego uruchomienia `node verify.cjs`):
- `persist-override`: `15 tests completed, 1 failed` (`run override is never written to the repository`).
- `consumer-only`: `15 tests completed, 2 failed` (oba testy odczytu wiersza).
- `wrong-scope-order`: `15 tests completed, 3 failed` (`task value wins over project and application`, `blank run override counts as absent for every read`, `next run without the override reads the stored values again`).
- `leak-between-resolvers`: `15 tests completed, 1 failed` (`next run without the override reads the stored values again`).

## Ograniczenia

- Testy są widoczne i chronione, nie ukryte. Wymóg "widoczne testy sprawdzają podstawowe pierwszeństwo, niezależny odbiór resztę" z opisu zadania nie jest możliwy w obecnym schemacie bez osobnego weryfikatora; wszystkie testy są w fixture.
- Zadanie jest wolne (Gradle bez demona, ok. 70 s na sprawdzenie). Walidacja scenariusza z czterema kontrolami trwa kilka minut.
- `verify.cjs` usuwa tylko `build/test-results/test` przed uruchomieniem; katalogi `build/`, `.gradle/`, `.kotlin/` powstają w kopii projektu, nie w fixture.
