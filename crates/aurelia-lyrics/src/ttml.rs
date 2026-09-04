//! TTML (Timed Text Markup Language) parser for Apple Music-style lyrics.
//!
//! Supports both line-synced (`itunes:timing="Line"`) and word-synced
//! (`itunes:timing="Word"`) TTML documents, including Apple Music custom
//! namespace attributes such as `itunes:songPart`, `ttm:agent`, and
//! `iTunesMetadata` elements.

use crate::error::Result;
use crate::models::{
    ParsedLyrics, ParsedLyricsAgent, ParsedLyricsLine, ParsedLyricsSection, ParsedLyricsWord,
};
use quick_xml::events::Event;
use quick_xml::Reader;

/// Check whether a string looks like TTML (XML with a `<tt` root element).
#[must_use]
pub fn is_ttml(text: &str) -> bool {
    let trimmed = text.trim_start();
    // Handle optional XML declaration before <tt
    if trimmed.starts_with("<?xml") {
        // Find the end of the XML declaration and check for <tt
        if let Some(pos) = trimmed.find("?>") {
            let after_decl = trimmed[pos + 2..].trim_start();
            return after_decl.starts_with("<tt")
                && after_decl
                    .as_bytes()
                    .get(3)
                    .is_some_and(|&b| b.is_ascii_whitespace() || b == b'>' || b == b'/');
        }
        return false;
    }
    trimmed.starts_with("<tt")
        && trimmed
            .as_bytes()
            .get(3)
            .is_some_and(|&b| b.is_ascii_whitespace() || b == b'>' || b == b'/')
}

/// Parse Apple Music TTML timestamp into milliseconds.
///
/// Supported formats:
/// - `"30.348"` — seconds with decimal
/// - `"1:30.456"` — M:SS.ms
/// - `"00:01:30.456"` — H:MM:SS.ms
/// - `"1:30"` — M:SS (no fraction)
fn parse_ttml_timestamp(ts: &str) -> Option<i64> {
    let ts = ts.trim();
    if ts.is_empty() {
        return None;
    }

    let parts: Vec<&str> = ts.splitn(3, ':').collect();
    match parts.len() {
        1 => {
            // seconds.fraction (e.g. "30.348")
            let secs: f64 = ts.parse().ok()?;
            Some((secs * 1000.0).round() as i64)
        }
        2 => {
            // M:SS.fraction (e.g. "1:30.456")
            let minutes: i64 = parts[0].parse().ok()?;
            let seconds: f64 = parts[1].parse().ok()?;
            Some(minutes * 60_000 + (seconds * 1000.0).round() as i64)
        }
        3 => {
            // H:MM:SS.fraction (e.g. "00:01:30.456")
            let hours: i64 = parts[0].parse().ok()?;
            let minutes: i64 = parts[1].parse().ok()?;
            let seconds: f64 = parts[2].parse().ok()?;
            Some(hours * 3_600_000 + minutes * 60_000 + (seconds * 1000.0).round() as i64)
        }
        _ => None,
    }
}

/// Collect attributes from a quick-xml `BytesStart` into a `Vec<(String, String)>`.
fn collect_attrs(e: &quick_xml::events::BytesStart<'_>) -> Vec<(String, String)> {
    e.attributes()
        .filter_map(|a| a.ok())
        .map(|a| {
            let key = String::from_utf8_lossy(a.key.as_ref()).to_string();
            let val = String::from_utf8_lossy(&a.value).to_string();
            (key, val)
        })
        .collect()
}

/// Resolve an attribute name, handling namespace prefixes.
///
/// Looks up `local_name` first without a prefix, then with each known prefix.
fn get_attr<'a>(
    attrs: &'a [(String, String)],
    local_name: &str,
    prefixes: &[&str],
) -> Option<&'a str> {
    // Try exact local name first
    for (k, v) in attrs {
        if k == local_name {
            return Some(v.as_str());
        }
    }
    // Try prefixed variants
    for prefix in prefixes {
        let prefixed = format!("{prefix}:{local_name}");
        for (k, v) in attrs {
            if *k == prefixed {
                return Some(v.as_str());
            }
        }
    }
    None
}

/// Parse a TTML lyrics document into a [`ParsedLyrics`].
///
/// Returns an error for malformed XML or a document without a TTML root.
pub fn parse_ttml(xml: &str) -> Result<ParsedLyrics> {
    parse_ttml_inner(xml)
        .ok_or_else(|| crate::error::LyricsError::Xml("Malformed TTML document".into()))
}

/// Internal parser that returns `None` on any structural error.
fn parse_ttml_inner(xml: &str) -> Option<ParsedLyrics> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().expand_empty_elements = true;
    let mut depth = 0usize;
    let mut root_seen = false;

    let mut language: Option<String> = None;
    let mut timing_mode: Option<String> = None; // "Line" or "Word"
    let mut agents: Vec<ParsedLyricsAgent> = Vec::new();
    let mut songwriters: Vec<String> = Vec::new();
    let mut sections: Vec<ParsedLyricsSection> = Vec::new();
    let mut all_lines: Vec<ParsedLyricsLine> = Vec::new();

    // State tracking for nested elements
    let mut in_metadata = false;
    let mut in_itunes_metadata = false;
    let mut in_songwriters = false;
    let mut in_songwriter = false;
    let mut in_body = false;
    let mut in_div = false;
    let mut in_p = false;
    let mut in_span = false;

    // Current div/section state
    let mut current_section_name: Option<String> = None;
    let mut current_section_agent: Option<String> = None;
    let mut current_section_lines: Vec<ParsedLyricsLine> = Vec::new();

    // Current <p> (line) state
    let mut current_line_start: Option<i64> = None;
    let mut current_line_end: Option<i64> = None;
    let mut current_line_agent: Option<String> = None;
    let mut current_line_text = String::new();
    let mut current_line_words: Vec<ParsedLyricsWord> = Vec::new();
    let mut is_word_synced = false;

    // Current <span> (word) state
    let mut current_span_start: Option<i64> = None;
    let mut current_span_end: Option<i64> = None;
    let mut current_span_text = String::new();
    // Text between </span> and the next <span> (whitespace prefix for next word)
    let mut inter_span_text = String::new();

    // Songwriter text accumulator
    let mut songwriter_text = String::new();

    // Translation handling
    let mut in_translations = false;
    let mut in_translation = false;
    let mut current_translation_key: Option<String> = None;
    let mut current_translation_text = String::new();
    let mut translations_map: std::collections::HashMap<String, String> =
        std::collections::HashMap::new();
    let mut current_line_key: Option<String> = None;

    let mut buf = Vec::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) | Ok(Event::Empty(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_string();
                let local_tag = tag.split(':').next_back().unwrap_or(&tag);
                if depth == 0 {
                    if root_seen || local_tag != "tt" {
                        return None;
                    }
                    root_seen = true;
                }
                depth += 1;
                let attrs = collect_attrs(e);

                match local_tag {
                    "tt" => {
                        language = get_attr(&attrs, "xml:lang", &[]).map(ToString::to_string);
                        timing_mode =
                            get_attr(&attrs, "timing", &["itunes"]).map(ToString::to_string);
                    }
                    "metadata" => {
                        in_metadata = true;
                    }
                    "agent" => {
                        if in_metadata {
                            let id = get_attr(&attrs, "xml:id", &[])
                                .or_else(|| get_attr(&attrs, "id", &["xml"]))
                                .unwrap_or("")
                                .to_string();
                            let agent_type = get_attr(&attrs, "type", &[])
                                .unwrap_or("person")
                                .to_string();
                            if !id.is_empty() {
                                agents.push(ParsedLyricsAgent { id, agent_type });
                            }
                        }
                    }
                    "iTunesMetadata" => {
                        in_itunes_metadata = true;
                    }
                    "songwriters" => {
                        if in_itunes_metadata {
                            in_songwriters = true;
                        }
                    }
                    "songwriter" => {
                        if in_songwriters {
                            in_songwriter = true;
                            songwriter_text.clear();
                        }
                    }
                    "translations" => {
                        if in_itunes_metadata {
                            in_translations = true;
                        }
                    }
                    "translation" => {
                        if in_translations {
                            in_translation = true;
                        }
                    }
                    "text" => {
                        if in_translation {
                            current_translation_key = get_attr(&attrs, "for", &[])
                                .map(|s| s.trim_start_matches('L').to_string());
                            current_translation_text.clear();
                        }
                    }
                    "body" => {
                        in_body = true;
                    }
                    "div" => {
                        if in_body {
                            in_div = true;
                            current_section_name =
                                get_attr(&attrs, "songPart", &["itunes"]).map(ToString::to_string);
                            current_section_agent =
                                get_attr(&attrs, "agent", &["ttm"]).map(ToString::to_string);
                            current_section_lines.clear();
                        }
                    }
                    "p" => {
                        if in_div {
                            in_p = true;
                            current_line_start =
                                get_attr(&attrs, "begin", &[]).and_then(parse_ttml_timestamp);
                            current_line_end =
                                get_attr(&attrs, "end", &[]).and_then(parse_ttml_timestamp);
                            current_line_agent =
                                get_attr(&attrs, "agent", &["ttm"]).map(ToString::to_string);
                            // Capture the line key for translation lookup (e.g., "L1", "L2")
                            current_line_key =
                                get_attr(&attrs, "key", &["itunes"]).map(ToString::to_string);
                            current_line_text.clear();
                            current_line_words.clear();
                            inter_span_text.clear();
                            is_word_synced = timing_mode.as_deref() == Some("Word");
                        }
                    }
                    "span" if in_p => {
                        in_span = true;
                        current_span_start =
                            get_attr(&attrs, "begin", &[]).and_then(parse_ttml_timestamp);
                        current_span_end =
                            get_attr(&attrs, "end", &[]).and_then(parse_ttml_timestamp);
                        // Prepend any whitespace between the previous </span> and this <span>.
                        // Normalize to a single space (real TTML uses " ", but pretty-printed
                        // XML may have newlines/indentation).
                        let gap = std::mem::take(&mut inter_span_text);
                        current_span_text = if !gap.trim().is_empty() {
                            // Non-whitespace content between spans — preserve it
                            gap
                        } else if !gap.is_empty() {
                            " ".to_string()
                        } else {
                            String::new()
                        };
                    }
                    _ => {}
                }
            }
            Ok(Event::End(ref e)) => {
                depth = depth.checked_sub(1)?;
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_string();
                let local_tag = tag.split(':').next_back().unwrap_or(&tag);

                match local_tag {
                    "metadata" => {
                        in_metadata = false;
                    }
                    "iTunesMetadata" => {
                        in_itunes_metadata = false;
                    }
                    "songwriters" => {
                        in_songwriters = false;
                    }
                    "songwriter" => {
                        if in_songwriter {
                            let trimmed = songwriter_text.trim().to_string();
                            if !trimmed.is_empty() {
                                songwriters.push(trimmed);
                            }
                            songwriter_text.clear();
                            in_songwriter = false;
                        }
                    }
                    "text" => {
                        if in_translation {
                            if let Some(key) = current_translation_key.take() {
                                let trimmed = current_translation_text.trim().to_string();
                                if !trimmed.is_empty() {
                                    translations_map.insert(key, trimmed);
                                }
                            }
                            current_translation_text.clear();
                        }
                    }
                    "translation" => {
                        in_translation = false;
                    }
                    "translations" => {
                        in_translations = false;
                    }
                    "body" => {
                        in_body = false;
                    }
                    "span" => {
                        if in_span {
                            // Append the full span text (including any prefix whitespace) to line text
                            current_line_text.push_str(&current_span_text);
                            // Commit the word
                            if let Some(start) = current_span_start {
                                current_line_words.push(ParsedLyricsWord {
                                    time_ms: start,
                                    end_time_ms: current_span_end,
                                    word: current_span_text.clone(),
                                });
                            }
                            in_span = false;
                        }
                    }
                    "p" => {
                        if in_p {
                            let line_start = current_line_start.unwrap_or(0);
                            let line_text = current_line_text.trim().to_string();

                            let agent = current_line_agent
                                .take()
                                .or_else(|| current_section_agent.clone());

                            let words = if is_word_synced && !current_line_words.is_empty() {
                                Some(std::mem::take(&mut current_line_words))
                            } else {
                                None
                            };

                            // Look up translation for this line using its itunes:key
                            let translation = current_line_key
                                .as_ref()
                                .and_then(|key| {
                                    // Try with "L" prefix first, then without
                                    translations_map.get(key).or_else(|| {
                                        let without_prefix = key.trim_start_matches('L');
                                        translations_map.get(without_prefix)
                                    })
                                })
                                .cloned();

                            let line = ParsedLyricsLine {
                                time_ms: line_start,
                                end_time_ms: current_line_end,
                                line: line_text,
                                words,
                                agent_id: agent,
                                translation,
                            };
                            current_section_lines.push(line);
                            current_line_key = None;
                            in_p = false;
                        }
                    }
                    "div" if in_div => {
                        // Commit all lines from this section into the global list
                        let section_lines = std::mem::take(&mut current_section_lines);
                        for line in &section_lines {
                            all_lines.push(line.clone());
                        }

                        // Build section if we have a name
                        if let Some(name) = current_section_name.take() {
                            let start_time_ms =
                                section_lines.first().map(|l| l.time_ms).unwrap_or(0);
                            let end_time_ms = section_lines
                                .last()
                                .and_then(|l| l.end_time_ms)
                                .or_else(|| section_lines.last().map(|l| l.time_ms))
                                .unwrap_or(start_time_ms);

                            sections.push(ParsedLyricsSection {
                                name,
                                start_time_ms,
                                end_time_ms,
                                lines: section_lines,
                                agent_id: current_section_agent.clone(),
                            });
                        }
                        current_section_agent = None;
                        in_div = false;
                    }
                    _ => {}
                }
            }
            Ok(Event::Text(ref e)) => {
                let text = e.decode().ok()?.to_string();
                if depth == 0 && !text.trim().is_empty() {
                    return None;
                }
                if in_songwriter {
                    songwriter_text.push_str(&text);
                } else if in_translation && current_translation_key.is_some() {
                    current_translation_text.push_str(&text);
                } else if in_span {
                    current_span_text.push_str(&text);
                } else if in_p {
                    if is_word_synced && !current_line_words.is_empty() {
                        // Track inter-span text (whitespace between </span> and <span>)
                        // Only after the first word — text before the first span is formatting
                        inter_span_text.push_str(&text);
                    } else if !is_word_synced {
                        current_line_text.push_str(&text);
                    }
                }
            }
            Ok(Event::Eof) => {
                if !root_seen || depth != 0 {
                    return None;
                }
                break;
            }
            Err(_) => return None,
            _ => {}
        }
        buf.clear();
    }

    // Sort all lines by start time
    all_lines.sort_by_key(|l| l.time_ms);

    let plain: Vec<String> = all_lines.iter().map(|l| l.line.clone()).collect();

    Some(ParsedLyrics {
        plain,
        synced: all_lines,
        sections: if sections.is_empty() {
            None
        } else {
            Some(sections)
        },
        agents: if agents.is_empty() {
            None
        } else {
            Some(agents)
        },
        songwriters: if songwriters.is_empty() {
            None
        } else {
            Some(songwriters)
        },
        language,
        are_from_remote: false,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_ttml_detects_xml_declaration() {
        assert!(is_ttml(
            r#"<?xml version="1.0" ?><tt xmlns="http://www.w3.org/ns/ttml"></tt>"#
        ));
    }

    #[test]
    fn is_ttml_detects_bare_tt() {
        assert!(is_ttml("<tt></tt>"));
        assert!(is_ttml("<tt/>"));
        assert!(parse_ttml("<tt/>trailing text").is_err());
        assert!(is_ttml(r#"<tt xmlns="http://www.w3.org/ns/ttml"></tt>"#));
    }

    #[test]
    fn is_ttml_rejects_non_ttml() {
        assert!(!is_ttml("[00:01.50]Hello"));
        assert!(!is_ttml("plain lyrics"));
        assert!(!is_ttml("<html></html>"));
        assert!(!is_ttml("<ttml></ttml>")); // <ttml is not <tt followed by space
    }

    #[test]
    fn parses_timestamp_seconds() {
        assert_eq!(parse_ttml_timestamp("30.348"), Some(30348));
    }

    #[test]
    fn parses_timestamp_minutes() {
        assert_eq!(parse_ttml_timestamp("1:30.456"), Some(90456));
    }

    #[test]
    fn parses_timestamp_hours() {
        assert_eq!(parse_ttml_timestamp("00:01:30.456"), Some(90456));
    }

    #[test]
    fn parses_line_synced_ttml() {
        let ttml = r#"<?xml version="1.0" ?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Line" xml:lang="es">
  <head>
    <metadata>
      <ttm:agent type="person" xml:id="v1"/>
      <ttm:agent type="other" xml:id="v2000"/>
      <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
        <songwriters>
          <songwriter>Writer One</songwriter>
          <songwriter>Writer Two</songwriter>
        </songwriters>
      </iTunesMetadata>
    </metadata>
  </head>
  <body dur="3:00.000">
    <div begin="0.027" end="35.359" itunes:songPart="Chorus" ttm:agent="v2000">
      <p begin="0.027" end="2.005" ttm:agent="v2000">First line</p>
      <p begin="20.844" end="23.161" ttm:agent="v1">Second line</p>
    </div>
    <div begin="53.206" end="1:10.398" itunes:songPart="Verse">
      <p begin="53.206" end="54.505" ttm:agent="v1">Third line</p>
    </div>
  </body>
</tt>"#;

        let parsed = parse_ttml(ttml).unwrap();

        // Language
        assert_eq!(parsed.language.as_deref(), Some("es"));

        // Agents
        let agents = parsed.agents.unwrap();
        assert_eq!(agents.len(), 2);
        assert_eq!(agents[0].id, "v1");
        assert_eq!(agents[1].id, "v2000");

        // Songwriters
        let sw = parsed.songwriters.unwrap();
        assert_eq!(sw, vec!["Writer One", "Writer Two"]);

        // Synced lines
        assert_eq!(parsed.synced.len(), 3);
        assert_eq!(parsed.synced[0].time_ms, 27);
        assert_eq!(parsed.synced[0].line, "First line");
        assert_eq!(parsed.synced[0].agent_id.as_deref(), Some("v2000"));

        assert_eq!(parsed.synced[1].time_ms, 20844);
        assert_eq!(parsed.synced[1].line, "Second line");
        assert_eq!(parsed.synced[1].agent_id.as_deref(), Some("v1"));

        // Plain
        assert_eq!(parsed.plain.len(), 3);

        // Sections
        let sections = parsed.sections.unwrap();
        assert_eq!(sections.len(), 2);
        assert_eq!(sections[0].name, "Chorus");
        assert_eq!(sections[0].lines.len(), 2);
        assert_eq!(sections[0].lines[0].line, "First line");
    }

    #[test]
    fn parses_word_synced_ttml() {
        let ttml = r#"<?xml version="1.0" ?><tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word" xml:lang="en"><head><metadata><ttm:agent type="person" xml:id="v1"/></metadata></head><body dur="5:00.000"><div begin="30.348" end="55.833" itunes:songPart="Verse"><p begin="30.348" end="33.231" ttm:agent="v1"><span begin="30.348" end="30.777">hello</span> <span begin="30.777" end="31.110">world</span></p></div></body></tt>"#;

        let parsed = parse_ttml(ttml).unwrap();
        assert_eq!(parsed.synced.len(), 1);
        let line = &parsed.synced[0];

        let words = line.words.as_ref().unwrap();
        assert_eq!(words.len(), 2);
        assert_eq!(words[0].word, "hello");
        assert_eq!(words[1].word, " world");

        assert_eq!(line.line, "hello world");
    }
}
