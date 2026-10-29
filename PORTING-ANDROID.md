# Porting Horizon to Android

**Status: scaffold — the foundation is built and verified, the game itself is not yet wired in.**

This document is the engineer's handbook for running the Horizon client on
Android. It covers what this scaffold provides, how to integrate it into the
Horizon repository, what each workspace crate needs for Android, and the
debugging/troubleshooting playbook.

Everything marked **verified** below was actually executed while building this
scaffold (2026-10-30): the Rust crate type-checks against the real dependency
tree on all four Android targets, and the Gradle project produced real debug
and release APKs with AGP 9.5.

---

## 1. What this scaffold is (and isn't)

| Piece | State |
|---|---|
| `crates/horizon-android` — android-activity/winit shell, Vulkan-only wgpu bootstrap, touch overlay, JNI bridge, placeholder renderer | **Code complete.** `cargo check` passes on `aarch64/armv7/x86_64/i686`-linux-android; unit tests pass; clippy clean |
| `android/` — Kotlin Compose launcher (MSAL sign-in, game-file import via SAF, settings, device info) + NativeActivity host | **Builds.** `assembleDebug` + `assembleRelease` verified with AGP 9.5.0 / Gradle 9.8.2 / Kotlin 2.4.21 |
| Native `.so` link step (cargo-ndk) | Scripted, exercised in CI; not linked in this sandbox (no NDK) |
| `horizon-client` actually rendering on device | **Not started** — that's the porting work this handbook walks you through |
| On-device `horizon-prepare` | **Blocked on the JDK-25 step** — see § 7 |

The placeholder renderer is deliberately honest: it boots the exact surface
lifecycle path the real client needs (window → Vulkan surface → pipeline →
frame loop → suspend/resume) and draws the touch overlay from live input. When
you wire `horizon-client` in (§ 6.1), you delete `demo.rs` and drive the
client's frame loop from the same hooks — the plumbing around it stays.

---

## 2. Architecture

```
┌────────────────────────────────────────────────────────────┐
│ Android process                                            │
│                                                            │
│  Kotlin (launcher)            Rust (libhorizon_android.so) │
│  ┌──────────────────────┐     ┌─────────────────────────┐  │
│  │ MainActivity (M3)    │     │ android_main()          │  │
│  │  sign-in (MSAL)      │     │  ├─ backend.rs  wgpu    │  │
│  │  import (SAF/zip)    │JNI  │  │   Vulkan-only init   │  │
│  │  settings            ├────►│  ├─ touch.rs    overlay │  │
│  │  device info         │cfg  │  ├─ demo.rs     (del me)│  │
│  │ HorizonGameActivity  │     │  └─ jni_bridge.rs       │  │
│  │  (NativeActivity)    │     │     ▲ events (progress) │  │
│  └──────────┬───────────┘     └─────┼───────────────────┘  │
│             │  loads lib ───────────► JNI_OnLoad (JavaVM)  │
│             │ starts activity ──────► android_main(app)    │
└────────────────────────────────────────────────────────────┘
```

Key decisions:

* **Native-first.** The game window is a `NativeActivity` owned by Rust via
  winit's `android-native-activity` feature (android-activity 0.6.1 under
  winit 0.30.13 — version-locked by winit's re-export; **do not** add a
  separate `android-activity` dependency). Kotlin is only the launcher shell.
* **Configuration flows ahead of the activity.** Kotlin calls
  `HorizonNative.configure(json)` *before* starting the game activity; Rust
  stores it in a process-global `OnceLock`. This sidesteps the entire
  "pass state into android_main" problem that plagues NativeActivity ports.
* **Events flow back over a cached class ref.** Rust→Kotlin progress events
  call a static method on a `Global<JClass>` captured while the app
  classloader is active — the classic `find_class` system-classloader failure
  mode cannot happen.
* **Surface lifecycle follows winit's Android contract.** Window + surface are
  created on `resumed` and *dropped* on `suspended`. This is non-negotiable:
  wgpu swapchains cannot survive the native window going away (§ 13).
* **Vulkan-only.** Same backend family as the desktop client (§ 5).

### The JNI contract

| Kotlin (`HorizonNative`) | Rust (`jni_bridge.rs`) | Notes |
|---|---|---|
| `nativeConfigure(json): Boolean` | `..._nativeConfigure` | paths + settings, caches event class |
| `nativeGetVersion(): String` | `..._nativeGetVersion` | crate version |
| `nativeGpuInfo(): String` | `..._nativeGpuInfo` | adapter string after first boot |
| `nativeValidateGameFiles(dir): Boolean` | `..._nativeValidateGameFiles` | structural checks, emits `validate` events |
| `onNativeEvent(json)` (static) | `emit_event` | called from Rust worker threads |

Keep the two files in lockstep. The JSON contract is serde-driven
(`LaunchConfiguration` in `lib.rs`) so new fields don't need new JNI surface.

---

## 3. Integration into the repository

Run `./install-into-repo.sh /path/to/horizon` (copies crate, android project,
scripts, `.cargo/config.toml`, CI, and this file as `PORTING-ANDROID.md`),
then make these edits in the repo root:

1. **`Cargo.toml`** — add `"crates/horizon-android"` to workspace `members`.
   No exclusions needed: the crate's desktop bin target is feature-gated
   (`required-features = ["desktop"]`), so Android builds never compile it.
2. **wgpu-hal patch applies automatically.** The repo's local
   `third_party/wgpu-hal` (30.0.1) patch — the Vulkan descriptor-pool fix —
   flows to `horizon-android` because it pins the same `wgpu = "30"` line.
   That fix matters on Android too: Adreno and Mali both have descriptor-pool
   quirks in the same code paths the patch touches.
3. **Optional:** switch the crate's `Cargo.toml` dependencies to
   `workspace = true` where the workspace manages versions (wgpu, winit, log,
   serde). The scaffold ships self-contained so it builds standalone;
   the comment block in its `Cargo.toml` marks exactly which lines to change.

Edition 2024 / rustc 1.85 minimum matches the workspace. The crate uses
`#[unsafe(no_mangle)]` (edition-2024 spelling) for all exported symbols.

---

## 4. Build system

### Toolchain matrix (verified combination)

| Tool | Version |
|---|---|
| Rust | stable (1.99 used for verification) |
| wgpu | 30.0.1 |
| winit | 0.30.13 (+ `android-native-activity`) |
| android-activity | 0.6.1 (via winit re-export) |
| jni | 0.22.4 (matches android-activity) |
| AGP | 9.5.0 |
| Gradle | 9.8.2 |
| Kotlin | 2.4.21 (AGP 9 built-in Kotlin — **no** `kotlin-android` plugin) |
| compileSdk | 37 (minor 2) — required by Compose 1.12 |
| minSdk / targetSdk | 35 (Android 15) / 36 |
| Compose BOM | 2026.09.00 |
| MSAL | 8.6.0 |

### Commands

```sh
# One-time
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo install cargo-ndk
export ANDROID_HOME=...   # with an NDK under $ANDROID_HOME/ndk/

# Native libs → android/app/src/main/jniLibs/
./scripts/build-android-libs.sh --profile dev      # or release | fast
./scripts/build-android-libs.sh --abis arm64-v8a   # fast local iteration

# APK
./scripts/build-apk.sh --variant debug             # rust libs + gradle
cd android && ./gradlew assembleDebug              # gradle only (libs already built)

# Device
./scripts/run-on-device.sh                         # install + logcat -s horizon
```

Gradle can also chain the Rust build: `./gradlew assembleDebug -PrustBuild`.

### CI

`.github/workflows/android.yml` runs three jobs: per-target `cargo check` +
unit tests, a debug APK (all four ABIs), and an unsigned release AAB on
`main`. NDK version is auto-selected (`sdkmanager --list | sort -V | tail`),
so CI doesn't rot when a new NDK ships.

---

## 5. Graphics on Android

### Why Vulkan-only

The desktop client is Vulkan on Linux/Windows (Metal on Mac). Matching that
backend keeps the tested code paths identical and — critically — lets the
workspace's local `wgpu-hal` patch apply as-is. Android 15 devices with
Vulkan 1.1+ (enforced via `uses-feature android.hardware.vulkan.version
0x401000`) cover effectively every device class the game can reasonably
target.

If GL fallback ever becomes necessary: wgpu's GLES backend compiles for
Android, but `InstanceDescriptor` then needs the display-handle path for
Wayland-style ANativeWindow presentation, several of Horizon's shaders assume
feature levels that vary on GLES, and the swapchain color-space handling
diverges. Budget it as a project, not a flag.

### wgpu 30 specifics this scaffold already handles

These API points bit during development — the code contains the corrections:

* `Instance::new` takes `InstanceDescriptor` **by value** and the struct has
  no `Default` — build via `InstanceDescriptor::new_without_display_handle()`
  then set `backends`.
* `request_adapter`/`request_device` return futures — the scaffold blocks on
  them with `pollster` from the `android_main` thread (correct: it's a
  dedicated thread, not the Java main thread).
* `get_current_texture()` returns a `CurrentSurfaceTexture` **enum** (not a
  `Result`): `Success`/`Suboptimal` carry a texture; `Timeout`/`Occluded`
  mean skip the frame; `Outdated`/`Lost`/`Validation` mean reconfigure.
  `demo.rs::FrameError` folds this into the two states the shell reacts to.
* Presenting moved to the queue: `queue.present(frame)`.
* `RenderPipelineDescriptor`/`RenderPassDescriptor` take `multiview_mask` and
  `VertexState.buffers` entries are `Option<VertexBufferLayout>`; pipeline
  layouts use `immediate_size` (push constants are gone).

### Mobile GPU notes

| Family | Notes |
|---|---|
| Adreno 7xx/8xx (Snapdragon) | Best-behaved wgpu target. Watch `VK_ERROR_DEVICE_LOST` under thermal throttle — it surfaces as `CurrentSurfaceTexture::Lost`/validation errors; reconfigure and continue. |
| Mali-G / Immortalis (MediaTek, Exynos) | Descriptor-pool and sparse-binding quirks are in the same territory as the Intel fix the workspace's wgpu-hal patch addresses. If you see hangs in `allocate_descriptor_sets`, check whether the patch covers the code path. |
| Xclipse (Samsung AMD-derived) | RDNA-based; subgroup support exists but prefer feature-checking (`adapter.features()`) over assuming. |

Shader-side: audit `horizon-shaders` for desktop-assumed features
(subgroups, f16, timestamp queries) and gate them on
`adapter.features()`/`limits()` rather than compile-time cfg. wgpu 30's
`RequestAdapterOptions.apply_limit_buckets` is relevant here: it lets you
request adapter limit buckets so the client can pick its render path the same
way it picks graphics presets today.

### Surface configuration

`backend.rs::configure_surface` picks the first sRGB format, `PresentMode::Fifo`
(vsync — the only universally supported mode on Android), `CompositeAlphaMode::Auto`,
`desired_maximum_frame_latency = 2`. For lower input latency on 120 Hz panels,
experiment with `desired_maximum_frame_latency = 1` behind the existing
graphics presets.

---

## 6. Crate-by-crate portability audit

Audited against the repository structure; items marked ⚠ need in-repo
verification during Phase 1 — treat them as the first week's checklist.

| Crate | Android readiness | Work needed |
|---|---|---|
| `horizon-core` | ✅ Pure logic | None expected; run it under the host test suite for aarch64. |
| `horizon-client` | ✅⁻ wgpu + winit already | The main porting surface — see § 6.1. |
| `horizon-server` | ✅ Pure Rust + tokio | Should compile for Android as-is. Interesting option: run it on-device to host LAN worlds (Phase 6). |
| `horizon-protocol` | ✅ tokio networking | None; mind Android's cleartext-traffic policy for LAN discovery (document `networkSecurityConfig` if mCasting-style discovery uses UDP broadcast — it's fine, but http cleartext to arbitrary IPs is not). |
| `horizon-assets` | ✅⁻ | Filesystem paths must come from the JNI-injected `game_dir`/`local_dir`, never `dirs`-style home lookup (§ 6.2). |
| `horizon-prepare` | ⚠ The JDK-25 blocker | § 7. |
| `horizon-shaders` / `horizon-shader-api` | ✅ WGSL | Feature-audit against mobile adapters (§ 5). |
| `horizon-threads` | ⚠ | If it sets thread priority/affinity via OS APIs, map to Android: name threads (`prctl PR_SET_NAME` — the NDK does this via `pthread_setname_np`) and big.LITTLE core selection differs from desktop schedulers. |
| `horizon-dualsense` | ✅⁻ Input | Horizon already has a controller input layer — map winit's Android `KeyEvent`/`MotionEvent` (gamepad axes/buttons) into it. Bluetooth gamepads work day one via android-activity's input plumbing; the touch overlay is the additional path. |
| `horizon-hdr` | ⚠ | Desktop-oriented (HDR metadata/tonemapping). wgpu-hal's Vulkan backend exposes HDR via surface color spaces on Android too, but device support is uneven; keep it behind a preset until tested. ⚠ Verify what surface `color_space`s the adapters report. |
| `horizon-mod-sdk` / `horizon-mods` (WASM) | ⚠ | wasmtime builds on `aarch64-linux-android` (Termux proves it), but the workspace's wasmtime feature set (threads? component model?) needs a compile check in Phase 1. |
| `horizon-telemetry-server`, `horizon-mod-registry` | — | Excluded from the workspace upstream; ignore. |
| `xtask` | — | Host tooling; unaffected. |
| `launcher` (desktop) | — | Gains the "Export for Android" feature (§ 7), not a port target. |

### 6.1 `horizon-client` — the actual wiring

The integration point is `GameShell` in `crates/horizon-android/src/lib.rs`:

1. Replace `demo::Renderer` with the client's renderer/app state. The shell
   already provides: window creation on `resumed`, teardown on `suspended`,
   resize handling, redraw pumping, `TouchOverlay` input, and the
   `LaunchConfiguration` (paths, render distance, preset, username).
2. The client's event loop presumably runs its own `EventLoop` on desktop.
   Two options, in order of preference:
   a. Refactor the client to expose `ClientApp` implementing winit's
      `ApplicationHandler` — then `GameShell` just holds it and forwards
      events (the cleanest; winit's trait makes this mechanical).
   b. If the client's loop is monolithic, run it behind a facade that
      translates the shell's callbacks into the client's expected calls.
3. Feed `touch::InputAction`s into the client's existing input-binding layer
   (the same place keyboard/mouse/dualsense inputs land) — see § 8.
4. Limits audit: replace `Limits::default()` in `backend.rs` with the
   client's required limits, and add an adapter feature check that logs
   (and preset-degrades) instead of panicking on missing mobile features.

### 6.2 Filesystem rules on Android

* App-private paths (`filesDir/game`, `filesDir/local`) need no permissions
  and survive reinstalls — that's where the launcher imports everything.
  Rust receives absolute paths via `LaunchConfiguration`.
* No `$HOME/.minecraft`. If the client (or any dep) calls `dirs::home_dir`
  or similar, replace with config-injected paths. This is the single most
  common crash source in NativeActivity ports: a dep expecting a desktop
  filesystem.
* `std::fs` works normally inside app storage. Scoped storage only matters
  for SAF imports, which Kotlin handles before Rust ever runs.

---

## 7. The prepare problem (`horizon-prepare` + JDK 25)

The desktop flow is:

```
MS sign-in → prepare files from the user's own MC Java 26.3 install
           → horizon-prepare (runs a server jar under JDK 25, checksums vs
             research-lock.json) → .local/ prepared resources → play
```

Horizon **never downloads game files**, and `horizon-prepare` needs a real
JDK 25 at prepare time. Android cannot run arbitrary JDK-25 jars (ART is not
a JVM), and bundling a JVM in an APK is not viable. Three options, in the
order the port should pursue them:

### Option A — Portable export (recommended, ships first)

The desktop launcher gains an **"Export for Android"** action:

1. Runs the normal prepare on the PC (`.local/` output).
2. Zips `versions/` + `assets/` + `libraries/` + `local/` (the prepared
   output) + a `prepared.json` manifest (format version, MC version,
   checksums, export date).
3. User transfers the zip to the phone (USB, cloud, local network —
   anything), taps **Import portable export** in the Android launcher.

The Android side is already built: `GameFilesManager.importFromZip` unpacks
exactly this layout into `files/game` + `files/local`, and validation
recognizes `prepared.json`. The desktop-side export button is a small Tauri/
launcher feature — file an upstream issue or PR.

**Size note:** a full export is multi-GB. Two mitigations for later: split
the export into `base` (versions + libraries) and `assets` (the bulk), and
document that `assets/` can be re-exported alone after resource-pack changes.

### Option B — Pure-Rust on-device prepare

`horizon-prepare`'s JDK dependency exists to run/parse the server jar for
parity data. A pure-Rust reimplementation of those extraction steps removes
the JVM entirely and makes on-device prepare possible (raw `.minecraft`
import → prepare on the phone). This is real upstream engineering — the
parsing logic is shared, only the execution/verification strategy differs.
Track it as its own workstream; the `nativeValidateGameFiles` structural
checks are the natural seam to extend.

### Option C — Bundled headless JDK

Not recommended. ~200MB, Play Store policy friction, and Option B kills the
need for it.

---

## 8. Touch overlay

`crates/horizon-android/src/touch.rs` is a complete, tested state machine
(5 unit tests) that turns winit touch events into `InputAction`s. It is
input-intent only — no rendering, no client coupling.

### Layout (landscape-first, proportional to `min(w, h)`)

```
┌──────────────────────────────────────────────────────────────┐
│ [pause]        [hotbar: 9 slots, top center]      [inv][chat]│
│ [zoom]                                                      │
│                                                    [jump]    │
│               look-drag zone                       [attack]   │
│          (right of x=0.42w, below hotbar)          [sneak]    │
│  ◉ move-stick                                     [use][pick]│
│ (fixed base, Bedrock-style)                                   │
└──────────────────────────────────────────────────────────────┘
```

* **Fixed-base joystick** (classic Bedrock feel): engage anywhere in the left
  zone, stick anchored at its layout position. 50%-of-knob deadzone, clamped
  to unit circle, y inverted (drag up = forward).
* **Look zone**: right side; deltas are `Look { dx, dy }` in logical px —
  apply `sensitivity` (from `LaunchConfiguration`) and invert-look at the
  consumer.
* **Buttons** have 1.35× hit tolerance (thumb precision), light up when held,
  and emit Begin/End pairs so charge-and-release mechanics (bow, shield)
  work.
* **Hotbar**: 9 slots along the top; tap selects; selection is echoed back by
  `hotbar_geometry()` for drawing.

### Mapping to client input

| InputAction | Client binding (same layer as keyboard) |
|---|---|
| `Move { x, y }` | analog strafe/forward (sprint = stick at rim > 0.95, or the sprint button) |
| `Look { dx, dy }` | mouse-delta path (sensitivity applied) |
| `Jump` / `SneakBegin/End` / `SprintBegin/End` | key states |
| `AttackBegin/End` / `UseBegin/End` / `PickBlock` | mouse buttons |
| `HotbarSlot(0..=8)` | hotbar keys 1-9 |
| `Inventory` / `Chat` / `Pause` / `ZoomBegin/End` / `ToggleDebug` | keys E / T / Esc / C / F3 |

### Tap-to-place trade-offs

Bedrock's "tap to place / long-press to break" is deliberately **not**
auto-mapped. It fights with look-drag semantics and crosshair-based vanilla
mechanics (ranged attacks, block targeting). If you want it later, add it as
a settings toggle implemented on top of `Look` drags + a `ToggleDebug`-style
action — the state machine's `Tracked::Look` variant is where a long-press
timer would live.

### Drawing the overlay

`TouchOverlay::geometry()` returns circles (color + alpha, `a == 0` → ring);
`hotbar_geometry()` returns the strip + selected slot. The placeholder
renderer converts these to triangle fans — swap that for the client's UI
renderer (same data) in Phase 3. HUD-scale note: sizes are relative to
`min(w, h)` so nothing covers the world on tablets.

---

## 9. Auth & entitlements

### MSAL setup (one-time)

1. Azure Portal → App registration (the desktop launcher's registration
   works if it has a mobile platform; otherwise add one).
2. Add **Mobile and desktop application** platform → redirect URI
   `msauth://dev.horizon.android/<signature-hash>`.
3. Signature hash:
   ```sh
   keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore \
     -storepass android | openssl sha1 -binary | base64 | tr '+/' '-_' | tr -d '='
   ```
4. Put the client id + hash into `res/raw/auth_config.json` **and** the
   `BrowserTabActivity` intent-filter path in the manifest.

Until that's done, `HorizonApp` falls back to `StubAuthManager` (local
`PlayerNNN` profile) — the launcher shows a note in that mode.

### The entitlement chain (Phase 5 work)

MSAL gives you an MSA token; proving Minecraft ownership and getting the real
profile is the standard launcher chain, same calls the desktop launcher makes:

```
MSA token (scope XboxLive.signin)
  → POST xbl.xboxlive.com/user/authenticate        (userhash + XSTS token)
  → POST api.minecraftservices.com/authentication/login_with_xbox
  → profile via api.minecraftservices.com/minecraft/profile
```

Store tokens via MSAL's account cache (already wired by `signIn`); the
`McProfile.uuid`/`username` fields are the output. The scaffold stops after
MSAL sign-in and marks this clearly in `MsalAuthManager` — implement the two
REST calls with OkHttp/ktor when multiplayer lands, then delete the stub note.

---

## 10. Performance engineering

* **Target:** 60 fps on a Snapdragon 8-gen-class phone at render distance 8;
  budget ~12 ms frame, ~2.5 GB peak native memory.
* **Render distance is the lever.** The launcher already caps at 16; default
  8. `graphics_preset` maps onto Horizon's existing presets — wire
  `LaunchConfiguration.graphics_preset` into the same preset loader the
  desktop menus use.
* **Threads:** `horizon-threads` should discover cores via
  `std::thread::available_parallelism()` (respects Android's cgroup CPU
  affinity, which the scheduler sizes per-device) rather than assuming
  P-core counts.
* **Thermal:** sustained loads throttle Adreno/Mali within minutes. For
  profiling, use 15+ minute sessions, not fresh-boot benchmarks. Expose the
  frame-time graph from Horizon's debug HUD on-device early.
* **Memory:** `Activity.onLowMemory`/`onTrimMemory` surface into Rust only
  through the winit lifecycle — prefer reacting to `suspended` (drop surface,
  save world) and letting Android's LMK handle the rest. Never keep GPU
  resources across suspend.

---

## 11. Lifecycle & session persistence

* `android_main` **can be called more than once per process** (activity
  recreation, e.g. foldables changing configuration despite
  `configChanges`). All state is per-call; process-globals are `OnceLock`s.
* On `suspended`: drop surface + window (done), then (client integration)
  save the world and pause the tick loop. If the process is killed while
  suspended, that save is your resume point.
* Back button: with native-activity, key events arrive through winit's
  `WindowEvent::KeyboardInput` on Android — map the back key to the pause
  menu (don't exit the process). ⚠ Verify the exact scancode on-device in
  Phase 1; it's the one input path the placeholder didn't exercise.
* Screen rotation: `userLandscape` + `configChanges` avoids activity
  recreation for orientation; resizes still arrive as `WindowEvent::Resized`.

---

## 12. Debugging & profiling

* **logcat:** `adb logcat -s horizon` — all Rust logging goes through
  `android_logger` with the `horizon` tag. Panics print with backtraces if
  you build with `RUST_BACKTRACE=1` equivalent (`ndk-stack` below).
* **Symbolized native crashes:**
  ```sh
  adb logcat -b crash
  ndk-stack -sym target/aarch64-linux-android/debug < crash.txt
  ```
  Keep unstripped build outputs; the release `.so` in the APK is stripped.
* **RenderDoc:** works against Android Vulkan — package a debug build, attach
  to `dev.horizon.android`, capture frames of the placeholder first to
  validate the pipeline before the client lands.
* **Android GPU Inspector (AGI):** frame profiling on Adreno/Mali; use for
  the real client's draw-call budgeting.
* **simpleperf:** native CPU profiling of the Rust code
  (`simpleperf record -p $(pidof dev.horizon.android)`); works without
  source changes.
* **Perfetto:** system traces incl. surface flinger + thermal.
* **wgpu traces:** `DeviceDescriptor.trace` (wgpu 30: `Trace::Path`) — record
  on device, replay on desktop with the same adapter feature set.

---

## 13. Troubleshooting

Every entry below was hit and fixed while building this scaffold.

| Symptom | Cause | Fix |
|---|---|---|
| `error: no 'SurfaceError' in wgpu root` / `poll` signature mismatch | wgpu 30 API changes (enum `CurrentSurfaceTexture`, `PollType::Wait { .. }`, `queue.present`) | Use the patterns in `backend.rs`/`demo.rs`; they're the corrected forms. |
| `android_binding!` not found | android-activity 0.6 removed it | Entry is `#[unsafe(no_mangle)] fn android_main(app: AndroidApp)` with `AndroidApp` from **winit's** re-export. |
| Two `android_main` symbols / link errors | a direct `android-activity` dep at a different version than winit's | Don't depend on android-activity directly. |
| `SurfaceError`-style `Outdated` spam after locking/unlocking the screen | surface kept across suspend | `suspended` must drop renderer + window — see `GameShell`. |
| `dlopen failed: library "libhorizon_android.so" not found` | jniLibs missing (Rust build skipped) | Run `scripts/build-android-libs.sh` first, or `./gradlew assembleDebug -PrustBuild`. |
| `JNI_OnLoad` runs but `find_class` fails later from Rust threads | system classloader | The bridge caches a `Global<JClass>` on the Java thread at `nativeConfigure` — keep that pattern for new entry points. |
| Gradle: `Toolchain ... does not provide JAVA_COMPILER` | JRE-only Java | Install a full JDK 17+ (21 recommended for AGP 9). |
| Gradle: `display-mask:0.3.0` unresolvable | MSAL's `common` declares a dead jcenter artifact | Already excluded in `app/build.gradle.kts`; keep the exclude until Microsoft republishes. |
| Gradle: `requires compileSdk 37` | Compose 1.12 (BOM 2026.09) needs API 37 | `compileSdk = 37; compileSdkMinor = 2` (targetSdk stays 36). |
| AAPT: `unexpected element <queries> found in <manifest><application>` | queries must be a manifest-level sibling | See the manifest — it's in the right place now. |
| MSAL `onAccountLoaded` override mismatch | 8.x takes nullable params | Match `MsalAuthManager.kt` exactly. |
| Maven Central bursts of HTTP 429 in CI | rate limiting | Gradle caches make this rare; if it recurs, add a repo mirror in `settings.gradle.kts`. |
| `no Vulkan adapter found` on emulator | emulator without Vulkan | Use `-gpu swiftshader_indirect` (software Vulkan) or a physical device. |
| `armv7` link failures on some NDK versions | 16 KB page sizes (newer NDKs default `-Wl,-z,max-page-size=16384`) | Fine for minSdk 35 devices; if you see alignment crashes on old images, pin the NDK's page-size flag. |

---

## 14. Roadmap

| Phase | Deliverable | Notes |
|---|---|---|
| 0 (this scaffold) | Launcher + shell + demo renderer + docs | Done. |
| 1 | `horizon-client` boots on device: menus render | § 6.1 wiring; limits/feature audit; back-key verification. |
| 2 | World render at reduced distance | Preset mapping, chunk-budget tuning on-device. |
| 3 | Touch overlay in-game via client input layer | § 8 mapping; overlay drawn by client UI renderer. |
| 4 | Prepare pipeline | Desktop "Export for Android" (Option A) then evaluate Option B. |
| 5 | Entitlements + multiplayer | § 9 chain, LAN + servers. |
| 6 | Release engineering | Signed AAB, Play asset delivery (split the export), F-Droid notes, staged rollout. |

---

## 15. Legal

Same rules as upstream Horizon: the app contains **no Minecraft assets** and
never downloads them — the user imports their own files, exactly like the
desktop launcher. "Horizon" is an unofficial project; keep Mojang trademark
disclaimers in store listings, and don't ship the Azure client id of anyone
who hasn't consented.
