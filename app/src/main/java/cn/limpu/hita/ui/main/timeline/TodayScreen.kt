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
import cn.limpu.hita.ui.main.rememberMainBottomBarClearance

@Composable
internal fun TodayScreen(state: TodayUiState, onAction: (TodayAction) -> Unit,
    bottomClearance: Dp = rememberMainBottomBarClearance()) {
    TodayCourseColors(state) {
        TodayScreenContent(state, onAction, bottomClearance)
    }
}

@Composable
private fun TodayScreenContent(state: TodayUiState, onAction: (TodayAction) -> Unit, bottomClearance: Dp) {
    val holder = rememberSaveableStateHolder()
    holder.SaveableStateProvider("${state.scopeKey}:${state.window.date}") {
        var expanded by rememberSaveable { mutableStateOf(false) }
        // Keep these alive even while data is loading: a short placeholder list must not clamp restoration.
        val agendaScroll = rememberLazyListState()
        val pageScroll = rememberLazyListState()
        BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            val fontScale = LocalDensity.current.fontScale
            val mode = TodayLayoutPolicy.choose(maxWidth.value, maxHeight.value, bottomClearance.value, fontScale)
            var previousMode by rememberSaveable { mutableStateOf(mode.name) }
            LaunchedEffect(mode) {
                if (previousMode != mode.name) { agendaScroll.scrollToItem(0); pageScroll.scrollToItem(0); previousMode = mode.name }
            }
            val compact = mode == TodayLayoutMode.FIXED_COMPACT
            val agenda = state.agenda
            val visibleCount = (if (expanded) agenda?.allToday?.size else agenda?.remaining?.size) ?: 0
            // 每节课可用高度不足一张卡片时退化为单行课程条，保证一天六节课不用滚动。
            val dense = visibleCount > 0 && TodayLayoutPolicy.agendaRowsAreDense(
                heightDp = maxHeight.value, bottomClearanceDp = bottomClearance.value,
                compact = compact, fontScale = fontScale, count = visibleCount,
            )
            if (mode == TodayLayoutMode.PAGE_SCROLL) {
                if (todayHasAgendaData(state)) LazyColumn(state = pageScroll, modifier = Modifier.fillMaxSize().testTag("today-page-scroll"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottomClearance + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 12.dp)) {
                    item("header") { TodayHeader(state) }
                    todayAgendaItems(state, expanded, { expanded = !expanded }, onAction, dense)
                    item("summaries") { TodaySummaryCards(state, onAction) }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = bottomClearance + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TodayHeader(state)
                    TodayAgendaPlaceholder(state, onAction)
                    TodaySummaryCards(state, onAction)
                }
            } else Column(Modifier.fillMaxSize().padding(bottom = bottomClearance + 12.dp).testTag("today-fixed")) {
                Spacer(Modifier.height(12.dp))
                if (todayHasAgendaData(state)) LazyColumn(state = agendaScroll,
                    modifier = Modifier.weight(1f).fillMaxWidth().todayScrollFade(agendaScroll).testTag("today-agenda-scroll"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 12.dp)) {
                    todayAgendaItems(state, expanded, { expanded = !expanded }, onAction, dense)
                } else Box(Modifier.weight(1f).padding(horizontal = 20.dp)) { TodayAgendaPlaceholder(state, onAction) }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.padding(horizontal = 20.dp).testTag("today-fixed-summaries")) {
                    TodaySummaryCards(state, onAction, fixed = true, compact = compact)
                }
            }
        }
    }
}
