# morning report

`src/main.rs` is the morning report for the weather-station network: it
reads the last collector batch (semicolon-separated
`station;temp;humidity;wind` lines) and prints one report line per reading.
Single file, no external crates - build and run with
`rustc src/main.rs -o report && ./report`.
