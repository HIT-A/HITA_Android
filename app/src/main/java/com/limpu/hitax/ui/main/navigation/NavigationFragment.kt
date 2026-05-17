package com.limpu.hitax.ui.main.navigation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Observer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limpu.hitax.R
import com.limpu.hitax.data.repository.EASRepository
import com.limpu.hitax.data.repository.TimetableRepository
import com.limpu.hitax.data.source.preference.CourseReminderStore
import com.limpu.hitax.data.work.CourseReminderScheduler
import androidx.fragment.app.viewModels
import com.limpu.hitax.databinding.FragmentNavigationBinding
import com.limpu.hitax.ui.base.HiltBaseFragment
import com.limpu.hitax.ui.eas.classroom.EmptyClassroomActivity
import com.limpu.hitax.ui.eas.exam.ExamActivity
import com.limpu.hitax.ui.eas.imp.ImportTimetableActivity
import com.limpu.hitax.ui.eas.login.PopUpLoginEAS
import com.limpu.hitax.ui.eas.score.ScoreInquiryActivity
import com.limpu.hitax.utils.ActivityUtils
import com.limpu.hitax.utils.ActivityUtils.CourseResourceMode
import com.limpu.hitax.utils.IcsImportUtils
import com.limpu.hitauser.data.repository.LocalUserRepository
import com.limpu.hitauser.util.ImageUtils
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class NavigationFragment : HiltBaseFragment<FragmentNavigationBinding>() {

    @Inject
    lateinit var localUserRepository: LocalUserRepository

    @Inject
    lateinit var easRepository: EASRepository

    @Inject
    lateinit var timetableRepository: TimetableRepository

    protected val viewModel: NavigationViewModel by viewModels()
    private val easTokenObserver = Observer<com.limpu.hitax.data.model.eas.EASToken> {
        refreshEasUserCard()
    }

    // 头像选择器
    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveAvatarLocally(it) }
    }

    // ICS 文件选择器
    private val selectIcsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { importICS(it) }
    }

    // 通知权限请求 launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 权限已授予，开启课程提醒
            enableCourseReminder()
        } else {
            // 权限被拒绝，关闭开关并提示
            binding?.switchCourseReminder?.isChecked = false
            Toast.makeText(requireContext(), "需要通知权限才能发送课程提醒", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 显示头像选择菜单
     */
    private fun showAvatarPicker() {
        context?.let {
            MaterialAlertDialogBuilder(it)
                .setTitle("更换头像")
                .setItems(arrayOf("从相册选择", "取消")) { _, which ->
                    when (which) {
                        0 -> pickAvatarLauncher.launch("image/*")
                    }
                }
                .show()
        }
    }

    /**
     * 压缩并保存头像到本地
     */
    private fun saveAvatarLocally(uri: Uri) {
        context?.let { ctx ->
            Thread {
                try {
                    val destFile = java.io.File(ctx.filesDir, "avatar_local.jpg")
                    val futureTarget = com.bumptech.glide.Glide.with(ctx)
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

                    activity?.runOnUiThread {
                        Toast.makeText(ctx, "头像更换成功", Toast.LENGTH_SHORT).show()
                        binding?.avatar?.let { avatarView ->
                            ImageUtils.loadAvatarInto(ctx, localPath, avatarView)
                        }
                        com.bumptech.glide.Glide.get(ctx).clearMemory()
                    }
                } catch (e: Exception) {
                    activity?.runOnUiThread {
                        Toast.makeText(ctx, "图片处理失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    override fun initViews(view: View) {
        viewModel.recentTimetableLiveData.observe(this) {
            if (it == null) {
                binding?.recentSubtitle?.setText(R.string.none)
            } else {
                binding?.recentSubtitle?.text = it.name
            }
        }
        viewModel.timetableCountLiveData.observe(this) {
            if (it == 0) {
                binding?.timetableSubtitle?.setText(R.string.no_timetable)
            } else {
                binding?.timetableSubtitle?.text = getString(R.string.timetable_count_format, it)
            }

        }
        binding?.cardTimetable?.setOnClickListener {
            ActivityUtils.startTimetableManager(requireContext())
        }
        binding?.cardRecentTimetable?.setOnClickListener {
            viewModel.recentTimetableLiveData.value?.let {
                ActivityUtils.startTimetableDetailActivity(requireContext(), it.id)
            }
        }
        binding?.cardImport?.setOnClickListener {
            ActivityUtils.startActivity(
                requireContext(),
                ImportTimetableActivity::class.java
            )
        }
        binding?.cardEmptyClassroom?.setOnClickListener {
            ActivityUtils.startActivity(
                requireContext(),
                EmptyClassroomActivity::class.java
            )
        }
        binding?.cardScores?.setOnClickListener {
            ActivityUtils.startActivity(
                requireContext(),
                ScoreInquiryActivity::class.java
            )
        }
        binding?.cardSubjects?.setOnClickListener {
            ActivityUtils.showEasVerifyWindow(
                requireActivity(),  // 使用requireActivity()而不是requireContext()来避免Hilt FragmentContextWrapper
                easRepository,
                directTo = ExamActivity::class.java,
                onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                    override fun onSuccess(window: PopUpLoginEAS) {
                        ActivityUtils.startActivity(
                            requireActivity(),
                            ExamActivity::class.java
                        )
                        window.dismiss()
                    }

                    override fun onFailed(window: PopUpLoginEAS) {

                    }
                }
            )
        }
        binding?.cardCourseLookup?.setOnClickListener {
            ActivityUtils.startCourseResourceSearchActivity(requireContext(), mode = CourseResourceMode.VIEW)
        }
        binding?.cardCourseSubmitPr?.setOnClickListener {
            ActivityUtils.startCourseResourceSearchActivity(requireContext(), mode = CourseResourceMode.SUBMIT)
        }
        
        // 课程提醒开关
        val reminderStore = CourseReminderStore(requireContext())
        binding?.switchCourseReminder?.isChecked = reminderStore.isEnabled()
        binding?.switchCourseReminder?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 开启时检查通知权限（仅Android 13+需要）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (requireContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        // 已有权限，直接开启
                        enableCourseReminder()
                    } else {
                        // 请求权限
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    // Android 13以下不需要权限，直接开启
                    enableCourseReminder()
                }
            } else {
                // 关闭课程提醒
                reminderStore.setEnabled(false)
                CourseReminderScheduler.autoSchedule(requireContext())
                Toast.makeText(requireContext(), "课程提醒已关闭", Toast.LENGTH_SHORT).show()
            }
        }
        binding?.cardCourseReminder?.setOnClickListener {
            binding?.switchCourseReminder?.toggle()
        }
        
        // ICS 导入
        binding?.cardIcsImport?.setOnClickListener {
            selectIcsLauncher.launch(IcsImportUtils.pickerMimeTypes())
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onStart() {
        super.onStart()
        viewModel.startRefresh()
        easRepository.observeEasToken().observe(viewLifecycleOwner, easTokenObserver)
        refreshEasUserCard()
    }

    private fun refreshEasUserCard() {
        val localUser = localUserRepository.getLoggedInUser()
        val localAvatar = localUser.avatar?.takeIf { it.startsWith("local://") }

        if (localUser.isValid()) {
            binding?.avatar?.let { avatarView ->
                ImageUtils.loadAvatarInto(requireContext(), localAvatar ?: localUser.avatar, avatarView)
                avatarView.setOnClickListener { showAvatarPicker() }
            }
            binding?.username?.text = localUser.username
            binding?.nickname?.text = localUser.nickname
            binding?.userCard?.setOnClickListener { _ ->
                ActivityUtils.startProfileActivity(
                    requireContext(),
                    localUser.id,
                    binding?.avatar
                )
            }
        } else {
            val easToken = easRepository.getEasToken()
            if (easToken.isLogin()) {
                binding?.username?.text = easToken.name?.ifBlank { easToken.stuId?.ifBlank { easToken.username } }
                    ?: easToken.stuId?.ifBlank { easToken.username }
                    ?: easToken.username
                    ?: getString(R.string.eas_account_not_logged_in_title)
                binding?.nickname?.text = buildString {
                    val primary = easToken.stuId?.trim().orEmpty()
                    val secondary = listOf(
                        easToken.school,
                        easToken.major,
                        easToken.grade,
                        easToken.className
                    ).mapNotNull { info -> info?.trim()?.takeIf(String::isNotBlank) }
                        .joinToString(" · ")
                    append(primary)
                    if (secondary.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(secondary)
                    }
                }.ifBlank { easToken.username.orEmpty() }
                binding?.avatar?.let { avatarView ->
                    if (localAvatar != null) {
                        ImageUtils.loadAvatarInto(requireContext(), localAvatar, avatarView)
                    } else {
                        avatarView.setImageResource(R.drawable.place_holder_avatar)
                    }
                    avatarView.setOnClickListener { showAvatarPicker() }
                }
                binding?.userCard?.setOnClickListener {
                    ActivityUtils.showEasVerifyWindow<Activity>(
                        requireActivity(),
                        easRepository,
                        directTo = null,
                        onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                            override fun onSuccess(window: PopUpLoginEAS) {
                                window.dismiss()
                            }
                            override fun onFailed(window: PopUpLoginEAS) {}
                        }
                    )
                }
            } else {
                binding?.username?.setText(R.string.eas_account_not_logged_in_title)
                binding?.nickname?.setText(R.string.eas_account_not_logged_in_subtitle)
                binding?.avatar?.let { avatarView ->
                    if (localAvatar != null) {
                        ImageUtils.loadAvatarInto(requireContext(), localAvatar, avatarView)
                    } else {
                        avatarView.setImageResource(R.drawable.place_holder_avatar)
                    }
                    avatarView.setOnClickListener {
                        ActivityUtils.showEasVerifyWindow<Activity>(
                            requireActivity(),
                            easRepository,
                            directTo = null,
                            onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                                override fun onSuccess(window: PopUpLoginEAS) {
                                    window.dismiss()
                                }
                                override fun onFailed(window: PopUpLoginEAS) {}
                            }
                        )
                    }
                }
                binding?.userCard?.setOnClickListener {
                    ActivityUtils.showEasVerifyWindow<Activity>(
                        requireActivity(),
                        easRepository,
                        directTo = null,
                        onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                            override fun onSuccess(window: PopUpLoginEAS) {
                                window.dismiss()
                            }
                            override fun onFailed(window: PopUpLoginEAS) {}
                        }
                    )
                }
            }
        }
    }

    override fun initViewBinding(): FragmentNavigationBinding {
        return FragmentNavigationBinding.inflate(layoutInflater)
    }

    override fun onStop() {
        easRepository.observeEasToken().removeObserver(easTokenObserver)
        super.onStop()
    }

    /**
     * 导入 ICS 文件
     */
    private fun importICS(uri: Uri) {
        val context = requireContext()

        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Toast.makeText(context, "无法读取所选 ICS 文件", Toast.LENGTH_SHORT).show()
                return
            }
            timetableRepository.importFromICSAsNewTimetable(
                inputStream,
                IcsImportUtils.getDisplayName(context, uri)
            )
                .observe(this) { result ->
                    when (result.state) {
                        com.limpu.component.data.DataState.STATE.SUCCESS -> {
                            val importResult = result.data ?: return@observe
                            Toast.makeText(
                                context,
                                "已创建课表“${importResult.timetableName}”，导入 ${importResult.importedCount} 个课程",
                                Toast.LENGTH_SHORT
                            ).show()
                            ActivityUtils.startTimetableDetailActivity(
                                context,
                                importResult.timetableId
                            )
                        }
                        com.limpu.component.data.DataState.STATE.FETCH_FAILED -> {
                            Toast.makeText(context, "导入失败: ${result.message}", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
        } catch (e: Exception) {
            Toast.makeText(context, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 开启课程提醒
     */
    private fun enableCourseReminder() {
        val reminderStore = CourseReminderStore(requireContext())
        reminderStore.setEnabled(true)
        CourseReminderScheduler.autoSchedule(requireContext())
        Toast.makeText(requireContext(), "课程提醒已开启（上课前10分钟提醒）", Toast.LENGTH_SHORT).show()
    }
}
