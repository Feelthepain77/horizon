//! Launching the real Horizon client (horizon-client) on Android.
//!
//! What the client needs from us, and why:
//!
//! * `--resources` — a **prepared root**: a directory containing
//!   `horizon-resources.json` (the manifest horizon-prepare writes). That is
//!   the only game data the client reads; the raw `versions/`/`assets/`
//!   layout is a desktop prepare *input*, never read at play time. The
//!   launcher imports a portable export from the desktop into
//!   `files/prepared` and hands us the path.
//! * the **game home** — cwd for the whole run *and* `HORIZON_HOME`.
//!   `options.txt`, `saves/`, `logs/` and `.horizon-cache` are created there
//!   by the client itself. We pass it as `home_dir` from Kotlin.
//! * `--session-file` — a fresh (< 120 s) JSON the launcher wrote just
//!   before starting us; the client reads and deletes it on startup. A
//!   local account (offline UUID) or a Microsoft session — same format,
//!   see `auth::AuthSession::parse` in the client.
//! * `--name`, `--render-distance` — the remaining knobs.
//!
//! Without a prepared root the shell falls back to the demo renderer
//! (`Ok(false)`), so the app still works before the first import.
//!
//! The shell also does what the desktop `main` does before `game::run`
//! (crates/horizon-client/src/main.rs): it starts the client's own
//! vanilla-format file log in `<home>/logs/latest.log` and installs the
//! crash reporter that writes `<home>/crash-reports/crash-<date>-client.txt`
//! for a panic or a fatal error. Without these, the game's only output on a
//! device is logcat — invisible without adb — and a launch that dies looks
//! exactly like a PLAY button that does nothing. The launcher reads both
//! back and shows them ("Last game run").

use std::path::{Path, PathBuf};
use std::sync::Once;

use crate::LaunchConfiguration;

/// The file that marks a directory as a prepared resources root.
const PREPARED_MARK: &str = "horizon-resources.json";

/// Is `dir` a prepared resources root — what `--resources` needs?
pub fn is_prepared_root(dir: &Path) -> bool {
    dir.join(PREPARED_MARK).is_file()
}

/// The file log and the crash reporter start once per process: a second
/// `logging::init` would reopen (and roll) the log the first writer still
/// holds, and `log::set_logger` accepts only one logger per process anyway.
/// `android_main` runs again for every activity recreation with the same
/// home, and the existing writer keeps appending to the same `latest.log`.
static GAME_BOOTSTRAP: Once = Once::new();

/// Launch the client described by `config`.
///
/// `Ok(false)` — no prepared root; the caller should run the demo shell.
/// `Err` — the client was launched and failed; the message is in the log
/// file and the crash report, the caller logs it, and returning from
/// `android_main` lands the user back in the launcher (which shows why).
pub fn launch(config: &LaunchConfiguration) -> Result<bool, String> {
    let Some(prepared) = config.prepared_dir.as_deref().map(PathBuf::from) else {
        return Ok(false);
    };
    if !is_prepared_root(&prepared) {
        log::warn!(
            "no {PREPARED_MARK} in {} — the demo shell runs instead",
            prepared.display()
        );
        return Ok(false);
    }

    // The game home: cwd for the whole run, and HORIZON_HOME. Defaults to a
    // `home/` folder beside the prepared root (Kotlin always sends an
    // explicit path; this is the safety net).
    let home = config
        .home_dir
        .as_deref()
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            prepared
                .parent()
                .unwrap_or_else(|| Path::new("."))
                .join("home")
        });
    std::fs::create_dir_all(&home).map_err(|e| format!("create {}: {e}", home.display()))?;
    std::env::set_current_dir(&home).map_err(|e| format!("chdir {}: {e}", home.display()))?;

    // SAFETY: android_main is the process entry; no other thread reads the
    // environment yet (the client and its worker threads start inside
    // run_with, below). The telemetry URL matches what CI sets for test
    // runs: no live ping, no uploads.
    unsafe {
        std::env::set_var("HORIZON_HOME", &home);
        std::env::set_var("HORIZON_TELEMETRY_URL", "off");
    }

    let mut args: Vec<String> = Vec::new();
    args.push("--resources".into());
    args.push(prepared.to_string_lossy().into_owned());
    if let Some(distance) = config.render_distance {
        args.push("--render-distance".into());
        args.push(distance.to_string());
    }
    if let Some(name) = config.player_name.as_deref().filter(|n| !n.is_empty()) {
        args.push("--name".into());
        args.push(name.to_owned());
    }
    if let Some(session) = config.session_file.as_deref().filter(|s| !s.is_empty()) {
        args.push("--session-file".into());
        args.push(session.to_owned());
    }
    // The desktop main()'s bootstrap, once per process: the file log first
    // (every later line, ours included, lands in <home>/logs/latest.log),
    // the build's name as its first line, then the panic hook that turns a
    // crash into a crash report. android_main no longer installs
    // android_logger before this: it would own the log facade for the whole
    // process, and the file logger could never take it.
    GAME_BOOTSTRAP.call_once(|| {
        horizon_client::logging::init(&home, "Render thread");
        log::info!(
            "horizon-android {} ({})",
            env!("CARGO_PKG_VERSION"),
            horizon_client::crash::version()
        );
        horizon_client::crash::install(home.clone(), "client");
    });

    log::info!("launching horizon-client with {args:?}");
    // `contain` turns a panic inside the client into an Err with the panic's
    // message (its stack is logged, and lands in the report through the
    // recent-log excerpt) instead of a dead thread the activity never hears
    // about — the game window would just sit black until swiped away.
    let outcome = match horizon_client::crash::contain(|| horizon_client::game::run_with(args)) {
        Ok(result) => result,
        Err(panic) => Err(Box::<dyn std::error::Error>::from(format!(
            "the game thread panicked: {panic}"
        ))),
    };
    match outcome {
        Ok(()) => {
            horizon_client::logging::flush();
            Ok(true)
        }
        Err(error) => {
            horizon_client::crash::report_error(&home, "client", &*error);
            horizon_client::logging::flush();
            // The launcher is in this same process and still collecting:
            // tell it now, so the reason is on screen the moment the user
            // lands back in it (the report and log tail follow from disk on
            // the next refresh).
            #[cfg(target_os = "android")]
            crate::jni_bridge::emit_event("game", &format!("error: {error}"));
            Err(format!("{error}"))
        }
    }
}
