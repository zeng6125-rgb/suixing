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

    #[test]
    fn only_a_fling_overrides_the_distance_target() {
        assert!(!settle_open(false, 399.0));
        assert!(settle_open(true, -399.0));
        assert!(settle_open(false, 400.0));
        assert!(!settle_open(true, -400.0));
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

/// Geometry for the Compose drawer, stored in a caller-owned direct buffer.
#[repr(C)]
pub struct DrawerDrag {
    start: f32,
    width: f32,
    min: f32,
    max: f32,
    offset: f32,
    stroke_start: f32,
    furthest_travel: f32,
}

const _: () = assert!(std::mem::size_of::<DrawerDrag>() == 28);

impl DrawerDrag {
    fn new(start: f32, width: f32) -> Self {
        let start = start.clamp(0.0, 1.0);
        Self {
            start,
            width,
            min: -start * width,
            max: (1.0 - start) * width,
            offset: 0.0,
            stroke_start: 0.0,
            furthest_travel: 0.0,
        }
    }

    fn progress(&self) -> f32 {
        self.start + self.offset / self.width
    }

    fn advance(&mut self, delta: f32) -> f32 {
        self.offset = drag_offset(self.offset, delta, self.min, self.max);
        if self.offset == self.min || self.offset == self.max {
            self.stroke_start = self.offset;
            self.furthest_travel = 0.0;
        } else {
            self.furthest_travel = self
                .furthest_travel
                .max((self.offset - self.stroke_start).abs());
        }
        self.progress()
    }

    fn opens_by_distance(&self) -> bool {
        let travel = (self.offset - self.stroke_start).abs();
        let quarter_width = self.width * 0.25;
        if travel > quarter_width && self.furthest_travel - travel < quarter_width {
            if self.stroke_start == self.min {
                return true;
            }
            if self.stroke_start == self.max {
                return false;
            }
        }
        self.progress() > 0.5
    }
}

/// # Safety
/// `state` must point to writable storage for one `DrawerDrag`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_drawer_drag_begin(state: *mut DrawerDrag, start: f32, width: f32) {
    // SAFETY: the JNI caller supplies writable storage for a DrawerDrag.
    unsafe { state.write_unaligned(DrawerDrag::new(start, width)) };
}

/// # Safety
/// `state` must point to initialized, exclusively writable `DrawerDrag` storage.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_drawer_drag_advance(state: *mut DrawerDrag, delta: f32) -> f32 {
    // SAFETY: JNI owns this buffer exclusively and begin initialized it.
    let mut drag = unsafe { state.read_unaligned() };
    let progress = drag.advance(delta);
    // SAFETY: the same buffer remains writable for the duration of this call.
    unsafe { state.write_unaligned(drag) };
    progress
}

/// # Safety
/// `state` must point to an initialized `DrawerDrag`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_drawer_drag_opens(state: *const DrawerDrag) -> bool {
    // SAFETY: JNI passes the initialized buffer owned by the active drag.
    unsafe { state.read_unaligned() }.opens_by_distance()
}

const VELOCITY_HISTORY: usize = 20;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct VelocitySample {
    time_ms: i64,
    x: f32,
    _padding: u32,
}

const _: () = assert!(std::mem::size_of::<VelocitySample>() == 16);

// The impulse estimator for absolute samples follows AndroidX VelocityTracker1D (Apache-2.0).
// https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/input/pointer/util/VelocityTracker.kt
fn impulse_velocity(
    sample: impl Fn(usize) -> VelocitySample,
    next: usize,
    count: usize,
    maximum: f32,
) -> f32 {
    if count < 2 {
        return 0.0;
    }
    let at_age = |age: usize| sample((next + VELOCITY_HISTORY - 1 - age) % VELOCITY_HISTORY);
    let newest = at_age(0);
    let oldest_age = (1..count)
        .take_while(|&age| newest.time_ms.abs_diff(at_age(age).time_ms) <= 40)
        .count();
    let to_velocity = |work: f32| (2.0 * work.abs()).sqrt().copysign(work);
    let mut work = 0.0;
    let mut older = at_age(oldest_age);
    for age in (0..oldest_age).rev() {
        let newer = at_age(age);
        if newer.time_ms != older.time_ms {
            let speed = (newer.x - older.x) / (newer.time_ms - older.time_ms) as f32;
            work += (speed - to_velocity(work)) * speed.abs();
            if age + 1 == oldest_age {
                work *= 0.5;
            }
        }
        older = newer;
    }
    let velocity = to_velocity(work) * 1000.0;
    if velocity == 0.0 || velocity.is_nan() {
        0.0
    } else {
        velocity.clamp(-maximum, maximum)
    }
}

/// # Safety
/// `samples` must hold 20 slots, with the `count` entries before `next` initialized.
/// `next` must be below 20 and `count` at most 20.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_drawer_velocity(
    samples: *const VelocitySample,
    next: usize,
    count: usize,
    maximum: f32,
) -> f32 {
    // SAFETY: the JNI caller supplies all 20 slots. Only the populated ring entries are read.
    impulse_velocity(
        |index| unsafe { samples.add(index).read_unaligned() },
        next,
        count,
        maximum,
    )
}

#[unsafe(export_name = "sx_drawer_settle_open")]
pub extern "C" fn settle_open(open_by_distance: bool, velocity_dp_per_second: f32) -> bool {
    if velocity_dp_per_second.abs() >= 400.0 {
        velocity_dp_per_second > 0.0
    } else {
        open_by_distance
    }
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
