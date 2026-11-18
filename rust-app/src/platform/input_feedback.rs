//! Visual feedback for approved shell input, using the existing overlay owner.
//! No command is executed, replayed, or recorded by this adapter.
use super::automation::Target;
use crate::shell_runner::FrozenRequest;

pub(crate) struct Guard {
    #[cfg(target_os = "android")]
    operation: Option<super::permissions::OperationKey>,
}
impl Drop for Guard {
    fn drop(&mut self) {
        #[cfg(target_os = "android")]
        if let Some(operation) = self.operation.take() {
            let _ = super::automation::overlay::set_driving(operation, false);
        }
    }
}

#[cfg(not(target_os = "android"))]
pub(crate) fn begin(_request: &FrozenRequest, _target: Option<&Target>) -> Guard {
    Guard {}
}

#[cfg(target_os = "android")]
pub(crate) fn begin(request: &FrozenRequest, fixed: Option<&Target>) -> Guard {
    use super::{
        automation::{self, overlay},
        permissions::OperationKey,
    };
    use std::sync::OnceLock;
    struct Patterns {
        input: regex::Regex,
        handoff: regex::Regex,
    }
    static PATTERNS: OnceLock<Option<Patterns>> = OnceLock::new();
    let patterns = PATTERNS.get_or_init(|| Some(Patterns {
        input: regex::Regex::new(r"\binput(?P<options>(?:\s+-\S+\s+\S+)*)\s+(?P<action>tap|longpress|draganddrop|swipe|text|keyevent)\s+(?P<arguments>[^;\r\n]+)").ok()?,
        handoff: regex::Regex::new(r"\b(?:am\s+start(?:-activity)?|monkey)\b").ok()?,
    }));
    let Some(patterns) = patterns else {
        return Guard { operation: None };
    };
    let command = request.argv.join(" ");
    let input = patterns.input.captures(&command);
    if input.is_none() && !patterns.handoff.is_match(&command) {
        return Guard { operation: None };
    }
    let display = input
        .as_ref()
        .and_then(|capture| {
            let options = capture["options"].split_whitespace().collect::<Vec<_>>();
            options
                .windows(2)
                .find(|pair| pair[0] == "-d")
                .and_then(|pair| pair[1].parse::<i32>().ok())
        })
        .unwrap_or(0);
    // The full-screen overlay belongs to the primary display. A different
    // pinned display must never draw a primary-screen touch ring.
    if display != 0 || fixed.is_some_and(|target| target.display_id != 0) {
        return Guard { operation: None };
    }
    let target = match fixed
        .cloned()
        .map(Ok)
        .unwrap_or_else(|| automation::target(0))
    {
        Ok(target) if automation::verify_target(&target).is_ok() => target,
        _ => return Guard { operation: None },
    };
    let operation = OperationKey {
        id: crate::controller::unique_id(),
        conversation_id: None,
        generation: 1,
    };
    let mut points = Vec::new();
    let mut duration = 0;
    if let Some(capture) = input {
        let values = capture["arguments"]
            .split_whitespace()
            .take(5)
            .map(|value| value.parse::<f32>().ok())
            .collect::<Vec<_>>();
        let count = match &capture["action"] {
            "tap" | "longpress" | "draganddrop" => 2,
            "swipe" => 4,
            _ => 0,
        };
        if count > 0
            && values.len() >= count
            && values[..count]
                .iter()
                .all(|value| value.is_some_and(f32::is_finite))
        {
            let first = (values[0].unwrap(), values[1].unwrap());
            points.push(first);
            if count == 4 {
                let last = (values[2].unwrap(), values[3].unwrap());
                if first != last {
                    points.push(last);
                }
                duration = values
                    .get(4)
                    .copied()
                    .flatten()
                    .unwrap_or(300.)
                    .clamp(0., 5000.) as u64;
            } else if &capture["action"] != "tap" {
                duration = 800;
            }
        }
    }
    if points
        .iter()
        .any(|&(x, y)| x < 0. || y < 0. || x >= target.width as f32 || y >= target.height as f32)
    {
        points.clear();
    }
    let active = overlay::set_driving(operation.clone(), true).is_ok();
    overlay::flash(operation.clone(), target, points, duration);
    Guard {
        operation: active.then_some(operation),
    }
}
