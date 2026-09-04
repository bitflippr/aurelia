//! Error handling for Aurelia
//!
//! Uses thiserror for structured error types and anyhow for ergonomic error handling

/// Application-specific error type using thiserror
#[derive(thiserror::Error, Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Error)]
pub enum AppError {
    #[error("Network error: {0}")]
    Network(String),

    #[error("Authentication error: {0}")]
    Auth(String),

    #[error("Database error: {0}")]
    Database(String),

    #[error("Serialization error: {0}")]
    Serialization(String),

    #[error("File system error: {0}")]
    FileSystem(String),

    #[error("API parsing error: {0}")]
    ApiParse(String),

    #[error("Configuration error: {0}")]
    Config(String),

    #[error("HTTP {status}: {detail}")]
    Http { status: u16, detail: String },

    #[error("Application error: {0}")]
    General(String),

    #[error("FFI error: {0}")]
    UniFfi(String),
}

impl From<reqwest::Error> for AppError {
    fn from(err: reqwest::Error) -> Self {
        match err.status() {
            Some(status) => Self::Http {
                status: status.as_u16(),
                detail: err.to_string(),
            },
            None => Self::Network(err.to_string()),
        }
    }
}

impl From<serde_json::Error> for AppError {
    fn from(err: serde_json::Error) -> Self {
        Self::Serialization(err.to_string())
    }
}

impl From<std::io::Error> for AppError {
    fn from(err: std::io::Error) -> Self {
        Self::FileSystem(err.to_string())
    }
}

/// Result type alias for convenience
pub type AppResult<T> = std::result::Result<T, AppError>;
