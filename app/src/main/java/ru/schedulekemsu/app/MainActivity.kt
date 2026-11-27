package ru.schedulekemsu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[ScheduleViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color.Black,
                onPrimary = Color.White,
                background = Color.White,
                onBackground = Color.Black,
                surface = Color.White,
                onSurface = Color.Black,
                onSurfaceVariant = Color.Black,
                outline = Color(0xFF777777),
                outlineVariant = Color(0xFFB8B8B8)
            )) {
                val state by model.state.collectAsStateWithLifecycle()
                when {
                    state.hasSession -> ScheduleScreen(state, model)
                    state.ready -> LoginScreen(state, model)
                    else -> Scaffold { }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.onScreenStarted()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) model.onScreenStopped()
    }
}

@Composable
internal fun ScreenTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun LoginScreen(state: ScreenState, model: ScheduleViewModel) {
    Scaffold(Modifier.imePadding()) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ScreenTitle("Вход в ЭИОС")
            Text("КемГУ")
            Spacer(Modifier.height(12.dp))
            if (state.pendingLogin == null) {
                LoginForm(state.login, !state.busy, model::setLogin, model::login)
            } else {
                CodeForm(!state.busy, model::confirm, model::cancelCode)
            }
            state.message?.let { Text(it) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LoginForm(login: String, enabled: Boolean, setLogin: (String) -> Unit, submit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val canSubmit = enabled && login.isNotBlank() && password.isNotEmpty()
    val loginAction = {
        if (canSubmit) {
            focus.clearFocus()
            val submitted = password
            password = ""
            submit(submitted)
        }
    }
    OutlinedTextField(
        login, setLogin, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("Логин") }, singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next)
    )
    OutlinedTextField(
        password, { password = it }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { loginAction() })
    )
    PrimaryButton("Войти", canSubmit, loginAction)
}

@Composable
private fun CodeForm(enabled: Boolean, submit: (String) -> Unit, cancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val canSubmit = enabled && code.isNotBlank()
    val confirm = {
        if (canSubmit) {
            focus.clearFocus()
            val submitted = code
            code = ""
            submit(submitted)
        }
    }
    OutlinedTextField(
        code, { code = it }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("Код подтверждения") }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { confirm() })
    )
    PrimaryButton("Подтвердить", canSubmit, confirm)
    TextButton(cancel, enabled = enabled) { Text("Назад") }
}

@Composable
private fun PrimaryButton(title: String, enabled: Boolean, action: () -> Unit) {
    Button(
        action, Modifier.fillMaxWidth().height(48.dp), enabled = enabled,
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(disabledContainerColor = Color.Black, disabledContentColor = Color(0xFFCCCCCC))
    ) {
        Text(title, fontWeight = FontWeight.Bold)
    }
}
