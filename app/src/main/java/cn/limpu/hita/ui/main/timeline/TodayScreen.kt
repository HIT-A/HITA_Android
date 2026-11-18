package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.ui.main.rememberMainBottomBarClearance

@Composable
internal fun TodayScreen(state: TodayUiState, hints: List<EventItem>, onHint: (EventItem) -> Unit,
    onAction: (TodayAction) -> Unit, bottomClearance: Dp = rememberMainBottomBarClearance()) {
    val holder = rememberSaveableStateHolder()
    holder.SaveableStateProvider("${state.scopeKey}:${state.window.date}") {
        var expanded by rememberSaveable { mutableStateOf(false) }
        // Keep these alive even while data is loading: a short placeholder list must not clamp restoration.
        val agendaScroll = rememberLazyListState()
        val pageScroll = rememberLazyListState()
        BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            val mode = TodayLayoutPolicy.choose(maxWidth.value, maxHeight.value, bottomClearance.value, LocalDensity.current.fontScale)
            var previousMode by rememberSaveable { mutableStateOf(mode.name) }
            LaunchedEffect(mode) {
                if (previousMode != mode.name) { agendaScroll.scrollToItem(0); pageScroll.scrollToItem(0); previousMode = mode.name }
            }
            if (mode == TodayLayoutMode.PAGE_SCROLL) {
                if (todayHasAgendaData(state)) LazyColumn(state = pageScroll, modifier = Modifier.fillMaxSize().testTag("today-page-scroll"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottomClearance + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("header") { TodayHeader(state) }
                    item("agenda-heading") { TodayAgendaHeading(state, onAction) }
                    todayAgendaItems(state, expanded, { expanded = !expanded }, hints, onHint, onAction)
                    item("summaries") { TodaySummaryCards(state, onAction) }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = bottomClearance + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TodayHeader(state)
                    TodayAgendaHeading(state, onAction)
                    TodayAgendaPlaceholder(state, onAction)
                    TodaySummaryCards(state, onAction)
                }
            } else Column(Modifier.fillMaxSize().padding(bottom = bottomClearance + 12.dp).testTag("today-fixed")) {
                TodayHeader(state, fixed = true, compact = mode == TodayLayoutMode.FIXED_COMPACT)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.padding(horizontal = 20.dp).height(48.dp)) {
                    TodayAgendaHeading(state, onAction)
                }
                Spacer(Modifier.height(12.dp))
                if (todayHasAgendaData(state)) LazyColumn(state = agendaScroll,
                    modifier = Modifier.weight(1f).fillMaxWidth().todayScrollFade(agendaScroll).testTag("today-agenda-scroll"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    todayAgendaItems(state, expanded, { expanded = !expanded }, hints, onHint, onAction)
                } else Box(Modifier.weight(1f).padding(horizontal = 20.dp)) { TodayAgendaPlaceholder(state, onAction) }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.padding(horizontal = 20.dp).testTag("today-fixed-summaries")) {
                    TodaySummaryCards(state, onAction, fixed = true, compact = mode == TodayLayoutMode.FIXED_COMPACT)
                }
            }
        }
    }
}
