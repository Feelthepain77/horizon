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
 *                       assets, libraries, the server jar; saves/ is copied
 *                       into the game home. Everything else (runtime, logs,
 *                       screenshots, mods, resourcepacks) stays behind.
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

/** Progress of a running import. `totalBytes == null` when the size is unknown. */
data class ImportState(
    val running: Boolean = false,
    val bytes: Long = 0,
    val totalBytes: Long? = null,
    val currentFile: String = "",
    val error: String? = null,
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
     * parts the prepare reads (the pinned version's dir, assets, libraries,
     * root-level jars that may be the server jar; saves into the game home),
     * then runs the on-device prepare in Rust. Progress lines from the
     * prepare arrive as `prepare` events on HorizonNative.events.
     */
    suspend fun importFromMinecraft(uri: Uri): Unit = withContext(Dispatchers.IO) {
        _importState.value = ImportState(running = true)
        try {
            val resolver = context.contentResolver
            val root = findMinecraftRoot(resolver, uri, 0)
                ?: fail(
                    "the picked folder holds no .minecraft (a directory with versions/ and " +
                        "assets/, searched three levels deep)"
                )
            stageMinecraft(resolver, root)
            _importState.value = _importState.value.copy(running = false)

            // Hand off to Rust; the report says what (if anything) is left.
            val report = parseReport(
                HorizonNative.prepareFromMinecraft(context.filesDir.absolutePath)
            )
            lastReport = report
            if (report?.error != null) {
                _importState.value = ImportState(error = report.error)
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            _importState.value = ImportState(error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Copy the parts of a .minecraft the prepare reads into the staging dir. */
    private fun stageMinecraft(resolver: android.content.ContentResolver, root: Uri) {
        val staging = StorageLayout.minecraftStaging(context)
        staging.mkdirs()
        var copied = 0L
        val onBytes = { bytes: Long, file: String ->
            copied += bytes
            _importState.value = _importState.value.copy(bytes = copied, currentFile = file)
        }

        // assets and libraries wholesale: the prepare reads the index and
        // objects from assets; the importer verifies libraries against the
        // version JSON.
        for (name in listOf("assets", "libraries")) {
            childDir(resolver, root, name)?.let { dir ->
                copyDocumentTree(resolver, dir, File(staging, name), onBytes)
            }
        }

        // The pinned version's dir (jar + json). A .minecraft without it gets
        // a version-specific message from the prepare itself.
        val version = HorizonNative.pinnedVersion()
        val versions = childDir(resolver, root, "versions")
            ?: fail("the .minecraft has no versions/ directory")
        val versionDir = childDir(resolver, versions, version)
            ?: fail("the .minecraft has no versions/$version — Horizon is pinned to Minecraft $version")
        copyDocumentTree(resolver, versionDir, File(File(staging, "versions"), version), onBytes)

        // Root-level jars: the server jar the player dropped in (Rust's sha1
        // check against research-lock.json sorts the candidates out).
        for (child in SafUtil.listChildren(resolver, root)) {
            val name = SafUtil.displayName(resolver, child) ?: continue
            if (name.endsWith(".jar") && !SafUtil.isDirectory(resolver, child)) {
                copyDocumentTree(resolver, child, File(staging, name), onBytes)
            }
        }

        // saves → the game home, so the worlds come along.
        StorageLayout.homeDir(context).mkdirs()
        childDir(resolver, root, "saves")?.let { dir ->
            copyDocumentTree(resolver, dir, File(StorageLayout.homeDir(context), "saves"), onBytes)
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
     * export may wrap it in a top-level folder) goes to `files/prepared`.
     */
    suspend fun importFromZip(uri: Uri): Unit = withContext(Dispatchers.IO) {
        _importState.value = ImportState(running = true)
        try {
            // First pass: find the root prefix (shallowest directory holding
            // the manifest). Second pass: copy everything under it.
            var rootPrefix: String? = null
            context.contentResolver.openInputStream(uri)!!.buffered(1 shl 16).use { input ->
                ZipImporter(input).import { entry, _ ->
                    // The directory part of the manifest's entry is the root
                    // prefix ("" when the manifest sits at the zip's root).
                    if (rootPrefix == null && entry.substringAfterLast('/') == mark) {
                        rootPrefix = entry.substringBefore(mark)
                    }
                }
            }
            val prefix = rootPrefix ?: fail("no $mark in the export — prepare on the PC first, then Export for Android")
            context.contentResolver.openInputStream(uri)!!.buffered(1 shl 16).use { input ->
                ZipImporter(input).import { entry, stream ->
                    if (entry.startsWith(prefix)) copyEntry(entry.removePrefix(prefix), stream)
                }
            }
            _importState.value = ImportState(running = false)
        } catch (e: Exception) {
            coroutineContext.ensureActive() // rethrow cancellation as-is
            _importState.value = ImportState(error = e.message ?: e.javaClass.simpleName)
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
        _importState.value = ImportState(running = true)
        try {
            val resolver = context.contentResolver
            // The directory holding the manifest is the prepared root; copy
            // that whole subtree.
            val root = findDirectoryHolding(resolver, uri, 0) { it == mark }
                ?: fail("no $mark in the folder — prepare on the PC first, then Export for Android")
            var copied = 0L
            copyDocumentTree(resolver, root, preparedDir) { bytes, file ->
                copied += bytes
                _importState.value = _importState.value.copy(bytes = copied, currentFile = file)
            }
            _importState.value = _importState.value.copy(running = false)
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
            targetDir.parentFile?.mkdirs()
            resolver.openInputStream(document)!!.use { input ->
                targetDir.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        onBytes(n.toLong(), targetDir.name)
                    }
                }
            }
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
}
