package cn.limpu.hita.ui.links

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.HitaThemeStyle
import cn.limpu.hita.ui.design.hitaGlassCardBorder
import cn.limpu.hita.ui.design.hitaGlassCardColors
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.design.hitaStyleCardShape
import cn.limpu.hita.ui.design.hitaSumiBrushUnderline

/** 实用网址页：常用校内网站列表，点按打开链接、长按复制。按校区展示（深圳/本部/威海）。 */
class UsefulLinksActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setContent {
                HitaComposeTheme {
                    UsefulLinksScreen(onBack = { finish() })
                }
            }
        })
    }
}

private data class UsefulLink(
    @StringRes val nameRes: Int,
    val url: String,
    @DrawableRes val iconRes: Int,
)

private data class UsefulLinkGroup(
    @StringRes val titleRes: Int,
    val links: List<UsefulLink>,
)

private val usefulLinkGroups = listOf(
    UsefulLinkGroup(
        titleRes = R.string.useful_links_group_study,
        links = listOf(
            UsefulLink(R.string.useful_link_jxypt, "https://jxypt.hitsz.edu.cn/ve", R.drawable.ic_baseline_access_time_24),
            UsefulLink(R.string.useful_link_jw, "https://jw.hitsz.edu.cn/", R.drawable.ic_home),
            UsefulLink(R.string.useful_link_lab, "https://lab.hitsz.edu.cn/", R.drawable.ic_baseline_format_list_bulleted_24),
            UsefulLink(R.string.useful_link_mycos, "https://hitsz.mycospxk.com/", R.drawable.ic_baseline_edit_24),
            UsefulLink(R.string.useful_link_grader, "https://grader.hitsz.edu.cn/home", R.drawable.ic_baseline_done_24),
            UsefulLink(R.string.useful_link_cslab, "https://git.cs-lab.top/cslab", R.drawable.ic_baseline_cloud_download_24),
        )
    ),
    UsefulLinkGroup(
        titleRes = R.string.useful_links_group_affairs,
        links = listOf(
            UsefulLink(R.string.useful_link_portal, "https://www.hitsz.edu.cn/", R.drawable.ic_menu_discover),
            UsefulLink(R.string.useful_link_cist, "https://cist.hitsz.edu.cn/index.htm", R.drawable.ic_baseline_location_city_24),
            UsefulLink(R.string.useful_link_info, "https://info.hitsz.edu.cn/", R.drawable.ic_bc_news),
            UsefulLink(R.string.useful_link_xgb, "https://xgb.hit.edu.cn/", R.drawable.ic_bc_organization),
            UsefulLink(R.string.useful_link_xuefei, "https://xuefei.hitsz.edu.cn/", R.drawable.ic_bx_credit),
            UsefulLink(R.string.useful_link_finance_reimbursement, "https://cws.hitsz.edu.cn/info/1082/1093.htm", R.drawable.ic_bx_credit),
            UsefulLink(R.string.useful_link_szsj, "http://szsj.hitsz.edu.cn/index/index/index.html", R.drawable.ic_bc_organization),
        )
    ),
    UsefulLinkGroup(
        titleRes = R.string.useful_links_group_network,
        links = listOf(
            UsefulLink(R.string.useful_link_net, "https://net.hitsz.edu.cn/", R.drawable.ic_baseline_link_24),
            UsefulLink(R.string.useful_link_hpc, "http://hpc.hitsz.edu.cn/", R.drawable.ic_baseline_widgets_24),
            UsefulLink(R.string.useful_link_instrument, "https://17.hitsz.edu.cn/", R.drawable.ic_baseline_link_24),
            UsefulLink(R.string.useful_link_mail, "https://mail.hit.edu.cn/", R.drawable.ic_baseline_email_24),
        )
    ),
    UsefulLinkGroup(
        titleRes = R.string.useful_links_group_resources,
        links = listOf(
            UsefulLink(R.string.useful_link_hoa, "https://hoa.moe/", R.drawable.ic_baseline_search_24),
            UsefulLink(R.string.useful_link_hoa_fastdl, "https://gh.hoa.moe/", R.drawable.ic_baseline_cloud_download_24),
            UsefulLink(R.string.useful_link_textbook, "http://www.hitsz.textbook.wang/textbooks/home/index", R.drawable.ic_baseline_link_24),
        )
    ),
)

/** 本部校区链接（来源：本科生院信息获取途径说明）。 */
private val benbuLinkGroups = listOf(
    UsefulLinkGroup(
        titleRes = R.string.useful_links_group_study,
        links = listOf(
            UsefulLink(R.string.useful_link_benbu_jwts, "http://jwts.hit.edu.cn", R.drawable.ic_home),
            UsefulLink(R.string.useful_link_benbu_jwes, "http://jwes.hit.edu.cn", R.drawable.ic_baseline_format_list_bulleted_24),
            UsefulLink(R.string.useful_link_benbu_eelab, "http://eelab.hit.edu.cn", R.drawable.ic_baseline_format_list_bulleted_24),
            UsefulLink(R.string.useful_link_benbu_hituc, "https://hituc.hit.edu.cn", R.drawable.ic_baseline_location_city_24),
            UsefulLink(R.string.useful_link_benbu_lib, "http://www.lib.hit.edu.cn", R.drawable.ic_baseline_search_24),
        )
    ),
    UsefulLinkGroup(
        titleRes = R.string.useful_links_group_affairs,
        links = listOf(
            UsefulLink(R.string.useful_link_benbu_official, "https://www.hit.edu.cn", R.drawable.ic_menu_discover),
            UsefulLink(R.string.useful_link_benbu_today, "http://today.hit.edu.cn", R.drawable.ic_bc_news),
            UsefulLink(R.string.useful_link_benbu_news, "http://news.hit.edu.cn", R.drawable.ic_bc_news),
            UsefulLink(R.string.useful_link_benbu_future, "https://future.hit.edu.cn", R.drawable.ic_baseline_location_city_24),
            UsefulLink(R.string.useful_link_benbu_xg, "https://xg.hit.edu.cn", R.drawable.ic_bc_organization),
            UsefulLink(R.string.useful_link_benbu_hqfw, "http://hqfw.hit.edu.cn", R.drawable.ic_baseline_widgets_24),
            UsefulLink(R.string.useful_link_benbu_map, "http://map.hit.edu.cn", R.drawable.ic_baseline_location_city_24),
        )
    ),
    UsefulLinkGroup(
        titleRes = R.string.useful_links_group_network,
        links = listOf(
            UsefulLink(R.string.useful_link_benbu_portal, "http://i.hit.edu.cn", R.drawable.ic_home),
            UsefulLink(R.string.useful_link_benbu_ivpn, "https://ivpn.hit.edu.cn", R.drawable.ic_baseline_link_24),
        )
    ),
    UsefulLinkGroup(
        titleRes = R.string.useful_links_group_resources,
        links = listOf(
            UsefulLink(R.string.useful_link_benbu_homepage, "https://homepage.hit.edu.cn", R.drawable.ic_baseline_email_24),
        )
    ),
)

/** 威海校区链接（来源：校区首页快速通道，另加实验课与实验室系统）。 */
private val weihaiLinkGroups = listOf(
    UsefulLinkGroup(
        titleRes = R.string.useful_links_group_study,
        links = listOf(
            UsefulLink(R.string.useful_link_weihai_jwts, "http://jwts.hitwh.edu.cn/", R.drawable.ic_home),
            UsefulLink(R.string.useful_link_weihai_yjs, "https://yjsgl.hitwh.edu.cn/", R.drawable.ic_baseline_format_list_bulleted_24),
            UsefulLink(R.string.useful_link_weihai_lib, "http://lib.hitwh.edu.cn", R.drawable.ic_baseline_search_24),
            UsefulLink(R.string.useful_link_weihai_wlsy, "http://wlsy.hitwh.edu.cn/", R.drawable.ic_baseline_format_list_bulleted_24),
            UsefulLink(R.string.useful_link_weihai_ilab, "http://ilab.hitwh.edu.cn/", R.drawable.ic_baseline_format_list_bulleted_24),
            UsefulLink(
                R.string.useful_link_weihai_openlab,
                "http://openlab.hitwh.edu.cn/#/login?redirect=/index",
                R.drawable.ic_baseline_format_list_bulleted_24,
            ),
        )
    ),
    UsefulLinkGroup(
        titleRes = R.string.useful_links_group_affairs,
        links = listOf(
            UsefulLink(R.string.useful_link_weihai_news, "http://news.hitwh.edu.cn/1040/list.htm", R.drawable.ic_bc_news),
            UsefulLink(R.string.useful_link_weihai_notice, "http://today.hitwh.edu.cn/1024/list.htm", R.drawable.ic_bc_news),
            UsefulLink(R.string.useful_link_weihai_xuegong, "http://xuegong.hitwh.edu.cn/", R.drawable.ic_bc_organization),
            UsefulLink(R.string.useful_link_weihai_job, "http://job.hitwh.edu.cn/signout", R.drawable.ic_baseline_edit_24),
            UsefulLink(R.string.useful_link_weihai_box, "http://box.hitwh.edu.cn/", R.drawable.ic_baseline_email_24),
            UsefulLink(R.string.useful_link_weihai_space, "https://space.hit.edu.cn/cdyygl", R.drawable.ic_baseline_location_city_24),
        )
    ),
    UsefulLinkGroup(
        titleRes = R.string.useful_links_group_network,
        links = listOf(
            UsefulLink(R.string.useful_link_weihai_portal, "https://i.hitwh.edu.cn/", R.drawable.ic_home),
            UsefulLink(R.string.useful_link_weihai_ivpn, "https://ivpn.hitwh.edu.cn", R.drawable.ic_baseline_link_24),
            UsefulLink(R.string.useful_link_weihai_webvpn, "https://webvpn.hitwh.edu.cn", R.drawable.ic_baseline_link_24),
            UsefulLink(R.string.useful_link_weihai_oa, "http://oa.hitwh.edu.cn/", R.drawable.ic_baseline_widgets_24),
            UsefulLink(R.string.useful_link_weihai_ids, "https://ids.hit.edu.cn/authserver/login", R.drawable.ic_baseline_done_24),
            UsefulLink(R.string.useful_link_weihai_mail, "https://mail.hit.edu.cn", R.drawable.ic_baseline_email_24),
        )
    ),
    UsefulLinkGroup(
        titleRes = R.string.useful_links_group_resources,
        links = listOf(
            UsefulLink(R.string.useful_link_weihai_homepage, "http://homepage.hit.edu.cn/home-index", R.drawable.ic_baseline_email_24),
        )
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsefulLinksScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.navi_useful_links)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.rotate(180f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        }
    ) { padding ->
        val tokens = HitaTheme.tokens
        val context = LocalContext.current
        // 按校区选择链接组；未登录显示占位说明
        val linkGroups = remember {
            val token = EasPreferenceSource(context).getEasToken()
            if (!token.isLogin()) {
                null
            } else when (token.campus) {
                EASToken.Campus.SHENZHEN -> usefulLinkGroups
                EASToken.Campus.BENBU -> benbuLinkGroups
                EASToken.Campus.WEIHAI -> weihaiLinkGroups
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = tokens.spacing.xl),
        ) {
            if (linkGroups == null) {
                item(key = "empty_campus") {
                    Text(
                        text = stringResource(R.string.useful_links_empty_campus),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = tokens.spacing.xl),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
            linkGroups.forEach { group ->
                item(key = "header_${group.titleRes}") {
                    Text(
                        text = stringResource(group.titleRes),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(
                                start = tokens.spacing.lg,
                                top = tokens.spacing.lg,
                                end = tokens.spacing.lg,
                                bottom = tokens.spacing.xs
                            )
                            .hitaSumiBrushUnderline()
                    )
                }
                item(key = "card_${group.titleRes}") {
                    UsefulLinkGroupCard(group)
                }
            }
            item(key = "footer_hint") {
                Text(
                    text = stringResource(R.string.useful_links_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = tokens.spacing.lg),
                    textAlign = TextAlign.Center
                )
            }
            }
        }
    }
}

@Composable
private fun UsefulLinkGroupCard(group: UsefulLinkGroup) {
    val tokens = HitaTheme.tokens
    val cardShape = hitaStyleCardShape(tokens.radius.lg, 14.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg)
            .hitaGlassCardModifier(cardShape, elevation = 12.dp),
        shape = cardShape,
        colors = hitaGlassCardColors(glassAlpha = 0.46f),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = hitaGlassCardBorder(alpha = 0.28f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = tokens.spacing.xs),
            verticalArrangement = Arrangement.Center
        ) {
            group.links.forEach { link ->
                UsefulLinkRow(link)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UsefulLinkRow(link: UsefulLink) {
    val tokens = HitaTheme.tokens
    val context = LocalContext.current
    val name = stringResource(link.nameRes)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .combinedClickable(
                onClick = { openLink(context, name, link.url) },
                onLongClick = { copyLinkToClipboard(context, name, link.url) }
            )
            .padding(horizontal = tokens.spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinkCircleIcon(iconRes = link.iconRes)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = tokens.spacing.md)
        ) {
            Text(
                text = name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = link.url,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.size(tokens.spacing.xs))
    }
}

@Composable
private fun LinkCircleIcon(@DrawableRes iconRes: Int) {
    val iconTint = if (HitaTheme.style == HitaThemeStyle.Persona5) {
        Color(0xFFFF6675)
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun copyLinkToClipboard(context: Context, label: String, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, url))
    Toast.makeText(context, R.string.useful_links_copied, Toast.LENGTH_SHORT).show()
}

private fun openLink(context: Context, label: String, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        // 无可用浏览器时退化为复制链接
        copyLinkToClipboard(context, label, url)
    }
}
