package dev.horizon.android.auth

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import dev.horizon.android.setup.StorageLayout

/**
 * A local account: play without any Microsoft account.
 *
 * The identity is deterministic: the UUID is vanilla's offline scheme,
 * UUIDv3 of "OfflinePlayer:&lt;name&gt;" — the same one singleplayer and
 * offline-mode servers have always used — so worlds, skins and friends see
 * a stable identity across reinstalls. Online-mode servers will refuse it
 * (by design: ownership cannot be proven without Microsoft's chain);
 * singleplayer, LAN and offline-mode servers work.
 */
data class LocalAccount(
    val name: String,
    val uuid: String,
    val createdAt: Long,
)

/**
 * Local-account storage (files/accounts/accounts.json) plus the per-launch
 * session file. The session file is the client's only identity input: a
 * fresh (&lt;120 s) JSON it reads and deletes at startup — the same format
 * for a local account and a Microsoft session (see `auth.rs` in the client).
 */
class LocalAccountManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    private val file: File
        get() = File(StorageLayout.accountsDir(context), "accounts.json")

    fun accounts(): List<LocalAccount> = runCatching {
        val arr = JSONObject(file.readText()).optJSONArray("accounts") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                LocalAccount(
                    name = o.getString("name"),
                    uuid = o.getString("uuid"),
                    createdAt = o.getLong("createdAt"),
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /**
     * Create and persist an account. `null` when the name is not a valid
     * player name (3..16 chars of letters, digits or underscore) — the same
     * rule the client's session parser applies.
     */
    fun create(name: String): LocalAccount? {
        val trimmed = name.trim()
        if (!trimmed.matches(Regex("^[A-Za-z0-9_]{3,16}$"))) return null
        val account = LocalAccount(trimmed, offlineUuid(trimmed), System.currentTimeMillis())
        persist(accounts() + account)
        return account
    }

    fun delete(uuid: String) {
        persist(accounts().filterNot { it.uuid == uuid })
        if (activeUuid() == uuid) select(null)
    }

    fun select(uuid: String?) {
        prefs.edit().putString("active", uuid).apply()
    }

    fun activeUuid(): String? = prefs.getString("active", null)

    fun active(): LocalAccount? = accounts().firstOrNull { it.uuid == activeUuid() }

    private fun persist(list: List<LocalAccount>) {
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put(
                    "accounts",
                    JSONArray().apply {
                        list.forEach { a ->
                            put(
                                JSONObject()
                                    .put("name", a.name)
                                    .put("uuid", a.uuid)
                                    .put("createdAt", a.createdAt)
                            )
                        }
                    },
                )
                .toString()
        )
    }

    companion object {
        /** Vanilla's offline scheme: UUIDv3 of "OfflinePlayer:<name>". */
        fun offlineUuid(name: String): String =
            UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)).toString()

        /**
         * Write the session file the client reads (and deletes) within 120 s
         * of startup. `access_token` must be non-empty; for a local account
         * it is never used at runtime (only online-mode server joins would).
         * The client refuses a session file other users can read, hence the
         * owner-only permissions.
         */
        fun writeSession(file: File, name: String, uuid: String, accessToken: String = "offline") {
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
            file.writeText(
                JSONObject()
                    .put("name", name)
                    .put("uuid", uuid)
                    .put("access_token", accessToken)
                    .toString()
            )
            file.setReadable(true, true)
            file.setWritable(true, true)
            file.setExecutable(false, false)
        }
    }
}
