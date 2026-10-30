package dev.horizon.android.setup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.horizon.android.game.HorizonNative
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Canonical storage layout for game data inside the app's private storage:
 *
 * ```
 * files/prepare-root/   the prepare root: research-lock.json (written by
 *                       Rust from the build's copy), .local/minecraft/<version>
 *                       (imported, verified jars) and .local/horizon-client/
 *                       <version> — THE prepared resources root, what the
 *                       client gets as --resources. The on-device prepare and
 *                       the portable-export import write the same directory,
 *                       so the two paths compose.
 * files/minecraft-staging/  the SAF-copied .minecraft: versions/<version>,
 *                       and of assets/ and libraries/ only
 *                       the pinned version's slice (its asset index and the
 *                       objects the prepare reads, the libraries its version
 *                       json names); saves/ is copied into the game home.
 *                       Everything else (every other version's files,
 *                       runtime, logs, screenshots, mods, resourcepacks)
 *                       stays behind.
 * files/home/           the game home: cwd for the client run + HORIZON_HOME.
 *                       options.txt, saves/, logs/ and .horizon-cache are
 *                       created there by the client itself.
 * files/accounts/       local accounts + the per-launch session file.
 * files/imports/        staging dir for in-flight portable-export imports.
 * ```
 *
 * Rust receives these absolute paths via `HorizonNative.configure` — no SAF
 * dance on the game side.
 */
object StorageLayout {
    /** The prepare root: research-lock.json + the imported jars + the
     *  prepared resources (see [preparedDir]). */
    fun prepareRoot(context: Context): File = File(context.filesDir, "prepare-root")

    /** The prepared resources root (`--resources`), named by the pinned
     *  version exactly as horizon-prepare names it, so the on-device prepare
     *  and the portable-export import land in the same place. */
    fun preparedDir(context: Context): File =
        File(File(File(prepareRoot(context), ".local"), "horizon-client"), HorizonNative.pinnedVersion())

    /** The SAF-staged .minecraft the on-device prepare reads. */
    fun minecraftStaging(context: Context): File = File(context.filesDir, "minecraft-staging")

    /** The game home (cwd + HORIZON_HOME). */
    fun homeDir(context: Context): File = File(context.filesDir, "home")

    /** Local accounts and the session file written per launch. */
    fun accountsDir(context: Context): File = File(context.filesDir, "accounts")

    fun sessionFile(context: Context): File = File(accountsDir(context), "session.json")

    fun importsDir(context: Context): File = File(context.filesDir, "imports")
}

/** Progress of a running import. `totalBytes == null` when the size is
 *  unknown (indeterminate bar). `phase` names the current stage; `doneMessage`
 *  is set when a run finished cleanly, so the UI can say it is done and what
 *  (if anything) is still missing. */
data class ImportState(
    val running: Boolean = false,
    val bytes: Long = 0,
    val totalBytes: Long? = null,
    val currentFile: String = "",
    val phase: String = "",
    val error: String? = null,
    val doneMessage: String? = null,
)

/**
 * Imports the **prepared resources** the client plays from.
 *
 * Two paths compose into the same prepared root:
 *
 * * a **portable export** — a zip or folder the desktop launcher produced
 *   after running horizon-prepare there (unpacked by its
 *   `horizon-resources.json` manifest, wherever the export put it);
 * * the player's own **.minecraft** — staged over SAF and prepared on
 *   device by Rust (see `importFromMinecraft`): the file steps run on the
 *   phone, the Java-25 exporter tables arrive through the export path once.
 *
 * Horizon never downloads game files (same policy as the desktop launcher).
 * The raw `versions/`/`assets/`/`libraries/` layout is a prepare *input*;
 * the client never reads it at play time.
 */
class GameFilesManager(private val context: Context) {

    private val _importState = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _importState

    private val preparedDir get() = StorageLayout.preparedDir(context)

    /** The file that marks a directory as the prepared resources root. */
    private val mark = "horizon-resources.json"

    /** The fix when the picked thing is not an export: this fork's CI builds
     *  one on every green android run — say where it is. */
    private val notAnExportAdvice: String
        get() = "not a portable export. Download the horizon-portable-export-" +
            "${HorizonNative.pinnedVersion()} artifact from the latest successful android run " +
            "(github.com/Feelthepain77/horizon, Actions tab), then pick that zip here"

    /** True when the prepared root is importable/playable right now. */
    fun hasPrepared(): Boolean = File(preparedDir, mark).isFile

    fun importedBytes(): Long = preparedDir.walkBottomUp()
        .filter { it.isFile }.sumOf { it.length() }

    fun deleteAll() {
        // the whole prepare root (jars + prepared resources) and the staging
        StorageLayout.prepareRoot(context).deleteRecursively()
        StorageLayout.minecraftStaging(context).deleteRecursively()
        StorageLayout.importsDir(context).deleteRecursively()
        _importState.value = ImportState()
        lastReport = null
    }

    // ----------------------------------------------------------- .minecraft --

    /** The latest on-device prepare report (null before the first import). */
    var lastReport: PrepareReport? = null
        private set

    /** The prepare outcome, as Rust reports it (see jni_bridge.rs). */
    data class PrepareReport(
        val ok: Boolean,
        val version: String,
        val error: String?,
        val missing: List<String>,
        val preparedBytes: Long,
    )

    /**
     * Import the player's own .minecraft folder — the no-PC path. Stages the
     * parts the prepare reads (the pinned version's dir, its asset index and
     * the objects the prepare copies, the libraries its version json names;
     * saves into the game home), then runs the on-device prepare in Rust —
     * with the phase set at each step, so the UI can show a determinate bar
     * and say when it is done. Progress lines from the prepare arrive as
     * `prepare` events on HorizonNative.events.
     */
    suspend fun importFromMinecraft(uri: Uri): Unit = withContext(Dispatchers.IO) {
        _importState.value = ImportState(running = true, phase = "Looking for your .minecraft")
        try {
            val resolver = context.contentResolver
            val root = findMinecraftRoot(resolver, uri, 0)
                ?: fail(
                    "the picked folder holds no .minecraft (a directory with versions/ and " +
                        "assets/, searched three levels deep)"
                )
            stageMinecraft(resolver, root)
            _importState.value = _importState.value.copy(
                running = true,
                bytes = 0,
                phase = "Preparing resources on device",
                currentFile = "",
            )

            // Hand off to Rust; the report says what (if anything) is left.
            val report = parseReport(
                HorizonNative.prepareFromMinecraft(context.filesDir.absolutePath)
            )
            lastReport = report
            if (report?.error != null) {
                _importState.value = _importState.value.copy(
                    running = false, phase = "", error = report.error, doneMessage = null,
                )
            } else {
                val done = report?.let { r ->
                    if (r.ok) {
                        "Resources ready — PLAY is unlocked"
                    } else {
                        "Prepared the file steps; still missing: ${r.missing.joinToString(", ")} — " +
                            "import a portable export once to finish"
                    }
                } ?: "Import finished"
                _importState.value = _importState.value.copy(
                    running = false, phase = "", currentFile = "", doneMessage = done,
                )
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            _importState.value = ImportState(error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Copy the parts of a .minecraft the prepare reads into the staging dir:
     *  the pinned version's own dir, its asset index and the objects the
     *  prepare copies from it, the libraries its version json names, and the
     *  worlds. Other versions' objects and libraries, logs, screenshots, mods
     *  and resourcepacks stay behind — a multi-version .minecraft can hold
     *  gigabytes the pinned version never reads. No jar is staged besides the
     *  client jar inside versions/<version>/ (the prepare reads no server jar).
     *
     *  The stage is measured before the copy begins — the version json
     *  declares the jar's size, the index declares every object's, the json
     *  names the libraries — so the progress bar is determinate from the
     *  first byte. */
    private fun stageMinecraft(resolver: android.content.ContentResolver, root: Uri) {
        val staging = StorageLayout.minecraftStaging(context)
        _importState.value = _importState.value.copy(currentFile = "clearing the previous stage")
        staging.deleteRecursively() // a fresh, selective stage: no wholesale leftovers
        staging.mkdirs()
        var copied = 0L
        val onBytes = { bytes: Long, file: String ->
            copied += bytes
            _importState.value = _importState.value.copy(bytes = copied, currentFile = file)
        }

        // The pinned version's dir (jar + json) first: its json says what
        // else to copy. A .minecraft without it gets a version-specific
        // message from the prepare itself.
        val version = HorizonNative.pinnedVersion()
        val versions = childDir(resolver, root, "versions")
            ?: fail("the .minecraft has no versions/ directory")
        val versionDir = childDir(resolver, versions, version)
            ?: fail("the .minecraft has no versions/$version — Horizon is pinned to Minecraft $version")

        // Measure what the stage will hold: two small SAF reads (the version
        // json, the asset index) plus one size query per save file, then the
        // bar is determinate for the whole copy.
        _importState.value = _importState.value.copy(currentFile = "measuring the .minecraft")
        val versionJsonDoc = childDocumentAt(resolver, versionDir, "$version.json")
        val manifest = versionJsonDoc?.let { doc ->
            runCatching {
                resolver.openInputStream(doc)!!.bufferedReader().use { it.readText() }
            }.getOrNull()?.let { JSONObject(it) }
        } ?: fail("versions/$version/$version.json is unreadable")
        var total = 0L
        total += manifest.optJSONObject("downloads")?.optJSONObject("client")?.optLong("size") ?: 0L
        total += versionJsonDoc?.let { SafUtil.size(resolver, it) } ?: 0L
        val assetsDir = childDir(resolver, root, "assets")
        if (assetsDir != null) {
            val indexId = manifest.optJSONObject("assetIndex")?.optString("id").orEmpty()
            if (indexId.isNotEmpty()) {
                childDocumentAt(resolver, assetsDir, "indexes/$indexId.json")?.let { indexDoc ->
                    total += SafUtil.size(resolver, indexDoc)
                    runCatching {
                        JSONObject(
                            resolver.openInputStream(indexDoc)!!.bufferedReader().use { it.readText() }
                        )
                    }.getOrNull()?.optJSONObject("objects")?.let { objects ->
                        for (name in objects.keys()) {
                            if (isWantedAsset(name)) {
                                total += objects.optJSONObject(name)?.optLong("size") ?: 0L
                            }
                        }
                    }
                }
            }
        }
        val libraries = manifest.optJSONArray("libraries")
        for (index in 0 until (libraries?.length() ?: 0)) {
            total += libraries?.optJSONObject(index)
                ?.optJSONObject("downloads")?.optJSONObject("artifact")?.optLong("size") ?: 0L
        }
        childDir(resolver, root, "saves")?.let { saves -> total += SafUtil.treeSize(resolver, saves) }
        _importState.value = _importState.value.copy(
            phase = "Staging your .minecraft",
            totalBytes = total.takeIf { it > 0 },
            bytes = 0,
            currentFile = "",
        )

        copyDocumentTree(resolver, versionDir, File(File(staging, "versions"), version), onBytes)
        stageAssets(resolver, root, manifest, onBytes)
        stageLibraries(resolver, root, manifest, onBytes)

        // saves → the game home, so the worlds come along.
        StorageLayout.homeDir(context).mkdirs()
        childDir(resolver, root, "saves")?.let { dir ->
            copyDocumentTree(resolver, dir, File(StorageLayout.homeDir(context), "saves"), onBytes)
        }
    }

    /**
     * The pinned version's asset index and the objects the prepare copies
     * from it (sounds.rs's filters — keep them in sync): the sounds, the
     * languages, Unifont, the built-in packs and the title panorama. The
     * rest of the store — every other version's objects, often most of it —
     * stays behind. A missing index or assetIndex id is left for the prepare
     * to report; its messages name the exact file.
     */
    private fun stageAssets(
        resolver: android.content.ContentResolver,
        root: Uri,
        manifest: JSONObject,
        onBytes: (Long, String) -> Unit,
    ) {
        val assetsDir = childDir(resolver, root, "assets") ?: return
        val indexId = manifest.optJSONObject("assetIndex")?.optString("id").orEmpty()
        if (indexId.isEmpty()) return
        val stagedAssets = File(StorageLayout.minecraftStaging(context), "assets")
        val stagedIndex = File(File(stagedAssets, "indexes"), "$indexId.json")
        childDocumentAt(resolver, assetsDir, "indexes/$indexId.json")?.let { index ->
            copyDocument(resolver, index, stagedIndex, onBytes)
        }
        val entries = runCatching {
            JSONObject(stagedIndex.readText()).optJSONObject("objects")
        }.getOrNull() ?: return

        val hashes = ArrayList<String>()
        for (name in entries.keys()) {
            if (isWantedAsset(name)) {
                val hash = entries.optJSONObject(name)?.optString("hash").orEmpty()
                if (hash.length == 40) hashes.add(hash)
            }
        }
        // One listing per two-digit prefix of the store's objects/ — the
        // layout the index's hashes address — copying only the wanted ones.
        for ((prefix, group) in hashes.groupBy { it.substring(0, 2) }) {
            val objectsDir = childDocumentAt(resolver, assetsDir, "objects/$prefix") ?: continue
            val wantedHere = group.toHashSet()
            for ((document, name) in SafUtil.listChildrenNamed(resolver, objectsDir)) {
                if (name in wantedHere) {
                    copyDocument(
                        resolver,
                        document,
                        File(File(File(stagedAssets, "objects"), prefix), name),
                        onBytes,
                    )
                }
            }
        }
    }

    /**
     * The libraries the version json names — every `downloads.artifact` path,
     * a superset of what the prepare imports (it also applies the json's OS
     * and architecture rules). Each is copied at the path the official
     * launcher uses, or where the Prism launcher keeps it (artifacts.rs
     * launcher_library), so the prepare finds it in the stage either way.
     */
    private fun stageLibraries(
        resolver: android.content.ContentResolver,
        root: Uri,
        manifest: JSONObject,
        onBytes: (Long, String) -> Unit,
    ) {
        val librariesDir = childDir(resolver, root, "libraries") ?: return
        val entries = manifest.optJSONArray("libraries") ?: return
        val staged = File(StorageLayout.minecraftStaging(context), "libraries")
        for (index in 0 until entries.length()) {
            val library = entries.optJSONObject(index) ?: continue
            val path = library.optJSONObject("downloads")
                ?.optJSONObject("artifact")?.optString("path").orEmpty()
            if (!isSafeRelative(path)) continue
            val source = childDocumentAt(resolver, librariesDir, path)
                ?: prismLibraryPath(library.optString("name"))
                    ?.let { childDocumentAt(resolver, librariesDir, it) }
                // A library absent from the player's install (one the json's
                // rules leave out, a native their launcher never downloaded)
                // is the prepare's to report if it needs it.
                ?: continue
            copyDocument(resolver, source, File(staged, path), onBytes)
        }
    }

    /** The directory that directly holds versions/ and assets/ — a .minecraft —
     *  searched up to [depth] levels below the picked folder (the picker often
     *  returns a parent of the real one). */
    private fun findMinecraftRoot(
        resolver: android.content.ContentResolver,
        dir: Uri,
        depth: Int,
    ): Uri? {
        if (depth > 3) return null
        val children = SafUtil.listChildren(resolver, dir)
        fun holds(name: String) = children.any {
            SafUtil.displayName(resolver, it) == name && SafUtil.isDirectory(resolver, it)
        }
        if (holds("versions") && holds("assets")) return dir
        for (child in children) {
            if (SafUtil.isDirectory(resolver, child)) {
                findMinecraftRoot(resolver, child, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** The named subdirectory of a SAF directory, or null when absent. */
    private fun childDir(
        resolver: android.content.ContentResolver,
        dir: Uri,
        name: String,
    ): Uri? = SafUtil.listChildren(resolver, dir).firstOrNull {
        SafUtil.displayName(resolver, it) == name && SafUtil.isDirectory(resolver, it)
    }

    /** The document at [path] (segments joined by '/') under [dir], or null
     *  when any segment is missing. Intermediate segments are directories. */
    private fun childDocumentAt(
        resolver: android.content.ContentResolver,
        dir: Uri,
        path: String,
    ): Uri? {
        var current = dir
        val parts = path.split('/')
        for ((index, part) in parts.withIndex()) {
            val last = index == parts.lastIndex
            current = SafUtil.listChildrenNamed(resolver, current)
                .firstOrNull { (document, name) ->
                    name == part && (last || SafUtil.isDirectory(resolver, document))
                }?.first ?: return null
        }
        return current
    }

    /** A relative path with only normal components (artifacts.rs safe_relative). */
    private fun isSafeRelative(path: String): Boolean =
        path.isNotEmpty() && !path.contains('\\') && !path.startsWith("/") &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** Where the Prism launcher keeps a native library: the classifier folded
     *  into the artifact name (artifacts.rs launcher_library). */
    private fun prismLibraryPath(name: String): String? {
        val parts = name.split(':')
        if (parts.size != 4) return null
        val artifact = "${parts[1]}-${parts[3]}"
        return "${parts[0].replace('.', '/')}/$artifact/${parts[2]}/$artifact-${parts[2]}.jar"
    }

    /** Copy one SAF document to [target]. */
    private fun copyDocument(
        resolver: android.content.ContentResolver,
        document: Uri,
        target: File,
        onBytes: (Long, String) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        resolver.openInputStream(document)!!.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    onBytes(read.toLong(), target.name)
                }
            }
        }
    }

    /** The report JSON from Rust (see jni_bridge.rs). */
    private fun parseReport(json: String): PrepareReport? = runCatching {
        val obj = JSONObject(json)
        val missing = buildList {
            obj.optJSONArray("missing")?.let { array ->
                for (index in 0 until array.length()) add(array.optString(index))
            }
        }
        PrepareReport(
            ok = obj.optBoolean("ok"),
            version = obj.optString("version"),
            error = if (obj.isNull("error")) null else obj.optString("error"),
            missing = missing,
            preparedBytes = obj.optLong("prepared_bytes"),
        )
    }.getOrNull()

    // ------------------------------------------------------------------ zip --

    /**
     * Import a portable-export zip: the entry tree under the directory that
     * holds `horizon-resources.json` (wherever in the zip it lives — the
     * export may wrap it in a top-level folder) goes to the prepared root.
     *
     * A zip handed over by the system picker is not always the export
     * itself: GitHub wraps every artifact download in an extra zip, and file
     * managers do similar things. Rather than making the player unwrap by
     * hand, zips found inside a zip that has no manifest are extracted and
     * tried in turn, a few levels deep — picking the artifact exactly as
     * downloaded just works.
     */
    suspend fun importFromZip(uri: Uri): Unit = withContext(Dispatchers.IO) {
        _importState.value = ImportState(running = true, phase = "Importing the portable export")
        try {
            importZipLevel({ context.contentResolver.openInputStream(uri)!! }, "the zip", 0)
            _importState.value = _importState.value.copy(
                running = false,
                phase = "",
                doneMessage = "Portable export imported (${_importState.value.bytes / (1 shl 20)} MB) — " +
                    "re-validate to update the checklist",
            )
        } catch (e: Exception) {
            coroutineContext.ensureActive() // rethrow cancellation as-is
            _importState.value = ImportState(error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** One zip level: import it when it holds the manifest, otherwise try the
     *  zips nested inside it. */
    private fun importZipLevel(open: () -> InputStream, name: String, depth: Int) {
        // First pass: find the root prefix (shallowest directory holding
        // the manifest). Second pass: copy everything under it.
        var rootPrefix: String? = null
        open().buffered(1 shl 16).use { input ->
            ZipImporter(input).import { entry, _ ->
                // The directory part of the manifest's entry is the root
                // prefix ("" when the manifest sits at the zip's root).
                if (rootPrefix == null && entry.substringAfterLast('/') == mark) {
                    rootPrefix = entry.substringBefore(mark)
                }
            }
        }
        val prefix = rootPrefix
        if (prefix == null) {
            if (depth >= 3) fail("no $mark in $name, even nested — $notAnExportAdvice")
            importNestedZips(open, name, depth)
            return
        }
        open().buffered(1 shl 16).use { input ->
            ZipImporter(input).import { entry, stream ->
                if (entry.startsWith(prefix)) copyEntry(entry.removePrefix(prefix), stream)
            }
        }
    }

    /** Extract every zip nested in this level and import those, one level
     *  down (the GitHub artifact wrapper is exactly one such level). */
    private fun importNestedZips(open: () -> InputStream, name: String, depth: Int) {
        val nested = File(StorageLayout.importsDir(context), "nested-${System.nanoTime()}")
        nested.mkdirs()
        try {
            open().buffered(1 shl 16).use { input ->
                ZipImporter(input).import { entry, stream ->
                    if (entry.endsWith(".zip", ignoreCase = true)) {
                        val safe = entry.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                        File(nested, safe).outputStream().use { output -> stream.copyTo(output) }
                    }
                }
            }
            val zips = nested.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
            if (zips.isEmpty()) fail("no $mark in $name — $notAnExportAdvice")
            for (zip in zips) importZipLevel({ zip.inputStream() }, zip.name, depth + 1)
        } finally {
            nested.deleteRecursively()
        }
    }

    private fun fail(why: String): Nothing {
        _importState.value = ImportState(error = why)
        throw IllegalStateException(why)
    }

    private fun copyEntry(rel: String, stream: java.util.zip.ZipInputStream) {
        if (rel.isBlank() || rel == "/") return
        val target = File(preparedDir, rel)
        if (rel.endsWith("/")) {
            target.mkdirs()
            return
        }
        target.parentFile?.mkdirs()
        var copied = _importState.value.bytes
        stream.copyToWithProgress(target.outputStream()) { chunk ->
            copied += chunk
            _importState.value = _importState.value.copy(bytes = copied, currentFile = rel)
        }
    }

    // ----------------------------------------------------------------- tree --

    /**
     * Import from a folder (ACTION_OPEN_DOCUMENT_TREE): the subtree whose
     * directory holds `horizon-resources.json` — wherever it sits, up to a
     * few levels deep — goes to `files/prepared`. Typically the extracted
     * portable export; a raw `.minecraft` has no prepared root and is
     * rejected with a clear message.
     */
    suspend fun importFromTree(uri: Uri): Unit = withContext(Dispatchers.IO) {
        _importState.value = ImportState(running = true, phase = "Importing the export folder")
        try {
            val resolver = context.contentResolver
            // The directory holding the manifest is the prepared root; copy
            // that whole subtree.
            val root = findDirectoryHolding(resolver, uri, 0) { it == mark }
                ?: fail("no $mark in the folder — $notAnExportAdvice")
            var copied = 0L
            copyDocumentTree(resolver, root, preparedDir) { bytes, file ->
                copied += bytes
                _importState.value = _importState.value.copy(bytes = copied, currentFile = file)
            }
            _importState.value = _importState.value.copy(
                running = false,
                phase = "",
                doneMessage = "Portable export imported (${copied / (1 shl 20)} MB)",
            )
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            _importState.value = ImportState(error = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Depth-limited search: the directory whose *direct child* is a document
     * named by [want] (SAF `Uri` has no parent, so the search carries it).
     */
    private fun findDirectoryHolding(
        resolver: android.content.ContentResolver,
        dir: Uri,
        depth: Int,
        want: (String) -> Boolean,
    ): Uri? {
        if (depth > 4) return null
        for (child in SafUtil.listChildren(resolver, dir)) {
            val name = SafUtil.displayName(resolver, child) ?: continue
            if (want(name)) return dir
            if (SafUtil.isDirectory(resolver, child)) {
                findDirectoryHolding(resolver, child, depth + 1, want)?.let { return it }
            }
        }
        return null
    }

    private fun copyDocumentTree(
        resolver: android.content.ContentResolver,
        document: Uri,
        targetDir: File,
        onBytes: (Long, String) -> Unit,
    ) {
        if (SafUtil.isDirectory(resolver, document)) {
            targetDir.mkdirs()
            for (child in SafUtil.listChildren(resolver, document)) {
                copyDocumentTree(
                    resolver,
                    child,
                    File(targetDir, SafUtil.displayName(resolver, child) ?: "_"),
                    onBytes,
                )
            }
        } else {
            copyDocument(resolver, document, targetDir, onBytes)
        }
    }
}

/** Minimal zip walker that reports every entry (incl. directories) in order. */
private class ZipImporter(private val buffered: java.io.InputStream) {
    fun import(handle: (String, java.util.zip.ZipInputStream) -> Unit) {
        java.util.zip.ZipInputStream(buffered).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name.startsWith("__MACOSX") || entry.name.contains("..")) continue
                handle(entry.name, zis)
                zis.closeEntry()
            }
        }
    }
}

private fun java.util.zip.ZipInputStream.copyToWithProgress(
    output: OutputStream,
    onChunk: (Int) -> Unit,
) {
    output.use { out ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            onChunk(n)
        }
    }
}

/** The title panorama entries the prepare copies from the asset index
 *  (sounds.rs PANORAMA — keep in sync). */
private val PANORAMA = setOf(
    "minecraft/textures/gui/title/background/panorama_0.png",
    "minecraft/textures/gui/title/background/panorama_1.png",
    "minecraft/textures/gui/title/background/panorama_2.png",
    "minecraft/textures/gui/title/background/panorama_3.png",
    "minecraft/textures/gui/title/background/panorama_4.png",
    "minecraft/textures/gui/title/background/panorama_5.png",
    "minecraft/textures/gui/title/background/panorama_overlay.png",
)

/** Which index entries the prepare copies from the asset store (sounds.rs's
 *  filters — keep in sync): the sounds, the languages, Unifont, the built-in
 *  packs and the title panorama. One definition, used by the staging copy and
 *  by the up-front measurement alike. */
private fun isWantedAsset(name: String): Boolean = name == "minecraft/sounds.json" ||
    name.startsWith("minecraft/sounds/") ||
    name == "pack.mcmeta" ||
    name.startsWith("minecraft/lang/") ||
    name.startsWith("minecraft/font/") ||
    name.startsWith("minecraft/resourcepacks/") ||
    PANORAMA.contains(name)

/** SAF (Storage Access Framework) helpers built on DocumentsContract. */
object SafUtil {
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    fun listChildren(resolver: android.content.ContentResolver, treeUri: Uri): List<Uri> {
        val docId = try {
            DocumentsContract.getDocumentId(treeUri)
        } catch (_: IllegalArgumentException) {
            DocumentsContract.getTreeDocumentId(treeUri)
        }
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = mutableListOf<Uri>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                out.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
            }
        }
        return out
    }

    /** The children of a directory with their display names — one query. */
    fun listChildrenNamed(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
    ): List<Pair<Uri, String>> {
        val docId = try {
            DocumentsContract.getDocumentId(treeUri)
        } catch (_: IllegalArgumentException) {
            DocumentsContract.getTreeDocumentId(treeUri)
        }
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = mutableListOf<Pair<Uri, String>>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                out.add(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id) to cursor.getString(1)
                )
            }
        }
        return out
    }

    fun displayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        val docId = DocumentsContract.getDocumentId(uri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
        resolver.query(docUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(1)
        }
        return null
    }

    fun isDirectory(resolver: android.content.ContentResolver, uri: Uri): Boolean {
        val docId = DocumentsContract.getDocumentId(uri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
        resolver.query(docUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
            }
        }
        return false
    }

    /** A document's size in bytes (0 when the provider reports nothing). */
    fun size(resolver: android.content.ContentResolver, uri: Uri): Long {
        val docId = DocumentsContract.getDocumentId(uri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
        resolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 0L
    }

    /** The bytes under a SAF directory tree — one size query per file, no
     *  data read. Used for the saves slice of the staging measurement. */
    fun treeSize(resolver: android.content.ContentResolver, dir: Uri): Long {
        var total = 0L
        for (child in listChildren(resolver, dir)) {
            if (isDirectory(resolver, child)) {
                total += treeSize(resolver, child)
            } else {
                total += size(resolver, child)
            }
        }
        return total
    }
}
