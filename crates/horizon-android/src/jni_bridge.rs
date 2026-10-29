//! The Kotlin ⇄ Rust JNI contract (jni 0.22 — the same version
//! android-activity 0.6 uses, so the `Env`/`JavaVM` types interoperate).
//!
//! Kotlin side: `dev.horizon.android.game.HorizonNative` (see
//! `android/app/src/main/java/dev/horizon/android/game/HorizonNative.kt`).
//!
//! Flow:
//! 1. `HorizonApp` loads `libhorizon_android.so` → `JNI_OnLoad` captures the
//!    `JavaVM`.
//! 2. The launcher calls `nativeConfigure(json)` with paths + settings
//!    **before** starting the game activity, so `android_main` finds its
//!    configuration in a process-global `OnceLock`. While on the Java main
//!    thread (app classloader active) we also cache a `Global<JClass>` for
//!    the callback class — later `find_class` calls from native-attached
//!    threads would otherwise hit the system classloader.
//! 3. `nativeValidateGameFiles` runs on a Kotlin background thread and emits
//!    progress via the static method `HorizonNative.onNativeEvent(String)`.
//!
//! Adding new entry points: keep them in `HorizonNative.kt` and here, with
//! matching signatures. Prefer JSON strings over typed JNI for anything that
//! will evolve (see `LaunchConfiguration`).

use std::path::Path;
use std::sync::OnceLock;

use jni::errors::LogContextErrorAndDefault;
use jni::objects::{Global, JClass, JString, JValue};
use jni::signature::{JavaType, MethodSignature, Primitive};
use jni::sys::{JNI_VERSION_1_6, jboolean, jint};
use jni::{JavaVM, jni_str};

use crate::LaunchConfiguration;

static CONFIG: OnceLock<LaunchConfiguration> = OnceLock::new();
static JAVA_VM: OnceLock<JavaVM> = OnceLock::new();
static EVENT_CLASS: OnceLock<Global<JClass<'static>>> = OnceLock::new();

pub fn launch_configuration() -> LaunchConfiguration {
    CONFIG.get().cloned().unwrap_or_default()
}

/// Progress events back to Kotlin. Fire-and-forget: failures log but never
/// propagate (an event glitch must not kill a 40-minute import).
fn emit_event(kind: &str, payload: &str) {
    let Some(vm) = JAVA_VM.get() else { return };
    let Some(class) = EVENT_CLASS.get() else {
        return;
    };
    let json = serde_json::json!({ "type": kind, "payload": payload }).to_string();

    let result: std::result::Result<(), jni::errors::Error> = vm.attach_current_thread(|env| {
        let jstr = env.new_string(&json)?;
        // SAFETY: the signature string matches the argument list
        // (`[JavaType::Object]`, void return) exactly.
        let sig = unsafe {
            MethodSignature::from_raw_parts(
                jni_str!("(Ljava/lang/String;)V"),
                &[JavaType::Object],
                JavaType::Primitive(Primitive::Void),
            )
        };
        env.call_static_method(
            class,
            jni_str!("onNativeEvent"),
            &sig,
            &[JValue::Object(jstr.as_ref())],
        )?;
        Ok(())
    });
    if let Err(e) = result {
        log::warn!("event emit failed: {e}");
    }
}

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

/// Called by the runtime when `System.loadLibrary("horizon_android")` runs.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(
    vm: *mut jni::sys::JavaVM,
    _reserved: *mut core::ffi::c_void,
) -> jint {
    // SAFETY: the VM pointer is valid for the process lifetime per the JNI
    // spec. jni 0.22 makes JavaVM a process singleton internally.
    let vm = unsafe { JavaVM::from_raw(vm) };
    let _ = JAVA_VM.set(vm);
    log::info!("JNI_OnLoad: JavaVM captured");
    JNI_VERSION_1_6
}

/// `HorizonNative.nativeConfigure(json: String): Boolean`
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_game_HorizonNative_nativeConfigure<'caller>(
    mut unowned_env: jni::EnvUnowned<'caller>,
    _class: JClass<'caller>,
    json: JString<'caller>,
) -> jboolean {
    // Pass 1: pull the string out of the JNI frame. `String: Default`, so a
    // failure here resolves to an empty string and the parse below fails.
    let rust_json: String = unowned_env
        .with_env(|env| -> jni::errors::Result<String> { Ok(json.try_to_string(env)?) })
        .resolve_with::<LogContextErrorAndDefault, _>(|| "nativeConfigure: reading JSON".into());

    let Ok(config) = serde_json::from_str::<LaunchConfiguration>(&rust_json) else {
        log::error!("nativeConfigure: could not parse launch JSON");
        return jni::sys::JNI_FALSE;
    };
    log::info!("nativeConfigure: {config:?}");
    let _ = CONFIG.set(config);

    // Pass 2: cache the callback class while we're on the app classloader's
    // thread (native threads resolving app classes through the system
    // classloader is the classic `find_class` failure mode).
    if EVENT_CLASS.get().is_none() {
        let cached: jboolean = unowned_env
            .with_env(|env| -> jni::errors::Result<jboolean> {
                let class = env.find_class(jni_str!("dev/horizon/android/game/HorizonNative"))?;
                let global = env.new_global_ref(&class)?;
                let _ = EVENT_CLASS.set(global);
                Ok(jni::sys::JNI_TRUE)
            })
            .resolve_with::<LogContextErrorAndDefault, _>(|| {
                "nativeConfigure: caching event class".into()
            });
        if cached == jni::sys::JNI_FALSE {
            log::warn!("event sink class not cached — progress events disabled");
        }
    }
    jni::sys::JNI_TRUE
}

/// `HorizonNative.nativeGetVersion(): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_game_HorizonNative_nativeGetVersion<'caller>(
    mut unowned_env: jni::EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<JString<'caller>> {
            let version = format!("horizon-android {}", env!("CARGO_PKG_VERSION"));
            Ok(env.new_string(&version)?)
        })
        .resolve_with::<LogContextErrorAndDefault, _>(|| "nativeGetVersion".into())
}

/// `HorizonNative.nativeGpuInfo(): String` — populated after the first game
/// boot; safe to call any time (returns a placeholder before that).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_game_HorizonNative_nativeGpuInfo<'caller>(
    mut unowned_env: jni::EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<JString<'caller>> {
            Ok(env.new_string(crate::backend::gpu_info())?)
        })
        .resolve_with::<LogContextErrorAndDefault, _>(|| "nativeGpuInfo".into())
}

/// `HorizonNative.nativeValidateGameFiles(preparedDir: String): Boolean`
///
/// Inspection of the **prepared root** — the directory the client gets as
/// `--resources` — against the play-time requirements (the manifest plus the
/// exporter tables and spot files; see `prepare::REQUIRED_FILES`). Emits
/// `validate` events (`ok:manifest`, `missing:<name>`, `result:usable` /
/// `result:incomplete`) so the launcher can show exactly what is missing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_game_HorizonNative_nativeValidateGameFiles<
    'caller,
>(
    mut unowned_env: jni::EnvUnowned<'caller>,
    _class: JClass<'caller>,
    prepared_dir: JString<'caller>,
) -> jboolean {
    let dir: String = unowned_env
        .with_env(|env| -> jni::errors::Result<String> { prepared_dir.try_to_string(env) })
        .resolve_with::<LogContextErrorAndDefault, _>(|| {
            "nativeValidateGameFiles: reading path".into()
        });

    log::info!("validating prepared resources at {dir}");
    let inspection = crate::prepare::inspect(Path::new(&dir));
    if inspection.usable {
        emit_event("validate", "ok:manifest");
    } else {
        for name in &inspection.missing {
            emit_event("validate", &format!("missing:{name}"));
        }
    }
    let usable = inspection.usable;
    emit_event(
        "validate",
        if usable {
            "result:usable"
        } else {
            "result:incomplete"
        },
    );
    if usable {
        log::info!("prepared resources at {dir} look usable");
    } else {
        log::warn!(
            "prepared resources at {dir} incomplete ({} missing)",
            inspection.missing.len()
        );
    }
    usable as jboolean
}

/// `HorizonNative.nativePinnedVersion(): String` — the Minecraft version
/// this build is pinned to (research-lock.json). Both sides of the boundary
/// agree on the prepared root's name from it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_game_HorizonNative_nativePinnedVersion<'caller>(
    mut unowned_env: jni::EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<JString<'caller>> {
            Ok(env.new_string(crate::prepare::pinned_version())?)
        })
        .resolve_with::<LogContextErrorAndDefault, _>(|| "nativePinnedVersion".into())
}

/// `HorizonNative.nativePrepareFromMinecraft(filesRoot: String): String`
///
/// Runs the on-device prepare over the SAF-staged `.minecraft` (see
/// `prepare.rs`) and returns a JSON report:
/// `{ok, version, error?, missing, prepared_bytes}`. Progress lines arrive
/// as `prepare` events; the run always finishes with `result:usable` or
/// `result:incomplete`, so the launcher can close its progress row.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_android_game_HorizonNative_nativePrepareFromMinecraft<
    'caller,
>(
    mut unowned_env: jni::EnvUnowned<'caller>,
    _class: JClass<'caller>,
    files_root: JString<'caller>,
) -> JString<'caller> {
    let root: String = unowned_env
        .with_env(|env| -> jni::errors::Result<String> { files_root.try_to_string(env) })
        .resolve_with::<LogContextErrorAndDefault, _>(|| {
            "nativePrepareFromMinecraft: reading path".into()
        });

    log::info!("prepare: running the on-device prepare under {root}");
    let report = {
        let log = |line: &str| emit_event("prepare", line);
        match crate::prepare::prepare_from_minecraft(Path::new(&root), &log) {
            Ok(report) => report,
            Err(error) => {
                log::error!("prepare failed: {error}");
                crate::prepare::PrepareReport::failed(error)
            }
        }
    };
    emit_event(
        "prepare",
        if report.ok {
            "result:usable"
        } else {
            "result:incomplete"
        },
    );
    let json = serde_json::json!({
        "ok": report.ok,
        "version": report.version,
        "error": report.error,
        "missing": report.missing,
        "prepared_bytes": report.prepared_bytes,
    })
    .to_string();
    unowned_env
        .with_env(|env| -> jni::errors::Result<JString<'caller>> { Ok(env.new_string(&json)?) })
        .resolve_with::<LogContextErrorAndDefault, _>(|| {
            "nativePrepareFromMinecraft: returning report".into()
        })
}
