package ru.nto.guestmanagement.mobile.ui.login

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.nto.guestmanagement.mobile.R
import ru.nto.guestmanagement.mobile.data.ApiResult
import ru.nto.guestmanagement.mobile.data.RequestsApi
import ru.nto.guestmanagement.mobile.di.AppContainer

/** Ровно 4 символа: латинские буквы любого регистра и цифры ASCII. Ничего не обрезаем и не исправляем. */
private val CODE_FORMAT = Regex("[A-Za-z0-9]{4}")

fun isValidCode(code: String): Boolean = CODE_FORMAT.matches(code)

data class LoginUiState(
    /** Пока читаем сохранённый код из DataStore — экран пустой, чтобы форма входа не мелькала. */
    val checkingSavedCode: Boolean = true,
    val code: String = "",
    val loading: Boolean = false,
    /** Текст ошибки входа (ресурс строки) или `null`, если ошибки нет. */
    @StringRes val error: Int? = null,
    /** Вход выполнен (или найден сохранённый код) — экран должен вызвать onLoggedIn(). */
    val loggedIn: Boolean = false,
) {
    val canSubmit: Boolean get() = isValidCode(code) && !loading
}

class LoginViewModel(private val container: AppContainer) : ViewModel() {
    private val api = RequestsApi(container.httpClient)

    // Compose-состояние внутри ViewModel: экран читает state и перерисовывается при каждом изменении.
    var state by mutableStateOf(LoginUiState())
        private set

    init {
        // Пункт 5.1.5: если код сохранён с прошлого запуска — сразу пускаем в список, /auth НЕ вызываем.
        viewModelScope.launch {
            val saved = container.session.current()
            state = if (saved != null) {
                state.copy(checkingSavedCode = false, loggedIn = true)
            } else {
                state.copy(checkingSavedCode = false)
            }
        }
    }

    fun onCodeChange(newCode: String) {
        // Редактирование кода убирает прошлую ошибку (п. 5.1.6).
        state = state.copy(code = newCode, error = null)
    }

    fun submit() {
        // Защита от повторной отправки: пока идёт запрос или формат неверный — ничего не делаем.
        if (!state.canSubmit) return
        val code = state.code
        state = state.copy(loading = true, error = null)

        viewModelScope.launch {
            val result = api.auth(code)
            state = when (result) {
                is ApiResult.Success -> {
                    container.session.save(code)
                    state.copy(loading = false, loggedIn = true)
                }
                // На экране входа 401 — это просто «неверный код», а не конец сессии.
                ApiResult.Unauthorized -> state.copy(loading = false, error = R.string.login_error_unknown_code)
                ApiResult.Network -> state.copy(loading = false, error = R.string.login_error_network)
                else -> state.copy(loading = false, error = R.string.login_error_other)
            }
        }
    }
}
