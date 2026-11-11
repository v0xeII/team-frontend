package ru.nto.guestmanagement.mobile.ui.form

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

data class FormUiState(
    val input: FormInput = FormInput(),
    /** Была ли попытка отправки. До неё ошибки не показываем, после — пересчитываем при каждом вводе. */
    val submitAttempted: Boolean = false,
    /** Клиентские ошибки (ресурсы строк). */
    val clientErrors: Map<FormField, Int> = emptyMap(),
    /** Ошибки полей от сервера (`400 errors`) — текст как прислал сервер. */
    val serverErrors: Map<FormField, String> = emptyMap(),
    /** Общая ошибка (дубликат, сеть, прочее): либо ресурс, либо готовый текст. */
    val commonErrorRes: Int? = null,
    val commonErrorText: String? = null,
    val sending: Boolean = false,
    val created: Boolean = false,
    val sessionExpired: Boolean = false,
)

class RequestFormViewModel(private val container: AppContainer) : ViewModel() {
    private val api = RequestsApi(container.httpClient)

    // ViewModel переживает поворот экрана (её хранит навигация), поэтому введённые значения не теряются.
    var state by mutableStateOf(FormUiState())
        private set

    fun onChange(field: FormField, value: String) {
        val input = state.input.with(field, value)
        state = state.copy(
            input = input,
            // Серверная ошибка поля исчезает при правке ИМЕННО этого поля. У даты нет серверного ключа,
            // поэтому правка даты серверные ошибки validFrom/validTo не трогает.
            serverErrors = state.serverErrors - field,
            clientErrors = if (state.submitAttempted) validate(input).errors else emptyMap(),
        )
    }

    fun submit() {
        // Пока запрос идёт, повторные нажатия игнорируются (кнопка к тому же disabled).
        if (state.sending) return

        val result = validate(state.input)
        state = state.copy(
            submitAttempted = true,
            clientErrors = result.errors,
            commonErrorRes = null,
            commonErrorText = null,
        )
        val body = result.body ?: return // есть ошибки — запрос не отправляем

        state = state.copy(sending = true)
        viewModelScope.launch {
            val code = container.session.current()
            if (code == null) {
                expireSession()
                return@launch
            }
            state = when (val response = api.create(code, body)) {
                is ApiResult.Success -> state.copy(sending = false, created = true)
                is ApiResult.FieldErrors -> {
                    val mapped = response.errors.mapNotNull { (key, text) -> FormField.byApiKey(key)?.let { it to text } }
                    // Ключи, которым нет поля на экране, показываем общим сообщением, чтобы не потерять.
                    val unknown = response.errors.filterKeys { FormField.byApiKey(it) == null }.values
                    state.copy(
                        sending = false,
                        serverErrors = mapped.toMap(),
                        commonErrorText = unknown.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                    )
                }
                is ApiResult.Conflict -> state.copy(sending = false, commonErrorRes = R.string.form_error_duplicate)
                ApiResult.Unauthorized -> {
                    expireSession()
                    return@launch
                }
                ApiResult.Network -> state.copy(sending = false, commonErrorRes = R.string.form_error_network)
                // 5xx для пользователя — то же, что нет сети; прочие ответы — общий текст «не удалось».
                is ApiResult.Failure -> state.copy(
                    sending = false,
                    commonErrorRes = if (response.message?.startsWith("HTTP 5") == true) {
                        R.string.form_error_network
                    } else {
                        R.string.form_error_other
                    },
                )
                ApiResult.NotFound -> state.copy(sending = false, commonErrorRes = R.string.form_error_other)
            }
        }
    }

    private fun validate(input: FormInput) = validateForm(input, container.timeProvider.now())

    private suspend fun expireSession() {
        container.session.clear()
        state = state.copy(sending = false, sessionExpired = true)
    }
}
