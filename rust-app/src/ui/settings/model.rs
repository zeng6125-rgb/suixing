use super::row;
use crate::state::Settings;
use eframe::egui::{self, RichText};

/// A discrete zero stop is the only omission control. Last slider values remain
/// in the owning BehaviorSettings even when omission is selected.
pub(super) fn sampling(ui: &mut egui::Ui, settings: &mut Settings) {
    let selected = settings.active_provider_id.as_deref();
    if let Some(provider) = settings
        .providers
        .iter_mut()
        .find(|provider| Some(provider.id.as_str()) == selected && provider.own_sampling)
    {
        optional_sampling(
            ui,
            &provider.id,
            &mut provider.temperature,
            &mut provider.top_p,
            &mut settings.behavior,
        );
        let behavior = settings.behavior.clone();
        behavior.apply_sampling(settings);
        return;
    }
    let behavior = &mut settings.behavior;
    let mut temperature_stop = if behavior.send_temperature {
        (behavior.sampling_temperature * 10.0)
            .round()
            .clamp(0.0, 20.0) as i32
            + 1
    } else {
        0
    };
    let temperature_label = if temperature_stop == 0 {
        "温度 · 不发送".into()
    } else {
        format!("温度 {:.1}", behavior.sampling_temperature)
    };
    if ui
        .add(egui::Slider::new(&mut temperature_stop, 0..=21).text(temperature_label))
        .changed()
    {
        behavior.send_temperature = temperature_stop > 0;
        if temperature_stop > 0 {
            behavior.sampling_temperature = f64::from(temperature_stop - 1) / 10.0;
        }
    }
    let mut top_p_stop = if behavior.send_top_p {
        (behavior.sampling_top_p * 10.0).round().clamp(1.0, 10.0) as i32
    } else {
        0
    };
    let top_p_label = if top_p_stop == 0 {
        "Top P · 不发送".into()
    } else {
        format!("Top P {:.2}", behavior.sampling_top_p)
    };
    if ui
        .add(egui::Slider::new(&mut top_p_stop, 0..=10).text(top_p_label))
        .changed()
    {
        behavior.send_top_p = top_p_stop > 0;
        if top_p_stop > 0 {
            behavior.sampling_top_p = f64::from(top_p_stop) / 10.0;
        }
    }
    settings.temperature = behavior
        .send_temperature
        .then_some(behavior.sampling_temperature);
    settings.top_p = behavior.send_top_p.then_some(behavior.sampling_top_p);
    ui.label(
        RichText::new("最左为不发送；温度其余档为0.0–2.0，Top P其余档为0.1–1.0。")
            .small()
            .weak(),
    );
}

pub(super) fn context_and_behavior(ui: &mut egui::Ui, settings: &mut Settings) {
    row(ui, &mut settings.stream, "流式输出", "接收模型的实时输出。");
    row(
        ui,
        &mut settings.behavior.image_input,
        "图片输入",
        "允许把图片发送给视觉模型。",
    );
    let model = settings
        .active_provider_id
        .as_ref()
        .and_then(|id| {
            settings
                .providers
                .iter()
                .find(|provider| &provider.id == id)
        })
        .map(|provider| provider.selected_model.clone())
        .unwrap_or_else(|| settings.model.clone());
    if !model.is_empty() {
        let mut context = settings.model_contexts.get(&model).copied().unwrap_or(0);
        ui.horizontal_wrapped(|ui| {
            ui.label(format!("{model} 上下文窗口（0使用默认）"));
            if ui
                .add(
                    egui::DragValue::new(&mut context)
                        .range(0..=2_000_000)
                        .speed(1000),
                )
                .changed()
            {
                settings.model_contexts.insert(model.clone(), context);
            }
        });
    }
}

/// The original global send flags apply to every provider. A provider's null
/// inherits the global value; moving to "不发送" retains its saved number.
pub(super) fn optional_sampling(
    ui: &mut egui::Ui,
    id: impl std::hash::Hash + std::fmt::Debug,
    temperature: &mut Option<f64>,
    top_p: &mut Option<f64>,
    behavior: &mut crate::settings_policy::BehaviorSettings,
) {
    ui.push_id(id, |ui| {
        let retained_temp = temperature.unwrap_or(behavior.sampling_temperature);
        let retained_p = top_p.unwrap_or(behavior.sampling_top_p);
        let mut t = if behavior.send_temperature {
            (retained_temp * 10.0).round().clamp(0.0, 20.0) as i32 + 1
        } else {
            0
        };
        let mut p = if behavior.send_top_p {
            (retained_p * 10.0).round().clamp(1.0, 10.0) as i32
        } else {
            0
        };
        let temperature_label = if t == 0 {
            "温度 · 不发送"
        } else {
            "温度"
        };
        let p_label = if p == 0 {
            "Top P · 不发送"
        } else {
            "Top P"
        };
        if ui
            .add(egui::Slider::new(&mut t, 0..=21).text(temperature_label))
            .changed()
        {
            behavior.send_temperature = t > 0;
            if t > 0 {
                *temperature = Some(f64::from(t - 1) / 10.0);
            }
        }
        if ui
            .add(egui::Slider::new(&mut p, 0..=10).text(p_label))
            .changed()
        {
            behavior.send_top_p = p > 0;
            if p > 0 {
                *top_p = Some(f64::from(p) / 10.0);
            }
        }
    });
}
