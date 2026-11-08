package org.scientificnet.mobile.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** The two backends the website talks to, identified by the token audience. */
enum class Audience(val audienceMarker: String) {
    /** webservices.scientificnet.org + studyplan API: timetable, user data, grades. */
    COCKPIT("CockpitMobileTest"),

    /** api.scientificnet.org/exam-enrolment-evaluation: exam enrolment. */
    EXAMS("exam-enrolment-evaluation");

    companion object {
        fun fromAud(aud: String): Audience? = entries.firstOrNull { aud.contains(it.audienceMarker, ignoreCase = true) }
    }
}

@Serializable
data class Account(
    val username: String,
    val domain: String,
    val studentId: Long? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val degree: String? = null,
) {
    val displayName: String
        get() = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { username }
}

@Serializable
data class StoredToken(val token: String, val expiresAtEpochSec: Long) {
    fun isValid(nowSec: Long = System.currentTimeMillis() / 1000, skewSec: Long = 90) =
        expiresAtEpochSec - skewSec > nowSec
}

object Jwt {
    /** Returns the decoded payload of a JWT, or null if it isn't one. */
    fun payload(token: String): JsonObject? = runCatching {
        val part = token.split('.')[1]
        val bytes = java.util.Base64.getUrlDecoder().decode(part.trimEnd('='))
        Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
    }.getOrNull()

    fun audiences(payload: JsonObject): List<String> = when (val aud = payload["aud"]) {
        is JsonPrimitive -> listOf(aud.content)
        is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.content }
        else -> emptyList()
    }

    fun expiry(payload: JsonObject): Long? = (payload["exp"] as? JsonPrimitive)?.longOrNull
}

/**
 * Persists the account and the bearer tokens captured from the website.
 * Stored in app-private SharedPreferences.
 */
class SessionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _account = MutableStateFlow(loadAccount())
    val account: StateFlow<Account?> = _account.asStateFlow()

    private fun loadAccount(): Account? =
        prefs.getString(KEY_ACCOUNT, null)?.let { runCatching { json.decodeFromString<Account>(it) }.getOrNull() }

    fun saveAccount(account: Account) {
        prefs.edit().putString(KEY_ACCOUNT, json.encodeToString(account)).apply()
        _account.value = account
    }

    fun token(audience: Audience): StoredToken? =
        prefs.getString(KEY_TOKEN_PREFIX + audience.name, null)
            ?.let { runCatching { json.decodeFromString<StoredToken>(it) }.getOrNull() }

    fun validToken(audience: Audience): String? = token(audience)?.takeIf { it.isValid() }?.token

    fun saveToken(audience: Audience, token: StoredToken) {
        prefs.edit().putString(KEY_TOKEN_PREFIX + audience.name, json.encodeToString(token)).apply()
    }

    fun invalidate(audience: Audience) {
        prefs.edit().remove(KEY_TOKEN_PREFIX + audience.name).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        _account.value = null
    }

    private companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_TOKEN_PREFIX = "token_"
    }
}
