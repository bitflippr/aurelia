//! Data models for Aurelia
//!
//! This module contains all the data structures used throughout the application,
//! including API response types, internal data models, and configuration structures.

pub mod artwork;
pub mod auth;
pub mod jellyfin;
pub mod library;
pub mod lrclib;
pub mod music;

pub use artwork::*;
pub use aurelia_lyrics::models::*;
pub use auth::*;
pub use jellyfin::*;
pub use library::*;
pub use lrclib::*;
pub use music::*;
