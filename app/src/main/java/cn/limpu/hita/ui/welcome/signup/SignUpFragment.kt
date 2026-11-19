package cn.limpu.hita.ui.welcome.signup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import cn.limpu.hita.R
import cn.limpu.hita.ui.about.UserAgreementDialog
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaPasswordField
import cn.limpu.hita.ui.design.HitaTheme
import com.limpu.hitauser.data.model.SignUpResult
import com.limpu.hitauser.data.model.UserLocal
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SignUpFragment : Fragment() {

    private val viewModel: SignUpViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme() {
                    SignUpScreen(
                        viewModel = viewModel,
                        onSignUpSuccess = { requireActivity().finish() },
                        onShowAgreement = {
                            UserAgreementDialog().show(childFragmentManager, "user_agreement")
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun newInstance(): SignUpFragment = SignUpFragment()
    }
}

@Composable
private fun SignUpScreen(
    viewModel: SignUpViewModel,
    onSignUpSuccess: () -> Unit,
    onShowAgreement: () -> Unit
) {
    val tokens = HitaTheme.tokens
    val context = LocalContext.current
    val formState by viewModel.loginFormState.observeAsState()
    val signUpResultLiveData = remember { viewModel.signUpResult }
    val signUpResult by signUpResultLiveData.observeAsState()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var isMale by remember { mutableStateOf(true) }
    var agreementChecked by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var usernameFieldError by remember { mutableStateOf<Int?>(null) }
    var passwordFieldError by remember { mutableStateOf<Int?>(null) }
    var passwordConfirmFieldError by remember { mutableStateOf<Int?>(null) }
    var nicknameFieldError by remember { mutableStateOf<Int?>(null) }
    var lastHandledResult by remember { mutableStateOf<SignUpResult?>(null) }

    fun updateFormState() {
        viewModel.signUpDataChanged(username, password, passwordConfirm, nickname)
    }

    LaunchedEffect(formState) {
        formState?.let { state ->
            if (state.usernameError != null) usernameFieldError = state.usernameError
            if (state.passwordError != null) passwordFieldError = state.passwordError
            if (state.passwordConfirmError != null) passwordConfirmFieldError = state.passwordConfirmError
            if (state.nicknameError != null) nicknameFieldError = state.nicknameError
            if (state.agreementError != null && !agreementChecked) {
                Toast.makeText(context, context.getString(state.agreementError!!), Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(signUpResult) {
        val result = signUpResult
        if (result != null && result !== lastHandledResult) {
            lastHandledResult = result
            isLoading = false
            Toast.makeText(context, context.getString(result.message), Toast.LENGTH_SHORT).show()
            when (result.state) {
                SignUpResult.STATES.SUCCESS -> onSignUpSuccess()
                SignUpResult.STATES.USER_EXISTS -> usernameFieldError = result.message
                else -> {}
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(tokens.spacing.sm),
        shape = RoundedCornerShape(tokens.radius.xl),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    usernameFieldError = null
                    updateFormState()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.lg),
                label = { Text(stringResource(R.string.username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                isError = usernameFieldError != null,
                supportingText = usernameFieldError?.let { err -> { Text(stringResource(err)) } }
            )

            HitaPasswordField(
                value = password,
                onValueChange = {
                    password = it
                    passwordFieldError = null
                    updateFormState()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.lg),
                label = { Text(stringResource(R.string.password)) },
                imeAction = ImeAction.Next,
                isError = passwordFieldError != null,
                supportingText = passwordFieldError?.let { err -> { Text(stringResource(err)) } }
            )

            HitaPasswordField(
                value = passwordConfirm,
                onValueChange = {
                    passwordConfirm = it
                    passwordConfirmFieldError = null
                    updateFormState()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.lg),
                label = { Text(stringResource(R.string.signup_confirm_password)) },
                imeAction = ImeAction.Next,
                isError = passwordConfirmFieldError != null,
                supportingText = passwordConfirmFieldError?.let { err -> { Text(stringResource(err)) } }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .height(72.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isMale,
                    onClick = { isMale = true },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = colorResource(R.color.cruel_summer_fade)
                    )
                )
                Icon(
                    painter = painterResource(R.drawable.ic_male_blue_24),
                    contentDescription = "Male",
                    modifier = Modifier.padding(start = 12.dp)
                )
                Spacer(modifier = Modifier.width(tokens.spacing.sm))
                RadioButton(
                    selected = !isMale,
                    onClick = { isMale = false },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = colorResource(R.color.subject3)
                    )
                )
                Icon(
                    painter = painterResource(R.drawable.ic_female_pink_24),
                    contentDescription = "Female",
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            OutlinedTextField(
                value = nickname,
                onValueChange = {
                    nickname = it
                    nicknameFieldError = null
                    updateFormState()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.lg),
                label = { Text(stringResource(R.string.signup_nick)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                isError = nicknameFieldError != null,
                supportingText = nicknameFieldError?.let { err -> { Text(stringResource(err)) } }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = agreementChecked,
                    onCheckedChange = {
                        agreementChecked = it
                        viewModel.isAgreementChecked = it
                        updateFormState()
                    },
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = stringResource(R.string.user_agreement_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = tokens.spacing.xs)
                )
            }

            Button(
                onClick = {
                    if (!agreementChecked) {
                        Toast.makeText(context, context.getString(R.string.user_agreement_required), Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isLoading = true
                    viewModel.signUp(
                        username, password,
                        if (isMale) UserLocal.GENDER.MALE else UserLocal.GENDER.FEMALE,
                        nickname
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = tokens.spacing.lg,
                        end = tokens.spacing.lg,
                        top = tokens.spacing.sm,
                        bottom = tokens.spacing.sm
                    )
                    .height(48.dp),
                enabled = formState?.isFormValid == true && !isLoading,
                shape = RoundedCornerShape(tokens.radius.xl),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.sign_up), fontSize = 18.sp)
                }
            }
        }
    }
}
