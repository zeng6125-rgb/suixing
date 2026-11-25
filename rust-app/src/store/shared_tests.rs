use super::*;
use serde_json::{Value, json};

fn files() -> PathBuf {
    let path =
        std::env::temp_dir().join(format!("jagent-shared-{}", crate::controller::unique_id()));
    fs::create_dir_all(path.join("chats/store")).unwrap();
    path
}

fn seed_chat(files: &Path) {
    let messages = [
        json!({"id":"u1", "role":"user", "text":"你好", "timestamp":10}),
        json!({"id":"a1", "role":"assistant", "text":"回答", "reasoning":"思考", "runId":"r1",
            "model":"test", "promptTokens":12, "completionTokens":3, "elapsedMs":20, "runElapsedMs":50, "timestamp":20}),
    ];
    let mut bytes = Vec::new();
    for message in messages {
        serde_json::to_writer(&mut bytes, &message).unwrap();
        bytes.push(b'\n');
    }
    let committed = bytes.len();
    bytes.extend_from_slice(b"uncommitted tail");
    fs::write(files.join("chats/store/c1.0.jsonl"), bytes).unwrap();
    fs::write(
        files.join("chats/store/c1.meta"),
        json!({
            "id":"c1", "title":"原会话", "createdAt":10, "updatedAt":20,
            "assistantId":"default", "gen":0, "logBytes":committed,
            "messageCount":2, "turnCount":1, "lastMessageId":"a1", "customField":"保留"
        })
        .to_string(),
    )
    .unwrap();
}

#[test]
fn shared_goal_resumes_a_compose_objective_and_writes_back() {
    use crate::goal::{Command, Status};

    let files = files();
    seed_chat(&files);
    let path = files.join("goal.json");
    fs::write(
        &path,
        json!({"text":"整理资料", "updatedAt":42, "status":"PAUSED", "extra":"保留"}).to_string(),
    )
    .unwrap();
    let root = files.join("rust-state");
    let mut store = Store::open_with_shared_files(root.clone(), Some(files.clone())).unwrap();
    assert_eq!(store.goal().updated_at, 42);
    assert_eq!(store.goal().chat_id, "");
    assert_eq!(
        store
            .goal_command("c1", "", Command::Read)
            .unwrap()
            .unwrap()
            .text,
        "整理资料"
    );
    store
        .activate_goal("c1", "goal-run", None, Some(""))
        .unwrap();
    let saved: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    assert_eq!(saved["status"], "ACTIVE");
    assert_eq!(saved["chatId"], "c1");
    assert_eq!(saved["wakeAt"], 0);
    assert_eq!(saved["extra"], "保留");
    assert!(saved["updatedAt"].as_i64().unwrap() >= 42);

    let mut reopened = Store::open_with_shared_files(root, Some(files)).unwrap();
    assert_eq!(reopened.goal().status, Status::Paused);
    reopened.clear_goal("goal-run").unwrap();
    let saved: Value = serde_json::from_slice(&fs::read(path).unwrap()).unwrap();
    assert_eq!(saved["text"], "");
    assert_eq!(saved["chatId"], "");
}

#[test]
fn shared_drawer_uses_compose_order_in_both_directions() {
    let files = files();
    seed_chat(&files);
    let root = files.join("rust-state");
    let path = files.join("drawer-order");
    fs::write(&path, r#"{"normal":["c1"]}"#).unwrap();
    let mut store = Store::open_with_shared_files(root.clone(), Some(files.clone())).unwrap();
    let chat = store.new_conversation("第二条").unwrap();
    let snapshot = store.preference_snapshot();
    assert_eq!(snapshot.order.normal, ["c1"]);
    store
        .preferences_move("move", &snapshot.stamp, "c1", &chat.id, false)
        .unwrap();
    let saved: Value = serde_json::from_slice(&fs::read(path).unwrap()).unwrap();
    assert_eq!(saved["normal"], json!(["c1", chat.id]));
    let store = Store::open_with_shared_files(root, Some(files)).unwrap();
    assert_eq!(
        store.preference_snapshot().order.normal,
        ["c1", chat.id.as_str()]
    );
}

#[test]
fn shared_reads_and_viewports_read_and_write_compose_preferences() {
    use crate::display::{BodyKind, ReadPosition, ViewSource};
    use crate::legacy_import::LegacyPreferenceValue;

    let files = files();
    seed_chat(&files);
    shared_preferences::save(
        &files.canonicalize().unwrap(),
        shared_preferences::READS,
        "completed_unread",
        Some(&LegacyPreferenceValue::StringSet(vec!["c1".into()])),
    )
    .unwrap();
    let bookmark = json!({"conversationId":"c1", "messageId":"a1", "followLatest":false});
    shared_preferences::save(
        &files.canonicalize().unwrap(),
        shared_preferences::VIEWPORTS,
        "position/c1",
        Some(&LegacyPreferenceValue::String(bookmark.to_string())),
    )
    .unwrap();
    let root = files.join("rust-state");
    let mut store = Store::open_with_shared_files(root.clone(), Some(files.clone())).unwrap();
    assert!(store.preferences_unread("c1"));
    assert_eq!(store.reading_positions[0].message_id, "a1");
    store.preferences_seen("c1");
    store.flush_preferences().unwrap();
    let source = ViewSource::Main {
        conversation_id: "c1".into(),
    };
    store
        .save_read_position(ReadPosition {
            source: source.clone(),
            message_id: "u1".into(),
            body_message_id: None,
            footer_from: None,
            body_kind: BodyKind::RowHeader,
            byte_offset: 0,
            pixel_offset: 19.0,
            follow_tail: false,
            legacy: None,
        })
        .unwrap();
    let wire = shared_preferences::read(
        &files.canonicalize().unwrap(),
        shared_preferences::VIEWPORTS,
    )
    .unwrap()
    .unwrap();
    let bookmark: Value = serde_json::from_str(&wire.strings["position/c1"]).unwrap();
    assert_eq!(bookmark["messageId"], "u1");
    let store = Store::open_with_shared_files(root, Some(files)).unwrap();
    assert!(!store.preferences_unread("c1"));
    assert_eq!(store.read_position(&source).unwrap().pixel_offset, 19.0);
}

#[test]
fn shared_schedules_writes_the_compose_board_and_preserves_extra_fields() {
    use crate::schedules::Operation;

    let files = files();
    let path = files.join("schedules.json");
    fs::write(
        &path,
        json!([{
            "id":"reminder", "title":"整理资料", "prompt":"列出待办",
            "kind":"every_seconds", "everySeconds":600, "nextAt":123000,
            "enabled":false, "future":"保留"
        }])
        .to_string(),
    )
    .unwrap();
    let root = files.join("rust-state");
    let mut store = Store::open_with_shared_files(root.clone(), Some(files.clone())).unwrap();
    store
        .mutate_schedule(Operation::Update {
            id: "reminder".into(),
            title: Some("归档资料".into()),
            prompt: None,
            timing: None,
        })
        .unwrap();
    let saved: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    assert_eq!(saved[0]["title"], "归档资料");
    assert_eq!(saved[0]["kind"], "every_seconds");
    assert_eq!(saved[0]["everySeconds"], 600);
    assert_eq!(saved[0]["future"], "保留");
    fs::write(&path, "[]").unwrap();
    let store = Store::open_with_shared_files(root, Some(files)).unwrap();
    assert!(store.schedules.is_empty());
}

#[test]
fn shared_plugins_uses_the_compose_directory_for_state_writes() {
    let files = files();
    let directory = files.join("plugins/sample");
    fs::create_dir_all(&directory).unwrap();
    fs::write(
        directory.join("manifest.json"),
        json!({
            "schema_version":1, "api_version":1, "id":"sample", "version":"1.0.0",
            "display_name":{"zh":"示例"}, "description":{"zh":"示例插件"}, "tools":[]
        })
        .to_string(),
    )
    .unwrap();
    fs::write(
        directory.join("state.json"),
        r#"{"enabled":true,"installed_at":1234}"#,
    )
    .unwrap();
    let root = files.join("rust-state");
    let mut store = Store::open_with_shared_files(root.clone(), Some(files.clone())).unwrap();
    assert_eq!(
        store.plugin_directory(),
        fs::canonicalize(files.join("plugins")).unwrap()
    );
    #[cfg(unix)]
    store.set_plugin_enabled("sample", false).unwrap();
    #[cfg(not(unix))]
    store
        .persist(
            &store.plugin_directory().join("sample/state.json"),
            &json!({"enabled":false, "installed_at":1234}),
            16 * 1024,
        )
        .unwrap();
    let saved: Value =
        serde_json::from_slice(&fs::read(directory.join("state.json")).unwrap()).unwrap();
    assert_eq!(saved, json!({"enabled":false, "installed_at":1234}));
    #[cfg(unix)]
    {
        let store = Store::open_with_shared_files(root, Some(files)).unwrap();
        assert!(!store.plugins().unwrap()[0].state.enabled);
    }
}

#[test]
fn shared_children_reads_compose_history_and_writes_rust_child_records() {
    use crate::subagents::{Record, Status};

    let files = files();
    seed_chat(&files);
    let directory = shared_children::directory(&files, "c1");
    fs::create_dir_all(&directory).unwrap();
    fs::write(
        directory.join("compose.json"),
        json!({
            "id":"compose", "parentChatId":"c1", "parentRunId":"r1", "name":"旧子任务",
            "task":"整理资料", "context":"", "config":{"approvalMode":"ASK", "timeoutSeconds":0},
            "status":"COMPLETED", "result":"完成", "messages":[
                {"id":"child-input", "role":"user", "text":"整理资料", "timestamp":10},
                {"id":"child-answer", "role":"assistant", "text":"完成", "timestamp":20}
            ]
        })
        .to_string(),
    )
    .unwrap();
    let root = files.join("rust-state");
    let mut store = Store::open_with_shared_files(root.clone(), Some(files.clone())).unwrap();
    assert_eq!(store.children_for("c1", None).unwrap()[0].detail, "完成");
    let source = store.child_source("c1", "compose").unwrap();
    use crate::display::HistorySource;
    assert_eq!(
        store
            .open_history(source, 1, 1, || false)
            .unwrap()
            .headers()
            .len(),
        2
    );

    let mut record = Record {
        id: "rust".into(),
        name: "新子任务".into(),
        status: Status::Running,
        messages: store.messages("c1").unwrap(),
        detail: String::new(),
        usage: Vec::new(),
        config: Some(Default::default()),
    };
    store.save_child("c1", "r1", record.clone()).unwrap();
    record.status = Status::Completed;
    store.save_child("c1", "r1", record.clone()).unwrap();
    store.save_child("c1", "r1", record).unwrap();
    let saved: Value =
        serde_json::from_slice(&fs::read(directory.join("rust.json")).unwrap()).unwrap();
    assert_eq!(saved["parentChatId"], "c1");
    assert_eq!(saved["parentRunId"], "r1");
    assert_eq!(saved["status"], "COMPLETED");
    assert_eq!(saved["config"]["approvalMode"], "ASK");
    assert_eq!(saved["messages"][1]["reasoning"], "思考");
    let mut store = Store::open_with_shared_files(root, Some(files)).unwrap();
    assert_eq!(store.children_for("c1", None).unwrap().len(), 2);
}

#[test]
fn shared_mcp_edits_the_compose_list_and_preserves_credentials() {
    let files = files();
    let path = files.join("mcp.json");
    fs::write(
        &path,
        json!([{
            "name":"测试服务", "transport":"http", "url":"https://example.invalid/mcp",
            "headers":"enc1:fixture", "future":42
        }])
        .to_string(),
    )
    .unwrap();
    let root = files.join("rust-state");
    let mut store = Store::open_with_shared_files(root.clone(), Some(files.clone())).unwrap();
    let id = store.mcp_servers[0].id.clone();
    store.set_mcp_enabled(&id, false).unwrap();
    let saved: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    assert_eq!(saved[0]["enabled"], false);
    assert_eq!(saved[0]["headers"], "enc1:fixture");
    assert_eq!(saved[0]["future"], 42);
    let config = store.mcp_servers[0].clone();
    store
        .save_mcp_server_with_secret_edits(config, SecretEdit::Keep, SecretEdit::Clear)
        .unwrap();
    let saved: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    assert_eq!(saved[0]["headers"], "");
    let mut reopened = Store::open_with_shared_files(root, Some(files)).unwrap();
    assert!(!reopened.mcp_servers[0].enabled);
    reopened.delete_mcp_server(&id).unwrap();
    assert_eq!(fs::read_to_string(path).unwrap(), "[]");
}

#[cfg(not(target_os = "android"))]
#[test]
fn shared_skills_edits_compose_documents_and_switches() {
    let files = files();
    let path = files.join("skills/sample/SKILL.md");
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(&path, "---\nname: Sample\n---\n正文").unwrap();
    fs::write(files.join("skill-state.json"), r#"["sample"]"#).unwrap();
    let root = files.join("rust-state");
    let mut store = Store::open_with_shared_files(root.clone(), Some(files.clone())).unwrap();
    let mut skill = store.skills()[0].clone();
    assert!(!skill.enabled);
    assert_eq!(skill.content, "正文");
    skill.content = "新正文".into();
    skill.enabled = true;
    store.save_skill(skill).unwrap();
    assert!(fs::read_to_string(path).unwrap().ends_with("新正文"));
    assert_eq!(
        fs::read_to_string(files.join("skill-state.json")).unwrap(),
        "[]"
    );
    let store = Store::open_with_shared_files(root, Some(files)).unwrap();
    assert!(store.skills()[0].enabled);
    assert_eq!(store.skills()[0].content, "新正文");
}

#[test]
fn shared_chat_approval_preserves_compose_metadata_and_log() {
    let files = files();
    seed_chat(&files);
    let path = files.join("chats/store/c1.meta");
    let before: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    let log = files.join("chats/store/c1.0.jsonl");
    let original_log = fs::read(&log).unwrap();
    let mut store =
        Store::open_with_shared_files(files.join("rust-state"), Some(files.clone())).unwrap();

    store
        .set_conversation_approval("c1", crate::subagent_configuration::ApprovalMode::Ask)
        .unwrap();

    let mut after: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    assert_eq!(after["approvalMode"], "ASK");
    assert_eq!(
        store.conversation("c1").unwrap().metadata.approval_mode,
        Some(crate::subagent_configuration::ApprovalMode::Ask)
    );
    assert!(after["updatedAt"].as_i64().unwrap() > before["updatedAt"].as_i64().unwrap());
    after["updatedAt"] = before["updatedAt"].clone();
    after.as_object_mut().unwrap().remove("approvalMode");
    assert_eq!(after, before);
    assert_eq!(fs::read(&log).unwrap(), original_log);
}

#[test]
fn shared_chat_reads_compose_and_writes_back_to_same_log() {
    let files = files();
    seed_chat(&files);
    let mut store =
        Store::open_with_shared_files(files.join("rust-state"), Some(files.clone())).unwrap();
    assert_eq!(store.conversations().len(), 1);
    let mut messages = store.messages("c1").unwrap();
    assert_eq!(messages.len(), 2);
    assert_eq!(messages[1].thinking, "思考");
    store
        .set_conversation_approval("c1", crate::subagent_configuration::ApprovalMode::Ask)
        .unwrap();
    messages[1].text = "新的回答".into();
    store.save_messages("c1", messages).unwrap();
    let meta: Value =
        serde_json::from_slice(&fs::read(files.join("chats/store/c1.meta")).unwrap()).unwrap();
    assert_eq!(meta["customField"], "保留");
    assert_eq!(meta["messageCount"], 2);
    assert_eq!(meta["approvalMode"], "ASK");
    assert_eq!(meta["totals"]["wallElapsedMs"], 50);
    let log = fs::read(files.join(format!("chats/store/c1.{}.jsonl", meta["gen"]))).unwrap();
    assert_eq!(log.len() as u64, meta["logBytes"].as_u64().unwrap());
    let written: Vec<Value> = log
        .split(|&b| b == b'\n')
        .filter(|line| !line.is_empty())
        .map(|line| serde_json::from_slice(line).unwrap())
        .collect();
    assert_eq!(written[1]["text"], "新的回答");
    assert_eq!(written[1]["reasoning"], "思考");
    assert_eq!(written[1]["promptTokens"], 12);
    let stamp = store.history_stamp_for(&crate::display::ViewSource::Main {
        conversation_id: "c1".into(),
    });
    let history = store
        .open_history(
            crate::display::ViewSource::Main {
                conversation_id: "c1".into(),
            },
            stamp.revision,
            stamp.projection_revision,
            || false,
        )
        .unwrap();
    assert_eq!(crate::display::HistorySource::headers(&history).len(), 2);
    let preview = store
        .console_tail_request("c1")
        .unwrap()
        .read(|| false)
        .unwrap();
    assert!(preview.contains("新的回答"));
    let mut reopened =
        Store::open_with_shared_files(files.join("rust-state"), Some(files.clone())).unwrap();
    assert_eq!(reopened.messages("c1").unwrap()[1].text, "新的回答");
    let chat = reopened.new_conversation("Rust 新会话").unwrap();
    assert!(
        files
            .join(format!("chats/store/{}.meta", chat.id))
            .is_file()
    );
    assert_eq!(
        fs::read_dir(files.join("rust-state/conversations"))
            .unwrap()
            .count(),
        0
    );
    let draft = files.join(format!("chats/{}.live.json", chat.id));
    let payload = files.join(format!("chats/{}.live-test.messages", chat.id));
    fs::write(&draft, "{}").unwrap();
    fs::write(&payload, "[]").unwrap();
    fs::write(files.join("chats/last-opened"), &chat.id).unwrap();
    reopened.delete_conversation(&chat.id).unwrap();
    assert!(!files.join(format!("chats/store/{}.meta", chat.id)).exists());
    assert!(!draft.exists());
    assert!(!payload.exists());
    assert!(!files.join("chats/last-opened").exists());
}

#[test]
fn shared_memory_keeps_original_keys_and_settings_share_preferences() {
    let files = files();
    fs::write(
        files.join("memory.json"),
        r#"{"偏好":{"value":"中文","updatedAt":10},"旧键":"旧值"}"#,
    )
    .unwrap();
    let mut store =
        Store::open_with_shared_files(files.join("rust-state"), Some(files.clone())).unwrap();
    let note = store
        .memories()
        .into_iter()
        .find(|item| item.id == "偏好")
        .unwrap();
    store
        .save_memory(Memory {
            text: "简体中文".into(),
            ..note
        })
        .unwrap();
    let mut settings = store.settings.clone();
    settings.model = "shared-model".into();
    settings.behavior.font_scale = 2;
    settings.behavior.show_timestamps = false;
    settings.memory_context = true;
    settings.assistants.push(crate::configuration::Assistant {
        id: "shared-assistant".into(),
        name: "共用助手".into(),
        system_prompt: "中文回答".into(),
        ..Default::default()
    });
    store.save_settings(settings).unwrap();
    let reopened =
        Store::open_with_shared_files(files.join("rust-state"), Some(files.clone())).unwrap();
    assert_eq!(reopened.settings.model, "shared-model");
    assert_eq!(reopened.settings.behavior.font_scale, 2);
    assert!(!reopened.settings.behavior.show_timestamps);
    assert!(reopened.settings.memory_context);
    assert_eq!(reopened.settings.assistants[0].system_prompt, "中文回答");
    let value: Value =
        serde_json::from_slice(&fs::read(files.join("memory.json")).unwrap()).unwrap();
    assert_eq!(value["偏好"]["value"], "简体中文");
    assert!(!files.join("rust-state/memories.json").exists());
    let prefs = fs::read(files.join("datastore/Suixing_settings.preferences_pb")).unwrap();
    let (compose, _) = crate::legacy_import::settings_preferences(&prefs).unwrap();
    let value: Value = serde_json::from_str(&compose.unwrap()).unwrap();
    assert_eq!(value["model"], "shared-model");
    assert_eq!(value["assistants"][0]["systemPrompt"], "中文回答");
    assert_eq!(value["assistants"][0]["providerId"], "");
    assert_eq!(value["fontScale"], 2);
}

#[test]
fn shared_chat_appends_without_replacing_committed_generation() {
    let files = files();
    seed_chat(&files);
    let mut store =
        Store::open_with_shared_files(files.join("rust-state"), Some(files.clone())).unwrap();
    let mut messages = store.messages("c1").unwrap();
    messages.push(Message {
        id: "u2".into(),
        role: "user".into(),
        text: "继续".into(),
        thinking: String::new(),
        complete: true,
        tool_calls: Vec::new(),
        tool_call_id: None,
        metadata: Default::default(),
    });
    store.save_messages("c1", messages).unwrap();
    let meta: Value =
        serde_json::from_slice(&fs::read(files.join("chats/store/c1.meta")).unwrap()).unwrap();
    assert_eq!(meta["gen"], 0);
    assert_eq!(meta["messageCount"], 3);
    assert_eq!(store.messages("c1").unwrap().len(), 3);
    let log = fs::read(files.join("chats/store/c1.0.jsonl")).unwrap();
    assert_eq!(log.len() as u64, meta["logBytes"].as_u64().unwrap());
    assert!(!String::from_utf8(log).unwrap().contains("uncommitted tail"));
}
