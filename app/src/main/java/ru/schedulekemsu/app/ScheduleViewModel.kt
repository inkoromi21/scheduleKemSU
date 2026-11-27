package ru.schedulekemsu.app

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class ScreenState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val login: String = "",
    val pendingLogin: String? = null,
    val hasSession: Boolean = false,
    val accountType: AccountType? = null,
    val teacherName: String? = null,
    val teachersTab: Boolean = false,
    val message: String? = null,
    val catalog: Catalog? = null,
    val choices: List<Choice> = emptyList(),
    val selectedTeacherId: Int? = null,
    val search: String = "",
    val schedule: TimeTable? = null,
    val saved: List<TimeTable> = emptyList()
) {
    val showsStudentTabs: Boolean get() = accountType == AccountType.STUDENT
    val browsingTeachers: Boolean get() = showsStudentTabs && teachersTab

    val teacherChoices: List<Choice>
        get() = if (browsingTeachers) {
            (choices + tablesForTab.map(TimeTable::choice)).distinctBy(Choice::id).sortedBy(Choice::name)
        } else emptyList()

    val tablesForTab: List<TimeTable>
        get() = when {
            accountType == AccountType.TEACHER -> {
                val matches = saved.filter { table ->
                    table.choice.kind == ScheduleKind.TEACHER &&
                        teacherName?.let { table.choice.name.matchesTeacher(it) } == true
                }
                listOfNotNull(matches.firstOrNull { it.snapshot.personal } ?: matches.singleOrNull())
            }
            browsingTeachers -> saved.filter { it.choice.kind == ScheduleKind.TEACHER }
            else -> {
                val groups = saved.filter { it.choice.kind == ScheduleKind.GROUP }
                groups.filter { it.snapshot.personal }.ifEmpty { groups }
            }
        }

    val visibleSchedule: TimeTable?
        get() = if (accountType == AccountType.TEACHER) tablesForTab.singleOrNull() else schedule
    fun withAccount(session: Session?): ScreenState {
        if (accountType == session?.role && teacherName == session?.teacherName) return this
        return copy(
            accountType = session?.role, teacherName = session?.teacherName, teachersTab = false,
            catalog = null, choices = emptyList(), selectedTeacherId = null,
            search = "", schedule = null
        )
    }

    fun withSavedSchedules(tables: List<TimeTable>): ScreenState {
        val currentKey = schedule?.choice?.key
        val available = copy(saved = tables).tablesForTab
        val selected = available.firstOrNull { it.choice.key == currentKey }
            ?: if (browsingTeachers) null else available.firstOrNull()
        return copy(saved = tables, schedule = selected)
    }

    fun afterSynchronization(tables: List<TimeTable>, screenStarted: Boolean, session: Session? = null): ScreenState {
        val updated = session?.let { withAccount(it) } ?: this
        if (screenStarted && (schedule != null || catalog != null)) {
            if (updated === this) return this
            val cachedForRole = updated.tablesForTab
            return updated.withSavedSchedules(if (cachedForRole.isEmpty()) tables else saved)
        }
        return updated.withSavedSchedules(tables)
    }
}

internal class ScheduleViewModel(application: Application) : AndroidViewModel(application) {
    private val data = LocalData(application)
    private val repository = ScheduleRepository(
        EiosApi(
            Build.VERSION.RELEASE,
            BuildConfig.VERSION_NAME,
            "https://api-next.kemsu.ru/".toHttpUrl(),
            trace = if (BuildConfig.DEBUG && Log.isLoggable("EiosWire", Log.DEBUG)) {
                { Log.d("EiosWire", it) }
            } else null
        ),
        data::saveSession
    )
    private val screen = MutableStateFlow(ScreenState())
    val state = screen.asStateFlow()
    private var pendingPassword: String? = null
    private val operations = Mutex()
    private var synchronization: Job? = null
    private var screenStarted = false
    private var cachedSchedules = emptyList<TimeTable>()

    private val restoration = viewModelScope.launch {
        var message: String? = null
        try {
            repository.session = data.readSession()
            repository.session?.let { cachedSchedules = readSaved(it.login) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "Не удалось восстановить сохранённые данные."
        }
        screen.update {
            it.copy(ready = true, hasSession = repository.session != null, message = message)
                .withAccount(repository.session)
                .withSavedSchedules(cachedSchedules)
        }
    }

    fun onScreenStarted() {
        if (screenStarted) return
        screenStarted = true
        applySavedSchedules()
        synchronizeInBackground()
    }

    fun onScreenStopped() {
        screenStarted = false
        applySavedSchedules()
    }

    private fun applySavedSchedules() {
        screen.update { it.withAccount(repository.session).withSavedSchedules(cachedSchedules) }
    }

    private fun synchronizeInBackground() {
        if (synchronization?.isActive == true) return
        synchronization = viewModelScope.launch {
            restoration.join()
            operations.withLock {
                val account = repository.session?.login ?: return@withLock
                try {
                    val fresh = repository.refreshSchedules(state.value.schedule?.choice)
                    if (fresh.isNotEmpty()) saveSchedules(account, fresh)
                    screen.update { it.afterSynchronization(cachedSchedules, screenStarted, repository.session) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The last successfully saved version stays available.
                }
            }
        }
    }

    private suspend fun saveSchedules(account: String, fresh: List<TimeTable>) {
        val next = (fresh + cachedSchedules).distinctBy { it.choice.key }
        data.saveSchedule(account, Json.encodeToString(next.map(TimeTable::snapshot)))
        cachedSchedules = next
    }

    private suspend fun readSaved(login: String) =
        decodeScheduleCache(data.readSchedule(login)).values.toList()

    fun setLogin(login: String) = screen.update { it.copy(login = login) }

    fun login(password: String) {
        val login = state.value.login.trim()
        if (state.value.hasSession || state.value.pendingLogin != null || login.isEmpty() || password.isEmpty()) return
        runOperation {
            val session = repository.login(login, password)
            if (session == null) {
                pendingPassword = password
                screen.update { it.copy(pendingLogin = login, message = "ЭИОС запросила код подтверждения.") }
            } else {
                finishLogin(session)
            }
        }
    }

    fun confirm(code: String) {
        val login = state.value.pendingLogin ?: return
        val password = pendingPassword ?: return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        runOperation { finishLogin(repository.confirm(login, trimmed, password)) }
    }

    fun cancelCode() {
        if (state.value.busy) return
        pendingPassword = null
        screen.update { it.copy(pendingLogin = null, message = null) }
    }

    private suspend fun finishLogin(session: Session) {
        val saved = readSaved(session.login)
        data.saveSession(session)
        repository.session = session
        cachedSchedules = saved
        pendingPassword = null
        screen.update {
            it.copy(hasSession = true, login = "", pendingLogin = null, catalog = null, message = null)
                .withAccount(session)
                .withSavedSchedules(cachedSchedules)
        }
        synchronizeInBackground()
    }

    fun logout() = runOperation(cancelSynchronization = true) {
        data.clearAccount(repository.session?.login)
        repository.session = null
        pendingPassword = null
        cachedSchedules = emptyList()
        screen.value = ScreenState(ready = true, busy = true)
    }

    fun openOwnTab() = screen.update {
        if (!it.browsingTeachers) return@update it
        val ownTab = it.copy(teachersTab = false, catalog = null, schedule = null, message = null)
        val available = if (ownTab.tablesForTab.isEmpty()) cachedSchedules else it.saved
        ownTab.withSavedSchedules(available)
    }

    fun openTeachersTab() {
        val current = state.value
        if (!current.showsStudentTabs || current.busy || current.browsingTeachers) return
        val previous = current.saved.firstOrNull {
            it.choice.kind == ScheduleKind.TEACHER && it.choice.id == current.selectedTeacherId
        }
        screen.update { it.copy(teachersTab = true, schedule = previous, message = null) }
        if (previous == null) openTeacherSearch()
    }

    fun openTeacherSearch() {
        val current = state.value
        if (!current.browsingTeachers || current.busy || current.catalog != null) return
        screen.update { it.copy(catalog = Catalog.TEACHERS, message = null) }
        if (current.choices.isEmpty()) loadTeachers()
    }

    fun loadTeachers() {
        val current = state.value
        if (!current.browsingTeachers || current.catalog == null) return
        runScheduleOperation {
            val choices = repository.catalog(Catalog.TEACHERS)
            if (repository.session?.role != AccountType.STUDENT) return@runScheduleOperation
            screen.update { it.copy(choices = choices) }
        }
    }

    fun search(text: String) {
        if (!state.value.browsingTeachers) return
        screen.update { it.copy(search = text) }
        openTeacherSearch()
    }

    fun closeTeacherSearch() = screen.update {
        if (!it.browsingTeachers || it.schedule == null) it else it.copy(catalog = null, message = null)
    }

    fun choose(choice: Choice) = runScheduleOperation {
        if (state.value.browsingTeachers && choice in state.value.teacherChoices &&
            repository.session?.role == AccountType.STUDENT
        ) {
            load(choice)
        }
    }

    fun show(schedule: TimeTable) = screen.update {
        if (schedule !in it.tablesForTab) return@update it
        it.copy(
            schedule = schedule, catalog = null, message = null,
            selectedTeacherId = if (it.browsingTeachers) schedule.choice.id else it.selectedTeacherId
        )
    }

    private suspend fun load(choice: Choice) {
        val displayed = state.value.saved.firstOrNull { it.choice.key == choice.key }
        if (displayed != null) {
            show(displayed)
            return
        }

        // Selecting a different timetable can open it immediately.
        val account = checkNotNull(repository.session).login
        val schedule = cachedSchedules.firstOrNull { it.choice.key == choice.key }
            ?: repository.load(choice).also { saveSchedules(account, listOf(it)) }
        if (repository.session?.role == AccountType.STUDENT) {
            screen.update { it.copy(saved = listOf(schedule) + it.saved) }
            show(schedule)
        }
    }

    private fun runScheduleOperation(action: suspend () -> Unit) = runOperation {
        val previous = repository.session
        try {
            action()
        } finally {
            val current = repository.session
            if (current !== previous && current?.access != null) synchronizeInBackground()
        }
    }

    private fun runOperation(cancelSynchronization: Boolean = false, action: suspend () -> Unit) {
        if (!state.value.ready || state.value.busy) return
        screen.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            var message: String? = null
            try {
                if (cancelSynchronization) synchronization?.cancelAndJoin()
                operations.withLock { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: EiosError) {
                message = error.message
            } catch (_: Exception) {
                message = "Не удалось прочитать или сохранить данные."
            } finally {
                screen.update { it.copy(busy = false, message = message ?: it.message) }
            }
        }
    }
}
