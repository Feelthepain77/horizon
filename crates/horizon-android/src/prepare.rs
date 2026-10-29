//! On-device preparation of the game resources from the player's own
//! `.minecraft` folder — the "no PC anywhere" path.
//!
//! horizon-prepare's pipeline is two kinds of steps:
//!
//! * **file steps** — importing and checking the pinned jars, extracting the
//!   client JAR's `assets/` + `data/` + `pack.png`, copying the asset store's
//!   sounds and overlays, the reference tag/damage bundles. Pure Rust; they
//!   run here, on the phone.
//! * **exporter steps** — the behaviour/render tables (`facts.json`,
//!   `model-states.json`, `cached-collision.nbt`, `gameplay-facts.json`, …)
//!   produced by JVM code that calls Mojang's own classes, pinned to Java 25.
//!   Android cannot run those (ART will not load Java-25 class files, and
//!   targetSdk 29+ forbids exec'ing a JVM from app storage), so the tables
//!   arrive through a **one-time portable-export import** — any export of
//!   the pinned version works, because the tables are deterministic outputs
//!   of the pinned jars.
//!
//! Preparation is incremental (a step is skipped while its inputs and
//! outputs are unchanged), so the tables only ever have to be imported
//! once: every later run of this module re-derives everything else from
//! `.minecraft` alone and leaves the exporter entries in the manifest
//! untouched. That is the whole trick behind "the PC is needed exactly
//! once, ever".
//!
//! # Layout (under the app's files dir)
//!
//! ```text
//! prepare-root/research-lock.json                written from this build's copy
//! prepare-root/.local/minecraft/<version>/       imported, verified jars (artifacts)
//! prepare-root/.local/horizon-client/<version>/  THE prepared root = --resources;
//!                                                 the portable-export import writes
//!                                                 the same directory
//! minecraft-staging/                             the SAF-copied .minecraft:
//!                                                 versions/<version>/, assets/,
//!                                                 libraries/, the server jar
//! ```
//!
//! Kotlin stages the `.minecraft` (SAF is unreachable from Rust) and then
//! calls [`prepare_from_minecraft`] on a background thread; everything below
//! runs there.
//!
//! Storage cost of the no-PC path, for the docs: staging is the size of the
//! player's `assets/` + `libraries/` + the two jars, the prepared root adds
//! the extracted JAR resources plus the ~380 MB of sound objects, and
//! `.local/minecraft/<version>` keeps another copy of the jars for the
//! importer's checks. A full install lands around 2–2.5 GB on the device.

use std::fs;
use std::io::Read as _;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use horizon_prepare::artifacts::{self, Lock};
use horizon_prepare::pyjson::{self, Value};

/// research-lock.json as shipped by this build: the single source of truth
/// for the pin on both sides of the JNI boundary. Written into the prepare
/// root on first run (and whenever the build's copy changes).
const RESEARCH_LOCK_JSON: &str = include_str!("../../../research-lock.json");

/// The manifest's `schema`, exactly as horizon-prepare writes it.
const SCHEMA: i64 = 7;

/// The Minecraft version this build is pinned to ("" never happens in
/// practice: the repo always carries research-lock.json).
pub fn pinned_version() -> String {
    static PIN: OnceLock<String> = OnceLock::new();
    PIN.get_or_init(|| {
        pyjson::loads(RESEARCH_LOCK_JSON)
            .ok()
            .and_then(|lock| {
                lock.get("minecraft")
                    .and_then(|game| game.get("version"))
                    .and_then(Value::as_str)
                    .map(str::to_owned)
            })
            .unwrap_or_default()
    })
    .clone()
}

/// The outcome of a prepare run, as JSON to the launcher.
pub struct PrepareReport {
    pub ok: bool,
    pub version: String,
    pub error: Option<String>,
    pub missing: Vec<String>,
    pub prepared_bytes: u64,
}

impl PrepareReport {
    /// Only the JNI bridge (Android) calls this; the host build never does.
    #[cfg_attr(not(target_os = "android"), allow(dead_code))]
    pub(crate) fn failed(error: String) -> Self {
        Self {
            ok: false,
            version: pinned_version(),
            error: Some(error),
            missing: Vec::new(),
            prepared_bytes: 0,
        }
    }
}

/// Run every file step of a preparation against the SAF-staged `.minecraft`.
///
/// `Ok(report)` with `report.ok == false` is not a failure: it means the file
/// steps are done and the exporter tables are still missing (listed in
/// `report.missing`) — import a portable export once to finish. `Err` is a
/// real failure (bad folder, wrong version, corrupt jars) with a message the
/// launcher shows verbatim.
pub fn prepare_from_minecraft(
    files_root: &Path,
    log: &dyn Fn(&str),
) -> Result<PrepareReport, String> {
    let version = pinned_version();
    if version.is_empty() {
        return Err("this build carries no pinned Minecraft version".into());
    }
    let prepare_root = files_root.join("prepare-root");
    let staging = files_root.join("minecraft-staging");
    let resources = prepare_root
        .join(".local")
        .join("horizon-client")
        .join(&version);
    log(&format!(
        "Preparing Minecraft {version} resources from your .minecraft (the file steps run \
         here; the exporter tables need a one-time portable export)"
    ));

    // research-lock.json for this build, where horizon-prepare's Lock reads it.
    ensure_research_lock(&prepare_root)?;
    let lock = Lock::read(&prepare_root)?;

    // The staged folder must at least describe the pinned version; a clearer
    // error than the importer's (whose hints assume a desktop launcher).
    let version_json = staging
        .join("versions")
        .join(&version)
        .join(format!("{version}.json"));
    if !version_json.is_file() {
        return Err(format!(
            "the imported folder has no versions/{version}/{version}.json — Horizon is pinned \
             to Minecraft {version}. Pick a .minecraft that played {version} at least once, or \
             download it in a launcher and copy that .minecraft over."
        ));
    }

    // Imported, verified artifacts. import() is idempotent: an already
    // verified copy is kept as-is, so re-runs only copy what changed.
    let server_jar = find_server_jar(&lock, &staging);
    if server_jar.is_none() {
        log(&format!(
            "no {version} server jar in the import yet — trying the previously imported copy \
             (put server-{version}.jar from minecraft.net into the .minecraft folder if this \
             is a first import)"
        ));
    }
    log("Importing and checking the pinned jars (sha1 against research-lock.json)");
    artifacts::import(
        &prepare_root,
        &lock,
        Some(&staging),
        server_jar.as_deref(),
        log,
    )?;
    let found = artifacts::verify(&lock)?;
    let client = found.jars["client"].clone();
    // jars["server"] is the bundle's unpacked inner game jar — what the
    // reference bundles are read from, same as the desktop run.
    let server = found.jars["server"].clone();

    fs::create_dir_all(&resources).map_err(|e| format!("{}: {e}", resources.display()))?;

    // The client JAR's resources: every assets/ and data/ member plus
    // pack.png — the bulk of what the game reads at play time.
    log("Extracting the client JAR's resources (assets, data, pack.png)");
    let (jar_version, resource_files) =
        horizon_prepare::extract_jar_resources(&client, &resources)?;

    // The asset store's overlays, each object checked against the index's
    // SHA-1; they replace the JAR's placeholders (1x1 panorama, empty
    // unifont, English-only languages, no sounds at all).
    log("Copying sounds from the asset store (the largest part, ~380 MB)");
    horizon_prepare::sounds(&prepare_root, Some(&staging), log)?;
    log("Copying the panorama, Unifont, built-in packs and languages");
    horizon_prepare::panorama(&prepare_root, Some(&staging), log)?;
    horizon_prepare::fonts(&prepare_root, Some(&staging), log)?;
    horizon_prepare::packs(&prepare_root, Some(&staging), log)?;
    horizon_prepare::languages(&prepare_root, Some(&staging), log)?;

    // Reference bundles straight from the jars, server cross-checked against
    // client — the exact shapes horizon-prepare's steps write.
    log("Writing the reference block-tag and damage bundles");
    let block_tags = jar_bundle(&server, &client, "data/minecraft/tags/block/")?;
    write_json(&resources.join("block-tags.json"), &block_tags)?;
    for (file, prefix) in [
        ("damage-types.json", "data/minecraft/damage_type/"),
        ("damage-tags.json", "data/minecraft/tags/damage_type/"),
    ] {
        let bundle = jar_bundle(&server, &client, prefix)?;
        write_json(&resources.join(file), &bundle)?;
    }

    // The manifest. Start from the previous one so every exporter entry a
    // portable export brought in survives; overwrite only what this run
    // owns. A stale exporter hash would just re-export on a desktop later.
    let manifest_path = resources.join("horizon-resources.json");
    let mut manifest: Vec<(String, Value)> = if manifest_path.is_file() {
        let text = fs::read_to_string(&manifest_path)
            .map_err(|e| format!("{}: {e}", manifest_path.display()))?;
        pyjson::loads(&text)
            .map_err(|e| format!("{}: {e}", manifest_path.display()))?
            .as_object()
            .map(|pairs| pairs.to_vec())
            .ok_or("horizon-resources.json is not an object")?
    } else {
        Vec::new()
    };
    let mut set = |key: &str, value: Value| {
        if let Some(pair) = manifest.iter_mut().find(|(k, _)| k == key) {
            pair.1 = value;
        } else {
            manifest.push((key.to_owned(), value));
        }
    };
    set("schema", Value::Int(SCHEMA.to_string()));
    set("minecraft", Value::str(&version));
    set("client_sha256", Value::Str(sha256_of(&client)?));
    set("server_sha256", Value::Str(sha256_of(&server)?));
    set(
        "data_version",
        jar_version
            .get("world_version")
            .cloned()
            .ok_or("the client JAR's version.json has no world_version")?,
    );
    set("resource_files", Value::Int(resource_files.to_string()));
    set(
        "block_tags_sha256",
        Value::Str(sha256_of(&resources.join("block-tags.json"))?),
    );
    write_json(&manifest_path, &Value::Object(manifest))?;

    let inspection = inspect(&resources);
    if inspection.usable {
        log("Prepared — the resources are complete, the game is ready to play");
    } else {
        log(&format!(
            "Prepared the file steps; {} exporter table(s) still missing — import a portable \
             export once to finish (any {version} export works)",
            inspection.missing.len()
        ));
    }
    Ok(PrepareReport {
        ok: inspection.usable,
        version,
        error: None,
        missing: inspection.missing,
        prepared_bytes: dir_bytes(&resources),
    })
}

/// The tables and spot files the client hard-requires at play time, in the
/// shape a full horizon-prepare run leaves behind. Anything listed here that
/// is missing names an exporter step this device cannot run — the launcher
/// shows the list and points at the one-time portable export.
pub const REQUIRED_FILES: &[&str] = &[
    "facts.json",
    "model-states.json",
    "gameplay-facts.json",
    "entity-types.json",
    "block-behaviours.json",
    "entity-models.json",
    "mob-data.json",
    "command-registries.json",
    "creative-inventory.json",
    "block-tags.json",
    "damage-types.json",
    "damage-tags.json",
    "damage-catalog.json",
    "player-float-ids.json",
    "movement-properties.nbt",
    "attributes.json",
    "landing-definitions.json",
    "cached-collision.nbt",
    "render-definitions.json",
    "occlusion.nbt",
    "registries.json",
    "commands.json",
    "items.json",
    "worldgen/noises.json",
    "worldgen/block_flags.json",
    "worldgen/blocks/block-properties.nbt",
    "worldgen/biome_parameters/overworld.json",
    "pack.png",
];

/// The result of checking a prepared root against [`REQUIRED_FILES`].
pub struct Inspection {
    pub usable: bool,
    pub missing: Vec<String>,
}

pub fn inspect(resources: &Path) -> Inspection {
    let mut missing: Vec<String> = Vec::new();
    let mark = resources.join("horizon-resources.json");
    if !mark.is_file() {
        missing.push("horizon-resources.json".to_owned());
    } else {
        match fs::read_to_string(&mark)
            .map_err(|e| e.to_string())
            .and_then(|text| pyjson::loads(&text))
        {
            Ok(manifest) => {
                for key in ["data_version", "resource_files"] {
                    if manifest.get(key).is_none() {
                        missing.push(format!("manifest:{key}"));
                    }
                }
            }
            Err(e) => missing.push(format!("horizon-resources.json: unreadable ({e})")),
        }
    }
    for name in REQUIRED_FILES {
        if !resources.join(name).is_file() {
            missing.push((*name).to_owned());
        }
    }
    for dir in ["assets", "data", "worldgen"] {
        if !resources.join(dir).is_dir() {
            missing.push(format!("{dir}/"));
        }
    }
    Inspection {
        usable: missing.is_empty(),
        missing,
    }
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

/// research-lock.json for this build, written into the prepare root. Written
/// whenever it differs, so a new pin re-prepares against it.
fn ensure_research_lock(prepare_root: &Path) -> Result<(), String> {
    fs::create_dir_all(prepare_root).map_err(|e| format!("{}: {e}", prepare_root.display()))?;
    let target = prepare_root.join("research-lock.json");
    let want = RESEARCH_LOCK_JSON.as_bytes();
    if fs::read(&target).unwrap_or_default() != want {
        fs::write(&target, want).map_err(|e| format!("{}: {e}", target.display()))?;
    }
    Ok(())
}

/// A sha1 pin from the lock (Lock::pin is crate-private in horizon-prepare).
fn lock_pin(lock: &Lock, key: &str) -> Result<String, String> {
    lock.json
        .get("minecraft")
        .and_then(|game| game.get(key))
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| format!("research-lock.json has no minecraft {key}"))
}

/// Every *.jar that could be the pinned server jar: the .minecraft root
/// (where "drop the server jar in the folder" tells the player to put it)
/// and the version dir (the client jar is there too; the sha1 check sorts
/// them out). Names containing "server" are hashed first so the big client
/// jar is only hashed when nothing else matched.
fn find_server_jar(lock: &Lock, staging: &Path) -> Option<PathBuf> {
    let pin = lock_pin(lock, "server_sha1").ok()?;
    let mut candidates: Vec<PathBuf> = Vec::new();
    for dir in [
        staging.join("versions").join(&lock.version),
        staging.to_path_buf(),
    ] {
        if let Ok(entries) = fs::read_dir(&dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_file() && path.extension().is_some_and(|ext| ext == "jar") {
                    candidates.push(path);
                }
            }
        }
    }
    candidates.sort_by_key(|jar| {
        !jar.file_name()
            .map(|name| name.to_string_lossy().to_lowercase().contains("server"))
            .unwrap_or(false)
    });
    candidates
        .into_iter()
        .find(|jar| sha1_of(jar).is_ok_and(|digest| digest == pin))
}

/// `{prefix}<path>.json` members of both jars as `{"minecraft:<path>": text}`,
/// server cross-checked against client — upstream's json_resources, replicated
/// locally because horizon-prepare keeps it crate-private.
fn jar_bundle(server: &Path, client: &Path, prefix: &str) -> Result<Value, String> {
    let from_server = zip_json_bundle(server, prefix)?;
    let from_client = zip_json_bundle(client, prefix)?;
    if from_server != from_client {
        return Err(format!(
            "Reference bundle disagrees between the jars: {prefix}"
        ));
    }
    Ok(from_server)
}

fn zip_json_bundle(jar: &Path, prefix: &str) -> Result<Value, String> {
    let file = fs::File::open(jar).map_err(|e| format!("{}: {e}", jar.display()))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("{}: {e}", jar.display()))?;
    let mut names: Vec<String> = (0..archive.len())
        .filter_map(|index| archive.name_for_index(index).map(str::to_string))
        .filter(|name| name.starts_with(prefix) && name.ends_with(".json"))
        .collect();
    names.sort();
    let mut bundle = Value::Object(Vec::new());
    for name in names {
        let mut entry = archive.by_name(&name).map_err(|e| format!("{name}: {e}"))?;
        let mut text = String::with_capacity(entry.size() as usize);
        entry
            .read_to_string(&mut text)
            .map_err(|e| format!("{name}: {e}"))?;
        let key = format!("minecraft:{}", &name[prefix.len()..name.len() - 5]);
        bundle.insert(&key, Value::Str(text));
    }
    Ok(bundle)
}

/// horizon-prepare's write_json (crate-private there): two-space indent,
/// final newline — byte-identical to what its steps write.
fn write_json(path: &Path, value: &Value) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    }
    let text = pyjson::dumps(value, &pyjson::INDENT2) + "\n";
    fs::write(path, text).map_err(|e| format!("{}: {e}", path.display()))
}

fn sha1_of(path: &Path) -> Result<String, String> {
    use sha1::Digest as _;
    let mut file = fs::File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut hash = sha1::Sha1::new();
    let mut buffer = vec![0u8; 1 << 20];
    loop {
        let n = file
            .read(&mut buffer)
            .map_err(|e| format!("{}: {e}", path.display()))?;
        if n == 0 {
            break;
        }
        hash.update(&buffer[..n]);
    }
    Ok(hex(&hash.finalize()))
}

fn sha256_of(path: &Path) -> Result<String, String> {
    use sha2::Digest as _;
    let mut file = fs::File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut hash = sha2::Sha256::new();
    let mut buffer = vec![0u8; 1 << 20];
    loop {
        let n = file
            .read(&mut buffer)
            .map_err(|e| format!("{}: {e}", path.display()))?;
        if n == 0 {
            break;
        }
        hash.update(&buffer[..n]);
    }
    Ok(hex(&hash.finalize()))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn dir_bytes(dir: &Path) -> u64 {
    fn walk(dir: &Path, total: &mut u64) {
        if let Ok(entries) = fs::read_dir(dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_dir() {
                    walk(&path, total);
                } else if let Ok(meta) = entry.metadata() {
                    *total += meta.len();
                }
            }
        }
    }
    let mut total = 0;
    walk(dir, &mut total);
    total
}

// ---------------------------------------------------------------------------
// tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pinned_version_is_sane() {
        let version = pinned_version();
        assert!(!version.is_empty(), "research-lock.json carries no version");
        assert!(
            version
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"_.-".contains(&b)),
            "unsafe version identifier {version:?}"
        );
    }

    #[test]
    fn required_files_have_no_duplicates() {
        let mut sorted = REQUIRED_FILES.to_vec();
        sorted.sort_unstable();
        let count = sorted.len();
        sorted.dedup();
        assert_eq!(count, sorted.len(), "REQUIRED_FILES has duplicates");
    }

    #[test]
    fn inspect_flags_an_empty_dir() {
        let scratch =
            std::env::temp_dir().join(format!("horizon-android-prepare-{}", std::process::id()));
        let _ = fs::remove_dir_all(&scratch);
        fs::create_dir_all(&scratch).unwrap();
        let inspection = inspect(&scratch);
        assert!(!inspection.usable);
        assert!(
            inspection
                .missing
                .contains(&"horizon-resources.json".to_owned())
        );
        assert!(inspection.missing.iter().any(|m| m == "facts.json"));
        let _ = fs::remove_dir_all(&scratch);
    }
}
