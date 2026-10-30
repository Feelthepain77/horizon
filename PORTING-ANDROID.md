# Porting Horizon to Android

**Status: scaffold — the foundation is built and verified, the game itself is not yet wired in.**

This document is the engineer's handbook for running the Horizon client on
Android. It covers what this scaffold provides, how to integrate it into the
Horizon repository, what each workspace crate needs for Android, and the
debugging/troubleshooting playbook.

Everything marked **verified** below was actually executed while building this
scaffold (2026-10-30): the Rust crate type-checks against the real dependency
tree on `aarch64-linux-android` (arm64-v8a — the only ABI the port builds; the
scaffold era verified all four, and 2026-10 cut the other three as bloat), and
the Gradle project produced real debug and release APKs with AGP 9.5.

---

## 1. What this scaffold is (and isn't)

| Piece | State |
|---|---|
| `crates/horizon-android` — android-activity/winit shell, Vulkan-only wgpu bootstrap, touch overlay, JNI bridge, placeholder renderer | **Code complete.** `cargo check` passes on `aarch64-linux-android`; unit tests pass; clippy clean |
| `android/` — Kotlin Compose launcher (MSAL sign-in, game-file import via SAF, settings, device info) + NativeActivity host | **Builds.** `assembleDebug` + `assembleRelease` verified with AGP 9.5.0 / Gradle 9.8.2 / Kotlin 2.4.21 |
| Native `.so` link step (cargo-ndk) | Scripted, exercised in CI; not linked in this sandbox (no NDK) |
| `horizon-client` actually rendering on device | **Not started** — that's the porting work this handbook walks you through |
| On-device prepare from `.minecraft` | **Ships** — file steps run on the phone (`prepare.rs`); the Java-25 exporter tables arrive via a **one-time** portable-export import; see § 7 |

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
| `nativeValidateGameFiles(dir): Boolean` | `..._nativeValidateGameFiles` | play-time inspection, emits `validate` events |
| `nativePinnedVersion(): String` | `..._nativePinnedVersion` | the MC version research-lock.json pins |
| `nativePrepareFromMinecraft(root): String` | `..._nativePrepareFromMinecraft` | on-device prepare; emits `prepare` events, returns the report JSON |
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
rustup target add aarch64-linux-android
cargo install cargo-ndk
export ANDROID_HOME=...   # with an NDK under $ANDROID_HOME/ndk/

# Native libs → android/app/src/main/jniLibs/
./scripts/build-android-libs.sh --profile dev      # or release | fast (arm64-v8a only)
./scripts/build-android-libs.sh --abis x86_64      # escape hatch for local emulator work

# APK
./scripts/build-apk.sh --variant debug             # rust libs + gradle
cd android && ./gradlew assembleDebug              # gradle only (libs already built)

# Device
./scripts/run-on-device.sh                         # install + logcat -s horizon
```

Gradle can also chain the Rust build: `./gradlew assembleDebug -PrustBuild`.

### CI

`.github/workflows/android.yml` runs three jobs: `cargo check` (arm64-v8a) +
unit tests, a debug APK (arm64-v8a), and an unsigned release AAB on
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

**Shipped.** The real client runs on Android today:

* `horizon-client/src/game.rs` gained `pub fn run_with(args)` (a thin
  threading of the existing `Options::read` argv parse), and the client
  library now also compiles `game.rs` (`extern crate self as horizon_client`
  in its lib.rs keeps its `use horizon_client::...` imports resolving), so
  `crates/horizon-android` can drive it from `android_main`, where there is
  no argv to read.
* **The event loop needs the `AndroidApp`.** winit on Android only creates
  an `EventLoop` from the handle `android_main` receives — a bare
  `EventLoop::new()` panics there, which is why PLAY used to open a game
  window that died instantly. `android_main` calls
  `horizon_client::game::set_android_app(app.clone())` before
  `client::launch`; the client's two loop creations (`run_loading`,
  `run_event_loop`) go through `game.rs::event_loop()`, which builds from
  that stored handle (a `Mutex<Option<_>>`, replaced on every activity
  recreation — not a `OnceLock`, which would keep a dead handle). On every
  other platform `event_loop()` is exactly `EventLoop::new()`.
* `crates/horizon-android/src/client.rs` builds the launch: `HORIZON_HOME`
  + cwd = the game home, `HORIZON_TELEMETRY_URL=off`, then
  `--resources <prepared root>` (see SS 7), `--render-distance`,
  `--name`, `--session-file`. Without a prepared root it returns `Ok(false)`
  and the shell falls back to the demo renderer.
* **Suspend/resume follows winit's Android contract** (`Resumed` == native
  window available, `Suspended` == native window gone): both `Game` and the
  loading screen drop their wgpu surface (`Renderer::drop_surface`, which
  also clears the spare set aside for a successor) and their window on
  `Suspended`, and rebuild both on `Resumed` on the same device — the
  established graphics-settings-change rebuild path. A load that finishes
  while suspended hands the game over without a renderer; `Game::resumed`
  builds it on return. While suspended the tick loop parks on
  `ControlFlow::Wait` instead of simulating headless. Held joystick keys
  and touches are released at suspend (their lift events never come).
* Touch input is translated inside the client (`game.rs`, cfg-gated
  `touch_input` + `Game::translate_touch`): touches become the desktop
  events the game already understands, so no other game logic is
  Android-aware. Keys go through `mapped_key` (the unified entry point the
  keyboard and controllers share) because winit's `KeyEvent` cannot be
  constructed outside winit; mouse-shaped events are constructed directly.
  The gesture map is SS 8. The system Back key (winit delivers it as
  `BrowserBack`; it has no physical code) maps to Escape: pause menu in
  game, back out of screens.
* The client is built `--no-default-features`: no audio (no ALSA on
  Android; sound is the cpal/Oboe workstream) and no controllers (gilrs has
  no Android backend; the touch overlay replaces them).

Desktop debugging still works: the `desktop` feature runs the demo shell
as before, and `cargo run -p horizon-client` is unchanged.

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

## 7. The prepare problem (`horizon-prepare` + JDK 25) — the split pipeline that ships

The desktop flow is:

```
MS sign-in → prepare files from the user's own MC Java 26.3 install
           → horizon-prepare (runs the exporter tables under JDK 25, checksums vs
             research-lock.json) → .local/horizon-client/<version> → play
```

Horizon **never downloads game files**, and a full prepare needs a real JDK 25
for the ~19 exporter tables (`facts.json`, `model-states.json`,
`cached-collision.nbt`, `gameplay-facts.json`, …) that JVM code calling
Mojang's own classes produces. Android cannot run those (ART will not load
Java-25 class files, and targetSdk 29+ forbids exec'ing a JVM from app
storage), and bundling a JVM in an APK is not viable (~200 MB, Pojav-scale
engineering, store-policy friction).

**What ships (2026-10) is a split pipeline.**

* **On-device, on the phone** (`crates/horizon-android/src/prepare.rs` +
  `GameFilesManager.importFromMinecraft`): the launcher asks for the player's
  `.minecraft` **on launch** (no PC in the loop), SAF-stages the parts the
  prepare reads (`versions/<version>/`, the pinned version's slice of
  `assets/` and `libraries/`; `saves/` is copied into the game home), then
  Rust checks the client jar (sha1 against research-lock.json), extracts its
  `assets/` + `data/` + `pack.png`, and copies the asset store's sounds and
  the panorama/Unifont/built-in-pack/language overlays. **No server jar is
  read or wanted** — the three reference tables a desktop derives from it
  (`block-tags.json`, `damage-types.json`, `damage-tags.json`) are exporter
  output and arrive with the portable export like every other table.
* **Once, from anywhere** — the Java-25 exporter tables arrive through a
  **portable export** import (a zip or folder containing the prepared root
  marked by `horizon-resources.json`; produce it by preparing on any desktop
  that ran the pinned version and zipping `.local/horizon-client/<version>`).
  Any export of the pinned version works: the tables are deterministic
  outputs of the pinned jars.

Both paths write the **same** prepared root
(`files/prepare-root/.local/horizon-client/<version>`), so they compose.
Preparation is incremental — hash-keyed steps are skipped while their inputs
and outputs are unchanged — so **the export is needed exactly once, ever**:
every later on-device prepare re-derives everything else from `.minecraft`
alone and leaves the exporter entries in `horizon-resources.json` untouched.
The launcher gates PLAY on the inspection (`prepare::REQUIRED_FILES`) and
shows exactly which tables are missing while any are.

**Storage cost of the no-PC path:** staging (the copied `.minecraft` parts),
the prepared root (JAR resources + ~380 MB of sounds) and the importer's
verified jar copies land around 2–2.5 GB. `Delete files` clears all three.

The desktop launcher's "Export for Android" button is still upstream work —
until it exists, produce the export by preparing on the PC (`horizon-prepare`)
and zipping the `.local/horizon-client/<version>` directory.

### What was rejected

* **Bundled headless JDK**: ~200 MB, Play Store policy friction, and the
  split pipeline reaches playability without it.
* **Pure-Rust reimplementation of the exporter tables**: real upstream
  engineering (upstream built the exporters precisely so vanilla behavior
  comes from Mojang's own code); it would remove the one-time export, but it
  is not needed to play. The `prepare::REQUIRED_FILES` seam is where it
  would land if ever attempted.

---

## 8. Touch overlay

**Shipped** (in the client, `game.rs` cfg(android) `touch_input`): the zones
are fixed fractions of the window and invisible in this version — drawing
them belongs with the client's HUD pass. On menus, a touch is a cursor move
plus a left click (press on start, moves in between, release on lift), so
every screen's hit-testing and sliders work unmodified.

In game (Screen::Playing), the gesture map:

| Gesture | Action |
|---|---|
| Touch in lower-left area, drag | Invisible joystick: 8-way WASD by angle; past 80% of its rim sprint (Left Ctrl) |
| Touch elsewhere, drag | Camera look (the same `MouseLook::on_move` raw mouse motion takes), gain 1.35 |
| Short still tap | Left click — attack / UI click |
| Long still press (>=380 ms) | Right click — place / use |
| Lift in the bottom 12% strip | Hotbar slot 1-9 by x position (the digit keys) |
| Second finger down in the play area | Inventory toggle (E) |
| Touch in the top-left corner (12% x 8%) | Pause menu (Escape) |

Thresholds (physical pixels, tuned for 1080p-class screens): dead zone 28,
joystick rim 200, tap travel 18, tap 280 ms, long press 380 ms. All are
constants in `touch_input`; density scaling is future work.

### The old overlay (kept for the desktop demo shell)

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


### Local accounts (shipped)

A local account needs no Microsoft anything: pick a name (3-16 of
`A-Za-z0-9_`), and the UUID is vanilla's offline scheme —
`UUIDv3("OfflinePlayer:<name>")` (`UUID.nameUUIDFromBytes` in Kotlin) — so
identity is stable across reinstalls. Singleplayer, LAN and offline-mode
servers work; online-mode servers refuse it by design (ownership cannot be
proven without Microsoft's chain).

The launch hands it to the client the same way the desktop launcher hands
over a Microsoft session: a session file, written fresh right before the
start (the client refuses one older than 120 s, requires owner-only
permissions, and deletes it after reading):

```json
{"name": "Steve", "uuid": "<dashed uuid>", "access_token": "offline"}
```

`access_token` must be non-empty but is only used by online-mode server
joins, which a local account never makes. See `LocalAccountManager` (Kotlin)
and `auth.rs` in the client (the parser).

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
  `configChanges`). All state is per-call; process-globals are `OnceLock`s
  — except the client's `AndroidApp` handle, which the shell replaces via
  `set_android_app` on every `android_main` (a `OnceLock` would keep the
  dead previous handle).
* On `suspended` (done, in the client): surface + window dropped
  (`Renderer::drop_surface` + `window = None`), held touches/keys released,
  tick loop parked on `ControlFlow::Wait`. On `resumed`: window + renderer
  rebuilt on the same device. The world keeps running from memory across a
  suspend; if the process is killed while suspended, the last autosave is
  the resume point. The loader thread is not paused — a backgrounded load
  finishes, and the handoff happens without a renderer if the window is
  gone (`Game::resumed` builds it on return).
* Back button: winit delivers Android's Back as `BrowserBack` (a named
  key, no physical code); `Game::translate_touch` maps it to Escape — pause
  menu in game, back out of screens — so it never exits the process.
* Quitting the game: the title screen's Quit Game exits the event loop,
  `run_with` returns, `android_main` returns, and the android-activity
  glue calls `ANativeActivity_finish` — the user lands back in the
  launcher.
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

---

## 14. Roadmap

| Phase | Deliverable | Notes |
|---|---|---|
| 0 (this scaffold) | Launcher + shell + demo renderer + docs | Done. |
| 1 | `horizon-client` boots on device: menus render | § 6.1 wiring; limits/feature audit; back-key verification. |
| 2 | World render at reduced distance | Preset mapping, chunk-budget tuning on-device. |
| 3 | Touch overlay in-game via client input layer | § 8 mapping; overlay drawn by client UI renderer. |
| 4 | Prepare pipeline | **Done (split):** on-device file steps from `.minecraft` + one-time portable export for the exporter tables; see § 7. |
| 5 | Entitlements + multiplayer | § 9 chain, LAN + servers. |
| 6 | Release engineering | Signed AAB, Play asset delivery (split the export), F-Droid notes, staged rollout. |

---

## 15. Legal

Same rules as upstream Horizon: the app contains **no Minecraft assets** and
never downloads them — the user imports their own files, exactly like the
desktop launcher. "Horizon" is an unofficial project; keep Mojang trademark
disclaimers in store listings, and don't ship the Azure client id of anyone
who hasn't consented.
