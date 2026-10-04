//! Colors and soft backdrops taken from album art.
//!
//! The interface tints pages and the full player with the art's colors and
//! draws its blurred backdrop from a tiny pre-blurred image, so nothing blurs
//! on the GPU at frame time.

use std::path::{Path, PathBuf};

use image::{DynamicImage, GenericImageView, imageops::FilterType};
use serde::Serialize;

use crate::session::Session;

/// Source size: enough for colors and a backdrop, small enough to fetch fast.
const SOURCE_SIZE: u32 = 128;
const BACKDROP_SIZE: u32 = 48;
const BACKDROP_BLUR: f32 = 4.0;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Artwork {
    /// Three colors, most prominent first, toned for a dark background.
    pub palette: [String; 3],
    pub backdrop: Option<String>,
}

pub async fn load(
    client: &reqwest::Client,
    session: &Session,
    cache_dir: &Path,
    item_id: &str,
    tag: Option<&str>,
) -> Result<Artwork, String> {
    let backdrop_path = cache_dir.join(format!(
        "{}-{}.png",
        sanitize(item_id),
        sanitize(tag.unwrap_or("untagged"))
    ));
    let mut url = format!(
        "{}/Items/{}/Images/Primary?fillWidth={SOURCE_SIZE}&fillHeight={SOURCE_SIZE}&quality=85&api_key={}",
        session.server_url.trim_end_matches('/'),
        item_id,
        session.token
    );
    if let Some(tag) = tag {
        url.push_str("&tag=");
        url.push_str(tag);
    }
    let bytes = client
        .get(&url)
        .send()
        .await
        .and_then(|response| response.error_for_status())
        .map_err(|error| error.to_string())?
        .bytes()
        .await
        .map_err(|error| error.to_string())?;
    let cache_dir = cache_dir.to_path_buf();
    tokio::task::spawn_blocking(move || {
        let image = image::load_from_memory(&bytes).map_err(|error| error.to_string())?;
        let palette = palette(&image);
        let backdrop = write_backdrop(&image, &cache_dir, &backdrop_path);
        Ok(Artwork { palette, backdrop })
    })
    .await
    .map_err(|error| error.to_string())?
}

fn sanitize(value: &str) -> String {
    value
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .collect()
}

fn write_backdrop(image: &DynamicImage, cache_dir: &Path, path: &PathBuf) -> Option<String> {
    if !path.exists() {
        std::fs::create_dir_all(cache_dir).ok()?;
        let small = image.resize_exact(BACKDROP_SIZE, BACKDROP_SIZE, FilterType::Triangle);
        let blurred = small.blur(BACKDROP_BLUR);
        blurred.save(path).ok()?;
    }
    Some(path.to_string_lossy().into_owned())
}

/// Hue buckets weighted by how vivid each pixel is; the three strongest
/// distinct hues, lightened or darkened to sit well behind light text.
fn palette(image: &DynamicImage) -> [String; 3] {
    let small = image.resize_exact(28, 28, FilterType::Triangle);
    #[derive(Clone, Copy, Default)]
    struct Bucket {
        weight: f32,
        hue: f32,
        saturation: f32,
    }
    let mut buckets = [Bucket::default(); 18];
    let mut gray_weight = 0.0f32;
    let mut gray_lightness = 0.0f32;
    for (_, _, pixel) in small.pixels() {
        let [r, g, b, _] = pixel.0;
        let (h, s, l) = rgb_to_hsl(r, g, b);
        let vivid = s * (1.0 - ((l - 0.5).abs() * 1.7).min(1.0));
        if vivid < 0.08 {
            gray_weight += 1.0;
            gray_lightness += l;
            continue;
        }
        let bucket = &mut buckets[((h / 20.0) as usize) % 18];
        bucket.weight += vivid;
        bucket.hue += h * vivid;
        bucket.saturation += s * vivid;
    }
    let mut ranked: Vec<(f32, f32, f32)> = buckets
        .iter()
        .filter(|bucket| bucket.weight > 0.0)
        .map(|bucket| {
            (
                bucket.weight,
                bucket.hue / bucket.weight,
                bucket.saturation / bucket.weight,
            )
        })
        .collect();
    ranked.sort_by(|a, b| b.0.total_cmp(&a.0));
    let mut picks: Vec<(f32, f32)> = Vec::new();
    for (_, hue, saturation) in ranked {
        let distinct = picks.iter().all(|(picked, _)| {
            let distance = (picked - hue).abs();
            distance.min(360.0 - distance) > 36.0
        });
        if distinct {
            picks.push((hue, saturation));
        }
        if picks.len() == 3 {
            break;
        }
    }
    if picks.is_empty() {
        let lightness = if gray_weight > 0.0 {
            gray_lightness / gray_weight
        } else {
            0.4
        };
        return [
            hsl_hex(250.0, 0.12, 0.3 + lightness * 0.15),
            hsl_hex(250.0, 0.08, 0.24),
            hsl_hex(220.0, 0.1, 0.28),
        ];
    }
    while picks.len() < 3 {
        let (hue, saturation) = picks[0];
        picks.push(((hue + 28.0 * picks.len() as f32) % 360.0, saturation * 0.8));
    }
    let lightness = [0.46, 0.4, 0.36];
    std::array::from_fn(|index| {
        let (hue, saturation) = picks[index];
        hsl_hex(hue, (saturation * 1.3).clamp(0.38, 0.78), lightness[index])
    })
}

fn rgb_to_hsl(r: u8, g: u8, b: u8) -> (f32, f32, f32) {
    let (r, g, b) = (r as f32 / 255.0, g as f32 / 255.0, b as f32 / 255.0);
    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let l = (max + min) / 2.0;
    if max == min {
        return (0.0, 0.0, l);
    }
    let d = max - min;
    let s = if l > 0.5 {
        d / (2.0 - max - min)
    } else {
        d / (max + min)
    };
    let h = if max == r {
        (g - b) / d + if g < b { 6.0 } else { 0.0 }
    } else if max == g {
        (b - r) / d + 2.0
    } else {
        (r - g) / d + 4.0
    };
    (h * 60.0, s, l)
}

fn hsl_hex(h: f32, s: f32, l: f32) -> String {
    let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
    let x = c * (1.0 - ((h / 60.0) % 2.0 - 1.0).abs());
    let m = l - c / 2.0;
    let (r, g, b) = match (h / 60.0) as u32 {
        0 => (c, x, 0.0),
        1 => (x, c, 0.0),
        2 => (0.0, c, x),
        3 => (0.0, x, c),
        4 => (x, 0.0, c),
        _ => (c, 0.0, x),
    };
    let channel = |value: f32| ((value + m).clamp(0.0, 1.0) * 255.0).round() as u8;
    format!("#{:02x}{:02x}{:02x}", channel(r), channel(g), channel(b))
}
