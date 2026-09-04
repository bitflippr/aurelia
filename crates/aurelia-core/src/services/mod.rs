//! Service layer for external API interactions
//!
//! This module contains service clients for interacting with external APIs
//! like Jellyfin and `LrcLib`.

pub mod jellyfin;
pub mod library_sync;
pub mod lrclib;

pub use jellyfin::*;
pub use lrclib::*;
