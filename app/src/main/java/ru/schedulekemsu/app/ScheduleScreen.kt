package ru.schedulekemsu.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp

private val gridLine = Color(0xFFB8B8B8)
private val weekdayLabels = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

@Composable
internal fun ScheduleScreen(state: ScreenState, model: ScheduleViewModel) {
    val tabState = rememberSaveableStateHolder()
    Scaffold(
        Modifier.imePadding(),
        bottomBar = {
            if (state.showsStudentTabs) StudentTabs(state, model)
        }
    ) { padding ->
        tabState.SaveableStateProvider(state.browsingTeachers) {
            ScheduleContent(state, model, Modifier.padding(padding))
        }
    }
}

@Composable
private fun StudentTabs(state: ScreenState, model: ScheduleViewModel) {
    Column(Modifier.background(Color.White).navigationBarsPadding()) {
        HorizontalDivider(color = gridLine)
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            BottomTab("Преподаватели", state.teachersTab, !state.busy, model::openTeachersTab, Modifier.weight(1f))
            BottomTab("Моё расписание", !state.teachersTab, !state.busy, model::openOwnTab, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BottomTab(title: String, selected: Boolean, enabled: Boolean, click: () -> Unit, modifier: Modifier) {
    Column(
        modifier.heightIn(min = 54.dp).selectable(selected, enabled = enabled, role = Role.Tab, onClick = click)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.width(72.dp).height(2.dp).background(if (selected) Color.Black else Color.Transparent))
        Spacer(Modifier.height(5.dp))
        Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ScheduleContent(state: ScreenState, model: ScheduleViewModel, modifier: Modifier) {
    val browsingTeachers = state.browsingTeachers
    val pickingTeacher = browsingTeachers && state.catalog != null
    val schedule = state.visibleSchedule
    val focus = LocalFocusManager.current
    val clearFocus = { focus.clearFocus() }
    BackHandler(enabled = pickingTeacher && schedule != null && !state.busy) {
        clearFocus()
        model.closeTeacherSearch()
    }
    val matches = remember(state.choices, state.saved, state.search, pickingTeacher) {
        if (pickingTeacher) state.teacherChoices.filter { it.matchesNameQuery(state.search) } else emptyList()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(pickingTeacher, state.search) {
        if (browsingTeachers) listState.scrollToItem(0)
    }

    LazyColumn(
        modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "heading") {
            if (browsingTeachers) {
                ScreenTitle("Расписание препода")
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ScreenTitle("Моё расписание", Modifier.weight(1f))
                    TextButton(model::logout, enabled = !state.busy) { Text("Выйти") }
                }
            }
        }

        if (browsingTeachers) {
            item(key = "teacher-search") { TeacherSearch(state, model, clearFocus) }
        }
        if (state.busy) {
            item(key = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        state.message?.let { message ->
            item(key = "message") {
                Text(message)
                if (pickingTeacher && state.choices.isEmpty() && !state.busy) {
                    TextButton(model::loadTeachers) { Text("Повторить поиск") }
                }
            }
        }

        when {
            pickingTeacher -> {
                if (matches.isEmpty() && !state.busy && (state.message == null || state.search.isNotBlank())) {
                    item(key = "empty-directory") { Text("Преподаватели не найдены.") }
                }
                items(matches, key = Choice::id) { choice ->
                    Column(
                        Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                            clearFocus()
                            model.choose(choice)
                        }
                    ) {
                        Text(choice.name, Modifier.padding(vertical = 13.dp))
                        HorizontalDivider(color = gridLine)
                    }
                }
            }
            schedule != null -> {
                item(key = "table") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(schedule.choice.name, style = MaterialTheme.typography.titleMedium)
                        ScheduleCalendar(schedule)
                    }
                }
                if (!browsingTeachers) {
                    items(state.tablesForTab.filter { it != schedule }, key = { it.choice.id }) { other ->
                        TextButton({ model.show(other) }, enabled = !state.busy) { Text(other.choice.name) }
                    }
                }
            }
            browsingTeachers -> item(key = "choose-teacher") {
                TextButton(model::openTeacherSearch, enabled = !state.busy) { Text("Выбрать преподавателя") }
            }
            else -> item(key = "no-schedule") {
                Text(
                    if (state.accountType == null) "ЭИОС пока не сообщила тип аккаунта."
                    else "Расписание пока недоступно."
                )
            }
        }
    }
}

@Composable
private fun TeacherSearch(state: ScreenState, model: ScheduleViewModel, clearFocus: () -> Unit) {
    var query by remember { mutableStateOf(state.search) }
    val updateQuery: (String) -> Unit = { text ->
        query = text
        model.search(text)
    }
    OutlinedTextField(
        query,
        updateQuery,
        Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) model.openTeacherSearch() },
        label = { Text("Поиск по ФИО") },
        leadingIcon = {
            Canvas(Modifier.size(22.dp)) {
                val stroke = 1.8.dp.toPx()
                drawCircle(Color.Black, radius = size.width * 0.29f, center = center * 0.82f, style = Stroke(stroke))
                drawLine(Color.Black, center * 1.22f, Offset(size.width * 0.91f, size.height * 0.91f), stroke)
            }
        },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton({ updateQuery("") }, modifier = Modifier.semantics { contentDescription = "Очистить поиск" }) {
                    Text("×", style = MaterialTheme.typography.titleLarge)
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search,
            hintLocales = LocaleList("ru-RU")
        ),
        keyboardActions = KeyboardActions(onSearch = { clearFocus() })
    )
}

@Composable
private fun ScheduleCalendar(schedule: TimeTable) {
    val today = remember { kemerovoDay() }
    val firstMonday = rememberSaveable { mondayOf(today) }
    var selectedDay by rememberSaveable { mutableIntStateOf(today) }
    val middle = 26
    val pager = rememberPagerState(initialPage = middle) { middle * 2 + 1 }

    Text(
        formatScheduleDay(selectedDay, "LLLL yyyy").replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.labelLarge
    )
    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(end = 9.dp),
        pageSpacing = 6.dp
    ) { page ->
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val monday = firstMonday + (page - middle) * 7
            weekdayLabels.forEachIndexed { index, label ->
                val day = monday + index
                DaySquare(day, label, day == selectedDay, Modifier.weight(1f)) { selectedDay = day }
            }
        }
    }
    val lessons = schedule.lessonsOn(selectedDay)
    when {
        lessons == null -> Text("На эту неделю расписание недоступно.")
        lessons.isEmpty() -> Text("Пар нет.")
        else -> LessonTable(lessons, schedule.week == null && schedule.snapshot.referenceWeek == null)
    }
}

@Composable
private fun DaySquare(day: Int, label: String, selected: Boolean, modifier: Modifier, click: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    val color = if (selected) Color.White else Color.Black
    Column(
        modifier.aspectRatio(1f).clip(shape).background(if (selected) Color.Black else Color.White)
            .border(1.dp, if (selected) Color.Black else gridLine, shape)
            .selectable(selected, role = Role.Button, onClick = click)
            .semantics { contentDescription = formatScheduleDay(day, "EEEE, d MMMM") },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
        Text(formatScheduleDay(day, "d"), color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LessonTable(lessons: List<Lesson>, showParity: Boolean) {
    Column(Modifier.fillMaxWidth().border(1.dp, gridLine)) {
        TableRow("Время", "Пара", "Ауд.", header = true)
        lessons.forEach { lesson ->
            HorizontalDivider(color = gridLine)
            val parity = if (!showParity) null else when (lesson.week) {
                Week.EVEN -> "Чётная неделя"
                Week.ODD -> "Нечётная неделя"
                Week.BOTH -> null
            }
            val details = listOfNotNull(lesson.details.takeIf(String::isNotBlank), parity).joinToString(" · ")
            TableRow(lesson.time, lesson.subject, lesson.room ?: "—", details)
        }
    }
}

@Composable
private fun TableRow(time: String, subject: String, room: String, details: String = "", header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(time, Modifier.weight(1.05f).padding(horizontal = 8.dp, vertical = 10.dp), style = style, fontWeight = weight)
        Box(Modifier.fillMaxHeight().width(1.dp).background(gridLine))
        Column(Modifier.weight(2.65f).padding(horizontal = 8.dp, vertical = 10.dp)) {
            Text(subject, style = style, fontWeight = weight)
            if (details.isNotBlank()) {
                Text(details, style = MaterialTheme.typography.bodySmall)
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(gridLine))
        Text(room, Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp), style = style, fontWeight = weight)
    }
}
