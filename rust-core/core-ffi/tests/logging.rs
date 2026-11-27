use std::fs;

use core_ffi::observability::{init, LOG_FILE_COUNT};

#[test]
fn a_logged_event_is_json_and_never_contains_a_rtsp_password() {
    let dir = std::env::temp_dir().join(format!("cctv-log-test-{}", std::process::id()));
    let _ = fs::create_dir_all(&dir);

    assert!(init(&dir, "cctv-test"));
    tracing::warn!(
        event = "connect_failed",
        url = "rtsp://admin:rahasia@10.0.0.5:554/live",
        "connect failed"
    );

    let files: Vec<_> = fs::read_dir(&dir)
        .expect("log dir exists")
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.file_name().to_string_lossy().starts_with("cctv-test"))
        .collect();
    assert!(!files.is_empty(), "the subscriber must create a log file");
    assert!(
        files.len() <= LOG_FILE_COUNT,
        "rotation keeps at most three files"
    );

    let contents = files
        .iter()
        .map(|entry| fs::read_to_string(entry.path()).unwrap_or_default())
        .collect::<String>();
    assert!(!contents.contains("rahasia"), "password must be redacted");
    assert!(!contents.contains("admin"), "user must be redacted");
    assert!(contents.contains("\"level\":\"WARN\""));
    assert!(contents.contains("connect_failed"));

    for line in contents.lines() {
        let value: serde_json::Value =
            serde_json::from_str(line).expect("every line must be a JSON object");
        assert!(value.get("ts").is_some());
        assert!(value.get("target").is_some());
        assert!(value.get("message").is_some());
    }

    let _ = fs::remove_dir_all(&dir);
}
