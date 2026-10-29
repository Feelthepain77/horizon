package dev.horizon.android.setup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Canonical storage layout for game data inside the app's private storage:
 *
 * ```
 * files/prepared/     the prepared resources root — a horizon-resources.json
 *                     at its top (what the client's --resources needs). The
 *                     desktop launcher's "Export for Android" action produces
 *                     it after running horizon-prepare on the PC.
 * files/home/         the game home: cwd for the client run + HORIZON_HOME.
 *                     options.txt, saves/, logs/ and .horizon-cache are
 *                     created there by the client itself.
 * files/accounts/     local accounts + the per-launch session file.
 * files/imports/      staging dir for in-flight imports.
 * ```
 *
 * Rust receives these absolute paths via `HorizonNative.configure` — no SAF
 * dance on the game side.
 */
object StorageLayout {
    /** The prepared resources root (`--resources`). */
    fun preparedDir(context: Context): File = File(context.filesDir, "prepared")

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
 * Horizon never downloads game files (same policy as the desktop launcher):
 * the user runs the desktop launcher on their PC, which prepares the game
 * data there (horizon-prepare) and packs it into a **portable export**. This
 * manager unpacks such an export — a zip or a folder — into
 * `files/prepared`, locating the prepared root by its
 * `horizon-resources.json` manifest wherever the export put it.
 *
 * The raw `versions/`/`assets/`/`libraries/` layout is a *desktop prepare
 * input*; the client never reads it at play time, so it is not imported.
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
        preparedDir.deleteRecursively()
        StorageLayout.importsDir(context).deleteRecursively()
        _importState.value = ImportState()
    }

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
