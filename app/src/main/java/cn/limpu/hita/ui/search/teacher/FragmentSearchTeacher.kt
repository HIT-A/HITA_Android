package cn.limpu.hita.ui.search.teacher

import cn.limpu.hita.data.model.teacher.TeacherSearched
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.search.SearchRoot
import cn.limpu.hita.utils.ActivityUtils
import com.limpu.style.base.FragmentSearchResult
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FragmentSearchTeacher : Fragment(), FragmentSearchResult {

    private val viewModel: SearchTeacherViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme() {
                    TeacherSearchScreen(
                        viewModel = viewModel,
                        context = requireContext()
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val parent = activity
        if (parent is SearchRoot) {
            viewModel.changeSearchText(parent.getSearchText())
        }
    }

    override fun setSearchText(searchText: String) {
        viewModel.changeSearchText(searchText)
    }
}

@Composable
private fun TeacherSearchScreen(
    viewModel: SearchTeacherViewModel,
    context: Context
) {
    val tokens = HitaTheme.tokens
    val searchResult by viewModel.searchResultLiveData.observeAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = tokens.spacing.sm)
    ) {
        val resultText = when {
            searchResult?.state == DataState.STATE.SUCCESS -> {
                val count = searchResult?.data?.size ?: 0
                if (count > 0) {
                    stringResource(R.string.teacher_total_searched, count)
                } else {
                    stringResource(R.string.nothing_found)
                }
            }
            searchResult?.state == DataState.STATE.FETCH_FAILED -> stringResource(R.string.fail)
            else -> ""
        }

        if (resultText.isNotEmpty()) {
            Text(
                text = resultText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(tokens.spacing.sm),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        val teachers = searchResult?.data ?: emptyList()
        LazyColumn {
            items(teachers) { teacher ->
                TeacherItem(
                    teacher = teacher,
                    onClick = {
                        val repoName = teacher.repoName.ifBlank {
                            teacher.courseCode.ifBlank { teacher.courseName.ifBlank { teacher.name } }
                        }
                        val courseName = teacher.courseName.ifBlank {
                            teacher.courseCode.ifBlank { repoName }
                        }
                        val courseCode = teacher.courseCode.ifBlank { repoName }
                        ActivityUtils.startCourseReadmeActivity(
                            context,
                            repoName = repoName,
                            courseName = courseName,
                            courseCode = courseCode,
                            repoType = teacher.repoType.ifBlank { "normal" },
                        )
                    },
                    onLongClick = {
                        ActivityUtils.startTeacherHomepageSearch(context, teacher.name)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TeacherItem(
    teacher: TeacherSearched,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val tokens = HitaTheme.tokens

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = tokens.spacing.sm,
                vertical = tokens.spacing.xs
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(tokens.radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_menu_24),
                contentDescription = null,
                modifier = Modifier
                    .padding(tokens.spacing.sm)
                    .size(46.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        top = tokens.spacing.sm,
                        end = tokens.spacing.sm,
                        bottom = tokens.spacing.sm
                    )
            ) {
                Text(
                    text = teacher.name,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = teacher.department,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )

                val tagText = if (teacher.repoType == "multi-project") "多课程" else "课程"
                Surface(
                    shape = RoundedCornerShape(tokens.radius.xs),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = tokens.spacing.xs)
                ) {
                    Text(
                        text = tagText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = tokens.spacing.xs,
                            vertical = 2.dp
                        )
                    )
                }
            }
        }
    }
}
