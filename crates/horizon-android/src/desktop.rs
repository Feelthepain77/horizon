//! Desktop debug runner (`--features desktop`).
//!
//! Boots the exact same shell/renderer/overlay as Android, but with a native
//! window, so overlay layout and renderer changes can be iterated on without
//! a device. The touch overlay unit tests cover the state machine; this
//! runner covers the visual + lifecycle side.

use horizon_android::run;

struct StderrLogger;
impl log::Log for StderrLogger {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        metadata.level() <= log::Level::Debug
    }
    fn log(&self, record: &log::Record) {
        if self.enabled(record.metadata()) {
            eprintln!("[{:5}] {}", record.level(), record.args());
        }
    }
    fn flush(&self) {}
}

static LOGGER: StderrLogger = StderrLogger;

fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    log::set_logger(&LOGGER).map(|_| log::set_max_level(log::LevelFilter::Debug))?;
    println!(
        "horizon-android desktop runner {} — overlay layout + renderer iterate here; \
         real touch input requires a device or emulator.",
        env!("CARGO_PKG_VERSION")
    );
    run(None)
}
