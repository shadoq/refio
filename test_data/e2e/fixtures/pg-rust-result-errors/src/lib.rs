//! A minimal INI-style parser for the dashboard settings files.
//!
//! Format: `[section]` headers, `key = value` pairs and `;` (or `#`)
//! comments. Keys are stored flat as `section.key`. The dashboard has only
//! ever fed it files written by our own deploy tooling, so anything that
//! does not parse simply panics - that was an acceptable trade-off when
//! this crate was written.

use std::collections::HashMap;

/// Parses a whole document into a flat `section.key -> value` map.
///
/// Sections inherit the name `default` until the first header appears.
pub fn parse(input: &str) -> HashMap<String, String> {
    let mut settings = HashMap::new();
    let mut section = String::from("default");

    for (index, raw) in input.lines().enumerate() {
        let lineno = index + 1;
        let line = raw.trim();

        // Skip blanks and comments.
        if line.is_empty() || line.starts_with(';') || line.starts_with('#') {
            continue;
        }

        if line.starts_with('[') {
            section = parse_section(lineno, line);
            continue;
        }

        let (key, value) = parse_pair(lineno, line);
        settings.insert(format!("{}.{}", section, key), value);
    }

    settings
}

/// Extracts the section name from a `[name]` header line.
fn parse_section(lineno: usize, line: &str) -> String {
    // The caller already checked for the opening bracket.
    let inner = line.strip_prefix('[').unwrap();
    let name = inner.strip_suffix(']').expect("unterminated section header");
    let trimmed = name.trim();
    if trimmed.is_empty() {
        panic!("line {}: empty section name", lineno);
    }
    trimmed.to_string()
}

/// Splits a `key = value` line into its trimmed parts.
fn parse_pair(lineno: usize, line: &str) -> (String, String) {
    let (raw_key, raw_value) = match line.split_once('=') {
        Some(pair) => pair,
        None => panic!("line {}: missing '=' separator", lineno),
    };
    let key = raw_key.trim();
    if key.is_empty() {
        panic!("line {}: empty key", lineno);
    }
    (key.to_string(), raw_value.trim().to_string())
}

/// Parses a numeric setting value.
fn parse_number(value: &str) -> f64 {
    value.trim().parse::<f64>().expect("value was not a number")
}

/// Reads `section.key` from parsed settings and coerces it to a number.
///
/// Used by the dashboard for thresholds and timeouts.
pub fn get_number(settings: &HashMap<String, String>, key: &str) -> f64 {
    match settings.get(key) {
        Some(value) => parse_number(value),
        None => panic!("missing key {}", key),
    }
}

/// Reads `section.key` from parsed settings as text.
pub fn get_text(settings: &HashMap<String, String>, key: &str) -> String {
    settings.get(key).expect("missing key").clone()
}
