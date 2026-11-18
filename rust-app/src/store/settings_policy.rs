//! Integration fragment. Root first adds Settings.behavior/Settings.execution
//! with serde(flatten), then declares this submodule in Store. Existing atomic
//! SettingsDocument/StoredSettingsSecrets remain the only settings transaction.
use super::*;
use crate::settings_policy::{BehaviorSettings, ExecutionSettings};
impl Store {
    pub fn save_behavior_settings(&mut self, behavior: BehaviorSettings) -> Result<(), String> {
        behavior.validate()?;
        let mut settings = self.settings.clone();
        behavior.apply_sampling(&mut settings);
        settings.behavior = behavior;
        // Explicit Keep for secrets whose plaintext is unavailable. Do not turn
        // an empty visible edit into Clear or rewrite unreadable old ciphertext.
        self.save_settings_with_secret_edits(settings, SettingsSecretEdits::default())
    }
    pub fn save_execution_settings(
        &mut self,
        mut execution: ExecutionSettings,
    ) -> Result<(), String> {
        execution.validate()?;
        // Ordinary policy edits cannot replace the actor's actual probe facts
        // with an older UI snapshot. Only save_root_capability_fact owns history.
        execution.root_available = self.settings.execution.root_available;
        execution.root_checked_at = self.settings.execution.root_checked_at;
        let mut settings = self.settings.clone();
        settings.execution = execution;
        self.save_settings_with_secret_edits(settings, SettingsSecretEdits::default())
    }
    pub fn save_root_capability_fact(
        &mut self,
        available: bool,
        checked_at: i64,
    ) -> Result<(), String> {
        if checked_at <= 0 {
            return Err("Root检测记录缺少实际检测时间".into());
        }
        let mut settings = self.settings.clone();
        if checked_at < settings.execution.root_checked_at {
            return Err("Root检测历史结果已过期，未覆盖新记录".into());
        }
        settings.execution.root_available = available;
        settings.execution.root_checked_at = checked_at;
        self.save_settings_with_secret_edits(settings, SettingsSecretEdits::default())
    }
    pub fn reconcile_behavior_document(
        &mut self,
        raw_settings: &serde_json::Value,
    ) -> Result<(), String> {
        // Boot/import actor-only helper. It changes the loaded representation in
        // memory; root's established migration transaction owns publication.
        let mut behavior = BehaviorSettings::from_rust_document(raw_settings, &self.settings)?;
        let old_preferences: Option<crate::platform_tools::automation_page::Preferences> =
            optional_json(
                &self.root,
                &self.root.join("automation-preferences.json"),
                64 * 1024,
            )?;
        if let Some(preferences) = old_preferences {
            if raw_settings.get("edgeLight").is_none() {
                behavior.edge_light = preferences.edge_enabled;
            }
            if raw_settings.get("edgeLightInForeground").is_none() {
                behavior.edge_light_in_foreground = preferences.edge_in_foreground;
            }
            if behavior.edge_color.is_none() {
                behavior.edge_color = Some(preferences.edge_color);
            }
        }
        let execution = ExecutionSettings::from_legacy(raw_settings)?;
        behavior.apply_sampling(&mut self.settings);
        self.settings.behavior = behavior;
        self.settings.execution = execution;
        Ok(())
    }
    pub fn load_complete_automation_preferences(
        &self,
    ) -> Result<crate::platform_tools::automation_page::Preferences, String> {
        let path = self.root.join("automation-preferences.json");
        let existing: Option<crate::platform_tools::automation_page::Preferences> =
            optional_json(&self.root, &path, 64 * 1024)?;
        // The old whole Settings file owns the edge flags. Only the existing
        // automation document's color is reused; there is no unrelated rewrite.
        let color = self
            .settings
            .behavior
            .edge_color
            .or_else(|| existing.map(|preferences| preferences.edge_color))
            .unwrap_or_else(|| {
                crate::platform_tools::automation_page::Preferences::default().edge_color
            });
        Ok(self.settings.behavior.automation_preferences(color))
    }
    pub fn save_complete_automation_preferences(
        &mut self,
        preferences: &crate::platform_tools::automation_page::Preferences,
    ) -> Result<(), String> {
        let mut settings = self.settings.clone();
        settings.behavior.apply_automation_preferences(preferences);
        // Both flags and existing color now commit in the one Settings document.
        self.save_settings_with_secret_edits(settings, SettingsSecretEdits::default())
    }
}
