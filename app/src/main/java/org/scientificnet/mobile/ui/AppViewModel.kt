package org.scientificnet.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.scientificnet.mobile.auth.CaptureResult
import org.scientificnet.mobile.auth.clearBrowserSession
import org.scientificnet.mobile.data.Account
import org.scientificnet.mobile.data.AuthRequiredException
import org.scientificnet.mobile.data.Career
import org.scientificnet.mobile.data.CreditPoints
import org.scientificnet.mobile.data.EnrolRequest
import org.scientificnet.mobile.data.EnrolmentExam
import org.scientificnet.mobile.data.ExamsResponse
import org.scientificnet.mobile.data.SessionStore
import org.scientificnet.mobile.data.SnsApi
import org.scientificnet.mobile.data.TimetableEntry
import java.time.LocalDate
import java.time.ZoneId

data class LoadState<T>(
    val loading: Boolean = false,
    val data: T? = null,
    val error: String? = null,
)

data class Grades(val credits: CreditPoints, val career: Career)

enum class AuthMode { INTERACTIVE, SILENT }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val api = SnsApi(store)

    val account: StateFlow<Account?> = store.account

    private val _authMode = MutableStateFlow<AuthMode?>(null)
    val authMode: StateFlow<AuthMode?> = _authMode.asStateFlow()

    private val _accountError = MutableStateFlow<String?>(null)
    val accountError: StateFlow<String?> = _accountError.asStateFlow()

    private val _weekOffset = MutableStateFlow(0)
    val weekOffset: StateFlow<Int> = _weekOffset.asStateFlow()

    private val _lectures = MutableStateFlow(LoadState<List<TimetableEntry>>())
    val lectures: StateFlow<LoadState<List<TimetableEntry>>> = _lectures.asStateFlow()

    private val _exams = MutableStateFlow(LoadState<ExamsResponse>())
    val exams: StateFlow<LoadState<ExamsResponse>> = _exams.asStateFlow()

    private val _grades = MutableStateFlow(LoadState<Grades>())
    val grades: StateFlow<LoadState<Grades>> = _grades.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busyExam = MutableStateFlow<Long?>(null)
    val busyExam: StateFlow<Long?> = _busyExam.asStateFlow()

    private var lecturesJob: Job? = null

    /** When the last automatic (silent) re-login finished; used to avoid refresh loops. */
    private var lastSilentAuthAt = 0L

    init {
        if (store.account.value != null) refreshAll()
    }

    // ---- account / auth -------------------------------------------------------------------

    fun startAddAccount() {
        _accountError.value = null
        _authMode.value = AuthMode.INTERACTIVE
    }

    fun cancelAuth() {
        val wasSilent = _authMode.value == AuthMode.SILENT
        _authMode.value = null
        if (wasSilent) {
            val msg = "Session expired. Pull to refresh or tap retry to sign in again."
            _lectures.update { if (it.loading) it.copy(loading = false, error = msg) else it }
            _exams.update { if (it.loading) it.copy(loading = false, error = msg) else it }
            _grades.update { if (it.loading) it.copy(loading = false, error = msg) else it }
        }
    }

    /** Last login result, kept so loading the profile can be retried without signing in again. */
    private var pendingCapture: CaptureResult? = null

    private val _canRetryProfile = MutableStateFlow(false)
    val canRetryProfile: StateFlow<Boolean> = _canRetryProfile.asStateFlow()

    fun retryProfile() {
        val capture = pendingCapture ?: return startAddAccount()
        _accountError.value = null
        _canRetryProfile.value = false
        onCaptured(capture)
    }

    fun onCaptured(result: CaptureResult) {
        pendingCapture = result
        result.tokens.forEach { (aud, token) -> store.saveToken(aud, token) }
        viewModelScope.launch {
            try {
                val existing = store.account.value
                if (existing == null || existing.username != result.username || existing.studentId == null) {
                    val user = api.userData(result.username, result.domain)
                    val degree = user.Degrees.firstOrNull { it.DegreeId == user.DefaultDegreeId } ?: user.Degrees.firstOrNull()
                    store.saveAccount(
                        Account(
                            username = result.username,
                            domain = result.domain,
                            studentId = degree?.StudentId ?: user.StudentId,
                            firstName = user.Firstname,
                            lastName = user.Lastname,
                            email = user.EmailAddress,
                            degree = degree?.DescriptionEnglish ?: degree?.Name,
                        ),
                    )
                }
                if (_authMode.value == AuthMode.SILENT) lastSilentAuthAt = System.currentTimeMillis()
                _authMode.value = null
                pendingCapture = null
                refreshAll()
            } catch (e: Exception) {
                _authMode.value = null
                _accountError.value = "Signed in, but loading your profile failed: ${e.message}"
                _canRetryProfile.value = true
            }
        }
    }

    fun signOut() {
        store.clear()
        clearBrowserSession()
        _lectures.value = LoadState()
        _exams.value = LoadState()
        _grades.value = LoadState()
    }

    private fun handleError(e: Throwable): String? {
        if (e is AuthRequiredException) {
            if (System.currentTimeMillis() - lastSilentAuthAt < 60_000) {
                return "Couldn't get access from the website just now. Tap retry to sign in again."
            }
            if (_authMode.value == null) _authMode.value = AuthMode.SILENT
            return null // keep "loading" until the refresh finishes
        }
        return e.message ?: e.toString()
    }

    // ---- data ----------------------------------------------------------------------------------

    /** Retry after an error: allows another automatic re-login. */
    fun retry() {
        lastSilentAuthAt = 0L
        refreshAll()
    }

    fun refreshAll() {
        loadLectures()
        loadExams()
        loadGrades()
    }

    fun shiftWeek(delta: Int) {
        _weekOffset.update { if (delta == 0) 0 else it + delta }
        loadLectures()
    }

    /** First and last day (inclusive) of the 7-day window currently shown. */
    fun weekRange(offset: Int = _weekOffset.value): Pair<LocalDate, LocalDate> {
        val start = LocalDate.now().plusDays(7L * offset)
        return start to start.plusDays(6)
    }

    fun loadLectures() {
        lecturesJob?.cancel()
        lecturesJob = viewModelScope.launch {
            _lectures.update { it.copy(loading = true, error = null) }
            try {
                val (start, end) = weekRange()
                val zone = ZoneId.systemDefault()
                val data = api.timetable(
                    start.atStartOfDay(zone).toInstant(),
                    end.plusDays(1).atStartOfDay(zone).toInstant(),
                ).sortedBy { it.StartDate }
                _lectures.value = LoadState(data = data)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                handleError(e)?.let { msg -> _lectures.update { it.copy(loading = false, error = msg) } }
            }
        }
    }

    fun loadExams() {
        val studentId = store.account.value?.studentId ?: return
        viewModelScope.launch {
            _exams.update { it.copy(loading = true, error = null) }
            try {
                _exams.value = LoadState(data = api.exams(studentId))
            } catch (e: Exception) {
                handleError(e)?.let { msg -> _exams.update { it.copy(loading = false, error = msg) } }
            }
        }
    }

    fun loadGrades() {
        val studentId = store.account.value?.studentId ?: return
        viewModelScope.launch {
            _grades.update { it.copy(loading = true, error = null) }
            try {
                val (credits, career) = loadBoth({ api.creditPoints(studentId) }, { api.career(studentId) })
                _grades.value = LoadState(data = Grades(credits, career))
            } catch (e: Exception) {
                handleError(e)?.let { msg -> _grades.update { it.copy(loading = false, error = msg) } }
            }
        }
    }

    fun setEnrolment(exam: EnrolmentExam, enrol: Boolean) {
        val studentId = store.account.value?.studentId ?: return
        val examId = exam.examId ?: return
        val sessionId = exam.examSessionId ?: return
        viewModelScope.launch {
            _busyExam.value = sessionId
            try {
                val req = EnrolRequest(examSessionId = sessionId, examId = examId)
                val updated = if (enrol) api.enrol(studentId, req) else api.unenrol(studentId, req)
                _exams.value = LoadState(data = updated)
                _message.value = if (enrol) "Enrolment successful" else "Unenrolment successful"
            } catch (e: Exception) {
                if (e is AuthRequiredException) {
                    handleError(e)
                    _message.value = "Session refreshed – please try again"
                } else {
                    _message.value = "Failed: ${e.message}"
                }
                loadExams()
            } finally {
                _busyExam.value = null
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

/**
 * Runs two requests in parallel. The `coroutineScope` makes a failure surface as an exception
 * from this call (catchable by the caller) instead of escaping to the uncaught-exception handler,
 * which crashed the app when a token had expired.
 */
suspend fun <A, B> loadBoth(first: suspend () -> A, second: suspend () -> B): Pair<A, B> = coroutineScope {
    val a = async { first() }
    val b = async { second() }
    a.await() to b.await()
}
