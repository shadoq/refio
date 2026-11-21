//! Morning report for the weather-station network.
//!
//! The readings arrive as semicolon-separated lines from the collector:
//! `station;temp;humidity;wind`. The report prints one line per reading.

/// One measurement from one station.
struct Reading {
    station: String,
    /// Degrees Celsius.
    temp: f64,
    /// Percent relative humidity.
    humidity: f64,
    /// Kilometers per hour.
    wind: f64,
}

/// Prints a reading in the report format.
fn print_reading(reading: &Reading) {
    // The dashboard expects "<temp> at <station>" followed by the details.
    println!(
        "{} at {}: {:.0}% RH, wind {:.1} km/h",
        reading.temp,
        reading.station,
        reading.humidity,
        reading.wind
    );
}

/// Builds a Reading from a collector line: `station;temp;humidity;wind`.
///
/// The collector is trusted to send well-formed lines, so anything unexpected
/// is a hard error.
fn reading_from_str(line: &str) -> Reading {
    let parts: Vec<&str> = line.split(';').collect();
    if parts.len() != 4 {
        panic!("malformed reading line: {}", line);
    }
    Reading {
        station: parts[0].trim().to_string(),
        temp: parts[1].trim().parse::<f64>().expect("bad temperature"),
        humidity: parts[2].trim().parse::<f64>().expect("bad humidity"),
        wind: parts[3].trim().parse::<f64>().expect("bad wind speed"),
    }
}

/// The last batch received from the collector.
const BATCH: &str = "\
Warsaw;21.5;58;12.4
Gdansk;18.0;64;9.1
Krakow;23.2;51;5.8
Zakopane;15.9;72;3.3";

fn main() {
    for line in BATCH.lines() {
        let reading = reading_from_str(line);
        print_reading(&reading);
    }
}
