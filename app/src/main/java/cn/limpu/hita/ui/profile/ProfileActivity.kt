package cn.limpu.hita.ui.profile

import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import com.limpu.hitauser.data.model.UserLocal
import com.limpu.hitauser.data.model.UserProfile
import com.limpu.hitauser.data.repository.LocalUserRepository
import com.limpu.hitauser.util.ImageUtils
import com.limpu.style.ThemeTools
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ProfileActivity : AppCompatActivity() {
    @Inject
    lateinit var localUserRepository: LocalUserRepository

    private val viewModel: ProfileViewModel by viewModels()
    private var currentAvatarView: ImageView? = null
    private var loadingSetter: ((Boolean) -> Unit)? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveAvatarLocally(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = when (ThemeTools.getThemeMode(this)) {
            ThemeTools.MODE.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeTools.MODE.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
        super.onCreate(savedInstanceState)

        setContent {
            HitaComposeTheme() {
                ProfileScreen(
                    viewModel = viewModel,
                    localUserRepository = localUserRepository,
                    userId = intent.getStringExtra("id"),
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onAvatarReady = { currentAvatarView = it },
                    onLoadingSetterReady = { loadingSetter = it },
                    onPickAvatar = { showAvatarPicker() },
                    onLogout = {
                        val user = localUserRepository.getLoggedInUser()
                        if (user.id == intent.getStringExtra("id")) {
                            AccountLogoutTarget.hita(user)?.let { target ->
                                AccountLogoutDialog.show(this@ProfileActivity, target) {
                                    target.signOut(this@ProfileActivity, localUserRepository, null).also { signedOut ->
                                        if (signedOut) finish()
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        intent.getStringExtra("id")?.let {
            loadingSetter?.invoke(true)
            viewModel.startRefresh(it)
        }
    }

    private fun showAvatarPicker() {
        MaterialAlertDialogBuilder(this)
            .setTitle("更换头像")
            .setItems(arrayOf("从相册选择", "取消")) { _, which ->
                if (which == 0) {
                    pickImageLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun saveAvatarLocally(uri: Uri) {
        loadingSetter?.invoke(true)
        Thread {
            try {
                val destFile = java.io.File(filesDir, "avatar_local.jpg")
                val futureTarget = com.bumptech.glide.Glide.with(this)
                    .asFile()
                    .load(uri)
                    .override(512, 512)
                    .centerCrop()
                    .submit()
                val tempFile = futureTarget.get()
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()

                val localPath = "local://${destFile.absolutePath}"
                localUserRepository.changeLocalAvatar(localPath)

                runOnUiThread {
                    loadingSetter?.invoke(false)
                    Toast.makeText(this, "头像更换成功", Toast.LENGTH_SHORT).show()
                    currentAvatarView?.let { ImageUtils.loadAvatarInto(this, localPath, it) }
                    com.bumptech.glide.Glide.get(this).clearMemory()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loadingSetter?.invoke(false)
                    Toast.makeText(this, "图片处理失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}

@Composable
private fun ProfileScreen(
    viewModel: ProfileViewModel,
    localUserRepository: LocalUserRepository,
    userId: String?,
    onBack: () -> Unit,
    onAvatarReady: (ImageView) -> Unit,
    onLoadingSetterReady: ((Boolean) -> Unit) -> Unit,
    onPickAvatar: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val tokens = HitaTheme.tokens
    val userState by viewModel.userProfileLiveData.observeAsState()
    var loading by remember { mutableStateOf(false) }
    var userProfile by remember { mutableStateOf<UserProfile?>(null) }

    LaunchedEffect(Unit) {
        onLoadingSetterReady { loading = it }
        userId?.let {
            loading = true
            viewModel.startRefresh(it)
        }
    }

    LaunchedEffect(userState) {
        val state = userState ?: return@LaunchedEffect
        loading = false
        if (state.state == DataState.STATE.SUCCESS) {
            userProfile = state.data
        } else {
            Toast.makeText(context, "获取出错", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ProfileHeader(
                userProfile = userProfile,
                localUserRepository = localUserRepository,
                isCurrentUser = { viewModel.isCurrentUser(it) },
                onBack = onBack,
                onAvatarReady = onAvatarReady,
                onPickAvatar = onPickAvatar
            )
            ProfileInfoCard(
                userProfile = userProfile,
                showLogout = viewModel.isCurrentUser(userProfile?.id),
                onLogout = onLogout,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.lg)
            )
        }

        if (loading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun ProfileHeader(
    userProfile: UserProfile?,
    localUserRepository: LocalUserRepository,
    isCurrentUser: (String?) -> Boolean,
    onBack: () -> Unit,
    onAvatarReady: (ImageView) -> Unit,
    onPickAvatar: () -> Unit,
) {
    val context = LocalContext.current
    val tokens = HitaTheme.tokens
    val avatarToShow = remember(userProfile) {
        val localUser = localUserRepository.getLoggedInUser()
        if (
            isCurrentUser(userProfile?.id) &&
            !localUser.avatar.isNullOrEmpty() &&
            localUser.avatar!!.startsWith("local://")
        ) {
            localUser.avatar
        } else {
            userProfile?.avatar
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.rotate(180f)
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = tokens.spacing.xl,
                    end = tokens.spacing.xl,
                    bottom = tokens.spacing.xl
                )
        ) {
            Card(
                shape = CircleShape,
                elevation = CardDefaults.cardElevation(defaultElevation = 32.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .size(90.dp)
                    .clickable(enabled = isCurrentUser(userProfile?.id), onClick = onPickAvatar)
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    factory = {
                        ImageView(it).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            onAvatarReady(this)
                        }
                    },
                    update = { imageView ->
                        ImageUtils.loadAvatarInto(context, avatarToShow, imageView)
                    }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = tokens.spacing.xl)
            ) {
                Text(
                    text = userProfile?.nickname.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                userProfile?.let {
                    Icon(
                        painter = painterResource(
                            if (it.gender == UserLocal.GENDER.MALE) {
                                R.drawable.ic_male_blue_24
                            } else {
                                R.drawable.ic_female_pink_24
                            }
                        ),
                        contentDescription = stringResource(
                            if (it.gender == UserLocal.GENDER.MALE) R.string.male else R.string.female
                        ),
                        tint = androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier
                            .padding(start = tokens.spacing.md)
                            .size(20.dp)
                    )
                }
            }
            Text(
                text = userProfile?.signature
                    ?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.drawer_signature_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = tokens.spacing.sm)
            )
        }
    }
}

@Composable
private fun ProfileInfoCard(
    userProfile: UserProfile?,
    showLogout: Boolean,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = HitaTheme.tokens
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(tokens.radius.lg),
        modifier = modifier
    ) {
        ProfileRow(
            icon = R.drawable.ic_baseline_person_24,
            title = stringResource(R.string.username),
            value = userProfile?.username.orEmpty()
        )
        if (showLogout) {
            ProfileRow(
                icon = R.drawable.ic_baseline_exit_to_app_24,
                title = stringResource(R.string.logout),
                value = "",
                onClick = onLogout
            )
        }
    }
}

@Composable
private fun ProfileRow(
    icon: Int,
    title: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    val tokens = HitaTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = tokens.spacing.lg)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
            shape = CircleShape,
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(6.dp)
            )
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            modifier = Modifier
                .weight(1f)
                .padding(start = tokens.spacing.xl)
        )
        if (value.isNotBlank()) {
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
