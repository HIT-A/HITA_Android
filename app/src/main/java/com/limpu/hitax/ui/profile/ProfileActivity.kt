package com.limpu.hitax.ui.profile

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limpu.component.data.DataState
import com.limpu.hitax.R
import com.limpu.hitax.data.repository.TimetableRepository
import com.limpu.hitax.databinding.ActivityProfileBinding
import com.limpu.hitax.ui.base.HiltBaseActivity
import com.limpu.hitax.utils.ActivityUtils
import com.limpu.hitauser.data.model.UserLocal
import com.limpu.hitauser.data.model.UserProfile
import com.limpu.hitauser.data.repository.LocalUserRepository
import com.limpu.hitauser.util.ImageUtils
import com.limpu.style.widgets.PopUpText
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 其他用户资料页面Activity
 */
@AndroidEntryPoint
class ProfileActivity : HiltBaseActivity<ActivityProfileBinding>() {

    @Inject
    lateinit var localUserRepository: LocalUserRepository

    protected val viewModel: ProfileViewModel by viewModels()

    // 图片选择器
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveAvatarLocally(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setToolbarActionBack(binding.toolbar)
    }

    override fun initViews() {
        setUpLiveData()
        val initElevation = binding.avatarCard.cardElevation
        binding.appbar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
            val percentage = -verticalOffset.toFloat() / binding.appbar.totalScrollRange.toFloat()
            binding.avatarCard.pivotY = binding.avatarCard.height.toFloat() * 0.5f
            binding.avatarCard.pivotX = binding.avatarCard.width.toFloat() * 0.3f
            binding.avatarCard.scaleX = 1 - percentage
            binding.avatarCard.scaleY = 1 - percentage
            binding.textNickname.alpha = 1 - 2.5f * percentage
            binding.textSignature.alpha = 1 - 2.5f * percentage
            binding.iconGender.alpha = 1 - 2.5f * percentage
            binding.avatarCard.cardElevation = initElevation * (1 - percentage)
        })
        binding.refresh.setColorSchemeColors(getColorPrimary())
        binding.refresh.setOnRefreshListener {
            startRefresh()
        }
        binding.logout.setOnClickListener {
            PopUpText().setTitle(R.string.logout_hint).setOnConfirmListener(
                object : PopUpText.OnConfirmListener {
                    override fun OnConfirm() {
                        viewModel.logout(getThis())
                        TimetableRepository(application).actionClearData()
                        finish()
                    }
                }
            ).show(supportFragmentManager, "logout")
        }
    }

    /**
     * 设置头像点击事件（仅当前用户可更换）
     */
    private fun setupAvatarClick(userId: String?) {
        if (viewModel.isCurrentUser(userId)) {
            binding.avatarCard.setOnClickListener {
                showAvatarPicker()
            }
            binding.avatarCard.foreground =
                getDrawable(android.R.drawable.ic_menu_camera)?.mutate()
        } else {
            binding.avatarCard.setOnClickListener(null)
            binding.avatarCard.foreground = null
        }
    }

    /**
     * 显示头像选择菜单
     */
    private fun showAvatarPicker() {
        MaterialAlertDialogBuilder(this)
            .setTitle("更换头像")
            .setItems(arrayOf("从相册选择", "取消")) { _, which ->
                when (which) {
                    0 -> pickImageLauncher.launch("image/*")
                }
            }
            .show()
    }

    /**
     * 压缩并保存头像到本地
     */
    private fun saveAvatarLocally(uri: Uri) {
        binding.refresh.isRefreshing = true
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
                    binding.refresh.isRefreshing = false
                    Toast.makeText(this, "头像更换成功", Toast.LENGTH_SHORT).show()
                    ImageUtils.loadAvatarInto(this, localPath, binding.avatar)
                    com.bumptech.glide.Glide.get(this).clearMemory()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.refresh.isRefreshing = false
                    Toast.makeText(this, "图片处理失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun setUpLiveData() {
        viewModel.userProfileLiveData.observe(this) { userProfileDataState ->
            binding.refresh.isRefreshing = false
            if (userProfileDataState?.state === DataState.STATE.SUCCESS) {
                setProfileView(userProfileDataState.data)
                if (viewModel.isCurrentUser(userProfileDataState.data?.id)) {
                    binding.logout.visibility = View.VISIBLE
                } else {
                    binding.logout.visibility = View.GONE
                }
            } else {
                Toast.makeText(getThis(), "获取出错", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startRefresh()
    }

    private fun startRefresh() {
        val id = intent.getStringExtra("id")
        if (id != null) {
            viewModel.startRefresh(id)
            binding.refresh.isRefreshing = true
        }
    }

    private fun setProfileView(userInfo: UserProfile?) {
        if (userInfo != null) {
            // 优先显示本地头像
            val localUser = localUserRepository.getLoggedInUser()
            val avatarToShow = if (viewModel.isCurrentUser(userInfo.id) && !localUser.avatar.isNullOrEmpty() && localUser.avatar!!.startsWith("local://")) {
                localUser.avatar
            } else {
                userInfo.avatar
            }
            ImageUtils.loadAvatarInto(getThis(), avatarToShow, binding.avatar)
            binding.textUsername.text = userInfo.username
            binding.textNickname.text = userInfo.nickname
            binding.iconGender.visibility = View.VISIBLE
            if (userInfo.signature.isNullOrEmpty()) {
                binding.textSignature.setText(R.string.drawer_signature_none)
            } else {
                binding.textSignature.text = userInfo.signature
            }
            if (userInfo.gender == UserLocal.GENDER.MALE) {
                binding.iconGender.setImageResource(R.drawable.ic_male_blue_24)
                binding.iconGender.contentDescription = getString(R.string.male)
            } else {
                binding.iconGender.setImageResource(R.drawable.ic_female_pink_24)
                binding.iconGender.contentDescription = getString(R.string.female)
            }
            setupAvatarClick(userInfo.id)
        }
    }

    override fun initViewBinding(): ActivityProfileBinding {
        return ActivityProfileBinding.inflate(layoutInflater)
    }
}
