package cn.limpu.hita.ui.eas.login

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Observer
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.ui.about.UserAgreementDialog
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.utils.ImageUtils
import cn.limpu.hita.utils.LogUtils
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONObject

@AndroidEntryPoint
class PopUpLoginEAS : BottomSheetDialogFragment() {

    var lock = false
    var autoLaunchWebLogin = false
    var preferredCampus: EASToken.Campus? = null
    var onResponseListener: OnResponseListener? = null
    private var pendingWebViewCampus: EASToken.Campus? = null
    private var autoLaunchTriggered = false
    private var silentWebLoginTried = false

    private val viewModel: LoginEASViewModel by viewModels()

    private fun isUserAgreementAccepted(): Boolean {
        return requireContext().getSharedPreferences("user_agreement", Context.MODE_PRIVATE)
            .getBoolean("accepted", false)
    }

    private fun markUserAgreementAccepted() {
        requireContext().getSharedPreferences("user_agreement", Context.MODE_PRIVATE)
            .edit().putBoolean("accepted", true).apply()
    }

    interface OnResponseListener {
        fun onSuccess(window: PopUpLoginEAS)
        fun onFailed(window: PopUpLoginEAS)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme() {
                    val loginResult by viewModel.loginResultLiveData.observeAsState()
                    val loginCheckResult by viewModel.loginCheckResult.observeAsState()

                    val webViewLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        handleWebViewResult(result.resultCode, result.data)
                    }

                    LoginEASScreen(
                        viewModel = viewModel,
                        loginResult = loginResult,
                        loginCheckResult = loginCheckResult,
                        isAgreementAccepted = isUserAgreementAccepted(),
                        onMarkAgreementAccepted = { markUserAgreementAccepted() },
                        initialCampus = preferredCampus ?: viewModel.easRepo.getEasToken().campus,
                        onLogin = { campus, username, password ->
                            performLogin(campus, username, password, webViewLauncher)
                        },
                        onAutoLaunch = { campus ->
                            if (!autoLaunchTriggered && autoLaunchWebLogin &&
                                campus == EASToken.Campus.BENBU && isUserAgreementAccepted()
                            ) {
                                autoLaunchTriggered = true
                                silentWebLoginTried = true
                                LogUtils.d("auto launch silent web login for session recovery campus=$campus")
                                launchCampusWebLogin(campus, silentMode = true, webViewLauncher)
                            }
                        },
                        onSuccess = {
                            onResponseListener?.onSuccess(this@PopUpLoginEAS)
                        },
                        onFailed = {
                            onResponseListener?.onFailed(this@PopUpLoginEAS)
                        },
                        onShowAgreement = { pageIndex ->
                            UserAgreementDialog().apply {
                                setShowActionButtons(false)
                                initialPage = pageIndex
                            }.show(childFragmentManager, "user_agreement_view")
                        }
                    )
                }
            }
        }
    }

    private fun performLogin(
        campus: EASToken.Campus,
        username: String,
        password: String,
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        when (campus) {
            EASToken.Campus.SHENZHEN -> viewModel.startLogin(username, password, campus)
            EASToken.Campus.BENBU, EASToken.Campus.WEIHAI -> {
                launchCampusWebLogin(campus, silentMode = false, launcher)
            }
        }
    }

    private fun launchCampusWebLogin(
        campus: EASToken.Campus,
        silentMode: Boolean,
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        pendingWebViewCampus = campus
        launcher.launch(
            Intent(requireContext(), WebViewLoginActivity::class.java).apply {
                putExtra(WebViewLoginActivity.EXTRA_SILENT_MODE, silentMode)
                putExtra(WebViewLoginActivity.EXTRA_CAMPUS, campus.name)
            }
        )
    }

    private fun handleWebViewResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            val campus = pendingWebViewCampus
            pendingWebViewCampus = null
            if (autoLaunchWebLogin && silentWebLoginTried && campus == EASToken.Campus.BENBU) {
                LogUtils.i("retry non-silent login")
                silentWebLoginTried = false
            } else {
                LogUtils.e("WebView login FAILED")
                onResponseListener?.onFailed(this)
            }
            return
        }

        val cookiesJson = data?.getStringExtra("cookies")
        val campus = pendingWebViewCampus
        LogUtils.i("WebView returned RESULT_OK, campus=$campus")

        pendingWebViewCampus = null
        silentWebLoginTried = false

        if (cookiesJson != null && (campus == EASToken.Campus.BENBU || campus == EASToken.Campus.WEIHAI)) {
            try {
                val cookiesJsonObj = JSONObject(cookiesJson)
                val cookiesMap = HashMap<String, String>()
                val keys = cookiesJsonObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    cookiesMap[key] = cookiesJsonObj.getString(key)
                }

                val electronicExpToken = data.getStringExtra("electronic_exp_token")
                startCookieLogin(campus, cookiesMap, electronicExpToken)
            } catch (e: Exception) {
                LogUtils.e("Failed to parse cookies: ${e.message}")
                onResponseListener?.onFailed(this)
            }
        } else {
            onResponseListener?.onFailed(this)
        }
    }

    private fun startCookieLogin(
        campus: EASToken.Campus,
        cookiesMap: HashMap<String, String>,
        electronicExpToken: String?,
    ) {
        val cookiesJson = JSONObject(cookiesMap as Map<*, *>).toString()
        val loginSource = viewModel.easRepo.login(cookiesJson, electronicExpToken.orEmpty(), campus)
        val observer = object : Observer<DataState<Boolean>> {
            override fun onChanged(value: DataState<Boolean>) {
                val state = value
                if (state.state == DataState.STATE.NOTHING) return
                loginSource.removeObserver(this)
                if (state.state == DataState.STATE.SUCCESS && state.data == true) {
                    onResponseListener?.onSuccess(this@PopUpLoginEAS)
                } else {
                    LogUtils.e("WebView cookie login failed: state=${state.state} message=${state.message}")
                    Toast.makeText(
                        requireContext(),
                        state.message ?: "登录验证失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    onResponseListener?.onFailed(this@PopUpLoginEAS)
                }
            }
        }
        loginSource.observe(this, observer)
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        if (lock) {
            if (context is Activity) {
                (context as Activity).finish()
            }
        }
    }
}

@Composable
private fun LoginEASScreen(
    viewModel: LoginEASViewModel,
    loginResult: DataState<Boolean>?,
    loginCheckResult: DataState<Boolean>?,
    isAgreementAccepted: Boolean,
    onMarkAgreementAccepted: () -> Unit,
    initialCampus: EASToken.Campus,
    onLogin: (EASToken.Campus, String, String) -> Unit,
    onAutoLaunch: (EASToken.Campus) -> Unit,
    onSuccess: () -> Unit,
    onFailed: () -> Unit,
    onShowAgreement: (Int) -> Unit
) {
    val tokens = HitaTheme.tokens
    val view = LocalView.current
    val token = viewModel.easRepo.getEasToken()

    var selectedCampus by remember { mutableStateOf(initialCampus) }
    var username by remember {
        mutableStateOf(token.username?.takeIf { token.campus == EASToken.Campus.SHENZHEN } ?: "")
    }
    var password by remember {
        mutableStateOf(token.password?.takeIf { token.campus == EASToken.Campus.SHENZHEN } ?: "")
    }
    var agreementChecked by remember { mutableStateOf(isAgreementAccepted) }
    var isLoading by remember { mutableStateOf(false) }
    var lastHandledLoginResult by remember { mutableStateOf<DataState<Boolean>?>(null) }
    var lastHandledCheckResult by remember { mutableStateOf<DataState<Boolean>?>(null) }

    LaunchedEffect(Unit) {
        onAutoLaunch(initialCampus)
    }

    LaunchedEffect(loginResult) {
        val result = loginResult ?: return@LaunchedEffect
        if (result === lastHandledLoginResult) return@LaunchedEffect
        lastHandledLoginResult = result
        isLoading = false
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (result.state == DataState.STATE.SUCCESS && result.data == true) {
            onSuccess()
        } else {
            Toast.makeText(
                view.context,
                result.message ?: view.context.getString(R.string.hint_eas_login),
                Toast.LENGTH_SHORT
            ).show()
            onFailed()
        }
    }

    LaunchedEffect(loginCheckResult) {
        val result = loginCheckResult ?: return@LaunchedEffect
        if (result === lastHandledCheckResult) return@LaunchedEffect
        lastHandledCheckResult = result
        isLoading = false
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (result.state == DataState.STATE.SUCCESS && result.data == true) {
            onSuccess()
        } else {
            Toast.makeText(
                view.context,
                result.message ?: "登录验证失败",
                Toast.LENGTH_SHORT
            ).show()
            onFailed()
        }
    }

    val isFormValid = when (selectedCampus) {
        EASToken.Campus.SHENZHEN -> username.isNotBlank() && password.isNotBlank()
        EASToken.Campus.BENBU, EASToken.Campus.WEIHAI -> true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(tokens.spacing.lg)
    ) {
        // Title row with login button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.eas_verify_title),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.hint_eas_login),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    if (!agreementChecked && !isAgreementAccepted) {
                        Toast.makeText(
                            view.context,
                            R.string.user_agreement_required,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@Button
                    }
                    onMarkAgreementAccepted()
                    isLoading = true
                    onLogin(selectedCampus, username, password)
                },
                enabled = isFormValid && !isLoading,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .height(36.dp)
                    .padding(start = tokens.spacing.sm)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = stringResource(R.string.log_in),
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        // Campus selection
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                EASToken.Campus.SHENZHEN to stringResource(R.string.eas_campus_shenzhen),
                EASToken.Campus.BENBU to stringResource(R.string.eas_campus_benbu),
                EASToken.Campus.WEIHAI to stringResource(R.string.eas_campus_weihai)
            ).forEachIndexed { index, (campus, label) ->
                if (index > 0) Spacer(modifier = Modifier.width(tokens.spacing.md))
                RadioButton(
                    selected = selectedCampus == campus,
                    onClick = { selectedCampus = campus },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary
                    )
                )
                Text(
                    text = label,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = tokens.spacing.xs)
                )
            }
        }

        // Username/password (only for Shenzhen)
        if (selectedCampus == EASToken.Campus.SHENZHEN) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.xs),
                label = { Text(stringResource(R.string.hint_eas_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                )
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.xs),
                label = { Text(stringResource(R.string.hint_eas_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                )
            )
        }

        // Agreement
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = agreementChecked,
                onCheckedChange = {
                    agreementChecked = it
                    if (it) onMarkAgreementAccepted()
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
            val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
            AndroidView(
                factory = { context ->
                    TextView(context).apply {
                        text = createAgreementSpannable(context, onShowAgreement)
                        movementMethod = LinkMovementMethod.getInstance()
                        setTextColor(textColor)
                        textSize = 12f
                    }
                },
                modifier = Modifier.padding(start = tokens.spacing.xs)
            )
        }
    }
}

private fun createAgreementSpannable(
    context: Context,
    onShowAgreement: (Int) -> Unit
): android.text.SpannableString {
    val hint = context.getString(R.string.user_agreement_hint)
    val span = android.text.SpannableString(hint)
    val uaStart = hint.indexOf("《")
    val uaEnd = hint.indexOf("》") + 1
    val ppStart = hint.indexOf("《", uaEnd)
    val ppEnd = hint.indexOf("》", ppStart) + 1

    if (uaStart >= 0 && uaEnd > uaStart) {
        span.setSpan(object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) {
                onShowAgreement(0)
            }
        }, uaStart, uaEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (ppStart >= 0 && ppEnd > ppStart) {
        span.setSpan(object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) {
                onShowAgreement(1)
            }
        }, ppStart, ppEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return span
}
