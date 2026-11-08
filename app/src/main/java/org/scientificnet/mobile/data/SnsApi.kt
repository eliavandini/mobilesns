package org.scientificnet.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Thrown when a token is missing, expired or rejected; the UI reacts by re-running the browser login. */
class AuthRequiredException(val audience: Audience) : IOException("Session expired for ${audience.name}")

class ApiException(val code: Int, message: String) : IOException(message)

/**
 * Talks to the same REST endpoints the my.scientificnet.org web apps use
 * (as observed in recorded browser traffic).
 */
class SnsApi(
    private val store: SessionStore,
    private val client: OkHttpClient = defaultClient(),
) {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    // --- endpoints --------------------------------------------------------------------------

    suspend fun userData(username: String, domain: String): UserData =
        get(Audience.COCKPIT, "$WEBSERVICES/rest/cm/core/api/Userdata/GetUserdata".toHttpUrl(), username, domain)

    suspend fun timetable(from: Instant, to: Instant): List<TimetableEntry> {
        val url = "$WEBSERVICES/rest/cm/student/api/v2/Timetable".toHttpUrl().newBuilder()
            .addQueryParameter("from", ISO_MILLIS.format(from))
            .addQueryParameter("to", ISO_MILLIS.format(to))
            .build()
        return get(Audience.COCKPIT, url)
    }

    suspend fun exams(studentId: Long): ExamsResponse =
        get(Audience.EXAMS, "$EXAM_API//api/student/$studentId/exams".toHttpUrl())

    suspend fun enrol(studentId: Long, exam: EnrolRequest): ExamsResponse =
        send(Audience.EXAMS, "POST", "$EXAM_API//api/student/$studentId/exams/enrol".toHttpUrl(), exam)

    suspend fun unenrol(studentId: Long, exam: EnrolRequest): ExamsResponse =
        send(Audience.EXAMS, "DELETE", "$EXAM_API//api/student/$studentId/exams/enrol".toHttpUrl(), exam)

    suspend fun creditPoints(studentId: Long): CreditPoints =
        get(Audience.COCKPIT, "$STUDYPLAN/api/Student/$studentId/creditpoints".toHttpUrl())

    suspend fun career(studentId: Long): Career =
        get(Audience.COCKPIT, "$STUDYPLAN/api/Student/$studentId/career".toHttpUrl())

    // --- plumbing ---------------------------------------------------------------------------

    private suspend inline fun <reified T> get(
        audience: Audience,
        url: HttpUrl,
        username: String? = null,
        domain: String? = null,
    ): T {
        val body = execute(audience, "GET", url, null, username, domain)
        return json.decodeFromString(body)
    }

    private suspend inline fun <reified B, reified T> send(audience: Audience, method: String, url: HttpUrl, payload: B): T {
        val body = execute(
            audience, method, url,
            json.encodeToString(payload).toRequestBody("application/json".toMediaType()),
        )
        return json.decodeFromString(body)
    }

    private suspend fun execute(
        audience: Audience,
        method: String,
        url: HttpUrl,
        body: RequestBody?,
        usernameOverride: String? = null,
        domainOverride: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val token = store.validToken(audience) ?: throw AuthRequiredException(audience)
        val account = store.account.value
        val username = usernameOverride ?: account?.username ?: throw AuthRequiredException(audience)
        val domain = domainOverride ?: account?.domain ?: throw AuthRequiredException(audience)

        val fullUrl = url.newBuilder()
            .addQueryParameter("username", username)
            .addQueryParameter("domain", domain)
            .build()
        val request = Request.Builder()
            .url(fullUrl)
            .method(method, body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json, text/plain, */*")
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            when {
                resp.code == 401 -> {
                    store.invalidate(audience)
                    throw AuthRequiredException(audience)
                }
                !resp.isSuccessful -> throw ApiException(resp.code, "HTTP ${resp.code}: ${text.take(300)}")
                else -> text
            }
        }
    }

    companion object {
        const val WEBSERVICES = "https://webservices.scientificnet.org"
        const val EXAM_API = "https://api.scientificnet.org/exam-enrolment-evaluation"
        const val STUDYPLAN = "https://api.scientificnet.org/studyplan-course-selection"

        private val ISO_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
