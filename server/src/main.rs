//! Binary entry point.
//!
//! Two subcommands: `serve` (default) and `enroll`. Enrollment prints the token
//! exactly once on stdout; it is never written to a log file.

use std::path::PathBuf;

use cctv_server::auth::{generate_token, hash_token};
use cctv_server::config::Config;
use cctv_server::state::AppState;
use cctv_server::store::Db;
use tracing_subscriber::EnvFilter;

const VERSION: &str = env!("CARGO_PKG_VERSION");

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let mut args = std::env::args().skip(1);
    match args.next().as_deref() {
        Some("enroll") => enroll(args).await,
        Some("serve") | None => serve().await,
        Some(other) => {
            eprintln!("unknown subcommand: {other}");
            std::process::exit(2);
        }
    }
}

async fn enroll(mut args: impl Iterator<Item = String>) -> Result<(), Box<dyn std::error::Error>> {
    let mut device_id: Option<String> = None;
    let mut db_path: Option<PathBuf> = None;
    while let Some(flag) = args.next() {
        match flag.as_str() {
            "--device-id" => device_id = args.next(),
            "--db" => db_path = args.next().map(PathBuf::from),
            other => return Err(format!("unknown flag: {other}").into()),
        }
    }
    let device_id = device_id.ok_or("--device-id is required")?;
    if device_id.is_empty() || device_id.len() > 64 {
        return Err("device id must be 1..=64 characters".into());
    }

    let path = db_path.unwrap_or_else(|| PathBuf::from("cctv.db"));
    let db = Db::open(&path).await?;
    let token = generate_token()?;
    db.create_device(&device_id, &hash_token(&token)).await?;

    println!("device_id: {device_id}");
    println!("token: {token}");
    println!("Store the token now; only its hash is saved.");
    Ok(())
}

async fn serve() -> Result<(), Box<dyn std::error::Error>> {
    let config = Config::from_env()?;
    let db = Db::open(&config.database_path).await?;
    let bind = config.bind;
    let state = AppState::new(db, config, VERSION);
    let app = cctv_server::build_app(state);

    let listener = tokio::net::TcpListener::bind(bind).await?;
    tracing::info!(address = %bind, "cctv-server listening");
    axum::serve(listener, app).await?;
    Ok(())
}
