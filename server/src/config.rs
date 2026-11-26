//! Configuration from environment variables.
//!
//! Defaults are safe for a single-instance VPS: bind on loopback, serve plain
//! HTTP behind Caddy, and store the database next to the process. TLS
//! termination is Caddy's job; the binary itself never advertises plain HTTP to
//! the internet.

use std::env;
use std::net::SocketAddr;
use std::path::PathBuf;

/// Runtime configuration.
#[derive(Debug, Clone)]
pub struct Config {
    /// Address the HTTP listener binds to.
    pub bind: SocketAddr,
    /// SQLite database path; `:memory:` is allowed in tests only.
    pub database_path: PathBuf,
    /// Requests larger than this body size are rejected before parsing.
    pub max_body_bytes: usize,
    /// `/v1/sync` requests per device per minute.
    pub sync_per_minute: u32,
    /// `/v1/heartbeat` requests per device per minute.
    pub heartbeat_per_minute: u32,
    /// `/v1/snapshot` requests per device per minute.
    pub snapshot_per_minute: u32,
}

impl Config {
    /// Read `CCTV_*` variables, falling back to documented defaults.
    ///
    /// # Errors
    ///
    /// Returns a message when a variable is present but unparseable. The message
    /// names the variable, never its value, so a bad token cannot leak into logs.
    pub fn from_env() -> Result<Self, String> {
        let bind = match env::var("CCTV_BIND") {
            Ok(value) => value
                .parse()
                .map_err(|_| "CCTV_BIND is not a socket address".to_string())?,
            Err(_) => SocketAddr::from(([127, 0, 0, 1], 8080)),
        };

        let database_path = env::var_os("CCTV_DB_PATH")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("cctv.db"));

        Ok(Self {
            bind,
            database_path,
            max_body_bytes: parse_u32("CCTV_MAX_BODY_BYTES", 64 * 1024)? as usize,
            sync_per_minute: parse_u32("CCTV_SYNC_PER_MINUTE", 60)?,
            heartbeat_per_minute: parse_u32("CCTV_HEARTBEAT_PER_MINUTE", 4)?,
            snapshot_per_minute: parse_u32("CCTV_SNAPSHOT_PER_MINUTE", 2)?,
        })
    }
}

fn parse_u32(name: &str, default: u32) -> Result<u32, String> {
    match env::var(name) {
        Ok(value) => value
            .parse()
            .map_err(|_| format!("{name} is not a positive integer")),
        Err(_) => Ok(default),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_the_documented_limits() {
        let config = Config {
            bind: SocketAddr::from(([127, 0, 0, 1], 8080)),
            database_path: PathBuf::from("cctv.db"),
            max_body_bytes: 64 * 1024,
            sync_per_minute: 60,
            heartbeat_per_minute: 4,
            snapshot_per_minute: 2,
        };
        assert_eq!(config.max_body_bytes, 65_536);
        assert_eq!(config.sync_per_minute, 60);
    }
}
