package ru.nto.guestmanagement.mobile.ui.list

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.nto.guestmanagement.mobile.Constants
import ru.nto.guestmanagement.mobile.MainActivity
import ru.nto.guestmanagement.mobile.di.DataStorePaths
import ru.nto.guestmanagement.mobile.testing.FakeRequest
import ru.nto.guestmanagement.mobile.testing.FakeServer
import ru.nto.guestmanagement.mobile.ui.TestIds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class RequestListScreenTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createEmptyComposeRule()

    private val server = FakeServer()
    private var scenario: ActivityScenario<MainActivity>? = null

    private val sample = listOf(
        FakeRequest(12, "Сидоров Пётр Ильич", "2026-10-14T10:00:00", "2026-10-14T16:00:00", "PENDING"),
        FakeRequest(13, "Кузнецова Анна", "2026-10-15T09:00:00", "2026-10-15T12:00:00", "APPROVED"),
        FakeRequest(14, "Петров Олег", "2026-10-16T11:00:00", "2026-10-16T13:00:00", "REJECTED"),
        FakeRequest(15, "Орлова Мария", "2026-10-17T14:00:00", "2026-10-17T15:00:00", "CANCELLED"),
    )

    @Before
    fun setUp() {
        server.start()
        Constants.HOST = server.url
        DataStorePaths.dir = tmp.newFolder("datastore")
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.stop()
    }

    // --- помощники ---

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.waitUntil(10_000) { exists(tag) }
    private fun waitGone(tag: String) = compose.waitUntil(10_000) { !exists(tag) }
    private fun listRequests() = server.count("GET /api/Ab12/requests")

    /** Вход через UI и ожидание первой загрузки списка. */
    private fun loginToList() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(TestIds.AUTH_CODE)
        compose.onNodeWithTag(TestIds.AUTH_CODE).performTextReplacement("Ab12")
        compose.onNodeWithTag(TestIds.AUTH_SUBMIT).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
        waitFor(TestIds.REQUESTS_ADD)
        compose.waitUntil(10_000) { listRequests() >= 1 }
        compose.waitForIdle()
    }

    private fun inCard(index: Int, tag: String): SemanticsNodeInteraction =
        compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(TestIds.requestsPos(index))))

    // --- тесты ---

    @Test
    fun cardsShowDataFormatsAndApiOrder_withExactlyOneRequestOnOpen() {
        server.items = sample
        loginToList()
        waitFor(TestIds.requestsPos(0))

        inCard(0, TestIds.REQUEST_NAME).assertTextEquals("Сидоров Пётр Ильич")
        inCard(0, TestIds.REQUEST_DATE).assertTextEquals("14.10.2026")
        inCard(0, TestIds.REQUEST_TIME).assertTextEquals("10:00–16:00")
        inCard(0, TestIds.REQUEST_STATUS).assertTextEquals("На рассмотрении")
        inCard(1, TestIds.REQUEST_STATUS).assertTextEquals("Одобрена")
        inCard(2, TestIds.REQUEST_STATUS).assertTextEquals("Отклонена")
        inCard(3, TestIds.REQUEST_NAME).assertTextEquals("Орлова Мария")

        assertEquals(1, listRequests())
    }

    @Test
    fun filtersSendStatusParameterAndShowSelection() {
        server.items = sample
        loginToList()

        assertEquals("GET /api/Ab12/requests", server.requests.last { it.contains("/requests") })
        compose.onNodeWithTag(TestIds.REQUESTS_FILTER_ALL).assertIsSelected()

        compose.onNodeWithTag(TestIds.REQUESTS_FILTER_PENDING).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
        compose.waitUntil(10_000) { server.requests.any { it.endsWith("?status=PENDING") } }
        compose.onNodeWithTag(TestIds.REQUESTS_FILTER_PENDING).assertIsSelected()
        compose.onNodeWithTag(TestIds.REQUESTS_FILTER_ALL).assertIsNotSelected()
        waitFor(TestIds.requestsPos(0))
        assertFalse(exists(TestIds.requestsPos(1)))

        compose.onNodeWithTag(TestIds.REQUESTS_FILTER_CANCELLED).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
        compose.waitUntil(10_000) { server.requests.any { it.endsWith("?status=CANCELLED") } }
        waitFor(TestIds.requestsPos(0))
        inCard(0, TestIds.REQUEST_NAME).assertTextEquals("Орлова Мария")
    }

    @Test
    fun emptyResultShowsEmptyMessage() {
        loginToList()
        waitFor(TestIds.REQUESTS_EMPTY)
        compose.onNodeWithTag(TestIds.REQUESTS_EMPTY).assertTextEquals("Заявок нет")
    }

    @Test
    fun initialErrorThenManualRefreshRecovers() {
        server.items = sample
        server.listFailureCode = 500
        loginToList()
        waitFor(TestIds.REQUESTS_ERROR)
        assertFalse(exists(TestIds.REQUESTS_OFFLINE))

        server.listFailureCode = 200
        compose.onNodeWithTag(TestIds.REQUESTS_REFRESH).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
        waitFor(TestIds.requestsPos(0))
        assertFalse(exists(TestIds.REQUESTS_ERROR))
    }

    @Test
    fun failedReloadKeepsListWithOfflineUntilNextSuccess() {
        server.items = sample
        loginToList()
        waitFor(TestIds.requestsPos(0))

        server.listFailureCode = 503
        compose.onNodeWithTag(TestIds.REQUESTS_REFRESH).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
        waitFor(TestIds.REQUESTS_OFFLINE)
        assertFalse(exists(TestIds.REQUESTS_ERROR))
        inCard(0, TestIds.REQUEST_NAME).assertTextEquals("Сидоров Пётр Ильич")

        server.listFailureCode = 200
        compose.onNodeWithTag(TestIds.REQUESTS_REFRESH).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
        waitGone(TestIds.REQUESTS_OFFLINE)
    }

    @Test
    fun repeatedRefreshWhileLoadingDoesNotSendSecondRequest() {
        server.items = sample
        loginToList()
        waitFor(TestIds.requestsPos(0))
        val before = listRequests()

        val gate = CountDownLatch(1)
        server.listGate = gate
        compose.onNodeWithTag(TestIds.REQUESTS_REFRESH).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
        compose.waitUntil(10_000) { listRequests() == before + 1 }
        compose.onNodeWithTag(TestIds.REQUESTS_REFRESH).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
        compose.onNodeWithTag(TestIds.REQUESTS_REFRESH).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
        Thread.sleep(300)
        gate.countDown()
        compose.waitForIdle()

        assertEquals(before + 1, listRequests())
    }

    @Test
    fun logoutReturnsToEmptyLoginAndForgetsCode() {
        loginToList()
        compose.onNodeWithTag(TestIds.REQUESTS_LOGOUT).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
        waitFor(TestIds.AUTH_CODE)
        compose.onNodeWithTag(TestIds.AUTH_CODE).assertTextEquals("")
        assertFalse(exists(TestIds.AUTH_SESSION_EXPIRED))

        // После «перезапуска» снова экран входа — код стёрт.
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(TestIds.AUTH_CODE)
    }

    @Test
    fun unauthorizedAfterLoginClearsCodeAndShowsSessionExpired() {
        server.items = sample
        loginToList()
        waitFor(TestIds.requestsPos(0))

        server.knownCodes = emptySet()
        compose.onNodeWithTag(TestIds.REQUESTS_REFRESH).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
        waitFor(TestIds.AUTH_SESSION_EXPIRED)
        compose.onNodeWithTag(TestIds.AUTH_SESSION_EXPIRED).assertTextEquals("Сессия завершена")

        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(TestIds.AUTH_CODE) // код очищен — вход не пропускается
    }
}
