#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn diagonal_start_waits_for_a_clear_lead() {
        assert_eq!(drag_axis(10.0, 12.0, 8.0), DragAxis::Pending);
        assert_eq!(drag_axis(-18.0, 12.0, 8.0), DragAxis::Horizontal);
        assert_eq!(drag_axis(10.0, -18.0, 8.0), DragAxis::Vertical);
    }

    #[test]
    fn anchor_overshoot_does_not_delay_reversal() {
        let offset = drag_offset(0.0, 600.0, 0.0, 300.0);
        assert_eq!(drag_offset(offset, -10.0, 0.0, 300.0), 290.0);
        let offset = drag_offset(0.0, -600.0, -300.0, 0.0);
        assert_eq!(drag_offset(offset, 10.0, -300.0, 0.0), -290.0);
    }

    #[test]
    fn release_uses_quarter_travel_velocity_and_the_interrupted_position() {
        assert!(!release_open(0.25, 0.0, 0.0));
        assert!(release_open(0.75, 0.0, 1.0));
        assert!(release_open(0.26, 0.0, 0.0));
        assert!(!release_open(0.74, 0.0, 1.0));
        assert!(!release_open(0.9, -801.0, 0.0));
        assert!(release_open(0.41, 0.0, 0.4));
        assert!(!release_open(0.59, 0.0, 0.6));
        assert!(release_open(0.5, 0.0, 0.5));
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum DragAxis {
    Pending = 0,
    Horizontal = 1,
    Vertical = 2,
}

pub const INPUT_GAIN: f32 = 1.2;

#[unsafe(export_name = "sx_drawer_drag_axis")]
pub extern "C" fn drag_axis(horizontal: f32, vertical: f32, slop: f32) -> DragAxis {
    let x = horizontal.abs();
    let y = vertical.abs();
    let lead = slop / 2.0;
    if x > slop.max(y + lead) {
        DragAxis::Horizontal
    } else if y > (slop * 2.0).max(x + lead) {
        DragAxis::Vertical
    } else {
        DragAxis::Pending
    }
}

#[unsafe(export_name = "sx_drawer_drag_offset")]
pub extern "C" fn drag_offset(offset: f32, delta: f32, min: f32, max: f32) -> f32 {
    (offset + delta).max(min).min(max)
}

#[unsafe(export_name = "sx_drawer_release_open")]
pub extern "C" fn release_open(progress: f32, velocity_px: f32, start: f32) -> bool {
    if velocity_px.abs() > 800.0 {
        velocity_px > 0.0
    } else if start > 0.0 && start < 1.0 {
        if progress == start {
            progress >= 0.5
        } else {
            progress > start
        }
    } else if start == 1.0 {
        progress >= 0.75
    } else {
        progress > 0.25
    }
}
