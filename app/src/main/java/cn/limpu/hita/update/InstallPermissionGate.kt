package cn.limpu.hita.update

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cn.limpu.hita.R
import cn.limpu.hita.utils.LogUtils
import com.limpu.hitauser.data.model.CheckUpdateResult

/**
 * 「安装未知应用」授权往返的续接。
 *
 * 只续接触发这次授权的那次更新请求：用户从系统设置返回时，由本 Activity 的结果回调
 * 判断授权是否真的生效，再把结果交给发起时登记的续接动作。授权页是另一个 Activity，
 * 系统不会在结果里报告是否授予，所以以 `canRequestPackageInstalls()` 的实际能力为准。
 *
 * 必须在 Activity 到达 `STARTED` 之前创建（`registerForActivityResult` 的硬性要求），
 * 因此由宿主 Activity 以字段形式持有。Activity 重建时连同视图一起作废，
 * 返回不会在新实例里自动重放——与「已处理的提示不重复」一致。
 */
class InstallPermissionGate(private val activity: AppCompatActivity) {

    private var pending: CheckUpdateResult? = null
    private var pendingAction: ((CheckUpdateResult) -> Unit)? = null
    private val settingsLauncher: ActivityResultLauncher<android.content.Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            handleReturnFromSettings()
        }

    /**
     * 记住这次请求并打开授权页；已授权时直接执行 [onGranted]。
     * 同一时刻只有一次待续接请求，新的请求覆盖旧的。
     */
    fun request(cr: CheckUpdateResult, onGranted: (CheckUpdateResult) -> Unit) {
        if (ApkInstallLauncher.canInstallPackages(activity)) {
            clear()
            onGranted(cr)
            return
        }
        pending = cr
        pendingAction = onGranted
        try {
            settingsLauncher.launch(ApkInstallLauncher.unknownSourceSettingsIntent(activity))
        } catch (e: ActivityNotFoundException) {
            clear()
            LogUtils.e("InstallPermissionGate: 打开安装未知应用设置页失败", e)
            Toast.makeText(activity, R.string.update_permission_open_failed, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            clear()
            LogUtils.e("InstallPermissionGate: 打开安装未知应用设置页被拒绝", e)
            Toast.makeText(activity, R.string.update_permission_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 用户取消授权引导：丢弃这一次续接，之后仍可重新发起更新。 */
    fun cancel() = clear()

    private fun clear() {
        pending = null
        pendingAction = null
    }

    private fun handleReturnFromSettings() {
        val cr = pending ?: return
        val action = pendingAction
        clear()
        if (action == null) return
        if (!ApkInstallLauncher.canInstallPackages(activity)) {
            Toast.makeText(activity, R.string.update_permission_still_denied, Toast.LENGTH_LONG).show()
            return
        }
        action(cr)
    }
}
