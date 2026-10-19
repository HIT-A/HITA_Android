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
        private const val MAX_SESSION_RETRIES = 3
    }

    @Inject
    lateinit var easRepository: EASRepository

    protected abstract val viewModel: T
    private var reloginInProgress = false
    private var sessionRetryCount = 0
    private var pendingSessionRetryAction: (() -> Boolean)? = null

    protected open fun shouldRefreshOnStart(): Boolean = true

    protected open fun shouldCheckLoginOnStart(): Boolean = false

    /** Most academic pages need Web access for recovery; score queries can use the API alone. */
    protected open val autoLaunchWebLoginForSessionRecovery: Boolean = true

    override fun onStart() {
        super.onStart()
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
        if (reloginInProgress || sessionRetryCount >= MAX_SESSION_RETRIES) {
            return false
        }
        reloginInProgress = true
        pendingSessionRetryAction = retryAction
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
                    reloginInProgress = false
                    sessionRetryCount++
                    val started = pendingSessionRetryAction?.invoke() == true
                    pendingSessionRetryAction = null
                    if (!started) {
                        sessionRetryCount = 0
                    }
                }

                override fun onFailed(window: PopUpLoginEAS) {
                    reloginInProgress = false
                    pendingSessionRetryAction = null
                }

                /**
                 * 用户主动"稍后"/下滑/返回：既不算失败也不消耗重试预算，
                 * 状态必须回滚，否则本页后续再也不会弹出过期提示（只能退出登录或重启应用）。
                 */
                override fun onCancelled(window: PopUpLoginEAS) {
                    LogUtils.d("session expired sheet dismissed by user; keep page usable")
                    reloginInProgress = false
                    pendingSessionRetryAction = null
                }
            }
        )
        return true
    }

    protected fun resetSessionRetryState() {
        reloginInProgress = false
        sessionRetryCount = 0
        pendingSessionRetryAction = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_SILENT_RELOGIN) {
            LogUtils.d("silentReloginResult: resultCode=$resultCode")
            if (resultCode == Activity.RESULT_OK) {
                reloginInProgress = false
                val started = pendingSessionRetryAction?.invoke() == true
                pendingSessionRetryAction = null
                if (!started) {
                    sessionRetryCount = 0
                }
            } else {
                reloginInProgress = false
                pendingSessionRetryAction = null
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
