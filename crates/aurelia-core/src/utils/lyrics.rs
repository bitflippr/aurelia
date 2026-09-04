//! Lyrics format conversion utilities

use crate::models::{
    JellyfinLyricLine, JellyfinLyrics, ParsedLyrics, ParsedLyricsAgent, ParsedLyricsLine,
    ParsedLyricsSection, ParsedLyricsWord,
};
use std::fmt::Write;

/// Convert 100ns ticks to milliseconds.
fn ticks_to_ms(ticks: f64) -> i64 {
    (ticks / 10_000.0).round() as i64
}

/// Convert 100ns ticks (i64) to milliseconds.
fn ticks_i64_to_ms(ticks: i64) -> i64 {
    (ticks as f64 / 10_000.0).round() as i64
}

/// Convert Jellyfin lyrics format to LRC format
///
/// Jellyfin uses 100ns ticks for timestamps, this converts to standard LRC format [MM:SS.mmm]
pub fn jellyfin_to_lrc(lyrics: &JellyfinLyrics) -> Result<String, std::fmt::Error> {
    let mut lrc_content = String::new();

    for line in &lyrics.lyrics {
        if let Some(timestamp) = line.timestamp {
            // Convert Jellyfin timestamp (100ns ticks) to LRC format (MM:SS.mmm)
            let total_seconds = timestamp / 10_000_000.0;
            let total_seconds_floor = total_seconds.floor();
            let minutes = (total_seconds_floor / 60.0).floor();
            let seconds = (total_seconds_floor % 60.0).floor();
            let milliseconds = ((timestamp % 10_000_000.0) / 10_000.0).floor();

            writeln!(
                lrc_content,
                "[{:02}:{:02}.{:03}] {}",
                minutes, seconds, milliseconds, line.text
            )?;
        } else {
            writeln!(lrc_content, "{}", line.text)?;
        }
    }

    Ok(lrc_content)
}

/// Extract word-level [`ParsedLyricsWord`] entries from Jellyfin `LyricLineCue` data.
///
/// Each cue provides character-position indices (`Position`..`EndPosition`) into
/// the line's `Text` together with start/end tick timestamps.
fn extract_words_from_cues(
    text: &str,
    cues: &[crate::models::JellyfinLyricLineCue],
) -> Option<Vec<ParsedLyricsWord>> {
    if cues.is_empty() {
        return None;
    }

    let chars: Vec<char> = text.chars().collect();
    let mut words = Vec::with_capacity(cues.len());
    let mut previous_word_end: Option<usize> = None;

    for cue in cues {
        // Reject invalid ranges rather than letting supplied Word text bypass bounds checks.
        let Ok(start) = usize::try_from(cue.position) else {
            continue;
        };
        let Ok(end) = usize::try_from(cue.end_position) else {
            continue;
        };
        if start >= end || end > chars.len() {
            continue;
        }
        let source_segment: String = chars[start..end].iter().collect();
        let raw_word = cue.word.clone().unwrap_or_else(|| source_segment.clone());

        // Skip empty cues (pure whitespace separators)
        let mut trimmed = raw_word.trim().to_string();
        if trimmed.is_empty() {
            continue;
        }

        let has_leading_gap = previous_word_end.is_some_and(|previous_end| {
            let previous_end = previous_end.min(chars.len());
            let gap = if start > previous_end {
                chars[previous_end..start].iter().collect::<String>()
            } else {
                String::new()
            };

            gap.chars().any(char::is_whitespace)
                || source_segment
                    .chars()
                    .next()
                    .is_some_and(char::is_whitespace)
                || raw_word.chars().next().is_some_and(char::is_whitespace)
        });

        if has_leading_gap {
            trimmed.insert(0, ' ');
        }

        words.push(ParsedLyricsWord {
            time_ms: ticks_i64_to_ms(cue.start),
            end_time_ms: cue.end.map(ticks_i64_to_ms),
            word: trimmed,
        });
        previous_word_end = Some(end);
    }

    if words.is_empty() { None } else { Some(words) }
}

fn jellyfin_line_to_parsed(
    lyrics: &JellyfinLyrics,
    line: &JellyfinLyricLine,
    index: usize,
) -> Option<ParsedLyricsLine> {
    let text = line.text.trim().to_string();
    let ticks = line.timestamp?;
    let words = line
        .cues
        .as_deref()
        .and_then(|cues| extract_words_from_cues(&line.text, cues));

    let end_from_words = words
        .as_ref()
        .and_then(|w| w.last())
        .and_then(|w| w.end_time_ms);

    let end_from_line = line.end.map(ticks_to_ms);

    let end_from_next = lyrics
        .lyrics
        .get(index + 1)
        .and_then(|next| next.timestamp)
        .map(ticks_to_ms);

    Some(ParsedLyricsLine {
        time_ms: ticks_to_ms(ticks),
        end_time_ms: end_from_line.or(end_from_words).or(end_from_next),
        line: text,
        words,
        agent_id: line.agent_id.clone(),
        translation: line.translation.clone(),
    })
}

/// Convert Jellyfin lyrics directly to [`ParsedLyrics`] without an intermediate LRC string.
///
/// Jellyfin timestamps are in 100ns tick units.  If the server parsed a TTML
/// file the response will include `Cues` on each line with word-level timing
/// data — these are converted to [`ParsedLyricsWord`] entries.
#[must_use]
pub fn jellyfin_to_parsed_lyrics(lyrics: &JellyfinLyrics) -> ParsedLyrics {
    let mut synced = Vec::new();
    let mut plain = Vec::new();
    let mut has_timestamps = false;

    for (i, line) in lyrics.lyrics.iter().enumerate() {
        let text = line.text.trim().to_string();
        if line.timestamp.is_some() {
            has_timestamps = true;
            if let Some(parsed_line) = jellyfin_line_to_parsed(lyrics, line, i) {
                synced.push(parsed_line);
            }
        } else {
            plain.push(text);
        }
    }

    let agents = lyrics.agents.as_ref().map(|agents| {
        agents
            .iter()
            .map(|agent| ParsedLyricsAgent {
                id: agent.id.clone(),
                agent_type: agent.agent_type.clone(),
            })
            .collect()
    });

    let sections = lyrics.sections.as_ref().map(|sections| {
        sections
            .iter()
            .map(|section| ParsedLyricsSection {
                name: section.name.clone(),
                start_time_ms: section.start_time_ms,
                end_time_ms: section.end_time_ms,
                lines: section
                    .lines
                    .iter()
                    .enumerate()
                    .filter_map(|(i, line)| jellyfin_line_to_parsed(lyrics, line, i))
                    .collect(),
                agent_id: section.agent_id.clone(),
            })
            .collect()
    });

    if has_timestamps && !synced.is_empty() {
        synced.sort_by_key(|l| l.time_ms);
        let plain_from_synced = synced.iter().map(|l| l.line.clone()).collect();
        ParsedLyrics {
            plain: plain_from_synced,
            synced,
            sections,
            agents,
            songwriters: lyrics.songwriters.clone(),
            language: lyrics.language.clone(),
            are_from_remote: true,
        }
    } else {
        ParsedLyrics {
            plain: plain.into_iter().filter(|l| !l.is_empty()).collect(),
            synced: vec![],
            sections,
            agents,
            songwriters: lyrics.songwriters.clone(),
            language: lyrics.language.clone(),
            are_from_remote: true,
        }
    }
}

/// Parse lyrics text using aurelia-lyrics crate, auto-detecting the format.
#[must_use]
pub fn parse_lyrics(text: &str) -> ParsedLyrics {
    let mut parsed = if aurelia_lyrics::ttml::is_ttml(text) {
        aurelia_lyrics::parse_ttml(text).unwrap_or_else(|_| ParsedLyrics {
            plain: vec![],
            synced: vec![],
            sections: None,
            agents: None,
            songwriters: None,
            language: None,
            are_from_remote: false,
        })
    } else {
        // Use parse_lrc for everything else (LRC and plain text)
        aurelia_lyrics::parse_lrc(text).unwrap_or_else(|_| ParsedLyrics {
            plain: vec![],
            synced: vec![],
            sections: None,
            agents: None,
            songwriters: None,
            language: None,
            are_from_remote: false,
        })
    };

    // Ensure remote flag is set to true to match legacy behavior
    parsed.are_from_remote = true;
    parsed
}

#[cfg(test)]
mod tests {
    use super::{jellyfin_to_lrc, jellyfin_to_parsed_lyrics};
    use crate::models::{
        JellyfinLyricAgent, JellyfinLyricLine, JellyfinLyricLineCue, JellyfinLyricSection,
        JellyfinLyrics,
    };

    fn make_line(text: &str, ticks: Option<f64>) -> JellyfinLyricLine {
        JellyfinLyricLine {
            text: text.to_string(),
            timestamp: ticks,
            end: None,
            cues: None,
            agent_id: None,
            translation: None,
            section: None,
        }
    }

    fn make_lyrics(lyrics: Vec<JellyfinLyricLine>) -> JellyfinLyrics {
        JellyfinLyrics {
            metadata: None,
            lyrics,
            songwriters: None,
            language: None,
            agents: None,
            sections: None,
        }
    }

    #[test]
    fn converts_jellyfin_timestamps_to_lrc() {
        let lyrics = make_lyrics(vec![
            make_line("Intro", Some(0.0)),
            make_line("Verse", Some(12_340_000.0)),
            make_line("No timestamp", None),
        ]);

        let rendered = jellyfin_to_lrc(&lyrics).expect("lrc render");
        let lines: Vec<&str> = rendered.lines().collect();

        assert_eq!(lines[0], "[00:00.000] Intro");
        assert_eq!(lines[1], "[00:01.234] Verse");
        assert_eq!(lines[2], "No timestamp");
    }

    #[test]
    fn invalid_cue_ranges_do_not_panic_or_replace_valid_words() {
        let cue = |position, end_position, word: &str| JellyfinLyricLineCue {
            position,
            end_position,
            start: 0,
            end: None,
            word: Some(word.into()),
        };
        let mut line = make_line("é a", Some(0.0));
        line.cues = Some(vec![
            cue(0, 1, "é"),
            cue(20, 21, "outside"),
            cue(-1, 1, "negative"),
            cue(2, 1, "reversed"),
            cue(1, 10, "overflow"),
            cue(2, 3, "a"),
        ]);
        let parsed = jellyfin_to_parsed_lyrics(&make_lyrics(vec![line]));
        let words = parsed.synced[0].words.as_ref().unwrap();
        assert_eq!(
            words.iter().map(|w| w.word.as_str()).collect::<Vec<_>>(),
            ["é", " a"]
        );
    }

    #[test]
    fn parsed_lyrics_without_cues_have_no_words() {
        let lyrics = make_lyrics(vec![
            make_line("Hello world", Some(0.0)),
            make_line("Goodbye world", Some(50_000_000.0)),
        ]);

        let parsed = jellyfin_to_parsed_lyrics(&lyrics);
        assert_eq!(parsed.synced.len(), 2);
        assert!(parsed.synced[0].words.is_none());
        assert!(parsed.synced[1].words.is_none());
        // end_time_ms should be derived from next line
        assert_eq!(parsed.synced[0].end_time_ms, Some(5000));
        assert!(parsed.synced[1].end_time_ms.is_none());
    }

    #[test]
    fn parsed_lyrics_extracts_word_cues() {
        // "Hello world" — 'Hello' at chars 0..5, 'world' at chars 6..11
        let lyrics = make_lyrics(vec![JellyfinLyricLine {
            text: "Hello world".to_string(),
            timestamp: Some(0.0),
            end: None,
            cues: Some(vec![
                JellyfinLyricLineCue {
                    position: 0,
                    end_position: 5,
                    start: 0,
                    end: Some(5_000_000), // 500ms
                    word: None,
                },
                JellyfinLyricLineCue {
                    position: 6,
                    end_position: 11,
                    start: 5_000_000,
                    end: Some(10_000_000), // 1000ms
                    word: None,
                },
            ]),
            agent_id: None,
            translation: None,
            section: None,
        }]);

        let parsed = jellyfin_to_parsed_lyrics(&lyrics);
        assert_eq!(parsed.synced.len(), 1);

        let words = parsed.synced[0].words.as_ref().expect("should have words");
        assert_eq!(words.len(), 2);

        assert_eq!(words[0].word, "Hello");
        assert_eq!(words[0].time_ms, 0);
        assert_eq!(words[0].end_time_ms, Some(500));

        assert_eq!(words[1].word, " world");
        assert_eq!(words[1].time_ms, 500);
        assert_eq!(words[1].end_time_ms, Some(1000));

        // Line end_time_ms should come from last word's end
        assert_eq!(parsed.synced[0].end_time_ms, Some(1000));
    }

    #[test]
    fn whitespace_only_cues_are_skipped() {
        let lyrics = make_lyrics(vec![JellyfinLyricLine {
            text: "Hi there".to_string(),
            timestamp: Some(0.0),
            end: None,
            cues: Some(vec![
                JellyfinLyricLineCue {
                    position: 0,
                    end_position: 2,
                    start: 0,
                    end: Some(2_000_000),
                    word: None,
                },
                // Whitespace-only cue (the space between words)
                JellyfinLyricLineCue {
                    position: 2,
                    end_position: 3,
                    start: 2_000_000,
                    end: Some(3_000_000),
                    word: None,
                },
                JellyfinLyricLineCue {
                    position: 3,
                    end_position: 8,
                    start: 3_000_000,
                    end: Some(6_000_000),
                    word: None,
                },
            ]),
            agent_id: None,
            translation: None,
            section: None,
        }]);

        let parsed = jellyfin_to_parsed_lyrics(&lyrics);
        let words = parsed.synced[0].words.as_ref().expect("should have words");
        assert_eq!(words.len(), 2);
        assert_eq!(words[0].word, "Hi");
        assert_eq!(words[1].word, " there");
    }

    #[test]
    fn preserves_space_when_cue_range_includes_separator() {
        let lyrics = make_lyrics(vec![JellyfinLyricLine {
            text: "Hello world".to_string(),
            timestamp: Some(0.0),
            end: None,
            cues: Some(vec![
                JellyfinLyricLineCue {
                    position: 0,
                    end_position: 5,
                    start: 0,
                    end: Some(5_000_000),
                    word: None,
                },
                JellyfinLyricLineCue {
                    position: 5,
                    end_position: 11,
                    start: 5_000_000,
                    end: Some(10_000_000),
                    word: None,
                },
            ]),
            agent_id: None,
            translation: None,
            section: None,
        }]);

        let parsed = jellyfin_to_parsed_lyrics(&lyrics);
        let words = parsed.synced[0].words.as_ref().expect("should have words");
        assert_eq!(words[0].word, "Hello");
        assert_eq!(words[1].word, " world");
    }

    #[test]
    fn empty_cues_array_produces_no_words() {
        let lyrics = make_lyrics(vec![JellyfinLyricLine {
            text: "No cues here".to_string(),
            timestamp: Some(0.0),
            end: None,
            cues: Some(vec![]),
            agent_id: None,
            translation: None,
            section: None,
        }]);

        let parsed = jellyfin_to_parsed_lyrics(&lyrics);
        assert!(parsed.synced[0].words.is_none());
    }

    #[test]
    fn parsed_lyrics_preserves_ttml_fork_metadata() {
        let make_fork_line = || JellyfinLyricLine {
            text: "Hello world".to_string(),
            timestamp: Some(5_000_000.0),
            end: Some(40_000_000.0),
            cues: Some(vec![JellyfinLyricLineCue {
                position: 0,
                end_position: 6,
                start: 10_000_000,
                end: Some(15_000_000),
                word: Some("Hello ".to_string()),
            }]),
            agent_id: Some("singerA".to_string()),
            translation: Some("Bonjour le monde".to_string()),
            section: Some("Verse 1".to_string()),
        };

        let lyrics = JellyfinLyrics {
            metadata: None,
            lyrics: vec![make_fork_line()],
            songwriters: Some(vec!["Songwriter One".to_string()]),
            language: Some("en".to_string()),
            agents: Some(vec![JellyfinLyricAgent {
                id: "singerA".to_string(),
                agent_type: "person".to_string(),
                name: Some("Alice".to_string()),
            }]),
            sections: Some(vec![JellyfinLyricSection {
                name: "Verse 1".to_string(),
                start_time_ms: 500,
                end_time_ms: 4000,
                lines: vec![make_fork_line()],
                agent_id: Some("singerA".to_string()),
            }]),
        };

        let parsed = jellyfin_to_parsed_lyrics(&lyrics);

        assert_eq!(parsed.language.as_deref(), Some("en"));
        assert_eq!(
            parsed.songwriters.as_deref(),
            Some(&["Songwriter One".to_string()][..])
        );
        assert_eq!(parsed.agents.as_ref().map(Vec::len), Some(1));
        assert_eq!(parsed.sections.as_ref().map(Vec::len), Some(1));
        assert_eq!(parsed.synced[0].end_time_ms, Some(4000));
        assert_eq!(parsed.synced[0].agent_id.as_deref(), Some("singerA"));
        assert_eq!(
            parsed.synced[0].translation.as_deref(),
            Some("Bonjour le monde")
        );
        assert_eq!(parsed.synced[0].words.as_ref().unwrap()[0].word, "Hello");
    }
}
