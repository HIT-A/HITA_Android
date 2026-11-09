package cn.limpu.hita.ui.eas

import android.app.Activity
import android.content.Intent
import androidx.viewbinding.ViewBinding
import com.limpu.component.data.DataState
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.ui.eas.login.PopUpLoginEAS
import cn.limpu.hita.ui.eas.login.ShenzhenWebAutoLogin
import cn.limpu.hita.ui.eas.login.WebViewLoginActivity
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.ui.base.HiltBaseActivity
import javax.inject.Inject

import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint

abstract class EASActivity<T : EASViewModel, V : ViewBinding> : HiltBaseActivity<V>() {

    companion object {
        private const val REQUEST_CODE_SILENT_RELOGIN = 10001
        private const val REQUEST_CODE_DIRECT_WEB_LOGIN = 10002
    }

    @Inject
    lateinit var easRepository: EASRepository

    protected abstract val viewModel: T
    private val sessionRecovery = EasSessionRecoveryState()

    protected open fun shouldRefreshOnStart(): Boolean = true

    protected open fun shouldCheckLoginOnStart(): Boolean = false

    /** Most academic pages need Web access for recovery; score queries can use the API alone. */
    protected open val autoLaunchWebLoginForSessionRecovery: Boolean = true

    override fun onStart() {
        val recoveringOnEntry = sessionRecovery.isRecovering
        super.onStart()
        // The login result owns the retry when returning from the authentication WebView.
        if (recoveringOnEntry || sessionRecovery.isRecovering) return
        if (shouldRefreshOnStart()) {
            refresh()
        }
        if (shouldCheckLoginOnStart()) {
            viewModel.startLoginCheck()
        }
    }

    abstract fun refresh()

    open fun onLoginCheckSuccess(retry: Boolean) {
        refresh()
    }

    open fun onLoginCheckFailed() {}

    protected fun handleSessionExpired(retryAction: () -> Boolean): Boolean {
        if (!sessionRecovery.begin(retryAction)) return false
        easRepository.markSessionExpired()
        val tokenCampus = easRepository.getEasToken().campus

        ActivityUtils.showEasVerifyWindow<Activity>(
            this,
            easRepository,
            directTo = null,
            autoLaunchWebLogin = autoLaunchWebLoginForSessionRecovery,
            preferredCampus = tokenCampus,
            onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                override fun onSuccess(window: PopUpLoginEAS) {
                    window.dismiss()
                    sessionRecovery.loginSucceeded()
                }

                override fun onFailed(window: PopUpLoginEAS) {
                    sessionRecovery.cancel()
                }

                /**
                 * 用户主动"稍后"/下滑/返回：既不算失败也不消耗重试预算，
                 * 状态必须回滚，否则本页后续再也不会弹出过期提示（只能退出登录或重启应用）。
                 */
                override fun onCancelled(window: PopUpLoginEAS) {
                    LogUtils.d("session expired sheet dismissed by user; keep page usable")
                    sessionRecovery.cancel()
                }
            }
        )
        return true
    }

    protected fun resetSessionRetryState() {
        sessionRecovery.querySucceeded()
    }

    /**
     * 直接打开教务网页登录，不经过账号/校区选择弹窗。
     *
     * 依赖 Web 会话的页面（可选课程、成绩分析、培养方案、课表导入）只需要"连上 Web 教务"，
     * 弹一个让用户再选一次校区的 BottomSheet 纯属多余。校区与学历取自已登录身份，
     * 登录成功后再把 cookie 交给 `PopUpLoginEAS.startCookieLogin` 同一套落库逻辑。
     */
    protected fun launchDirectWebLogin(campus: EASToken.Campus, studentType: EASToken.TYPE) {
        val intent = Intent(this, WebViewLoginActivity::class.java).apply {
            putExtra(WebViewLoginActivity.EXTRA_CAMPUS, campus.name)
            putExtra(
                WebViewLoginActivity.EXTRA_STUDENT_TYPE,
                if (studentType == EASToken.TYPE.GRAD) ShenzhenWebAutoLogin.POSTGRAD
                else ShenzhenWebAutoLogin.UNDERGRAD
            )
        }
        startActivityForResult(intent, REQUEST_CODE_DIRECT_WEB_LOGIN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_SILENT_RELOGIN) {
            LogUtils.d("silentReloginResult: resultCode=$resultCode")
            if (resultCode == Activity.RESULT_OK) {
                sessionRecovery.loginSucceeded()
            } else {
                sessionRecovery.cancel()
            }
        }

        if (requestCode == REQUEST_CODE_DIRECT_WEB_LOGIN) {
            if (resultCode != Activity.RESULT_OK || data == null) return
            // 网页登录已把 cookie 交回来；落库交给宿主页面自己的 refresh 触发校验，
            // 这里只负责把 Web 会话写进 token，失败时下一次 refresh 会再次提示。
            val cookiesJson = data.getStringExtra("cookies") ?: return
            val campusName = data.getStringExtra(WebViewLoginActivity.EXTRA_CAMPUS)
            val campus = runCatching { EASToken.Campus.valueOf(campusName.orEmpty()) }.getOrNull()
                ?: return
            val studentType = if (
                data.getStringExtra(WebViewLoginActivity.EXTRA_STUDENT_TYPE) ==
                ShenzhenWebAutoLogin.POSTGRAD
            ) EASToken.TYPE.GRAD else EASToken.TYPE.UNDERGRAD
            easRepository.loginWithWebSession(
                cookiesJson = cookiesJson,
                webBaseUrl = data.getStringExtra("web_base_url"),
                electronicExpToken = data.getStringExtra("electronic_exp_token"),
                campus = campus,
                studentType = studentType,
            ).observe(this) { state ->
                if (state.state == DataState.STATE.NOTHING) return@observe
                if (state.state == DataState.STATE.SUCCESS && state.data == true) {
                    sessionRecovery.loginSucceeded()
                    refresh()
                } else {
                    LogUtils.e("direct web login failed: ${state.message}")
                    sessionRecovery.cancel()
                }
            }
        }
    }

    override fun initViews() {
        viewModel.loginCheckResult.observe(this) {
            if (it.state == DataState.STATE.SUCCESS) {
                if (it.data == true) {
                    onLoginCheckSuccess(false)
                } else {
                    easRepository.markSessionExpired()
                    ActivityUtils.showEasVerifyWindow<Activity>(
                        getThis(),
                        easRepository,
                        lock = true,
                        onResponseListener = object :
                            PopUpLoginEAS.OnResponseListener {
                            override fun onSuccess(window: PopUpLoginEAS) {
                                window.dismiss()
                                onLoginCheckSuccess(true)
                            }

                            override fun onFailed(window: PopUpLoginEAS) {
                                onLoginCheckFailed()
                            }

                            // lock=true 时没有"稍后"，取消会连同宿主页面一起关闭，无需额外处理。
                            override fun onCancelled(window: PopUpLoginEAS) = Unit
                        })
                }

            }
        }
    }

}
