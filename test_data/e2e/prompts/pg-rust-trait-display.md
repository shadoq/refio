`src/main.rs` prints the morning report for the weather-station network. The report format
lives in a hand-rolled `print_reading` function, and `reading_from_str` panics with
`expect` on the first malformed collector line. The report generator now wants `Reading` to
be a first-class value: printable with `{}`, parseable with `str::parse`, and constructible
without panics.

Your task:

1. Implement `std::fmt::Display` for `Reading` in `src/main.rs` with unit-aware output - the
   same "21.5 at Warsaw: 58% RH, wind 12.4 km/h" shape `print_reading` produces today -
   writing the payload with `write!`.
2. Implement `std::str::FromStr` for `Reading` with a dedicated `ParseReadingError`,
   replacing the panicking `reading_from_str` helper: a malformed line becomes an `Err`,
   not a crash.
3. Load the collector batch in `main` through a fallible loader that uses `?`, reporting
   any parse failure instead of aborting; keep `print_reading` as a thin wrapper over the
   `Display` impl.
4. Keep the `Reading` fields and the accepted `station;temp;humidity;wind` input format
   exactly as they are.

Do not modify the README: the change belongs in `src/main.rs`.
