package ru.nto.guestmanagement.mobile.ui.details

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.nto.guestmanagement.mobile.R
import ru.nto.guestmanagement.mobile.data.ApiResult
import ru.nto.guestmanagement.mobile.data.RequestDetails
import ru.nto.guestmanagement.mobile.data.RequestStatus
import ru.nto.guestmanagement.mobile.data.RequestsApi
import ru.nto.guestmanagement.mobile.di.AppContainer
import ru.nto.guestmanagement.mobile.ui.refresh.RefreshGate

/** Почему не удалась первая загрузка. */
enum class LoadError { NOT_FOUND, FAILED }

data class DetailsUiState(
    /** Последние успешно загруженные детали; `null` — ещё не загружены. */
    val details: RequestDetails? = null,
    val loading: Boolean = false,
    /** Ошибка ПЕРВОЙ загрузки: сообщение и кнопка повтора вместо деталей. */
    val loadError: LoadError? = null,
    /** Ошибка ПОВТОРНОЙ загрузки: детали остаются, сверху «Нет связи». */
    val offline: Boolean = false,
    /** Открыт диалог подтверждения. Хранится во ViewModel, чтобы пережить поворот экрана. */
    val confirmVisible: Boolean = false,
    val cancelling: Boolean = false,
    /** Ошибка отмены (ресурс строки): «Заявка уже рассмотрена» или сетевая. */
    val cancelError: Int? = null,
    val sessionExpired: Boolean = false,
)

class RequestDetailsViewModel(
    private val container: AppContainer,
    private val requestId: Long,
) : ViewModel() {
    private val api = RequestsApi(container.httpClient)
    private val gate = RefreshGate()

    var state by mutableStateOf(DetailsUiState())
        private set

    /** Открытие, возврат на экран, выход из фона, кнопки «Обновить» и «Повторить». В init не грузим. */
    fun refresh() {
        gate.launch(viewModelScope) { load() }
    }

    fun askCancel() {
        if (state.details?.status == RequestStatus.PENDING && !state.cancelling) state = state.copy(confirmVisible = true)
    }

    fun dismissCancel() {
        state = state.copy(confirmVisible = false)
    }

    fun confirmCancel() {
        if (state.cancelling) return
        // Обновление, начатое до отмены, могло бы вернуть устаревший статус PENDING поверх результата отмены.
        gate.cancel()
        state = state.copy(confirmVisible = false, cancelling = true, cancelError = null)

        viewModelScope.launch {
            val code = container.session.current() ?: return@launch expireSession()
            when (val result = api.cancel(code, requestId)) {
                is ApiResult.Success -> state = state.copy(
                    details = result.value, // статус «Отменена», кнопка отмены исчезает сама
                    cancelling = false,
                    cancelError = null,
                    offline = false,
                )
                is ApiResult.Conflict -> {
                    // Охрана успела рассмотреть заявку. Сообщение остаётся, а детали перечитываем,
                    // чтобы показать настоящий статус.
                    state = state.copy(cancelling = false, cancelError = R.string.details_cancel_conflict)
                    gate.launch(viewModelScope) { load() }
                }
                ApiResult.Unauthorized -> expireSession()
                // Сеть и 5xx для пользователя одинаковы; статус не меняется, отмену можно повторить.
                ApiResult.Network -> state = state.copy(cancelling = false, cancelError = R.string.details_cancel_network)
                is ApiResult.Failure -> state = state.copy(
                    cancelling = false,
                    cancelError = if (result.message?.startsWith("HTTP 5") == true) {
                        R.string.details_cancel_network
                    } else {
                        R.string.details_cancel_other
                    },
                )
                else -> state = state.copy(cancelling = false, cancelError = R.string.details_cancel_other)
            }
        }
    }

    private suspend fun load() {
        state = state.copy(loading = true)
        val code = container.session.current() ?: return expireSession()

        when (val result = api.get(code, requestId)) {
            is ApiResult.Success -> state = state.copy(
                details = result.value,
                loading = false,
                loadError = null,
                offline = false,
            )
            ApiResult.Unauthorized -> expireSession()
            else -> state = if (state.details != null) {
                // Детали уже есть — оставляем их и показываем «Нет связи».
                state.copy(loading = false, offline = true)
            } else {
                state.copy(
                    loading = false,
                    loadError = if (result == ApiResult.NotFound) LoadError.NOT_FOUND else LoadError.FAILED,
                )
            }
        }
    }

    /** 401 после входа: стираем сохранённый код, затем экран вызывает onSessionExpired(). */
    private suspend fun expireSession() {
        container.session.clear()
        state = state.copy(loading = false, cancelling = false, sessionExpired = true)
    }
}
