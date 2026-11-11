package ru.nto.guestmanagement.mobile.ui.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.nto.guestmanagement.mobile.data.ApiResult
import ru.nto.guestmanagement.mobile.data.RequestStatus
import ru.nto.guestmanagement.mobile.data.RequestSummary
import ru.nto.guestmanagement.mobile.data.RequestsApi
import ru.nto.guestmanagement.mobile.di.AppContainer
import ru.nto.guestmanagement.mobile.ui.refresh.RefreshGate

data class ListUiState(
    /** Выбранный фильтр; `null` — «Все» (запрос без параметра status). */
    val filter: RequestStatus? = null,
    /** Загруженный список; `null` — для текущего фильтра ещё ничего не загружено. */
    val items: List<RequestSummary>? = null,
    val loading: Boolean = false,
    /** Ошибка ПЕРВОЙ загрузки: показываем сообщение вместо списка. */
    val initialError: Boolean = false,
    /** Ошибка ПОВТОРНОЙ загрузки: список остаётся, сверху «Нет связи». */
    val offline: Boolean = false,
    /** Сигналы навигации: экран увидит их и вызовет нужный колбэк. */
    val sessionExpired: Boolean = false,
    val loggedOut: Boolean = false,
)

class RequestListViewModel(private val container: AppContainer) : ViewModel() {
    private val api = RequestsApi(container.httpClient)
    private val gate = RefreshGate()

    var state by mutableStateOf(ListUiState())
        private set

    /**
     * Обновить данные: вызывается из ResumeEffect (открытие экрана, возврат, выход из фона) и кнопкой «Обновить».
     * Загрузку в init НЕ делаем — иначе при открытии ушло бы два GET (init + ResumeEffect).
     * Если обновление уже идёт, RefreshGate вернёт null и второго запроса не будет.
     */
    fun refresh() {
        gate.launch(viewModelScope) { load() }
    }

    fun selectFilter(filter: RequestStatus?) {
        if (filter == state.filter) return
        // Старая загрузка относится к другому фильтру — прерываем её, иначе её ответ перезапишет новый список.
        gate.cancel()
        state = state.copy(filter = filter, items = null, initialError = false, offline = false)
        gate.launch(viewModelScope) { load() }
    }

    fun logout() {
        gate.cancel()
        viewModelScope.launch {
            container.session.clear()
            state = state.copy(loggedOut = true)
        }
    }

    private suspend fun load() {
        state = state.copy(loading = true)
        val code = container.session.current()
        if (code == null) {
            expireSession()
            return
        }

        val result = api.list(code, state.filter)
        state = when (result) {
            is ApiResult.Success -> state.copy(
                items = result.value,
                loading = false,
                initialError = false,
                offline = false,
            )
            ApiResult.Unauthorized -> {
                expireSession()
                return
            }
            // Сеть, 5xx и прочее для пользователя одинаковы. Если список уже есть — сохраняем его и пишем
            // «Нет связи»; если нет — показываем ошибку первой загрузки. Оба флага одновременно не бывают.
            else -> if (state.items != null) {
                state.copy(loading = false, offline = true, initialError = false)
            } else {
                state.copy(loading = false, initialError = true, offline = false)
            }
        }
    }

    /** 401 после входа: сначала стираем сохранённый код, потом сообщаем экрану, что сессия закончилась. */
    private suspend fun expireSession() {
        container.session.clear()
        state = state.copy(loading = false, sessionExpired = true)
    }
}
