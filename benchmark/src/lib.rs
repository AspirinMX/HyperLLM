//! Shared Go-flag-compatible CLI argument and duration parsing utilities for the HyperLLM
//! benchmark binaries (`mock-upstream`, `loadgen`, `bench-seed`).
//!
//! Existing scripts (`run-suite.sh`, `setup-server.sh`) invoke flags using Go's single-dash
//! conventions (`-flag value`, `-flag=value`, `-stream=true`). Supporting both `-flag` and
//! `--flag` preserves 100% drop-in CLI compatibility with zero external proc-macro dependencies.

use std::collections::HashMap;
use std::time::Duration;

/// Parsed command-line key/value flags normalized without leading dashes.
#[derive(Debug, Default)]
pub struct Flags {
    values: HashMap<String, String>,
}

impl Flags {
    /// Parses `args` (skipping argv[0]). Supports `-k v`, `--k v`, `-k=v`, `--k=v`, and bare
    /// boolean switches `-k` / `--k` (which resolve to `"true"`).
    pub fn parse<I, S>(args: I) -> Result<Self, String>
    where
        I: IntoIterator<Item = S>,
        S: AsRef<str>,
    {
        let mut iter = args.into_iter().skip(1).peekable();
        let mut values = HashMap::new();
        while let Some(raw) = iter.next() {
            let arg = raw.as_ref();
            if arg == "--" {
                break;
            }
            if !arg.starts_with('-') || arg == "-" {
                return Err(format!("unexpected positional argument: {arg}"));
            }
            let stripped = arg.trim_start_matches('-');
            if stripped == "h" || stripped == "help" {
                return Err("help".to_string());
            }
            if let Some((k, v)) = stripped.split_once('=') {
                values.insert(k.to_string(), v.to_string());
            } else if let Some(next) = iter.peek() {
                let next_s = next.as_ref();
                if next_s.starts_with('-') && !is_negative_number(next_s) {
                    values.insert(stripped.to_string(), "true".to_string());
                } else {
                    let val = iter.next().unwrap();
                    values.insert(stripped.to_string(), val.as_ref().to_string());
                }
            } else {
                values.insert(stripped.to_string(), "true".to_string());
            }
        }
        Ok(Self { values })
    }

    pub fn get_str(&self, key: &str, default: &str) -> String {
        self.values
            .get(key)
            .cloned()
            .unwrap_or_else(|| default.to_string())
    }

    pub fn get_i64(&self, key: &str, default: i64) -> Result<i64, String> {
        match self.values.get(key) {
            Some(v) => v
                .parse::<i64>()
                .map_err(|e| format!("invalid integer for -{key}={v:?}: {e}")),
            None => Ok(default),
        }
    }

    pub fn get_usize(&self, key: &str, default: usize) -> Result<usize, String> {
        match self.values.get(key) {
            Some(v) => v
                .parse::<usize>()
                .map_err(|e| format!("invalid unsigned integer for -{key}={v:?}: {e}")),
            None => Ok(default),
        }
    }

    pub fn get_bool(&self, key: &str, default: bool) -> Result<bool, String> {
        match self.values.get(key) {
            Some(v) => match v.to_ascii_lowercase().as_str() {
                "1" | "t" | "true" | "yes" => Ok(true),
                "0" | "f" | "false" | "no" => Ok(false),
                _ => Err(format!("invalid boolean for -{key}={v:?}")),
            },
            None => Ok(default),
        }
    }

    pub fn get_duration(&self, key: &str, default: Duration) -> Result<Duration, String> {
        match self.values.get(key) {
            Some(v) => parse_go_duration(v)
                .map_err(|e| format!("invalid duration for -{key}={v:?}: {e}")),
            None => Ok(default),
        }
    }
}

fn is_negative_number(s: &str) -> bool {
    let bytes = s.as_bytes();
    bytes.len() >= 2 && bytes[0] == b'-' && bytes[1].is_ascii_digit()
}

/// Parses Go `time.ParseDuration` strings such as `"20s"`, `"500ms"`, `"1m30s"`, `"2h"`, `"0"`.
pub fn parse_go_duration(input: &str) -> Result<Duration, String> {
    let s = input.trim();
    if s.is_empty() {
        return Err("empty duration".to_string());
    }
    if s == "0" {
        return Ok(Duration::ZERO);
    }
    let mut total_nanos: f64 = 0.0;
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let num_start = i;
        while i < bytes.len() && (bytes[i].is_ascii_digit() || bytes[i] == b'.') {
            i += 1;
        }
        if i == num_start {
            return Err(format!("expected number at offset {i} in {input:?}"));
        }
        let val: f64 = s[num_start..i]
            .parse()
            .map_err(|e| format!("bad number in {input:?}: {e}"))?;
        let unit_start = i;
        while i < bytes.len() && !bytes[i].is_ascii_digit() && bytes[i] != b'.' {
            i += 1;
        }
        let unit = &s[unit_start..i];
        let mult: f64 = match unit {
            "ns" => 1.0,
            "us" | "µs" | "μs" => 1_000.0,
            "ms" => 1_000_000.0,
            "s" | "" => 1_000_000_000.0,
            "m" => 60.0 * 1_000_000_000.0,
            "h" => 3600.0 * 1_000_000_000.0,
            _ => return Err(format!("unknown duration unit {unit:?} in {input:?}")),
        };
        total_nanos += val * mult;
    }
    if total_nanos < 0.0 || !total_nanos.is_finite() {
        return Err(format!("duration out of range: {input:?}"));
    }
    Ok(Duration::from_nanos(total_nanos.round() as u64))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_go_and_gnu_flags() {
        let f = Flags::parse([
            "loadgen",
            "-target",
            "http://127.0.0.1:18081",
            "-stream=false",
            "--concurrency",
            "3000",
            "-ramp",
            "20s",
            "-duration=1m30s",
            "-ttft-ms",
            "500",
        ])
        .unwrap();
        assert_eq!(f.get_str("target", ""), "http://127.0.0.1:18081");
        assert!(!f.get_bool("stream", true).unwrap());
        assert_eq!(f.get_usize("concurrency", 0).unwrap(), 3000);
        assert_eq!(
            f.get_duration("ramp", Duration::ZERO).unwrap(),
            Duration::from_secs(20)
        );
        assert_eq!(
            f.get_duration("duration", Duration::ZERO).unwrap(),
            Duration::from_secs(90)
        );
        assert_eq!(f.get_i64("ttft-ms", 0).unwrap(), 500);
    }

    #[test]
    fn parses_go_durations() {
        assert_eq!(parse_go_duration("0").unwrap(), Duration::ZERO);
        assert_eq!(parse_go_duration("25ms").unwrap(), Duration::from_millis(25));
        assert_eq!(parse_go_duration("20s").unwrap(), Duration::from_secs(20));
        assert_eq!(
            parse_go_duration("1m5s500ms").unwrap(),
            Duration::from_millis(65_500)
        );
        assert_eq!(parse_go_duration("1.5s").unwrap(), Duration::from_millis(1500));
    }
}
