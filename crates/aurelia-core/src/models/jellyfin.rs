//! Jellyfin API-specific data models

use serde::{Deserialize, Serialize};

/// Jellyfin lyrics response (`LyricDto`).
#[derive(Serialize, Deserialize, Debug)]
pub struct JellyfinLyrics {
    /// Optional metadata about the lyrics.
    #[serde(rename = "Metadata")]
    #[serde(default)]
    pub metadata: Option<JellyfinLyricMetadata>,
    /// List of lyric lines with timestamps
    #[serde(rename = "Lyrics")]
    pub lyrics: Vec<JellyfinLyricLine>,
    #[serde(rename = "Songwriters")]
    #[serde(default)]
    pub songwriters: Option<Vec<String>>,
    #[serde(rename = "Language")]
    #[serde(default)]
    pub language: Option<String>,
    #[serde(rename = "Agents")]
    #[serde(default)]
    pub agents: Option<Vec<JellyfinLyricAgent>>,
    #[serde(rename = "Sections")]
    #[serde(default)]
    pub sections: Option<Vec<JellyfinLyricSection>>,
}

/// Metadata about the lyrics (`LyricMetadata`).
#[derive(Serialize, Deserialize, Debug)]
pub struct JellyfinLyricMetadata {
    #[serde(rename = "Artist")]
    #[serde(default)]
    pub artist: Option<String>,
    #[serde(rename = "Album")]
    #[serde(default)]
    pub album: Option<String>,
    #[serde(rename = "Title")]
    #[serde(default)]
    pub title: Option<String>,
    #[serde(rename = "Author")]
    #[serde(default)]
    pub author: Option<String>,
    #[serde(rename = "Length")]
    #[serde(default)]
    pub length: Option<i64>,
    #[serde(rename = "By")]
    #[serde(default)]
    pub by: Option<String>,
    #[serde(rename = "Offset")]
    #[serde(default)]
    pub offset: Option<i64>,
    #[serde(rename = "Creator")]
    #[serde(default)]
    pub creator: Option<String>,
    #[serde(rename = "Version")]
    #[serde(default)]
    pub version: Option<String>,
    #[serde(rename = "IsSynced")]
    #[serde(default)]
    pub is_synced: Option<bool>,
}

/// Individual lyric line with optional timestamp (`LyricLine`).
#[derive(Serialize, Deserialize, Debug)]
pub struct JellyfinLyricLine {
    /// Lyric text
    #[serde(rename = "Text")]
    pub text: String,
    /// Timestamp in ticks (100ns intervals from start)
    #[serde(rename = "Start")]
    pub timestamp: Option<f64>,
    /// End timestamp in ticks (100ns intervals).
    #[serde(rename = "End")]
    #[serde(default)]
    pub end: Option<f64>,
    /// Word-level timing cues within this line.
    #[serde(rename = "Cues")]
    #[serde(default)]
    pub cues: Option<Vec<JellyfinLyricLineCue>>,
    /// Singer/agent identifier for multi-vocal TTML.
    #[serde(rename = "AgentId")]
    #[serde(default)]
    pub agent_id: Option<String>,
    /// Translation text for this line.
    #[serde(rename = "Translation")]
    #[serde(default)]
    pub translation: Option<String>,
    /// Section name this line belongs to.
    #[serde(rename = "Section")]
    #[serde(default)]
    pub section: Option<String>,
}

/// Word-level timing cue within a lyric line (`LyricLineCue`).
///
/// Holds character position indices into the parent line's `Text` and
/// timing information for a single word/segment.
#[derive(Serialize, Deserialize, Debug)]
pub struct JellyfinLyricLineCue {
    /// Start character index in the line text (inclusive).
    #[serde(rename = "Position")]
    pub position: i32,
    /// End character index in the line text (exclusive).
    #[serde(rename = "EndPosition")]
    pub end_position: i32,
    /// Start timestamp in ticks (100ns intervals).
    #[serde(rename = "Start")]
    pub start: i64,
    /// End timestamp in ticks (100ns intervals), if available.
    #[serde(rename = "End")]
    #[serde(default)]
    pub end: Option<i64>,
    /// Word text supplied by Jellyfin TTML parser, if available.
    #[serde(rename = "Word")]
    #[serde(default)]
    pub word: Option<String>,
}

/// Which cues are background vocals (a TTML `ttm:role="x-bg"` span), read
/// from the same response as [`JellyfinLyrics`].
#[derive(Deserialize, Debug, Default)]
pub struct JellyfinBackgroundCues {
    #[serde(rename = "Lyrics")]
    #[serde(default)]
    lines: Vec<JellyfinBackgroundLine>,
}

#[derive(Deserialize, Debug, Default)]
struct JellyfinBackgroundLine {
    #[serde(rename = "Cues")]
    #[serde(default)]
    cues: Option<Vec<JellyfinBackgroundCue>>,
}

#[derive(Deserialize, Debug, Default)]
struct JellyfinBackgroundCue {
    #[serde(rename = "IsBackground")]
    #[serde(default)]
    is_background: bool,
}

impl JellyfinBackgroundCues {
    /// Whether cue `cue` of lyric line `line` is a background vocal.
    #[must_use]
    pub fn is_background(&self, line: usize, cue: usize) -> bool {
        self.lines
            .get(line)
            .and_then(|line| line.cues.as_ref())
            .and_then(|cues| cues.get(cue))
            .is_some_and(|cue| cue.is_background)
    }
}

/// Singer/performer agent metadata from TTML lyrics.
#[derive(Serialize, Deserialize, Debug)]
pub struct JellyfinLyricAgent {
    #[serde(rename = "Id")]
    pub id: String,
    #[serde(rename = "AgentType")]
    pub agent_type: String,
    #[serde(rename = "Name")]
    #[serde(default)]
    pub name: Option<String>,
}

/// Section metadata from TTML lyrics.
#[derive(Serialize, Deserialize, Debug)]
pub struct JellyfinLyricSection {
    #[serde(rename = "Name")]
    pub name: String,
    #[serde(rename = "StartTimeMs")]
    pub start_time_ms: i64,
    #[serde(rename = "EndTimeMs")]
    pub end_time_ms: i64,
    #[serde(rename = "Lines")]
    #[serde(default)]
    pub lines: Vec<JellyfinLyricLine>,
    #[serde(rename = "AgentId")]
    #[serde(default)]
    pub agent_id: Option<String>,
}

/// Device profile for client capabilities
#[derive(Serialize, Deserialize, Debug)]
pub struct DeviceProfile {
    /// Device profile name
    #[serde(rename = "Name")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// Device profile ID
    #[serde(rename = "Id")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    /// Maximum streaming bitrate
    #[serde(rename = "MaxStreamingBitrate")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_streaming_bitrate: Option<i32>,
    /// Maximum static bitrate
    #[serde(rename = "MaxStaticBitrate")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_static_bitrate: Option<i32>,
    /// Music streaming transcoding bitrate
    #[serde(rename = "MusicStreamingTranscodingBitrate")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub music_streaming_transcoding_bitrate: Option<i32>,
    /// Maximum static music bitrate
    #[serde(rename = "MaxStaticMusicBitrate")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_static_music_bitrate: Option<i32>,
    /// Direct play profiles
    #[serde(rename = "DirectPlayProfiles")]
    pub direct_play_profiles: Vec<DirectPlayProfile>,
    /// Transcoding profiles
    #[serde(rename = "TranscodingProfiles")]
    pub transcoding_profiles: Vec<TranscodingProfile>,
    /// Container profiles
    #[serde(rename = "ContainerProfiles")]
    pub container_profiles: Vec<ContainerProfile>,
    /// Codec profiles
    #[serde(rename = "CodecProfiles")]
    pub codec_profiles: Vec<CodecProfile>,
    /// Subtitle profiles
    #[serde(rename = "SubtitleProfiles")]
    pub subtitle_profiles: Vec<SubtitleProfile>,
}

/// Direct play profile
#[derive(Serialize, Deserialize, Debug)]
pub struct DirectPlayProfile {
    /// Container format
    #[serde(rename = "Container")]
    pub container: String,
    /// Audio codec
    #[serde(rename = "AudioCodec")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub audio_codec: Option<String>,
    /// Video codec
    #[serde(rename = "VideoCodec")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub video_codec: Option<String>,
    /// Profile type
    #[serde(rename = "Type")]
    pub profile_type: String,
}

/// Transcoding profile
#[derive(Serialize, Deserialize, Debug)]
pub struct TranscodingProfile {
    /// Container format
    #[serde(rename = "Container")]
    pub container: String,
    /// Profile type
    #[serde(rename = "Type")]
    pub profile_type: String,
    /// Video codec
    #[serde(rename = "VideoCodec")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub video_codec: Option<String>,
    /// Audio codec
    #[serde(rename = "AudioCodec")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub audio_codec: Option<String>,
    /// Protocol
    #[serde(rename = "Protocol")]
    pub protocol: String,
    /// Estimate content length
    #[serde(rename = "EstimateContentLength")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub estimate_content_length: Option<bool>,
    /// Enable MPEG-TS M2TS mode
    #[serde(rename = "EnableMpegtsM2TsMode")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enable_mpegts_m2_ts_mode: Option<bool>,
    /// Transcode seek info
    #[serde(rename = "TranscodeSeekInfo")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub transcode_seek_info: Option<String>,
    /// Copy timestamps
    #[serde(rename = "CopyTimestamps")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub copy_timestamps: Option<bool>,
    /// Context
    #[serde(rename = "Context")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub context: Option<String>,
    /// Enable subtitles in manifest
    #[serde(rename = "EnableSubtitlesInManifest")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enable_subtitles_in_manifest: Option<bool>,
    /// Maximum audio channels
    #[serde(rename = "MaxAudioChannels")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_audio_channels: Option<String>,
    /// Minimum segments
    #[serde(rename = "MinSegments")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub min_segments: Option<i32>,
    /// Segment length
    #[serde(rename = "SegmentLength")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub segment_length: Option<i32>,
    /// Break on non-key frames
    #[serde(rename = "BreakOnNonKeyFrames")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub break_on_non_key_frames: Option<bool>,
    /// Conditions
    #[serde(rename = "Conditions")]
    pub conditions: Vec<ProfileCondition>,
    /// Enable audio VBR encoding
    #[serde(rename = "EnableAudioVbrEncoding")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enable_audio_vbr_encoding: Option<bool>,
}

/// Container profile
#[derive(Serialize, Deserialize, Debug)]
pub struct ContainerProfile {
    /// Profile type
    #[serde(rename = "Type")]
    pub profile_type: String,
    /// Conditions
    #[serde(rename = "Conditions")]
    pub conditions: Vec<ProfileCondition>,
    /// Container
    #[serde(rename = "Container")]
    pub container: String,
    /// Sub container
    #[serde(rename = "SubContainer")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sub_container: Option<String>,
}

/// Codec profile
#[derive(Serialize, Deserialize, Debug)]
pub struct CodecProfile {
    /// Profile type
    #[serde(rename = "Type")]
    pub profile_type: String,
    /// Conditions
    #[serde(rename = "Conditions")]
    pub conditions: Vec<ProfileCondition>,
    /// Apply conditions
    #[serde(rename = "ApplyConditions")]
    pub apply_conditions: Vec<ProfileCondition>,
    /// Codec
    #[serde(rename = "Codec")]
    pub codec: String,
    /// Container
    #[serde(rename = "Container")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub container: Option<String>,
    /// Sub container
    #[serde(rename = "SubContainer")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sub_container: Option<String>,
}

/// Subtitle profile
#[derive(Serialize, Deserialize, Debug)]
pub struct SubtitleProfile {
    /// Format
    #[serde(rename = "Format")]
    pub format: String,
    /// Method
    #[serde(rename = "Method")]
    pub method: String,
    /// DIDL mode
    #[serde(rename = "DidlMode")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub didl_mode: Option<String>,
    /// Language
    #[serde(rename = "Language")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub language: Option<String>,
    /// Container
    #[serde(rename = "Container")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub container: Option<String>,
}

/// Profile condition
#[derive(Serialize, Deserialize, Debug)]
pub struct ProfileCondition {
    /// Condition type
    #[serde(rename = "Condition")]
    pub condition: String,
    /// Property
    #[serde(rename = "Property")]
    pub property: String,
    /// Value
    #[serde(rename = "Value")]
    pub value: String,
    /// Is required
    #[serde(rename = "IsRequired")]
    pub is_required: bool,
}

/// Client capabilities for session registration
#[derive(Serialize, Deserialize, Debug)]
pub struct ClientCapabilities {
    /// Supported media types for playback
    #[serde(rename = "PlayableMediaTypes")]
    pub playable_media_types: Vec<String>,
    /// Supported commands
    #[serde(rename = "SupportedCommands")]
    pub supported_commands: Vec<String>,
    /// Whether the client supports media control
    #[serde(rename = "SupportsMediaControl")]
    pub supports_media_control: bool,
    /// Whether the client supports persistent identifier
    #[serde(rename = "SupportsPersistentIdentifier")]
    pub supports_persistent_identifier: bool,
    /// Device profile
    #[serde(rename = "DeviceProfile")]
    pub device_profile: DeviceProfile,
    /// App store URL
    #[serde(rename = "AppStoreUrl")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub app_store_url: Option<String>,
    /// Icon URL
    #[serde(rename = "IconUrl")]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub icon_url: Option<String>,
}

/// Playback information for session reporting
#[derive(Serialize, Deserialize, Debug)]
pub struct PlaybackInfo {
    /// Item ID being played
    #[serde(rename = "ItemId")]
    pub item_id: String,
    /// Session ID
    #[serde(rename = "SessionId")]
    pub session_id: Option<String>,
    /// Media source ID
    #[serde(rename = "MediaSourceId")]
    pub media_source_id: String,
    /// Audio stream index
    #[serde(rename = "AudioStreamIndex")]
    pub audio_stream_index: Option<i32>,
    /// Subtitle stream index
    #[serde(rename = "SubtitleStreamIndex")]
    pub subtitle_stream_index: Option<i32>,
    /// Whether this is a live stream
    #[serde(rename = "IsPaused")]
    pub is_paused: bool,
    /// Whether playback is muted
    #[serde(rename = "IsMuted")]
    pub is_muted: bool,
    /// Current position in ticks
    #[serde(rename = "PositionTicks")]
    pub position_ticks: Option<f64>,
    /// Current volume level (0-100)
    #[serde(rename = "VolumeLevel")]
    pub volume_level: Option<i32>,
    /// Playback rate (1.0 = normal speed)
    #[serde(rename = "PlayMethod")]
    pub play_method: Option<String>,
    /// How the item is being played (Direct, Transcode, etc.)
    #[serde(rename = "PlaySessionId")]
    pub play_session_id: Option<String>,
    /// Repeat mode
    #[serde(rename = "RepeatMode")]
    pub repeat_mode: Option<String>,
}

/// Progress update for ongoing playback
#[derive(Serialize, Deserialize, Debug)]
pub struct PlaybackProgress {
    /// Item ID being played
    #[serde(rename = "ItemId")]
    pub item_id: String,
    /// Session ID
    #[serde(rename = "SessionId")]
    pub session_id: Option<String>,
    /// Media source ID
    #[serde(rename = "MediaSourceId")]
    pub media_source_id: String,
    /// Current position in ticks
    #[serde(rename = "PositionTicks")]
    pub position_ticks: Option<f64>,
    /// Whether playback is paused
    #[serde(rename = "IsPaused")]
    pub is_paused: bool,
    /// Whether playback is muted
    #[serde(rename = "IsMuted")]
    pub is_muted: bool,
    /// Current volume level (0-100)
    #[serde(rename = "VolumeLevel")]
    pub volume_level: Option<i32>,
    /// Playback rate
    #[serde(rename = "PlayMethod")]
    pub play_method: Option<String>,
    /// Play session ID
    #[serde(rename = "PlaySessionId")]
    pub play_session_id: Option<String>,
    /// Repeat mode
    #[serde(rename = "RepeatMode")]
    pub repeat_mode: Option<String>,
    /// Whether shuffle is enabled
    #[serde(rename = "ShuffleMode")]
    pub shuffle_mode: Option<String>,
    /// Live stream ID
    #[serde(rename = "LiveStreamId")]
    pub live_stream_id: Option<String>,
    /// Playback start time ticks
    #[serde(rename = "PlaybackStartTimeTicks")]
    pub playback_start_time_ticks: Option<f64>,
    /// Event type
    #[serde(rename = "EventName")]
    pub event_name: Option<String>,
}

/// Stop playback info
#[derive(Serialize, Deserialize, Debug)]
pub struct PlaybackStop {
    /// Item ID that was playing
    #[serde(rename = "ItemId")]
    pub item_id: String,
    /// Session ID
    #[serde(rename = "SessionId")]
    pub session_id: Option<String>,
    /// Media source ID
    #[serde(rename = "MediaSourceId")]
    pub media_source_id: String,
    /// Position when stopped in ticks
    #[serde(rename = "PositionTicks")]
    pub position_ticks: Option<f64>,
    /// Play session ID
    #[serde(rename = "PlaySessionId")]
    pub play_session_id: Option<String>,
}

/// Progress update data for frontend
#[derive(Serialize, Deserialize, Debug)]
pub struct PlaybackProgressData {
    pub item_id: String,
    pub position_ticks: Option<f64>,
    pub is_paused: bool,
    pub volume_level: Option<i32>,
    pub is_muted: bool,
    pub repeat_mode: Option<String>,
    pub shuffle_mode: Option<String>,
}
