package ru.schedulekemsu.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ScheduleTest {
    @Test
    fun groupKeepsParityAndAllowsMissingSlots() {
        val parsed = groupSchedule(json(groupResponse.replace("coupleEven1", "unused")))
        assertEquals(listOf(Week.BOTH, Week.ODD), parsed.single().lessons.map { it.week })
        assertEquals("Математика", parsed.single().lessons.first().subject)
        assertEquals("Лекция", parsed.single().lessons.first().details)
        assertEquals("105", parsed.single().lessons.first().room)
        assertEquals(3..8, parsed.single().lessons.last().weeks)
    }

    @Test
    fun teacherKeepsAllLessonsInOneSlotAndOptionalDetails() {
        val parsed = teacherSchedule(json(teacherResponse.replace("\"startWeekNum\":1,", "")))
        assertEquals(listOf(Week.ODD, Week.ODD), parsed.single().lessons.map { it.week })
        assertEquals("Информатика", parsed.single().lessons.last().subject)
        assertEquals("ПИ-252", parsed.single().lessons.last().details)
    }

    @Test
    fun roomKeepsPeriodAndDayIndexWhenSortingHeaders() {
        val parsed = roomSchedule(json(roomResponse))
        assertEquals(listOf("Понедельник", "Вторник"), parsed.map { it.name })
        assertEquals(
            listOf(Lesson(1, "08:30–10:00", Week.ODD, "Информатика", details = "ПИ-251", weeks = 3..8)),
            parsed.first().lessons
        )
        assertEquals(
            listOf(Lesson(2, "10:10–11:40", Week.BOTH, "Математика"), Lesson(2, "10:10–11:40", Week.EVEN, "Физика")),
            parsed.last().lessons
        )
    }

    @Test
    fun loginCatalogsAndLoadsMatchReferenceRequestOrder() = runBlocking {
        val responses = mapOf(
            "/api/auth" to """{"twoFactorAuthEnabled":true}""",
            "/api/auth-by-code" to """{"accessToken":"access","refreshToken":"refresh"}""",
            "/api/security/users" to """{"userType":"обучающийся"}""",
            "currentGroupList" to """{"currentGroupList":[{"groupId":42,"groupName":"ПИ-251"}]}""",
            "facultyList" to """{"result":[{"Id":8,"Faculty":"Институт цифры"}]}""",
            "groupList?facultyId=8" to """{"result":[{"Id":42,"GroupName":"ПИ-251"}]}""",
            "teacherList" to """{"teacherList":[{"prepId":17,"fio":"Тестовый преподаватель"}]}""",
            "scheduleAuditorList" to """{"auditorList":[{"auditoryId":23,"auditoryName":"105","auditoryBuild":"Корпус 5"}]}""",
            "currentDayInfo" to """{"currentDay":{"weekType":"Чётная","weekNum":2}}""",
            "weekList" to """{"result":[{"Id":71,"Num":1},{"Id":72,"Num":2},{"Id":73,"Num":2}]}""",
            "getGroupSheduleTable?groupId=42&semesterWeekId=72" to groupResponse,
            "prepSheduleTable?prepId=17" to teacherResponse,
            "auditorSchedule?auditoryId=23" to roomResponse
        )
        val fixture = Fixture { it.reply(200, responses[it.route] ?: error("Unexpected route")) }
        val repo = fixture.repo
        assertNull(repo.login("tester", "fixture-password"))
        assertNull(repo.session)
        repo.session = repo.confirm("tester", "012345", "fixture-password")
        assertEquals("access", repo.session?.access)
        assertEquals("fixture-password", repo.session?.password)
        assertEquals(AccountType.STUDENT, repo.session?.role)
        val group = repo.catalog(Catalog.OWN).single()
        val faculty = repo.catalog(Catalog.FACULTIES).single()
        assertEquals(group, repo.catalog(Catalog.GROUPS, faculty.id).single())
        val teacher = repo.catalog(Catalog.TEACHERS).single()
        val room = repo.catalog(Catalog.ROOMS).single()
        assertEquals("105 · Корпус 5", room.name)
        assertEquals(Week.EVEN, repo.load(group).week)
        assertEquals(teacher, repo.load(teacher).choice)
        assertEquals(room, repo.load(room).choice)
        assertEquals(
            responses.keys.take(8) + listOf(
                "currentDayInfo", "weekList", "getGroupSheduleTable?groupId=42&semesterWeekId=72",
                "currentDayInfo", "prepSheduleTable?prepId=17", "currentDayInfo", "auditorSchedule?auditoryId=23"
            ),
            fixture.requests.map { it.route }
        )
        val (login, code) = fixture.requests
        assertEquals(json("""{"login":"tester","password":"fixture-password","lifetime":"5m"}"""), login.json())
        assertEquals(json("""{"login":"tester","code":"012345","lifetime":"5m"}"""), code.json())
        assertEquals("application/json", login.body?.contentType().toString())
        assertEquals(listOf("POST", "POST"), fixture.requests.take(2).map { it.method })
        assertTrue(fixture.requests.take(2).all { it.header("x-access-token") == null })
        assertTrue(fixture.requests.drop(2).all { it.method == "GET" && it.header("x-access-token") == "access" })
        assertTrue(fixture.requests.all { it.header("User-Agent") == "Android 15 / Dart 3.10.8 / Build version 3.0.0 / kemsuMobile" })
    }

    @Test
    fun optionalRefreshAndEmployeeNameMatchReference() = runBlocking {
        val fixture = Fixture {
            when (it.route) {
                "/api/auth" -> it.reply(200, """{"accessToken":"access","userInfo":{"userType":"обучающийся"}}""")
                "/api/security/users" -> it.reply(200, """{"userType":"сотрудник","firstName":" Иван ","lastName":"Иванов"}""")
                else -> error("Unexpected route: ${it.route}")
            }
        }
        val session = fixture.repo.login("tester", "password")!!
        assertEquals(AccountType.TEACHER, session.role)
        assertEquals("Иванов Иван", session.teacherName)
        assertEquals(listOf("/api/auth", "/api/security/users"), fixture.requests.map { it.route })
        assertEquals("GET", fixture.requests.last().method)
        assertEquals("access", fixture.requests.last().header("x-access-token"))
        assertNull(session.refresh)
        val restored = Json.decodeFromString<Session>(Json.encodeToString(session))
        assertEquals(session.login, restored.login)
        assertEquals(session.access, restored.access)
        assertEquals(session.teacherName, restored.teacherName)
        assertEquals(AccountType.TEACHER, restored.accountType)
        assertNull(restored.refresh)
        assertEquals("password", restored.password)
        val legacy = Json.decodeFromString<Session>(
            """{"login":"tester","access":"access","refresh":null,"teacherName":null}"""
        )
        assertNull(legacy.password)
        assertNull(legacy.role)
        val legacyTeacher = Json.decodeFromString<Session>(
            """{"login":"tester","access":"access","refresh":null,"teacherName":"Иванов Иван"}"""
        )
        assertEquals(AccountType.TEACHER, legacyTeacher.role)
    }

    @Test
    fun expiredTokenRefreshesOnceAndRetriesUsingRotatedToken() = runBlocking {
        val fixture = Fixture { request ->
            when {
                request.route == "/api/refresh-token" -> request.reply(200, """{"accessToken":"new","refreshToken":"rotated"}""")
                request.header("x-access-token") == "old" -> request.reply(401, """{"message":"jwt expired"}""")
                else -> request.reply(200, """{"currentGroupList":[]}""")
            }
        }
        fixture.repo.session = Session("tester", "old", "refresh", null)
        assertTrue(fixture.repo.catalog(Catalog.OWN).isEmpty())
        assertEquals(
            listOf("currentGroupList", "/api/refresh-token", "/api/security/users", "currentGroupList"),
            fixture.requests.map { it.route }
        )
        assertEquals(listOf("old", "old", "new", "new"), fixture.requests.map { it.header("x-access-token") })
        assertEquals(json("""{"accessToken":"old","refreshToken":"refresh"}"""), fixture.requests[1].json())
        assertEquals("new", fixture.repo.session?.access)
        assertEquals("rotated", fixture.repo.session?.refresh)
    }

    @Test
    fun ordinaryUnauthorizedDoesNotRefresh() = runBlocking {
        val fixture = Fixture { it.reply(401, """{"message":"denied"}""") }
        fixture.repo.session = Session("tester", "old", "refresh", null)
        assertEquals("ЭИОС не подтвердила данные входа (HTTP 401).",
            runCatching { fixture.repo.catalog(Catalog.OWN) }.exceptionOrNull()?.message)
        assertEquals(listOf("currentGroupList"), fixture.requests.map { it.route })
        assertEquals("old", fixture.repo.session?.access)
    }

    @Test
    fun rejectedOrMissingRefreshKeepsProfileWithoutTokens() = runBlocking {
        for (refresh in listOf(null, "refresh")) {
            val fixture = Fixture {
                if (it.route == "/api/refresh-token") it.reply(403, "{}")
                else it.reply(401, """{"message":"jwt expired"}""")
            }
            fixture.repo.session = Session("tester", "old", refresh, null)
            assertEquals("Чтобы обновить расписание, один раз выйдите из аккаунта и войдите снова.",
                runCatching { fixture.repo.catalog(Catalog.OWN) }.exceptionOrNull()?.message)
            assertEquals("tester", fixture.repo.session?.login)
            assertNull(fixture.repo.session?.access)
            assertNull(fixture.repo.session?.refresh)
            assertEquals(if (refresh == null) 1 else 2, fixture.requests.size)
        }
    }

    @Test
    fun rejectedRefreshSignsInWithSavedCredentialsAndRetriesSchedule() = runBlocking {
        val fixture = Fixture { request ->
            when {
                request.route == "/api/refresh-token" -> request.reply(403, "{}")
                request.route == "/api/auth" ->
                    request.reply(200, """{"accessToken":"new","refreshToken":"rotated"}""")
                request.header("x-access-token") == "old" -> request.reply(401, """{"message":"jwt expired"}""")
                else -> request.reply(200, """{"currentGroupList":[]}""")
            }
        }
        fixture.repo.session = Session("tester", "old", "refresh", null, "secret fixture")
        assertTrue(fixture.repo.catalog(Catalog.OWN).isEmpty())
        assertEquals(
            listOf("currentGroupList", "/api/refresh-token", "/api/auth", "/api/security/users", "currentGroupList"),
            fixture.requests.map { it.route }
        )
        assertEquals("secret fixture", fixture.requests[2].json().text("password"))
        assertEquals("new", fixture.requests.last().header("x-access-token"))
        val persisted = fixture.savedSessions.last()
        assertEquals("rotated", persisted.refresh)
        assertEquals("secret fixture", persisted.password)
    }

    @Test
    fun startupRestoresExpiredSessionAndKeepsCredentialsOnNetworkFailure() = runBlocking {
        val expiredJwt = "e30.eyJleHAiOjF9.signature"
        var offline = true
        val fixture = Fixture {
            if (offline) throw IOException()
            it.reply(200, """{"accessToken":"renewed","refreshToken":"rotated"}""")
        }
        val restored = Session("tester", expiredJwt, "refresh", null, "secret fixture", AccountType.STUDENT)
        fixture.repo.session = restored
        assertTrue(runCatching { fixture.repo.restoreSession() }.exceptionOrNull() is EiosError)
        assertEquals(restored, fixture.repo.session)
        assertTrue(fixture.savedSessions.isEmpty())

        offline = false
        fixture.repo.restoreSession()
        assertEquals(listOf("/api/refresh-token", "/api/refresh-token"), fixture.requests.map { it.route })
        assertEquals("renewed", fixture.savedSessions.single().access)
        assertEquals("secret fixture", fixture.savedSessions.single().password)
        assertEquals(AccountType.STUDENT, fixture.savedSessions.single().role)
    }

    @Test
    fun profileRenewsExpiredTokenOnceAndPersistsResolvedRole() = runBlocking {
        val fixture = Fixture {
            when {
                it.route == "/api/refresh-token" -> it.reply(200, """{"accessToken":"new","refreshToken":"rotated"}""")
                it.route == "/api/security/users" && it.header("x-access-token") == "old" ->
                    it.reply(401, """{"message":"jwt expired"}""")
                it.route == "/api/security/users" -> it.reply(200, """{"userType":"обучающийся"}""")
                else -> error("Unexpected route: ${it.route}")
            }
        }
        fixture.repo.session = Session("tester", "old", "refresh", null)
        fixture.repo.restoreSession()
        assertEquals(
            listOf("/api/security/users", "/api/refresh-token", "/api/security/users"),
            fixture.requests.map { it.route }
        )
        assertEquals(listOf("old", "old", "new"), fixture.requests.map { it.header("x-access-token") })
        assertEquals("rotated", fixture.savedSessions.last().refresh)
        assertEquals(AccountType.STUDENT, fixture.savedSessions.last().role)
    }

    @Test
    fun startupWithSavedPasswordAndNoTokensKeepsAccountIfEiosRequiresCode() = runBlocking {
        val fixture = Fixture { it.reply(200, """{"twoFactorAuthEnabled":true}""") }
        val restored = Session("tester", null, null, null, "secret fixture")
        fixture.repo.session = restored
        assertTrue(runCatching { fixture.repo.restoreSession() }.exceptionOrNull() is EiosError)
        assertEquals(listOf("/api/auth"), fixture.requests.map { it.route })
        assertEquals(restored, fixture.repo.session)
        assertTrue(fixture.savedSessions.isEmpty())
    }

    @Test
    fun groupFallsBackToFirstWeekIfCurrentDayFails() = runBlocking {
        val fixture = Fixture {
            when (it.route) {
                "currentDayInfo" -> it.reply(503, "{}")
                "weekList" -> it.reply(200, """{"result":[{"Id":71,"Num":1},{"Id":72,"Num":2}]}""")
                "getGroupSheduleTable?groupId=42&semesterWeekId=71" -> it.reply(200, groupResponse)
                else -> error("Unexpected route")
            }
        }
        fixture.repo.session = Session("tester", "access", null, null)
        assertNull(fixture.repo.load(Choice(42, "ПИ-251", ScheduleKind.GROUP)).week)
        assertEquals("getGroupSheduleTable?groupId=42&semesterWeekId=71", fixture.requests.last().route)
    }

    @Test
    fun silentSignInFetchesOwnScheduleWithNewSession() = runBlocking {
        val fixture = Fixture {
            when (it.route) {
                "/api/auth" -> it.reply(200, """{"accessToken":"new","userInfo":{"userType":"обучающийся"}}""")
                "/api/security/users" -> it.reply(503, "{}")
                "currentGroupList" -> it.reply(200, """{"currentGroupList":[{"groupId":42,"groupName":"ПИ-251"}]}""")
                "currentDayInfo" -> it.reply(200, """{"currentDay":{"weekNum":2,"weekType":"чётная"}}""")
                "weekList" -> it.reply(200, """{"result":[{"Id":71,"Num":1},{"Id":72,"Num":2}]}""")
                "getGroupSheduleTable?groupId=42&semesterWeekId=72" -> it.reply(200, groupResponse)
                else -> error("Unexpected route")
            }
        }
        val own = Choice(42, "ПИ-251", ScheduleKind.GROUP)
        fixture.repo.session = Session("tester", null, null, null, "fixture-password")
        val schedule = fixture.repo.refreshSchedules(own).single()
        assertEquals(own, schedule.choice)
        assertEquals(Week.EVEN, schedule.week)
        assertTrue(schedule.snapshot.personal)
        assertEquals("/api/auth", fixture.requests.first().route)
        assertTrue(fixture.requests.drop(1).all { it.header("x-access-token") == "new" })
        assertEquals("new", fixture.savedSessions.single().access)
    }

    @Test
    fun backgroundFailureKeepsCachedWeekAndRetriesOnNextSync() = runBlocking {
        var online = false
        val fixture = Fixture {
            when (it.route) {
                "currentGroupList" -> it.reply(200, """{"currentGroupList":[{"groupId":42,"groupName":"ПИ-251"}]}""")
                "currentDayInfo" -> if (online) it.reply(200, """{"currentDay":{"weekNum":2}}""") else throw IOException()
                "weekList" -> it.reply(200, """{"result":[{"Id":71,"Num":1},{"Id":72,"Num":2}]}""")
                "getGroupSheduleTable?groupId=42&semesterWeekId=72" -> it.reply(200, groupResponse)
                else -> error("Unexpected route")
            }
        }
        fixture.repo.session = Session("tester", "access", null, null, accountType = AccountType.STUDENT)
        assertTrue(fixture.repo.refreshSchedules(null).isEmpty())
        assertEquals(listOf("currentGroupList", "currentDayInfo"), fixture.requests.map { it.route })
        online = true
        assertEquals(42, fixture.repo.refreshSchedules(null).single().choice.id)
    }

    @Test
    fun backgroundSyncMatchesWeekNumbersAcrossJsonRepresentations() = runBlocking {
        val fixture = Fixture {
            when (it.route) {
                "currentGroupList" -> it.reply(200, """{"currentGroupList":[{"groupId":42,"groupName":"ПИ-251"}]}""")
                "currentDayInfo" -> it.reply(200, """{"currentDay":{"weekNum":"2","weekType":"чётная"}}""")
                "weekList" -> it.reply(200, """{"result":[{"Id":71,"Num":1},{"Id":72,"Num":2}]}""")
                "getGroupSheduleTable?groupId=42&semesterWeekId=72" -> it.reply(200, groupResponse)
                else -> error("Unexpected route: ${it.route}")
            }
        }
        fixture.repo.session = Session("tester", "access", null, null, accountType = AccountType.STUDENT)

        val table = fixture.repo.refreshSchedules(null).single()

        assertEquals(2, table.snapshot.referenceWeek)
        assertEquals(Week.EVEN, table.week)
        assertEquals("getGroupSheduleTable?groupId=42&semesterWeekId=72", fixture.requests.last().route)
    }

    @Test
    fun foregroundKeepsDisplayedLessonsUntilLifecycleBoundary() {
        val choice = Choice(42, "ПИ-251", ScheduleKind.GROUP)
        val saved = TimeTable(choice, json(groupResponse), Week.ODD)
        val fresh = TimeTable(choice, json(groupResponse.replace("Математика", "Физика")), Week.ODD)
        val opening = ScreenState(hasSession = true)
        assertEquals(fresh, opening.afterSynchronization(listOf(fresh), screenStarted = true).schedule)

        val visible = opening.withSavedSchedules(listOf(saved))
        assertEquals(visible, visible.afterSynchronization(listOf(fresh), screenStarted = true))
        val stopped = visible.afterSynchronization(listOf(fresh), screenStarted = false)
        assertEquals(fresh, stopped.schedule)
        assertEquals(stopped, visible.withSavedSchedules(listOf(fresh)))

        val student = Session("student", "access", null, null, accountType = AccountType.STUDENT)
        val resolved = visible.afterSynchronization(listOf(fresh), screenStarted = true, session = student)
        assertEquals(AccountType.STUDENT, resolved.accountType)
        assertEquals(saved, resolved.schedule)
    }

    @Test
    fun dateStripUsesWeekdayNumbersParityAndTeachingPeriodsAfterCacheRestore() {
        val monday = mondayOf(kemerovoDay(1_795_654_800_000L))
        val table = TimeTable(
            Choice(42, "ПИ-251", ScheduleKind.GROUP), json(groupResponse), Week.ODD,
            referenceDay = monday + 3, referenceWeek = 3, semesterWeeks = listOf(2, 3, 4, 5)
        )
        val restored = decodeScheduleCache(Json.encodeToString(listOf(table.snapshot))).values.single()
        assertEquals(emptyList<Lesson>(), restored.lessonsOn(monday + 3))
        assertEquals(listOf("Математика", "Информатика"), restored.lessonsOn(monday)?.map(Lesson::subject))
        assertEquals(listOf("Математика"), restored.lessonsOn(monday + 7)?.map(Lesson::subject))
        assertEquals(listOf("Математика", "Информатика"), restored.lessonsOn(monday + 14)?.map(Lesson::subject))
        assertNull(restored.lessonsOn(monday + 21))

        val outsideLessonPeriod = TimeTable(
            table.choice, table.snapshot.body, Week.ODD, referenceDay = monday, referenceWeek = 1
        )
        assertEquals(listOf("Математика"), outsideLessonPeriod.lessonsOn(monday)?.map(Lesson::subject))
    }

    @Test
    fun teacherSearchMatchesMultipleNamePartsAndKeepsSavedTeachersAvailable() {
        val teacher = Choice(17, "Семёнов Артём Викторович", ScheduleKind.TEACHER)
        val other = Choice(18, "Семёнов Олег Иванович", ScheduleKind.TEACHER)
        val saved = TimeTable(teacher, json(teacherResponse), Week.ODD)
        val state = ScreenState(
            accountType = AccountType.STUDENT, teachersTab = true, choices = listOf(other, teacher), saved = listOf(saved)
        )
        assertEquals(listOf(teacher), state.teacherChoices.filter { it.matchesNameQuery(" арТЕМ  семенов ") })
        assertEquals(listOf(teacher), state.teacherChoices.filter { it.matchesNameQuery("Семенов А. В.") })
        assertEquals(listOf(teacher), state.copy(choices = emptyList()).teacherChoices)
        assertEquals(2, state.teacherChoices.count { it.matchesNameQuery("  ") })
    }

    @Test
    fun employeeSignsInAndOpensPersonalScheduleWithCalendarAndOfflineCache() = runBlocking {
        val fixture = Fixture {
            when (it.route) {
                "/api/auth" -> it.reply(200, """{"accessToken":"access"}""")
                "/api/security/users" -> it.reply(200, """{
                    "userType":"Сотрудник","lastName":"Иванов","firstName":"Иван","middleName":"Иванович"
                }""")
                "teacherList" -> it.reply(200, """{"teacherList":[
                    {"prepId":17,"fio":"Иванов Иван Иванович"},{"prepId":18,"fio":"Петров Пётр Петрович"}
                ]}""")
                "currentDayInfo" -> it.reply(200, """{"currentDay":{"weekType":"нечётная","weekNum":3}}""")
                "prepSheduleTable?prepId=17" -> it.reply(
                    200, teacherResponse.replace("\"groupName\":\"ПИ-251\"", "\"groupName\":\"ПИ-251\",\"auditoryName\":\"301\"")
                )
                else -> error("Unexpected route: ${it.route}")
            }
        }
        val employee = checkNotNull(fixture.repo.login("employee", "password"))
        fixture.repo.session = employee
        val tables = fixture.repo.refreshSchedules(Choice(18, "Петров Пётр Петрович", ScheduleKind.TEACHER))
        val screen = ScreenState(hasSession = true).withAccount(employee).withSavedSchedules(tables)

        assertEquals(AccountType.TEACHER, employee.role)
        assertFalse(screen.showsStudentTabs)
        assertFalse(screen.browsingTeachers)
        assertTrue(screen.teacherChoices.isEmpty())
        val personal = checkNotNull(screen.visibleSchedule)
        assertEquals(17, personal.choice.id)
        assertTrue(personal.snapshot.personal)
        assertEquals(
            listOf("/api/auth", "/api/security/users", "teacherList", "currentDayInfo", "prepSheduleTable?prepId=17"),
            fixture.requests.map { it.route }
        )
        val monday = mondayOf(checkNotNull(personal.snapshot.referenceDay))
        val lessons = checkNotNull(personal.lessonsOn(monday))
        assertEquals(listOf("ПИ-251", "ПИ-252"), lessons.map(Lesson::details))
        assertEquals(listOf("Информатика", "Информатика"), lessons.map(Lesson::subject))
        assertEquals("301", lessons.first().room)
        assertEquals(emptyList<Lesson>(), personal.lessonsOn(monday + 1))
        assertEquals(emptyList<Lesson>(), personal.lessonsOn(monday + 7))

        val cache = decodeScheduleCache(Json.encodeToString(tables.map(TimeTable::snapshot))).values.toList()
        val reopened = ScreenState(hasSession = true).withAccount(employee).withSavedSchedules(cache)
        assertEquals(personal.snapshot, reopened.visibleSchedule?.snapshot)
        val fresh = personal.snapshot.copy(body = json(teacherResponse.replace("Информатика", "Физика"))).table()
        assertEquals(reopened, reopened.afterSynchronization(listOf(fresh), screenStarted = true, session = employee))
        assertEquals(fresh, reopened.afterSynchronization(listOf(fresh), screenStarted = false, session = employee).visibleSchedule)
    }

    @Test
    fun legacySessionResolvesTeacherRoleInBackgroundAndLoadsOnlyTheirSchedule() = runBlocking {
        val fixture = Fixture {
            when (it.route) {
                "/api/security/users" -> it.reply(200, """{
                    "userType":" Сотрудник ","lastName":"Иванов","firstName":"Иван","middleName":"Иванович"
                }""")
                "teacherList" -> it.reply(200, """{"teacherList":[
                    {"prepId":17,"fio":"Иванов  Иван Иванович"},{"prepId":18,"fio":"Петров Пётр Петрович"}
                ]}""")
                "currentDayInfo" -> it.reply(200, """{"currentDay":{"weekType":"нечётная"}}""")
                "prepSheduleTable?prepId=17" -> it.reply(200, teacherResponse)
                else -> error("Unexpected route: ${it.route}")
            }
        }
        fixture.repo.session = Session("tester", "legacy", null, null)
        val schedule = fixture.repo.refreshSchedules(Choice(18, "Петров Пётр Петрович", ScheduleKind.TEACHER)).single()
        assertEquals(
            listOf("/api/security/users", "teacherList", "currentDayInfo", "prepSheduleTable?prepId=17"),
            fixture.requests.map { it.route }
        )
        assertEquals("legacy", fixture.requests.first().header("x-access-token"))
        assertEquals(AccountType.TEACHER, fixture.savedSessions.single().role)
        assertEquals(17, schedule.choice.id)
        assertTrue(schedule.snapshot.personal)
    }

    @Test
    fun employeeWithoutNameKeepsTeacherRoleAndUnknownProfileHasNoRole() = runBlocking {
        var userType = "сотрудник"
        val fixture = Fixture {
            when (it.route) {
                "/api/auth" -> it.reply(200, """{"accessToken":"access"}""")
                "/api/security/users" -> it.reply(200, """{"userType":"$userType"}""")
                else -> error("Unexpected route: ${it.route}")
            }
        }
        val employee = fixture.repo.login("tester", "password")!!
        assertEquals(AccountType.TEACHER, employee.role)
        assertNull(employee.teacherName)
        fixture.repo.session = employee
        assertTrue(fixture.repo.refreshSchedules(null).isEmpty())
        assertEquals(2, fixture.requests.size)
        userType = "неизвестный тип"
        assertNull(fixture.repo.login("tester", "password")?.role)
    }

    @Test
    fun legacySessionRecognizesStudentFromAuthenticatedOwnGroups() = runBlocking {
        var hasGroup = false
        val fixture = Fixture {
            when (it.route) {
                "/api/security/users" -> it.reply(503, "{}")
                "currentGroupList" -> it.reply(200, if (hasGroup)
                    """{"currentGroupList":[{"groupId":42,"groupName":"ПИ-251"}]}"""
                    else """{"currentGroupList":[]}""")
                "currentDayInfo" -> it.reply(200, """{"currentDay":{"weekNum":2}}""")
                "weekList" -> it.reply(200, """{"result":[{"Id":72,"Num":2}]}""")
                "getGroupSheduleTable?groupId=42&semesterWeekId=72" -> it.reply(200, groupResponse)
                else -> error("Unexpected route: ${it.route}")
            }
        }
        fixture.repo.session = Session("tester", "access", null, null)
        assertTrue(fixture.repo.refreshSchedules(null).isEmpty())
        assertNull(fixture.repo.session?.role)
        hasGroup = true
        assertEquals(42, fixture.repo.refreshSchedules(null).single().choice.id)
        assertEquals(AccountType.STUDENT, fixture.savedSessions.single().role)
    }

    @Test
    fun roleKeepsPersonalScheduleSeparateFromBrowsedAndLegacyCaches() {
        val group = TimeTable(Choice(42, "ПИ-251", ScheduleKind.GROUP), json(groupResponse), Week.ODD, personal = true)
        val oldGroup = TimeTable(Choice(43, "ПИ-252", ScheduleKind.GROUP), json(groupResponse), Week.ODD)
        val teacher = TimeTable(Choice(17, "Иванов Иван", ScheduleKind.TEACHER), json(teacherResponse), Week.ODD)
        val otherTeacher = TimeTable(Choice(18, "Петров Пётр", ScheduleKind.TEACHER), json(teacherResponse), Week.ODD)
        val cache = listOf(otherTeacher, teacher, oldGroup, group)
        val student = Session("student", "access", null, null, accountType = AccountType.STUDENT)
        val employee = Session("employee", "access", null, "Иванов Иван", accountType = AccountType.TEACHER)

        val ownTab = ScreenState(hasSession = true).withAccount(student).withSavedSchedules(cache)
        assertEquals(group, ownTab.schedule)
        assertEquals(listOf(group), ownTab.tablesForTab)
        val teachersTab = ownTab.copy(teachersTab = true).withSavedSchedules(cache)
        assertTrue(teachersTab.showsStudentTabs)
        assertTrue(teachersTab.browsingTeachers)
        assertNull(teachersTab.schedule)
        assertEquals(listOf(otherTeacher, teacher), teachersTab.tablesForTab)
        val searching = teachersTab.copy(catalog = Catalog.TEACHERS, choices = listOf(otherTeacher.choice), schedule = otherTeacher)
        val employeeScreen = searching.afterSynchronization(cache, screenStarted = true, session = employee)
        assertFalse(employeeScreen.showsStudentTabs)
        assertFalse(employeeScreen.browsingTeachers)
        assertNull(employeeScreen.catalog)
        assertTrue(employeeScreen.teacherChoices.isEmpty())
        assertEquals(teacher, employeeScreen.visibleSchedule)
        assertEquals(listOf(teacher), employeeScreen.tablesForTab)

        val staleScreen = searching.copy(accountType = AccountType.TEACHER, teacherName = employee.teacherName)
        assertFalse(staleScreen.browsingTeachers)
        assertTrue(staleScreen.teacherChoices.isEmpty())
        assertEquals(teacher, staleScreen.visibleSchedule)
        assertNull(staleScreen.copy(saved = listOf(otherTeacher, group)).visibleSchedule)
    }

    @Test
    fun cacheRestoresRawBodyParityAndRecencyAcrossRestarts() {
        val group = TimeTable(Choice(42, "ПИ-251", ScheduleKind.GROUP), json(groupResponse), Week.ODD, "2026-11-26T12:00:00.000")
        val room = TimeTable(Choice(23, "105", ScheduleKind.ROOM), json(roomResponse), Week.EVEN, "2026-11-26T18:00:00.000")
        val saved = Json.encodeToString(listOf(group.snapshot, room.snapshot))
        val restored = decodeScheduleCache(saved).values.toList()
        assertEquals(listOf(room.snapshot, group.snapshot), restored.map { it.snapshot })
        assertEquals(group.days, restored.last().days)
        assertEquals(room.days, restored.first().days)
    }

    @Test
    fun redirectRulesUseGetAfter303AndDoNotForwardCredentialsToAnotherHost() = runBlocking {
        val fixture = Fixture {
            when (it.route) {
                "/api/auth" -> it.reply(303, "", "/confirmed")
                "/confirmed" -> it.reply(200, """{"accessToken":"access"}""")
                else -> it.reply(302, "", "https://other.example/schedule")
            }
        }
        fixture.repo.session = fixture.repo.login("tester", "password")
        val redirected = fixture.requests.single { it.route == "/confirmed" }
        assertEquals("GET", redirected.method)
        assertNull(redirected.body)
        assertEquals("Не удалось подключиться к ЭИОС.",
            runCatching { fixture.repo.catalog(Catalog.OWN) }.exceptionOrNull()?.message)
        assertEquals(
            listOf("/api/auth", "/confirmed", "/api/security/users", "currentGroupList"),
            fixture.requests.map { it.route }
        )
    }

    @Test
    fun rejectedCodeAndNetworkFailureUseReferenceMessages() = runBlocking {
        val fixture = Fixture {
            if (it.route == "/api/auth-by-code") it.reply(401, """{"message":"private-server-response"}""")
            else throw IOException()
        }
        assertEquals("ЭИОС не подтвердила код входа (HTTP 401).",
            runCatching { fixture.repo.confirm("tester", "012345", "fixture-password") }.exceptionOrNull()?.message)
        assertEquals("Не удалось подключиться к ЭИОС.",
            runCatching { fixture.repo.login("tester", "password") }.exceptionOrNull()?.message)
        assertNull(fixture.repo.session)
    }

    private class Fixture(respond: (Request) -> Response) {
        val requests = mutableListOf<Request>()
        val savedSessions = mutableListOf<Session>()
        private val client = EiosApi.clientBuilder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            respond(request)
        }.build()
        val repo = ScheduleRepository(EiosApi("15", "3.0.0", "https://example.test/".toHttpUrl(), client)) {
            savedSessions.add(it)
        }
    }

    companion object {
        private val Request.route: String
            get() = url.encodedPath.removePrefix("/api/schedule/integration/") +
                (url.encodedQuery?.let { "?$it" } ?: "")

        private fun Request.json() = json(Buffer().apply { checkNotNull(body).writeTo(this) }.readUtf8())
        private fun json(source: String) = Json.decodeFromString<JsonObject>(source)

        private fun Request.reply(code: Int, json: String, location: String? = null) = Response.Builder()
            .request(this)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Fixture")
            .body(json.toResponseBody("application/json".toMediaType()))
            .apply { location?.let { header("Location", it) } }
            .build()

        private val groupResponse = """
            {"success":true,"result":{
                "WeekList":[{"DayNum":1,"DayName":"Понедельник"}],
                "CoupleList":[{"Num":1,"Description":"08:30–10:00"}],
                "Table":{"weekDay1":{
                    "coupleAll1":[{"DiscName":"Математика","lessonType":"Лекция","AuditoryName":"105"}],
                    "coupleOdd1":[{"DiscName":"Информатика","minWeekNum":3,"maxWeekNum":8}],
                    "coupleEven1":[]
                }}
            }}
        """.trimIndent()

        private val teacherResponse = """
            {"success":true,"result":{"prepScheduleTable":[{
                "weekDay":{"DayNum":1,"DayName":"Понедельник"},
                "ceilList":[{"couple":{"Num":1,"Description":"08:30–10:00"},"ceil":{
                    "uneven":[
                        {"discName":"Информатика","groupName":"ПИ-251","startWeekNum":1,"endWeekNum":16},
                        {"discName":"Информатика","groupName":"ПИ-252","startWeekNum":1,"endWeekNum":16}
                    ],
                    "even":[]
                }}]
            }]}}
        """.trimIndent()

        private val roomResponse = """
            {"success":true,
             "weekDayList":[{"DayNum":2,"DayName":"Вторник"},{"DayNum":1,"DayName":"Понедельник"}],
             "coupleList":[{"Num":2,"Description":"10:10–11:40"},{"Num":1,"Description":"08:30–10:00"}],
             "table":[
                [{"coupleAll":[{"discName":"Математика"}],"coupleEven":[{"discName":"Физика"}]},{}],
                [{"coupleAll":null},{"coupleOdd":[{"discName":"Информатика","groupName":"ПИ-251",
                    "startWeekNum":3,"endWeekNum":8}]}]
             ]}
        """.trimIndent()
    }
}
