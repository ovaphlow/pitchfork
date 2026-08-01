use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::Duration;

use anyhow::{Context, Result};

#[derive(Debug, Clone)]
pub struct Config {
    pub address: SocketAddr,
    pub database_url: String,
    pub files_dir: PathBuf,
    pub max_upload_bytes: usize,
    pub idp_base_url: String,
    pub idp_timeout: Duration,
    pub cors_origins: Vec<String>,
}

impl Config {
    pub fn load() -> Result<Self> {
        dotenvy::dotenv().ok();
        let address = env_or("NEXUS_ADDR", "127.0.0.1:8421")
            .parse()
            .context("parse NEXUS_ADDR")?;
        let max_upload_bytes = env_or("NEXUS_MAX_UPLOAD_BYTES", "20971520")
            .parse()
            .context("parse NEXUS_MAX_UPLOAD_BYTES")?;
        let timeout_ms: u64 = env_or("NEXUS_IDP_TIMEOUT_MS", "1500")
            .parse()
            .context("parse NEXUS_IDP_TIMEOUT_MS")?;
        let idp_base_url = env_or("NEXUS_IDP_BASE_URL", "http://127.0.0.1:8420")
            .trim_end_matches('/')
            .to_owned();
        let cors_origins = cors_origins(&env_or(
            "NEXUS_CORS_ORIGINS",
            "http://localhost:4324,http://127.0.0.1:4324",
        ))?;

        if !idp_base_url.starts_with("http://") && !idp_base_url.starts_with("https://") {
            anyhow::bail!("NEXUS_IDP_BASE_URL must be an HTTP URL");
        }

        Ok(Self {
            address,
            database_url: env_or("NEXUS_DATABASE_URL", "sqlite://.data/nexus.sqlite?mode=rwc"),
            files_dir: PathBuf::from(env_or("NEXUS_FILES_DIR", ".data/files")),
            max_upload_bytes,
            idp_base_url,
            idp_timeout: Duration::from_millis(timeout_ms),
            cors_origins,
        })
    }
}

fn cors_origins(value: &str) -> Result<Vec<String>> {
    value
        .split(',')
        .map(str::trim)
        .map(|origin| {
            let uri: http::Uri = origin
                .parse()
                .with_context(|| "NEXUS_CORS_ORIGINS contains an invalid origin")?;
            if !matches!(uri.scheme_str(), Some("http") | Some("https"))
                || uri.authority().is_none()
                || (!uri.path().is_empty() && uri.path() != "/")
                || uri.query().is_some()
                || origin == "*"
            {
                anyhow::bail!("NEXUS_CORS_ORIGINS contains an invalid origin");
            }
            Ok(origin.to_owned())
        })
        .collect()
}

fn env_or(name: &str, default: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| default.to_owned())
}

#[cfg(test)]
mod tests {
    use super::cors_origins;

    #[test]
    fn parses_explicit_origins() {
        assert_eq!(
            cors_origins("http://localhost:4324, https://ui.example").unwrap(),
            vec!["http://localhost:4324", "https://ui.example"]
        );
    }

    #[test]
    fn rejects_wildcard_and_paths() {
        assert!(cors_origins("*").is_err());
        assert!(cors_origins("http://localhost:4324/path").is_err());
    }
}
