use aurelia_core::build_mobile_stream_url;

#[test]
fn android_only_downmixes_eac3_without_another_lossy_encode() {
    for (container, codec) in [("m4a", "eac3"), ("eac3", "eac3"), ("mp4", "EAC3")] {
        let url = aurelia_core::build_android_stream_url(
            "https://music.example/jellyfin".into(),
            "session-token".into(),
            "surround-song".into(),
            Some(container.into()),
            Some(codec.into()),
        );
        let url = reqwest::Url::parse(&url).unwrap();
        let query: std::collections::HashMap<_, _> = url.query_pairs().collect();
        for (key, value) in [
            ("maxAudioChannels", "2"),
            ("transcodingAudioChannels", "2"),
            ("audioCodec", "flac"),
            ("transcodingContainer", "flac"),
            ("transcodingProtocol", "http"),
        ] {
            assert_eq!(query.get(key).map(|v| v.as_ref()), Some(value));
        }
        for key in ["audioBitRate", "audioSampleRate", "maxAudioBitDepth"] {
            assert!(!query.contains_key(key));
        }
    }
}

#[test]
fn android_other_formats_keep_existing_stream_selection() {
    for (container, codec) in [
        ("flac", "flac"),
        ("mp3", "mp3"),
        ("m4a", "alac"),
        ("m4a", "aac"),
        ("ac3", "ac3"),
        ("ogg", "opus"),
    ] {
        let args = (
            "https://music.example/jellyfin".to_string(),
            "token".to_string(),
            "song".to_string(),
            Some(container.to_string()),
        );
        let original = build_mobile_stream_url(
            args.0.clone(),
            args.1.clone(),
            args.2.clone(),
            args.3.clone(),
        );
        let current = aurelia_core::build_android_stream_url(
            args.0,
            args.1,
            args.2,
            args.3,
            Some(codec.into()),
        );
        assert_eq!(current, original);
        assert!(!current.contains("AudioChannels"));
        assert!(!current.contains("audioBitRate"));
        if current.contains("/universal") {
            assert!(current.contains("audioCodec=aac"));
        }
    }
}

#[test]
fn mobile_streams_authenticate_without_legacy_jellyfin_authorization() {
    for (container, route) in [
        ("flac", "stream"),
        ("m4a", "universal"),
        ("alac", "universal"),
    ] {
        let url = build_mobile_stream_url(
            "https://music.example/jellyfin".into(),
            "session-token".into(),
            "song-id".into(),
            Some(container.into()),
        );
        let url = reqwest::Url::parse(&url).unwrap();
        assert_eq!(url.path(), format!("/jellyfin/Audio/song-id/{route}"));
        assert!(
            url.query_pairs()
                .any(|(key, value)| key == "ApiKey" && value == "session-token")
        );
        assert!(!url.query_pairs().any(|(key, _)| key == "api_key"));
    }
}
