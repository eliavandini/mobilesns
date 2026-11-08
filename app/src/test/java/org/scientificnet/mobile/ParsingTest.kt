package org.scientificnet.mobile

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scientificnet.mobile.data.Audience
import org.scientificnet.mobile.data.Career
import org.scientificnet.mobile.data.CreditPoints
import org.scientificnet.mobile.data.ExamsResponse
import org.scientificnet.mobile.data.Jwt
import org.scientificnet.mobile.data.TimetableEntry
import org.scientificnet.mobile.data.UserData
import java.util.Base64

/** Synthetic fixtures mirroring the shapes of the real API responses. */
class ParsingTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Test fun userData() {
        val u = json.decodeFromString<UserData>(
            """{"StudentId":1,"StudentIds":[1],"DefaultDegreeId":9,"Degrees":[{"DegreeId":9,"DescriptionEnglish":"Bachelor in X","StudentId":1,"IsActive":true}],
               "Username":"JDoe","Firstname":"Jane","Lastname":"Doe","EmailAddress":"j@x","ImageUrl":"u","Roles":["student"],"Organisation":"UNIBZ"}""",
        )
        assertEquals(1L, u.Degrees.single().StudentId)
    }

    @Test fun timetable() {
        val t = json.decodeFromString<List<TimetableEntry>>(
            """[{"Id":5,"AcademicYear":20,"ActivityCode":"LECT","ActivityDescription":"Net Sec - M2","CourseDescription":"Net Sec",
                "EndDate":"2026-11-09T15:30:00","StartDate":"2026-11-09T13:30:00","HideEndTime":false,"RoomDescription":"Room 1",
                "Staff":[{"Id":1,"AcademicTitle":"Prof.","Firstname":"A","Lastname":"B","ImageUrl":"x"}],"IsMandatory":true,
                "BookingStatus":{"StatusCode":1,"BookingStart":null},"AisDegrees":[]}]""",
        )
        assertEquals("Prof. A B", t.single().Staff!!.single().displayName)
    }

    @Test fun exams() {
        val e = json.decodeFromString<ExamsResponse>(
            """{"statusCode":"OK","activeGroup":[{"examId":3,"examSessionId":4,"examDescription":"Algebra","examDate":"12.02.2027",
                "examTime":"09:00","examCode":"7","enrolmentDone":false,"action":{"type":"ENROL","comment":"TXT_ENROL",
                "tags":[{"name":"ENROLMENT_DEADLINE","value":"05.02.2027"}]}}],"inactiveGroup":[]}""",
        )
        assertEquals("ENROL", e.activeGroup!!.single().action!!.type)
        assertEquals(json.decodeFromString<ExamsResponse>("""{"statusCode":"OK","activeGroup":[],"inactiveGroup":[]}""").activeGroup, emptyList<Any>())
    }

    @Test fun careerAndCredits() {
        val c = json.decodeFromString<Career>(
            """{"groups":[{"id":1,"descriptionEn":"Mandatory","achievedCreditPoints":75,"minCreditPoints":129,"groupType":{"id":0},
                "exams":[{"examDate":"2026-02-10T00:00:00","status":{"id":2,"value":"Passed","descriptionEn":"Passed","protocolUrl":"x"},
                "mark":25,"cumLaude":false,"scientificCode":["MAT/05"],"id":10,"code":76242,"description":"Analysis","descriptionEn":"Analysis",
                "creditPoints":6,"degreeYear":1,"syllabusLink":{"url":"x"}},
                {"examDate":null,"status":{"id":0,"value":"To do"},"mark":null,"id":11,"description":"Other","creditPoints":6}]}]}""",
        )
        assertEquals(25, c.groups.single().exams.first().mark)
        val cp = json.decodeFromString<CreditPoints>("""{"studentId":1,"pointsCurricular":81,"averageMark30":24.7,"minimalPointsNecessary":177,"finalExamCreditPoints":3}""")
        assertEquals(24.7, cp.averageMark30!!, 0.001)
    }

    @Test fun jwtAudience() {
        fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val token = b64("""{"alg":"none"}""") + "." + b64("""{"aud":"https://example.onmicrosoft.com/CockpitMobileTest","exp":2000000000}""") + ".sig"
        val payload = Jwt.payload(token)
        assertNotNull(payload)
        assertEquals(Audience.COCKPIT, Jwt.audiences(payload!!).firstNotNullOf { Audience.fromAud(it) })
        assertEquals(2000000000L, Jwt.expiry(payload))
        assertTrue(Audience.fromAud("https://example.onmicrosoft.com/mysns-exam-enrolment-evaluation-api") == Audience.EXAMS)
    }
}
