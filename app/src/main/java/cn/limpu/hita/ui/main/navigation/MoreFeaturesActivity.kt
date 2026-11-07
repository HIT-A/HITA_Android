package cn.limpu.hita.ui.main.navigation

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.source.preference.OptionalTabStore
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme

/** 更多功能页：独立开关底部导航的待办、资讯、助手三个模块。返回即回到设置页。 */
class MoreFeaturesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = OptionalTabStore(this)
        setContentView(ComposeView(this).apply {
            setContent {
                HitaComposeTheme {
                    var homework by remember { mutableStateOf(store.features().homework) }
                    var info by remember { mutableStateOf(store.features().info) }
                    var agent by remember { mutableStateOf(store.features().agent) }
                    MoreFeaturesScreen(
                        homeworkEnabled = homework,
                        infoEnabled = info,
                        agentEnabled = agent,
                        onHomework = { homework = it; store.setHomework(it) },
                        onInfo = { info = it; store.setInfo(it) },
                        onAgent = { agent = it; store.setAgent(it) },
                        onBack = { finish() },
                    )
                }
            }
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreFeaturesScreen(
    homeworkEnabled: Boolean,
    infoEnabled: Boolean,
    agentEnabled: Boolean,
    onHomework: (Boolean) -> Unit,
    onInfo: (Boolean) -> Unit,
    onAgent: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_features_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                            contentDescription = stringResource(R.string.homework_cancel),
                            modifier = Modifier.padding(0.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = tokens.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
        ) {
            NavigationGroup(title = stringResource(R.string.more_features_group_tabs)) {
                featureSwitch(
                    title = stringResource(R.string.title_homework),
                    subtitle = stringResource(R.string.more_features_homework_hint),
                    checked = homeworkEnabled,
                    onChecked = onHomework,
                )
                featureSwitch(
                    title = stringResource(R.string.title_blog),
                    subtitle = stringResource(R.string.more_features_info_hint),
                    checked = infoEnabled,
                    onChecked = onInfo,
                )
                featureSwitch(
                    title = stringResource(R.string.title_agent),
                    subtitle = stringResource(R.string.more_features_agent_hint),
                    checked = agentEnabled,
                    onChecked = onAgent,
                )
            }
        }
    }
}

@Composable
private fun featureSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    NavigationRow(
        icon = R.drawable.ic_menu_settings,
        title = title,
        subtitle = subtitle,
        onClick = { onChecked(!checked) },
        trailing = {
            Switch(checked = checked, onCheckedChange = onChecked)
        },
    )
}
