//! horizon-android — Android runtime for the Horizon client.
//!
//! This crate is the seam between Android and the Horizon workspace:
//!
//! * `android_main` (below) is the process entry point invoked by
//!   `android-activity` on a dedicated thread when Kotlin launches
//!   [`dev.horizon.android.game.HorizonGameActivity`][1].
//! * [`jni_bridge`] holds the Kotlin⇄Rust contract: the launcher configures
//!   paths/settings via JNI *before* starting the game activity, and Rust can
//!   call back into Kotlin for progress events.
//! * [`backend`] boots a **Vulkan-only** wgpu stack (same backend family as
//!   the desktop client).
//! * [`touch`] is the on-screen control overlay (joystick, buttons, hotbar)
//!   that emits [`touch::InputAction`]s for the client's input layer.
//! * [`demo`] is a placeholder renderer that proves the whole path
//!   (surface lifecycle + pipeline + overlay) before the real client is
//!   wired in.
//!
//! [1]: defined in `android/app/src/main/AndroidManifest.xml`
//!
//! # Lifecycle rules that matter here
//!
//! * `android_main` may be called **more than once per process** (activity
//!   recreation). All state must be rebuilt per call; process-global state
//!   (launch config, cached JavaVM) lives in `OnceLock`s.
//! * The window/surface must be dropped on `suspended` — Android requires the
//!   swapchain released before the activity pauses, and winit surfaces taken
//!   across a suspend will be `Outdated`.
//! * Never block the *Java* main thread; everything here runs on the
//!   `android_main` thread or worker threads it spawns.
//!
//! # Desktop debugging
//!
//! `cargo run -p horizon-android --features desktop` boots the same shell with
//! a native window (mouse clicks map to touches in the overlay tests) — handy
//! for iterating on overlay layout without a device.

mod backend;
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
mod client;
mod demo;
pub mod prepare;
mod touch;

#[cfg(target_os = "android")]
mod jni_bridge;

pub use touch::{ButtonKind, InputAction, OverlayLayout, OverlayShape, TouchOverlay};

#[cfg(target_os = "android")]
pub use winit::platform::android::activity::AndroidApp;

/// Unit-struct stand-in on non-Android hosts, so `run(None)` compiles for the
/// desktop debug runner. Never constructed there.
#[cfg(not(target_os = "android"))]
#[derive(Debug, Clone, Copy)]
pub struct AndroidApp;

use std::sync::Arc;

use winit::application::ApplicationHandler;
use winit::event::{ElementState, KeyEvent, WindowEvent};
use winit::event_loop::{ActiveEventLoop, EventLoop};
use winit::window::{Window, WindowId};

/// Android entry point. `#[unsafe(no_mangle)]` (edition 2024 spelling) is
/// required so the android-activity glue can `dlsym` this symbol from
/// `libhorizon_android.so`.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
fn android_main(app: crate::AndroidApp) {
    // No android_logger here: `log::set_logger` accepts one logger per
    // process, and the client's own file logger (started in `client::launch`)
    // must be that one — it writes <home>/logs/latest.log, which the launcher
    // reads back as the "Last game run" panel. Horizon's log lines also go
    // to stderr, which a debuggable build mirrors into logcat anyway.
    // (The demo shell's lines only reach logcat on desktop debug runs.)
    log::info!(
        "horizon-android {} (rustc {}) starting",
        env!("CARGO_PKG_VERSION"),
        rustc_version()
    );
    // winit on Android builds event loops only from the `AndroidApp` this
    // function receives — the client's `EventLoop::new()` panics without it,
    // which is why PLAY used to open a window that immediately died. Store
    // it in the client before anything in its startup creates a loop. A
    // clone: the demo shell below still needs the original.
    horizon_client::game::set_android_app(app.clone());
    // The real client when the prepared files are there; the demo shell as
    // the fallback (no import yet, or an incomplete one).
    match client::launch(&launch_configuration()) {
        Ok(true) => {
            // The client ran and returned normally (the user quit).
        }
        Ok(false) => {
            log::info!("no prepared game files — starting the demo shell");
            if let Err(e) = run(Some(app)) {
                log::error!("demo shell terminated with error: {e:?}");
            }
        }
        Err(e) => {
            log::error!("horizon-client terminated with error: {e}");
            // Returning (not aborting) lets android-activity tear down
            // cleanly; the user lands back in the launcher.
        }
    }
}

// Used from `android_main` (Android-gated), so it looks dead on host builds.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn rustc_version() -> &'static str {
    option_env!("HORIZON_RUSTC_VERSION").unwrap_or("unknown")
}

/// Boot the winit shell. `None` runs the desktop window (debug feature).
#[cfg_attr(not(target_os = "android"), allow(unused_variables))]
pub fn run(
    android_app: Option<AndroidApp>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut builder = EventLoop::with_user_event();

    #[cfg(target_os = "android")]
    if let Some(app) = android_app {
        use winit::platform::android::EventLoopBuilderExtAndroid;
        builder.with_android_app(app);
    }

    let event_loop = builder.build()?;
    let mut shell = GameShell::new(launch_configuration());
    event_loop.run_app(&mut shell)?;
    Ok(())
}

/// Configuration passed from the Kotlin launcher via JNI (see `jni_bridge`).
/// Kept serde-driven so the Kotlin side evolves without recompiling Rust:
/// unknown fields are ignored, missing fields default.
#[derive(Debug, Default, Clone, serde::Serialize, serde::Deserialize)]
pub struct LaunchConfiguration {
    /// Absolute path to the imported game files
    /// (`versions/`, `assets/`, `libraries/`).
    ///
    /// Deprecated: the client never reads these at play time (they are
    /// desktop prepare inputs); kept so older launchers still parse.
    pub game_dir: Option<String>,
    /// Deprecated in the same way as `game_dir`.
    pub local_dir: Option<String>,
    /// Deprecated: see `player_name`.
    pub username: Option<String>,
    /// The prepared resources root — a directory containing
    /// `horizon-resources.json` (see `client::is_prepared_root`).
    pub prepared_dir: Option<String>,
    /// The game home: cwd for the whole run and `HORIZON_HOME`. The client
    /// writes `options.txt`, `saves/` and `logs/` there.
    pub home_dir: Option<String>,
    /// A session file the launcher just wrote; the client reads and deletes
    /// it within its first 120 s. Local account or Microsoft session — same
    /// format.
    pub session_file: Option<String>,
    /// The player name to join with, when there is no session file.
    pub player_name: Option<String>,
    pub render_distance: Option<u32>,
    /// Mirrors the desktop launcher's graphics presets.
    pub graphics_preset: Option<String>,
    pub invert_look: Option<bool>,
    /// Touch look sensitivity multiplier (1.0 = default).
    pub sensitivity: Option<f32>,
}

#[cfg(target_os = "android")]
fn launch_configuration() -> LaunchConfiguration {
    jni_bridge::launch_configuration()
}

#[cfg(not(target_os = "android"))]
fn launch_configuration() -> LaunchConfiguration {
    LaunchConfiguration::default()
}

/// The winit application shell. Owns the window + renderer across the
/// Android lifecycle (`resumed`/`suspended`) and routes input.
struct GameShell {
    /// Consumed by horizon-client at integration time (PORTING.md §
    /// "Replacing this module"); logged at boot meanwhile.
    #[allow(dead_code)]
    config: LaunchConfiguration,
    window: Option<Arc<Window>>,
    renderer: Option<demo::Renderer>,
    overlay: TouchOverlay,
    exit_requested: bool,
}

impl GameShell {
    fn new(config: LaunchConfiguration) -> Self {
        log::info!("launch configuration: {config:?}");
        Self {
            config,
            window: None,
            renderer: None,
            overlay: TouchOverlay::new(),
            exit_requested: false,
        }
    }

    fn boot_renderer(
        &mut self,
        window: Arc<Window>,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let instance = backend::create_instance();
        let renderer = demo::Renderer::new(&instance, window.clone())?;
        let size = window.inner_size();
        self.overlay
            .resize(size.width, size.height, window.scale_factor());
        self.renderer = Some(renderer);
        log::info!(
            "renderer booted ({}x{} @ {}x)",
            size.width,
            size.height,
            window.scale_factor()
        );
        Ok(())
    }
}

impl ApplicationHandler for GameShell {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        // (Re)create the window + surface. On Android `resumed` fires when
        // the native window is available; on desktop this runs once.
        if self.window.is_none() {
            let attrs = Window::default_attributes().with_title("Horizon (android shell)");
            match event_loop.create_window(attrs) {
                Ok(window) => {
                    let window = Arc::new(window);
                    if let Err(e) = self.boot_renderer(window.clone()) {
                        // Surface may not be ready yet on Android (init window
                        // still pending) — we retry on the next `resumed`.
                        log::warn!("renderer boot failed (will retry on next resume): {e:?}");
                        self.window = None;
                    } else {
                        self.window = Some(window);
                    }
                }
                Err(e) => log::error!("window creation failed: {e:?}"),
            }
        }
    }

    fn suspended(&mut self, _event_loop: &ActiveEventLoop) {
        // CRITICAL (Android): release the surface before suspend. wgpu
        // swapchains cannot survive the native window going away, and leaking
        // one trips `Outdated` + leaked-surface assertions in wgpu.
        self.renderer = None;
        self.window = None;
        log::info!("suspended: surface + window released");
    }

    fn window_event(
        &mut self,
        event_loop: &ActiveEventLoop,
        _window_id: WindowId,
        event: WindowEvent,
    ) {
        let Some(window) = self.window.clone() else {
            return;
        };

        match event {
            WindowEvent::Resized(size) => {
                if let Some(r) = self.renderer.as_mut() {
                    r.resize(size.width, size.height);
                    self.overlay
                        .resize(size.width, size.height, window.scale_factor());
                }
            }
            WindowEvent::Touch(touch) => {
                let actions = self.overlay.handle(&touch, window.scale_factor());
                if let Some(r) = self.renderer.as_mut() {
                    for a in actions {
                        r.input(a);
                    }
                }
                window.request_redraw();
            }
            WindowEvent::RedrawRequested => {
                let Some(r) = self.renderer.as_mut() else {
                    return;
                };
                match r.render(&self.overlay) {
                    Ok(()) => {}
                    // Surface churn is normal around suspend/resume on
                    // Android; reconfigure and keep going.
                    Err(demo::FrameError::SurfaceGone(why)) => {
                        log::warn!("surface gone ({why}); reconfiguring");
                        let size = window.inner_size();
                        r.resize(size.width, size.height);
                    }
                    Err(demo::FrameError::Skip(why)) => {
                        log::debug!("frame skipped ({why})")
                    }
                }
                if !self.exit_requested {
                    window.request_redraw();
                }
            }
            WindowEvent::CloseRequested => {
                self.exit_requested = true;
                event_loop.exit();
            }
            WindowEvent::KeyboardInput {
                event:
                    KeyEvent {
                        state: ElementState::Pressed,
                        ..
                    },
                ..
            } => {
                // Desktop convenience only. On-device, the pause overlay is
                // driven by the touch overlay's Pause button.
                #[cfg(feature = "desktop")]
                {
                    self.exit_requested = true;
                    event_loop.exit();
                }
            }
            _ => {}
        }
    }

    fn about_to_wait(&mut self, _event_loop: &ActiveEventLoop) {
        // Games redraw continuously; FIFO present mode paces us to vsync.
        if let Some(window) = &self.window {
            window.request_redraw();
        }
    }
}
