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

use std::path::{Path, PathBuf};

use crate::LaunchConfiguration;

/// The file that marks a directory as a prepared resources root.
const PREPARED_MARK: &str = "horizon-resources.json";

/// Is `dir` a prepared resources root — what `--resources` needs?
pub fn is_prepared_root(dir: &Path) -> bool {
    dir.join(PREPARED_MARK).is_file()
}

/// Launch the client described by `config`.
///
/// `Ok(false)` — no prepared root; the caller should run the demo shell.
/// `Err` — the client was launched and failed; the message is logged by the
/// caller, and returning from `android_main` lands the user back in the
/// launcher.
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
    log::info!("launching horizon-client with {args:?}");
    horizon_client::game::run_with(args)
        .map(|()| true)
        .map_err(|e| format!("{e}"))
}
