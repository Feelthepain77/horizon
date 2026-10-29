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
 * files/game/          imported .minecraft content (versions/, assets/, libraries/)
 * files/local/         prepared resources (".local/" equivalent from a portable export)
 * files/imports/       staging dir for in-flight imports
 * ```
 *
 * Rust receives these absolute paths via `HorizonNative.configure` — no SAF
 * dance on the game side.
 */
object StorageLayout {
    fun gameDir(context: Context): File = File(context.filesDir, "game")
    fun localDir(context: Context): File = File(context.filesDir, "local")
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
 * Imports Minecraft: Java Edition files from user-chosen documents.
 *
 * Horizon never downloads game files (same policy as the desktop launcher) —
 * the user supplies them from their own installation, either as:
 *  1. a **portable export zip** produced by the desktop launcher's
 *     "Export for Android" action (runs `horizon-prepare` on the PC and packs
 *     versions/ + assets/ + libraries/ + local/ — the recommended path, see
 *     PORTING.md § "Portable export format"), or
 *  2. a raw `.minecraft` folder picked with ACTION_OPEN_DOCUMENT_TREE.
 */
class GameFilesManager(private val context: Context) {

    private val _importState = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _importState

    private val gameDir get() = StorageLayout.gameDir(context)
    private val localDir get() = StorageLayout.localDir(context)

    /** True when the structural minimum (versions/ + assets/ + libraries/) exists. */
    fun looksImported(): Boolean =
        File(gameDir, "versions").isDirectory &&
            File(gameDir, "assets").isDirectory &&
            File(gameDir, "libraries").isDirectory

    /** A portable export additionally carries prepared resources. */
    fun hasPrepared(): Boolean = File(localDir, "prepared.json").isFile

    fun importedBytes(): Long = (gameDir.walkBottomUp() + localDir.walkBottomUp())
        .filter { it.isFile }.sumOf { it.length() }

    fun deleteAll() {
        gameDir.deleteRecursively()
        localDir.deleteRecursively()
        StorageLayout.importsDir(context).deleteRecursively()
        _importState.value = ImportState()
    }

    // ------------------------------------------------------------------ zip --

    /**
     * Import a portable-export zip. Entries map:
     *  - `versions/...`, `assets/...`, `libraries/...` -> files/game/
     *  - `local/...` or `.local/...`                   -> files/local/
     *  - everything else (manifests, checksums)        -> files/game/
     */
    suspend fun importFromZip(uri: Uri): Unit = withContext(Dispatchers.IO) {
        _importState.value = ImportState(running = true)
        try {
            context.contentResolver.openInputStream(uri)!!.buffered(1 shl 16).use { input ->
                ZipImporter(input).import { entry, stream ->
                    copyZipEntry(entry, stream)
                }
            }
            _importState.value = ImportState(running = false)
        } catch (e: Exception) {
            coroutineContext.ensureActive() // rethrow cancellation as-is
            _importState.value = ImportState(error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun copyZipEntry(name: String, stream: java.util.zip.ZipInputStream) {
        val targetRoot: File = when {
            name.startsWith("local/") || name.startsWith(".local/") -> localDir
            else -> gameDir
        }
        val rel = name.removePrefix("local/").removePrefix(".local/")
        val target = File(targetRoot, rel)
        if (name.endsWith("/")) {
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
     * Import required subtrees (`versions/`, `assets/`, `libraries/`) from a
     * `.minecraft` folder chosen with ACTION_OPEN_DOCUMENT_TREE.
     */
    suspend fun importFromTree(uri: Uri): Unit = withContext(Dispatchers.IO) {
        _importState.value = ImportState(running = true)
        try {
            val resolver = context.contentResolver
            val children = SafUtil.listChildren(resolver, uri)
            val wanted = setOf("versions", "assets", "libraries")
            var copied = 0L
            for (child in children) {
                val name = SafUtil.displayName(resolver, child) ?: continue
                if (name !in wanted) continue
                copyDocumentTree(resolver, child, File(gameDir, name)) { bytes, file ->
                    copied += bytes
                    _importState.value =
                        _importState.value.copy(bytes = copied, currentFile = file)
                }
            }
            _importState.value = _importState.value.copy(running = false)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            _importState.value = ImportState(error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun copyDocumentTree(
        resolver: android.content.ContentResolver,
        document: Uri,
        targetDir: File,
        onBytes: (Long, String) -> Unit,
    ) {
        val resolverDoc = document
        if (SafUtil.isDirectory(resolver, document)) {
            targetDir.mkdirs()
            for (child in SafUtil.listChildren(resolver, document)) {
                copyDocumentTree(resolver, child, File(targetDir, SafUtil.displayName(resolver, child) ?: "_"), onBytes)
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
