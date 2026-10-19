package cn.limpu.hita.ui.eas

import android.app.Activity
import android.content.Intent
import androidx.viewbinding.ViewBinding
import com.limpu.component.data.DataState
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.ui.eas.login.PopUpLoginEAS
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.ui.base.HiltBaseActivity
import javax.inject.Inject

import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint

abstract class EASActivity<T : EASViewModel, V : ViewBinding> : HiltBaseActivity<V>() {

    companion object {
        private const val REQUEST_CODE_SILENT_RELOGIN = 10001
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
