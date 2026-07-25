use axum::extract::{Request, State};
use axum::http::StatusCode;
use axum::http::header::COOKIE;
use axum::middleware::Next;
use axum::response::Response;
use reqwest::Client;
use serde::Deserialize;

use crate::AppState;
use crate::error::ApiError;

#[derive(Clone)]
pub struct IdentityClient {
    client: Client,
    session_url: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Identity {
    pub subject_id: String,
    pub access: String,
}

impl IdentityClient {
    pub fn new(idp_base_url: &str, client: Client) -> Self {
        Self {
            client,
            session_url: format!("{idp_base_url}/crate-api/identity/v1/session"),
        }
    }

    async fn current_identity(&self, cookie: Option<&str>) -> Result<Identity, ApiError> {
        let cookie = cookie.ok_or(ApiError::Unauthenticated)?;
        let response = self
            .client
            .get(&self.session_url)
            .header(COOKIE, cookie)
            .send()
            .await
            .map_err(|error| {
                tracing::warn!(error = %error, "identity service is unavailable");
                ApiError::Unauthenticated
            })?;

        if response.status() != StatusCode::OK {
            return Err(ApiError::Unauthenticated);
        }
        let identity = response.json::<Identity>().await.map_err(|error| {
            tracing::error!(error = %error, "identity service returned an invalid response");
            ApiError::Unauthenticated
        })?;
        if identity.subject_id.is_empty() || identity.access != "完整" {
            return Err(ApiError::Unauthenticated);
        }
        Ok(identity)
    }
}

pub async fn require_identity(
    State(state): State<AppState>,
    mut request: Request,
    next: Next,
) -> Result<Response, ApiError> {
    let cookie = request
        .headers()
        .get(COOKIE)
        .and_then(|value| value.to_str().ok());
    let identity = state.identity_client.current_identity(cookie).await?;
    request.extensions_mut().insert(identity);
    Ok(next.run(request).await)
}
