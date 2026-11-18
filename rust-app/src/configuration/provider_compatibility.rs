use super::*;
impl Settings {
    /// Match AppSettings.withProviders: deleting the last provider explicitly
    /// sets providersInitialized, which prevents revival from the old endpoint.
    pub(crate) fn seed_legacy_provider(&mut self) {
        if !self.providers.is_empty()
            || self.behavior.providers_initialized
            || self.endpoint.trim().is_empty()
        {
            return;
        }
        let models: Vec<_> = (!self.model.trim().is_empty())
            .then(|| self.model.clone())
            .into_iter()
            .collect();
        self.providers.push(Provider {
            id: "default".into(),
            name: "默认".into(),
            endpoint: self.endpoint.clone(),
            protocol: self.protocol,
            api_key: self.api_key.clone(),
            selected_model: self.model.clone(),
            models: models.clone(),
            enabled_models: Some(models),
            ..Default::default()
        });
    }
}
