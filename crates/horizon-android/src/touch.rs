//! Touch overlay: virtual controls that map `winit` touch events into
//! [`InputAction`]s — the same vocabulary `horizon-client` uses for keyboard,
//! mouse and controller bindings.
//!
//! # Design
//! * Landscape-first layout, proportional to the smaller screen axis so it
//!   adapts from 20:9 phones to tablets. Coordinates are *logical* pixels
//!   (physical px / `scale_factor`).
//! * Fixed-base virtual joystick on the left (classic Bedrock-style), a large
//!   look-drag zone on the right, action buttons clustered where thumbs rest,
//!   and a hotbar strip along the top edge.
//! * The module produces *input intent only*. It never touches wgpu — the
//!   renderer asks for [`TouchOverlay::geometry`] when it wants to draw the
//!   overlay, and `horizon-client` consumes [`InputAction`]s at its existing
//!   input-binding layer (see PORTING.md § "Wiring the overlay into the
//!   client").
//!
//! # Wiring
//! ```ignore
//! WindowEvent::Touch(t) => {
//!     let actions = overlay.handle(&t, window.scale_factor());
//!     for action in actions { client.input(action); }
//! }
//! ```

use std::collections::HashMap;

use winit::event::{Touch, TouchPhase};

/// Intent produced by the overlay. Maps 1:1 onto the actions that
/// `horizon-client` binds from keyboard/mouse/controller input.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum InputAction {
    /// Analog movement: `x` = strafe right, `y` = forward, both in
    /// `[-1.0, 1.0]`. Emitted continuously while the joystick is engaged.
    Move {
        x: f32,
        y: f32,
    },
    /// Camera delta in normalized screen units (already sensitivity-adjusted
    /// at the consumer side). `dx` = yaw right, `dy` = pitch down.
    Look {
        dx: f32,
        dy: f32,
    },
    Jump,
    AttackBegin,
    AttackEnd,
    UseBegin,
    UseEnd,
    PickBlock,
    SneakBegin,
    SneakEnd,
    SprintBegin,
    SprintEnd,
    /// Hotbar slot 0..=8 (on-screen index, not 1-based key).
    HotbarSlot(u8),
    Inventory,
    Pause,
    Chat,
    ZoomBegin,
    ZoomEnd,
    /// Long-press on the look zone — bedrock "tap to place" is intentionally
    /// NOT auto-mapped; see PORTING.md § "Tap-to-place trade-offs".
    ToggleDebug,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Circle {
    pub x: f32,
    pub y: f32,
    pub r: f32,
}

impl Circle {
    fn contains(&self, px: f32, py: f32, tolerance: f32) -> bool {
        let dx = px - self.x;
        let dy = py - self.y;
        dx * dx + dy * dy <= (self.r * tolerance) * (self.r * tolerance)
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Rect {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

/// Button identity — also drives drawing via [`OverlayShape`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ButtonKind {
    Jump,
    Attack,
    Use,
    Pick,
    Sneak,
    Sprint,
    Inventory,
    Chat,
    Pause,
    Zoom,
}

impl ButtonKind {
    /// `true` for shoulder-style buttons that are comfortable to hold.
    pub fn holdable(self) -> bool {
        matches!(
            self,
            ButtonKind::Attack
                | ButtonKind::Use
                | ButtonKind::Sneak
                | ButtonKind::Sprint
                | ButtonKind::Zoom
        )
    }
}

/// Proportional screen layout. Recomputed on every resize (cheap).
#[derive(Debug, Clone)]
pub struct OverlayLayout {
    /// Joystick base (fixed position, Bedrock-style).
    pub move_stick: Circle,
    /// Visual radius of the joystick knob.
    pub knob_radius: f32,
    /// Everything right of `look_zone.x` and below the hotbar is a drag area.
    pub look_zone: Rect,
    pub hotbar: Rect,
    pub buttons: HashMap<ButtonKind, Circle>,
}

impl OverlayLayout {
    /// Build a layout for a window of `width` x `height` **logical** pixels.
    pub fn for_size(width: f32, height: f32) -> Self {
        // Reference length: on a landscape phone this is the height, on a
        // portrait phone the width — keeps the overlay usable in both.
        let s = width.min(height);
        let mut buttons = HashMap::new();

        // Left: fixed-base movement joystick.
        let move_stick = Circle {
            x: width * 0.17,
            y: height * 0.72,
            r: s * 0.115,
        };

        // Right: thumb cluster. Attack/use at the natural resting point,
        // jump above-right, sneak/sprint smaller and offset.
        buttons.insert(
            ButtonKind::Attack,
            Circle {
                x: width * 0.86,
                y: height * 0.70,
                r: s * 0.070,
            },
        );
        buttons.insert(
            ButtonKind::Use,
            Circle {
                x: width * 0.775,
                y: height * 0.79,
                r: s * 0.058,
            },
        );
        buttons.insert(
            ButtonKind::Jump,
            Circle {
                x: width * 0.905,
                y: height * 0.545,
                r: s * 0.066,
            },
        );
        buttons.insert(
            ButtonKind::Sneak,
            Circle {
                x: width * 0.73,
                y: height * 0.615,
                r: s * 0.047,
            },
        );
        buttons.insert(
            ButtonKind::Pick,
            Circle {
                x: width * 0.665,
                y: height * 0.76,
                r: s * 0.042,
            },
        );
        buttons.insert(
            ButtonKind::Sprint,
            Circle {
                x: width * 0.055,
                y: height * 0.545,
                r: s * 0.042,
            },
        );
        buttons.insert(
            ButtonKind::Inventory,
            Circle {
                x: width * 0.945,
                y: height * 0.36,
                r: s * 0.040,
            },
        );
        buttons.insert(
            ButtonKind::Chat,
            Circle {
                x: width * 0.865,
                y: height * 0.335,
                r: s * 0.036,
            },
        );
        buttons.insert(
            ButtonKind::Pause,
            Circle {
                x: width * 0.035,
                y: height * 0.055,
                r: s * 0.038,
            },
        );
        buttons.insert(
            ButtonKind::Zoom,
            Circle {
                x: width * 0.055,
                y: height * 0.40,
                r: s * 0.040,
            },
        );

        let hotbar = Rect {
            x: width * 0.5 - s * 0.55,
            y: height * 0.012,
            w: s * 1.10,
            h: s * 0.062,
        };

        let look_zone = Rect {
            x: width * 0.42,
            y: height * 0.12,
            w: width * 0.58,
            h: height * 0.88,
        };

        Self {
            move_stick,
            knob_radius: s * 0.048,
            look_zone,
            hotbar,
            buttons,
        }
    }
}

/// What a given touch id is currently doing.
#[derive(Debug, Clone, Copy)]
enum Tracked {
    /// Engaged joystick; last emitted stick value.
    Joystick {
        x: f32,
        y: f32,
    },
    /// Look drag; last position for delta computation.
    Look {
        last_x: f32,
        last_y: f32,
    },
    Button {
        kind: ButtonKind,
    },
    /// Touch consumed by the hotbar.
    Hotbar,
}

/// One drawable overlay element, produced by [`TouchOverlay::geometry`].
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct OverlayShape {
    pub x: f32,
    pub y: f32,
    pub r: f32,
    /// Fill color, straight RGBA. `a = 0` means "ring only".
    pub color: [f32; 4],
}

/// State machine over active touches.
#[derive(Debug, Default)]
pub struct TouchOverlay {
    layout: Option<OverlayLayout>,
    tracked: HashMap<u64, Tracked>,
    selected_slot: u8,
    /// Debug counters surfaced by the demo HUD.
    pub touch_count: usize,
}

impl TouchOverlay {
    pub fn new() -> Self {
        Self::default()
    }

    /// Call on resize with the window size in **physical** pixels plus its
    /// scale factor.
    pub fn resize(&mut self, width_px: u32, height_px: u32, scale_factor: f64) {
        let sf = scale_factor as f32;
        let w = (width_px as f32 / sf).max(1.0);
        let h = (height_px as f32 / sf).max(1.0);
        self.layout = Some(OverlayLayout::for_size(w, h));
        // A resize between orientations can invalidate active touches.
        self.tracked.clear();
    }

    /// Feed a `WindowEvent::Touch`; returns the actions it produced.
    pub fn handle(&mut self, touch: &Touch, scale_factor: f64) -> Vec<InputAction> {
        let sf = scale_factor as f32;
        self.on(
            touch.id,
            touch.phase,
            touch.location.x as f32 / sf,
            touch.location.y as f32 / sf,
        )
    }

    /// Core state machine, in logical pixels. Split out from [`handle`] so it
    /// is unit-testable without constructing opaque winit event types.
    fn on(&mut self, id: u64, phase: TouchPhase, px: f32, py: f32) -> Vec<InputAction> {
        let Some(layout) = self.layout.as_ref() else {
            return Vec::new();
        };
        let mut out = Vec::new();

        match phase {
            TouchPhase::Started => {
                self.touch_count += 1;
                // Priority: buttons > joystick > hotbar > look-drag.
                if let Some((&kind, _)) = layout
                    .buttons
                    .iter()
                    .find(|(_, c)| c.contains(px, py, 1.35))
                {
                    self.tracked.insert(id, Tracked::Button { kind });
                    out.extend(button_press(kind));
                } else if layout.move_stick.contains(px, py, 1.8) {
                    self.tracked
                        .insert(id, Tracked::Joystick { x: 0.0, y: 0.0 });
                    out.push(InputAction::Move { x: 0.0, y: 0.0 });
                } else if in_rect(layout.hotbar, px, py) {
                    if let Some(slot) = hotbar_slot_at(layout, px) {
                        self.selected_slot = slot;
                        out.push(InputAction::HotbarSlot(slot));
                    }
                    self.tracked.insert(id, Tracked::Hotbar);
                } else if in_rect(layout.look_zone, px, py) {
                    self.tracked.insert(
                        id,
                        Tracked::Look {
                            last_x: px,
                            last_y: py,
                        },
                    );
                }
            }
            TouchPhase::Moved => match self.tracked.get_mut(&id) {
                Some(Tracked::Joystick { x, y }) => {
                    let (nx, ny) = joystick_value(layout, px, py);
                    if (nx, ny) != (*x, *y) {
                        *x = nx;
                        *y = ny;
                        out.push(InputAction::Move { x: nx, y: ny });
                    }
                }
                Some(Tracked::Look { last_x, last_y, .. }) => {
                    let (dx, dy) = (px - *last_x, py - *last_y);
                    *last_x = px;
                    *last_y = py;
                    out.push(InputAction::Look { dx, dy });
                }
                _ => {}
            },
            TouchPhase::Ended | TouchPhase::Cancelled => {
                self.touch_count = self.touch_count.saturating_sub(1);
                match self.tracked.remove(&id) {
                    Some(Tracked::Joystick { .. }) => {
                        out.push(InputAction::Move { x: 0.0, y: 0.0 })
                    }
                    Some(Tracked::Button { kind }) => out.extend(button_release(kind)),
                    _ => {}
                }
            }
        }
        out
    }

    /// Current joystick knob offset (logical px from stick center), if engaged.
    pub fn knob_offset(&self) -> Option<(f32, f32)> {
        let layout = self.layout.as_ref()?;
        match self
            .tracked
            .values()
            .find(|t| matches!(t, Tracked::Joystick { .. }))
        {
            Some(Tracked::Joystick { x, y }) => {
                Some((x * layout.move_stick.r, y * layout.move_stick.r))
            }
            _ => None,
        }
    }

    /// Whether a given button is currently held (drives highlight rendering).
    pub fn button_held(&self, kind: ButtonKind) -> bool {
        self.tracked
            .values()
            .any(|t| matches!(t, Tracked::Button { kind: k } if *k == kind))
    }

    pub fn selected_slot(&self) -> u8 {
        self.selected_slot
    }

    /// Draw list for the overlay renderer, in *logical* pixels relative to the
    /// layout origin. Convert to NDC with the window size used in `resize`.
    pub fn geometry(&self) -> Vec<OverlayShape> {
        let Some(layout) = self.layout.as_ref() else {
            return Vec::new();
        };
        let mut shapes = Vec::with_capacity(16);

        // Joystick base + knob.
        shapes.push(OverlayShape {
            x: layout.move_stick.x,
            y: layout.move_stick.y,
            r: layout.move_stick.r,
            color: [0.9, 0.9, 0.9, 0.0],
        });
        let (kx, ky) = self.knob_offset().unwrap_or((0.0, 0.0));
        let engaged = self.knob_offset().is_some();
        shapes.push(OverlayShape {
            x: layout.move_stick.x + kx,
            y: layout.move_stick.y + ky,
            r: layout.knob_radius,
            color: [0.95, 0.95, 0.95, if engaged { 0.85 } else { 0.45 }],
        });

        // Buttons.
        for (&kind, circle) in &layout.buttons {
            let held = self.button_held(kind);
            shapes.push(OverlayShape {
                x: circle.x,
                y: circle.y,
                r: circle.r,
                color: button_color(kind, held),
            });
        }

        shapes
    }

    /// Hotbar strip as 9 slot shapes (r encodes half-height here; the demo
    /// renderer draws them as squares from the bounding box).
    pub fn hotbar_geometry(&self) -> (Rect, u8) {
        match self.layout.as_ref() {
            Some(l) => (l.hotbar, self.selected_slot),
            None => (
                Rect {
                    x: 0.0,
                    y: 0.0,
                    w: 0.0,
                    h: 0.0,
                },
                0,
            ),
        }
    }
}

fn in_rect(r: Rect, px: f32, py: f32) -> bool {
    px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h
}

fn hotbar_slot_at(layout: &OverlayLayout, px: f32) -> Option<u8> {
    let slot_w = layout.hotbar.w / 9.0;
    let idx = ((px - layout.hotbar.x) / slot_w).floor();
    (0.0..9.0).contains(&idx).then_some(idx as u8)
}

fn joystick_value(layout: &OverlayLayout, px: f32, py: f32) -> (f32, f32) {
    let dx = px - layout.move_stick.x;
    let dy = py - layout.move_stick.y;
    let len = (dx * dx + dy * dy).sqrt();
    let dead = layout.knob_radius * 0.5;
    if len <= dead {
        return (0.0, 0.0);
    }
    let clamped = len.min(layout.move_stick.r);
    let scale = clamped / len;
    // y inverted: screen-down is stick-back.
    (
        clamp1(dx * scale / layout.move_stick.r),
        clamp1(-dy * scale / layout.move_stick.r),
    )
}

fn clamp1(v: f32) -> f32 {
    v.clamp(-1.0, 1.0)
}

fn button_press(kind: ButtonKind) -> Vec<InputAction> {
    match kind {
        ButtonKind::Jump => vec![InputAction::Jump],
        ButtonKind::Attack => vec![InputAction::AttackBegin],
        ButtonKind::Use => vec![InputAction::UseBegin],
        ButtonKind::Pick => vec![InputAction::PickBlock],
        ButtonKind::Sneak => vec![InputAction::SneakBegin],
        ButtonKind::Sprint => vec![InputAction::SprintBegin],
        ButtonKind::Zoom => vec![InputAction::ZoomBegin],
        ButtonKind::Inventory => vec![InputAction::Inventory],
        ButtonKind::Chat => vec![InputAction::Chat],
        ButtonKind::Pause => vec![InputAction::Pause],
    }
}

fn button_release(kind: ButtonKind) -> Vec<InputAction> {
    match kind {
        ButtonKind::Attack => vec![InputAction::AttackEnd],
        ButtonKind::Use => vec![InputAction::UseEnd],
        ButtonKind::Sneak => vec![InputAction::SneakEnd],
        ButtonKind::Sprint => vec![InputAction::SprintEnd],
        ButtonKind::Zoom => vec![InputAction::ZoomEnd],
        _ => vec![],
    }
}

fn button_color(kind: ButtonKind, held: bool) -> [f32; 4] {
    let base = match kind {
        ButtonKind::Jump => [0.35, 0.75, 0.40],
        ButtonKind::Attack => [0.85, 0.35, 0.30],
        ButtonKind::Use => [0.35, 0.55, 0.85],
        ButtonKind::Pick => [0.75, 0.55, 0.30],
        ButtonKind::Sneak => [0.55, 0.55, 0.60],
        ButtonKind::Sprint => [0.30, 0.70, 0.70],
        ButtonKind::Zoom => [0.60, 0.45, 0.75],
        ButtonKind::Inventory => [0.60, 0.60, 0.35],
        ButtonKind::Chat => [0.45, 0.60, 0.60],
        ButtonKind::Pause => [0.70, 0.70, 0.70],
    };
    let a = if held { 0.95 } else { 0.35 };
    [base[0], base[1], base[2], a]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn joystick_maps_and_releases() {
        let mut o = TouchOverlay::new();
        o.resize(2400, 1080, 2.0); // logical 1200x540
        // Touch down near the stick center (logical ~190, 400) then drag up.
        let a = o.on(1, TouchPhase::Started, 190.0, 400.0);
        assert!(a.iter().any(|x| matches!(x, InputAction::Move { .. })));
        let a = o.on(1, TouchPhase::Moved, 190.0, 320.0);
        match a.last() {
            Some(InputAction::Move { x: _, y }) => {
                assert!(*y > 0.5, "dragging up should be forward: {a:?}")
            }
            other => panic!("expected Move, got {other:?}"),
        }
        let a = o.on(1, TouchPhase::Ended, 190.0, 320.0);
        assert!(a.contains(&InputAction::Move { x: 0.0, y: 0.0 }));
    }

    #[test]
    fn joystick_deadzone_and_clamp() {
        let layout = OverlayLayout::for_size(1200.0, 540.0);
        // Deadzone: tiny offset from center emits zero.
        assert_eq!(
            joystick_value(&layout, layout.move_stick.x + 1.0, layout.move_stick.y),
            (0.0, 0.0)
        );
        // Far beyond the rim clamps to unit length.
        let (x, y) = joystick_value(&layout, layout.move_stick.x, layout.move_stick.y - 900.0);
        assert!((y - 1.0).abs() < 1e-6, "y should clamp to 1.0, got {y}");
        assert_eq!(x, 0.0);
    }

    #[test]
    fn hotbar_selects_slot() {
        let mut o = TouchOverlay::new();
        o.resize(2400, 1080, 2.0);
        let layout = o.layout.as_ref().unwrap();
        let slot5_center = layout.hotbar.x + layout.hotbar.w * (5.5 / 9.0);
        let a = o.on(
            7,
            TouchPhase::Started,
            slot5_center,
            layout.hotbar.y + layout.hotbar.h / 2.0,
        );
        assert!(a.contains(&InputAction::HotbarSlot(5)));
        assert_eq!(o.selected_slot(), 5);
    }

    #[test]
    fn look_drag_deltas() {
        let mut o = TouchOverlay::new();
        o.resize(2400, 1080, 2.0);
        // Pick a look-zone point clear of the button cluster (checked first).
        o.on(2, TouchPhase::Started, 700.0, 300.0);
        let a = o.on(2, TouchPhase::Moved, 750.0, 300.0);
        assert!(
            a.iter()
                .any(|x| matches!(x, InputAction::Look { dx, .. } if *dx > 0.0))
        );
    }

    #[test]
    fn button_hold_and_release() {
        let mut o = TouchOverlay::new();
        o.resize(2400, 1080, 2.0);
        let layout = o.layout.as_ref().unwrap();
        let c = layout.buttons[&ButtonKind::Attack];
        let a = o.on(3, TouchPhase::Started, c.x, c.y);
        assert!(a.contains(&InputAction::AttackBegin));
        assert!(o.button_held(ButtonKind::Attack));
        let a = o.on(3, TouchPhase::Ended, c.x, c.y);
        assert!(a.contains(&InputAction::AttackEnd));
        assert!(!o.button_held(ButtonKind::Attack));
    }
}
