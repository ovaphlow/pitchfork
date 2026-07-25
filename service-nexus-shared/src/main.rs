use std::str::FromStr;
use std::sync::Arc;

use anyhow::{Context, Result};
use nexus_shared::auth::IdentityClient;
use nexus_shared::config::Config;
use nexus_shared::{AppState, app};
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions};
use tracing_subscriber::Layer;
use tracing_subscriber::filter::LevelFilter;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;

#[tokio::main]
async fn main() -> Result<()> {
    let config = Config::load()?;
    let _log_guard = initialize_logging()?;
    tokio::fs::create_dir_all(&config.files_dir)
        .await
        .context("create file storage directory")?;

    let connection_options = SqliteConnectOptions::from_str(&config.database_url)
        .context("parse NEXUS_DATABASE_URL")?
        .create_if_missing(true)
        .foreign_keys(true)
        .journal_mode(SqliteJournalMode::Wal);
    let database = SqlitePoolOptions::new()
        .max_connections(8)
        .connect_with(connection_options)
        .await
        .context("connect to SQLite")?;
    sqlx::migrate!("./migrations")
        .run(&database)
        .await
        .context("run SQLite migrations")?;

    let http_client = reqwest::Client::builder()
        .timeout(config.idp_timeout)
        .no_proxy()
        .build()
        .context("create identity HTTP client")?;
    let state = AppState {
        database,
        files_dir: Arc::new(config.files_dir),
        identity_client: IdentityClient::new(&config.idp_base_url, http_client),
    };
    let listener = tokio::net::TcpListener::bind(config.address)
        .await
        .context("bind NEXUS_ADDR")?;
    tracing::info!(address = %config.address, "nexus shared service started");
    axum::serve(listener, app(state, config.max_upload_bytes))
        .with_graceful_shutdown(shutdown_signal())
        .await
        .context("serve HTTP")
}

fn initialize_logging() -> Result<tracing_appender::non_blocking::WorkerGuard> {
    std::fs::create_dir_all("logs").context("create log directory")?;
    let appender = tracing_appender::rolling::daily("logs", "nexus.jsonl");
    let (file_writer, guard) = tracing_appender::non_blocking(appender);
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::fmt::layer()
                .with_writer(std::io::stdout)
                .with_ansi(false),
        )
        .with(
            tracing_subscriber::fmt::layer()
                .json()
                .with_writer(file_writer)
                .with_filter(LevelFilter::WARN),
        )
        .init();
    Ok(guard)
}

async fn shutdown_signal() {
    let _ = tokio::signal::ctrl_c().await;
    tracing::info!("shutdown signal received");
}
