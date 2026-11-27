package ru.schedulekemsu.app

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okio.ByteString.Companion.decodeBase64
import java.io.IOException

internal open class EiosError(message: String) : Exception(message)

private class EiosSessionUnavailable(
    message: String = "Не удалось получить расписание из ЭИОС. Попробуйте позже."
) : EiosError(message)

@Serializable
internal enum class AccountType { STUDENT, TEACHER }

@Serializable
internal class Session(
    val login: String,
    val access: String?,
    val refresh: String?,
    val teacherName: String?,
    val password: String? = null,
    val accountType: AccountType? = null
) {
    // Older sessions stored the name only for employee accounts.
    val role get() = accountType ?: teacherName?.let { AccountType.TEACHER }

    fun withTokens(access: String?, refresh: String?) =
        Session(login, access, refresh, teacherName, password, accountType)

    fun asStudent() = Session(login, access, refresh, null, password, AccountType.STUDENT)

    fun withProfile(profile: JsonObject?): Session {
        profile ?: return this
        val type = when (profile.text("userType")?.trim()?.lowercase()) {
            "обучающийся" -> AccountType.STUDENT
            "сотрудник" -> AccountType.TEACHER
            else -> return this
        }
        val name = if (type == AccountType.TEACHER) {
            listOfNotNull(profile.text("lastName"), profile.text("firstName"), profile.text("middleName"))
                .map(String::trim).filter(String::isNotEmpty).joinToString(" ").ifEmpty { teacherName }
        } else null
        return Session(login, access, refresh, name, password, type)
    }
}

internal enum class Catalog(val method: String, val list: String, val id: String, val label: String, val kind: ScheduleKind?) {
    OWN("currentGroupList", "currentGroupList", "groupId", "groupName", ScheduleKind.GROUP),
    FACULTIES("facultyList", "result", "Id", "Faculty", null),
    GROUPS("groupList", "result", "Id", "GroupName", ScheduleKind.GROUP),
    TEACHERS("teacherList", "teacherList", "prepId", "fio", ScheduleKind.TEACHER),
    ROOMS("scheduleAuditorList", "auditorList", "auditoryId", "auditoryName", ScheduleKind.ROOM)
}

internal fun List<Choice>.findTeacher(name: String) =
    singleOrNull { it.name.matchesTeacher(name) }

private val nameWhitespace = Regex("\\s+")

internal fun String.matchesTeacher(name: String) =
    trim().replace(nameWhitespace, " ").equals(name.trim().replace(nameWhitespace, " "), ignoreCase = true)

internal class ScheduleRepository(
    private val api: EiosApi,
    private val saveSession: suspend (Session) -> Unit = {}
) {
    var session: Session? = null

    suspend fun login(login: String, password: String): Session? {
        val body = request {
            api.send("/api/auth", body = buildJsonObject {
                put("login", login)
                put("password", password)
                put("lifetime", "5m")
            })
        }
        return if (body.flag("twoFactorAuthEnabled") == true) null else signedInSession(body, login, password)
    }

    suspend fun confirm(login: String, code: String, password: String): Session {
        val body = request(verifyingCode = true) {
            api.send("/api/auth-by-code", body = buildJsonObject {
                put("login", login)
                put("code", code)
                put("lifetime", "5m")
            })
        }
        return signedInSession(body, login, password)
    }

    private suspend fun signedInSession(body: JsonObject, login: String, password: String): Session {
        if (body.flag("success") == false) throw EiosError("ЭИОС отклонила вход.")
        val access = body.token("accessToken")
        return Session(login, access, body.text("refreshToken"), null, password)
            .withProfile(body["userInfo"] as? JsonObject)
            .withProfile(profileBody(readProfile(access)))
    }

    private fun JsonObject.token(key: String): String =
        text(key)?.takeIf(String::isNotEmpty) ?: throw EiosError("ЭИОС не вернула токены сессии.")

    private suspend fun request(verifyingCode: Boolean = false, send: suspend () -> EiosReply): JsonObject {
        val response = try {
            send()
        } catch (_: IOException) {
            throw EiosError("Не удалось подключиться к ЭИОС.")
        }
        if (response.status !in 200..299) {
            val reason = when (response.status) {
                400 -> "ЭИОС не приняла запрос"
                401 -> if (verifyingCode) "ЭИОС не подтвердила код входа" else "ЭИОС не подтвердила данные входа"
                403 -> "ЭИОС отказала в доступе"
                else -> "Ошибка ответа ЭИОС"
            }
            throw EiosError("$reason (HTTP ${response.status}).")
        }
        return response.body ?: throw EiosError("ЭИОС вернула ответ в неизвестном формате.")
    }

    suspend fun restoreSession() {
        val current = session ?: return
        val access = current.access
        if (access == null || access.isExpiredJwt()) recoverSession(current)
        else restoreProfile(current, recoverExpired = true)
    }

    private suspend fun restoreProfile(current: Session, recoverExpired: Boolean = false): Session {
        if (current.role != null) return current
        val access = current.access ?: return current
        val response = readProfile(access)
        if (recoverExpired && response?.hasExpiredToken() == true) return recoverSession(current)
        val updated = current.withProfile(profileBody(response))
        return if (updated === current) current else installSession(updated)
    }

    private suspend fun readProfile(access: String): EiosReply? = withTimeoutOrNull(5_000L) {
        try {
            api.send("/api/security/users", token = access)
        } catch (_: IOException) {
            null
        }
    }

    private fun profileBody(response: EiosReply?): JsonObject? =
        response?.takeIf { it.status in 200..299 }?.body?.takeUnless { it.flag("success") == false }

    suspend fun refreshSchedules(selected: Choice?): List<TimeTable> {
        restoreSession()
        val previous = session ?: return emptyList()
        val groups = if (previous.role != AccountType.TEACHER) scheduleOrNull { catalog(Catalog.OWN) }.orEmpty()
            else emptyList()
        val current = session ?: return emptyList()
        // Legacy sessions may have valid tokens but no saved profile or password.
        val account = if (current.role == null && groups.isNotEmpty()) installSession(current.asStudent()) else current
        val own = scheduleOrNull {
            when (account.role) {
                AccountType.STUDENT -> groups
                AccountType.TEACHER -> account.teacherName?.let { name ->
                    listOfNotNull(catalog(Catalog.TEACHERS).findTeacher(name))
                }.orEmpty()
                null -> emptyList()
            }
        }.orEmpty()
        // A catalog request may have renewed the session and received a different profile.
        val browsed = selected?.takeIf { account.role == AccountType.STUDENT && it.kind == ScheduleKind.TEACHER }
        return buildList {
            for (choice in own + listOfNotNull(browsed)) {
                if (!isSameProfile(account)) break
                val table = scheduleOrNull { load(choice, allowWeekFallback = false, personal = choice in own) }
                if (table != null && isSameProfile(account)) add(table)
            }
        }
    }

    private fun isSameProfile(previous: Session): Boolean =
        session?.role == previous.role && session?.teacherName == previous.teacherName

    private suspend fun <T> scheduleOrNull(action: suspend () -> T): T? = try {
        action()
    } catch (_: EiosError) {
        null
    } catch (_: SerializationException) {
        null
    }

    private fun String.isExpiredJwt(): Boolean {
        val payload = split('.', limit = 3).takeIf { it.size == 3 }?.get(1)?.decodeBase64() ?: return false
        val claims = runCatching { Json.decodeFromString<JsonObject>(payload.utf8()) }.getOrNull()
        val expiration = (claims?.get("exp") as? JsonPrimitive)?.longOrNull ?: return false
        return expiration <= System.currentTimeMillis() / 1000
    }

    private suspend fun installSession(updated: Session): Session {
        saveSession(updated)
        session = updated
        return updated
    }

    // Startup restoration and schedule operations are serialized by the view model.
    private suspend fun recoverSession(current: Session): Session {
        try {
            val renewed = refresh(current)
            if (renewed != null) return restoreProfile(installSession(renewed))

            if (current.access != null || current.refresh != null) {
                installSession(current.withTokens(null, null))
            }
            val password = current.password ?: throw EiosSessionUnavailable(
                "Чтобы обновить расписание, один раз выйдите из аккаунта и войдите снова."
            )
            val signedIn = login(current.login, password) ?: throw EiosSessionUnavailable()
            return installSession(
                if (signedIn.role != null) signedIn
                else current.withTokens(signedIn.access, signedIn.refresh)
            )
        } catch (error: EiosSessionUnavailable) {
            throw error
        } catch (_: EiosError) {
            throw EiosSessionUnavailable()
        } catch (_: IOException) {
            throw EiosSessionUnavailable()
        }
    }

    private suspend fun read(method: String, query: Map<String, Int> = emptyMap()): JsonObject {
        val current = session ?: throw EiosError("Войдите в ЭИОС.")
        val access = current.access ?: checkNotNull(recoverSession(current).access)
        val path = "/api/schedule/integration/$method"

        val body = request {
            val response = api.send(path, query, token = access)
            if (!response.hasExpiredToken()) {
                response
            } else {
                if (current.access == null) throw EiosSessionUnavailable()
                val updated = recoverSession(current)
                api.send(path, query, token = updated.access).also {
                    if (it.hasExpiredToken()) throw EiosSessionUnavailable()
                }
            }
        }
        if (body.flag("success") == false) throw EiosError("ЭИОС отклонила запрос расписания.")
        return body
    }

    private fun EiosReply.hasExpiredToken() = status == 401 && body?.text("message") == "jwt expired"

    private suspend fun refresh(current: Session): Session? {
        val access = current.access ?: return null
        val refresh = current.refresh?.takeIf(String::isNotEmpty) ?: return null
        val response = api.send("/api/refresh-token", token = access, body = buildJsonObject {
            put("accessToken", access)
            put("refreshToken", refresh)
        })
        if (response.status in setOf(400, 401, 403)) return null
        val body = request { response }
        if (body.flag("success") == false) return null
        return current.withTokens(body.token("accessToken"), body.token("refreshToken"))
            .withProfile(body["userInfo"] as? JsonObject)
    }

    suspend fun catalog(catalog: Catalog, faculty: Int? = null): List<Choice> {
        val query = if (faculty == null) emptyMap() else mapOf("facultyId" to faculty)
        return read(catalog.method, query).objects(catalog.list).map { item ->
            val label = item.requiredText(catalog.label)
            val building = if (catalog == Catalog.ROOMS) item.text("auditoryBuild") else null
            val name = if (building.isNullOrBlank()) label else "$label · $building"
            Choice(item.number(catalog.id), name, catalog.kind)
        }.sortedBy(Choice::name)
    }

    suspend fun load(choice: Choice, allowWeekFallback: Boolean = true, personal: Boolean = false): TimeTable {
        val referenceDay = kemerovoDay()
        val today = try {
            read("currentDayInfo").optionalObject("currentDay")
        } catch (error: EiosSessionUnavailable) {
            throw error
        } catch (error: EiosError) {
            if (!allowWeekFallback && choice.kind == ScheduleKind.GROUP) throw error
            null
        }
        val parity = when (today?.strictOptionalText("weekType")?.lowercase()?.replace('ё', 'е')) {
            "нечетная" -> Week.ODD
            "четная" -> Week.EVEN
            else -> null
        }
        val weekNumber = (today?.get("weekNum") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        var semesterWeeks = emptyList<Int>()
        val body = when (checkNotNull(choice.kind)) {
            ScheduleKind.GROUP -> {
                val weeks = read("weekList").objects("result")
                val numberedWeeks = weeks.mapNotNull { week ->
                    (week["Num"] as? JsonPrimitive)?.intOrNull?.let { it to week }
                }
                semesterWeeks = numberedWeeks.map { it.first }.distinct()
                val currentWeek = numberedWeeks.firstOrNull { it.first == weekNumber }?.second
                val fallback = weeks.firstOrNull().takeIf { allowWeekFallback || weekNumber == null }
                val week = currentWeek ?: fallback ?: throw EiosError("ЭИОС не вернула текущую учебную неделю.")
                read("getGroupSheduleTable", mapOf("groupId" to choice.id, "semesterWeekId" to week.number("Id")))
            }
            ScheduleKind.TEACHER -> read("prepSheduleTable", mapOf("prepId" to choice.id))
            ScheduleKind.ROOM -> read("auditorSchedule", mapOf("auditoryId" to choice.id))
        }
        return TimeTable(
            choice, body, parity, personal = personal, referenceDay = referenceDay,
            referenceWeek = weekNumber, semesterWeeks = semesterWeeks
        )
    }
}
