//! HTTP response validation and error context used by the Jellyfin client.

use crate::error::{AppError, AppResult};
use reqwest::Response;

pub async fn handle_http_response(response: Response) -> AppResult<Response> {
    if response.status().is_success() {
        Ok(response)
    } else {
        let status = response.status().as_u16();
        let message = response
            .text()
            .await
            .unwrap_or_else(|_| "Unknown error".to_string());
        Err(AppError::Http {
            status,
            detail: message,
        })
    }
}

pub fn network_error_with_context<E: std::fmt::Display>(err: E, context: &str) -> AppError {
    AppError::Network(format!("{context}: {err}"))
}

pub fn api_parse_error_with_context<E: std::fmt::Display>(err: E, context: &str) -> AppError {
    AppError::ApiParse(format!("{context}: {err}"))
}
