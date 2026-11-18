use super::*;
use crate::state::{SettingsSnapshot, SettingsStamp};
pub(super) fn publish_saved(store: &Store, publish: &Publisher, mcp: Option<&McpRuntime>) {
    publish_state(store, publish);
    publish_tool_catalog(store, mcp, publish);
}
pub(super) fn publish_state(store: &Store, publish: &Publisher) {
    if let Some(owner) = ROUTING.get() {
        let _ = owner.publish_saved(store.settings.execution.clone());
    }
    publish.emit(Event::SettingsSaved(store.settings.clone()));
    publish.emit(Event::SettingsSnapshot(store.settings_snapshot()));
}
pub(super) fn finish(
    store: &Store,
    publish: &Publisher,
    mcp: Option<&McpRuntime>,
    request_id: String,
    expected: SettingsStamp,
    result: Result<SettingsSnapshot, String>,
) {
    if result.is_ok() {
        if let Some(owner) = ROUTING.get() {
            let _ = owner.publish_saved(store.settings.execution.clone());
        }
        publish_tool_catalog(store, mcp, publish);
    }
    // Failure still returns the actual current version; the UI retains its base.
    publish.emit(Event::SettingsSnapshot(store.settings_snapshot()));
    publish.emit(Event::SettingsMutationFinished {
        request_id,
        expected,
        result,
    });
}
