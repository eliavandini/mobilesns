package org.scientificnet.mobile.data

import kotlinx.serialization.Serializable

// ---- webservices.scientificnet.org/rest/cm/core/api/Userdata/GetUserdata ----

@Serializable
data class UserData(
    val StudentId: Long? = null,
    val StudentIds: List<Long> = emptyList(),
    val StudentRegNr: Long? = null,
    val DefaultDegreeId: Long? = null,
    val Degrees: List<Degree> = emptyList(),
    val Username: String? = null,
    val Firstname: String? = null,
    val Lastname: String? = null,
    val EmailAddress: String? = null,
    val Organisation: String? = null,
)

@Serializable
data class Degree(
    val DegreeId: Long? = null,
    val DescriptionEnglish: String? = null,
    val Name: String? = null,
    val StudentId: Long? = null,
    val IsActive: Boolean = true,
)

// ---- webservices.scientificnet.org/rest/cm/student/api/v2/Timetable ----

@Serializable
data class TimetableEntry(
    val Id: Long = 0,
    val ActivityCode: String? = null,
    val ActivityDescription: String? = null,
    val CourseDescription: String? = null,
    val StartDate: String? = null,
    val EndDate: String? = null,
    val HideEndTime: Boolean = false,
    val RoomDescription: String? = null,
    val RoomName: String? = null,
    val IsMandatory: Boolean = false,
    val MoodleUrl: String? = null,
    val TeamsJoinCode: String? = null,
    val Staff: List<Staff>? = null,
)

@Serializable
data class Staff(
    val AcademicTitle: String? = null,
    val Firstname: String? = null,
    val Lastname: String? = null,
    val TeamsUrl: String? = null,
    val TeamsJoinCode: String? = null,
    val MoodleUrl: String? = null,
) {
    val displayName: String
        get() = listOfNotNull(AcademicTitle, Firstname, Lastname)
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

/** A Microsoft Teams team for a lecture: link to open it and/or the code to join it. */
data class TeamsLink(val url: String?, val joinCode: String?, val owner: String?)

data class LectureLinks(val oleUrls: List<String>, val teams: List<TeamsLink>) {
    val isEmpty: Boolean get() = oleUrls.isEmpty() && teams.isEmpty()
}

/** Collects the OLE (Moodle) and Teams links of a lecture from the entry and its staff, without duplicates. */
fun TimetableEntry.links(): LectureLinks {
    fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
    val staff = Staff.orEmpty()

    val ole = (listOf(MoodleUrl) + staff.map { it.MoodleUrl })
        .mapNotNull { it.clean() }
        .distinct()

    val teams = mutableListOf<TeamsLink>()
    for (s in staff) {
        val url = s.TeamsUrl.clean()
        val code = s.TeamsJoinCode.clean()
        if (url == null && code == null) continue
        if (teams.any { (url != null && it.url == url) || (code != null && it.joinCode == code) }) continue
        teams += TeamsLink(url, code, s.displayName.clean())
    }
    TeamsJoinCode.clean()?.let { code ->
        if (teams.none { it.joinCode == code }) teams += TeamsLink(null, code, null)
    }
    return LectureLinks(ole, teams)
}

// ---- api.scientificnet.org/exam-enrolment-evaluation/api/student/{id}/exams ----

@Serializable
data class ExamsResponse(
    val statusCode: String? = null,
    val activeGroup: List<EnrolmentExam>? = null,
    val inactiveGroup: List<EnrolmentExam>? = null,
)

@Serializable
data class EnrolmentExam(
    val examId: Long? = null,
    val examSessionId: Long? = null,
    val groupId: Long? = null,
    val examDescription: String? = null,
    /** dd.MM.yyyy */
    val examDate: String? = null,
    /** HH:mm */
    val examTime: String? = null,
    val examCode: String? = null,
    val examTypeDescription: String? = null,
    val examProfessorName: String? = null,
    val commissionPresident: String? = null,
    val commissionMembers: String? = null,
    val enrolmentDone: Boolean? = null,
    val action: ExamAction? = null,
)

@Serializable
data class ExamAction(
    /** ENROL, UNENROL, EVAL, ... */
    val type: String? = null,
    /** TXT_ENROL, TXT_UNENROL, TXT_EVAL, TXT_ENROLMENT_NOT_YET_OPEN, TXT_UNENROL_NOT_POSSIBLE */
    val comment: String? = null,
    val tags: List<ActionTag>? = null,
)

@Serializable
data class ActionTag(
    val name: String? = null,
    val value: String? = null,
)

@Serializable
data class EnrolRequest(
    val examSessionId: Long,
    val examId: Long,
)

// ---- api.scientificnet.org/studyplan-course-selection/api/Student/{id}/... ----

@Serializable
data class CreditPoints(
    val pointsCurricular: Double? = null,
    val pointsNotCurricular: Double? = null,
    val averageMark: Double? = null,
    val averageMarkMax: Double? = null,
    val averageMark30: Double? = null,
    val averageMark30Max: Double? = null,
    val minimalPointsNecessary: Double? = null,
    val finalExamCreditPoints: Double? = null,
)

@Serializable
data class Career(
    val groups: List<CareerGroup> = emptyList(),
)

@Serializable
data class CareerGroup(
    val id: Long? = null,
    val descriptionEn: String? = null,
    val achievedCreditPoints: Double? = null,
    val minCreditPoints: Double? = null,
    val exams: List<CareerExam> = emptyList(),
)

@Serializable
data class CareerExam(
    val id: Long? = null,
    val code: Long? = null,
    val description: String? = null,
    val descriptionEn: String? = null,
    val creditPoints: Double? = null,
    val examDate: String? = null,
    val mark: Int? = null,
    val cumLaude: Boolean = false,
    val degreeYear: Int? = null,
    val isMandatory: Boolean = false,
    val status: CareerStatus? = null,
) {
    val title: String get() = descriptionEn?.takeIf { it.isNotBlank() } ?: description ?: "Exam"
}

@Serializable
data class CareerStatus(
    /** 0 = To do, 1 = Not passed, 2 = Passed */
    val id: Int? = null,
    val value: String? = null,
    val descriptionEn: String? = null,
    val protocolUrl: String? = null,
)
