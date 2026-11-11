package ru.nto.guestmanagement.mobile.ui.details

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class RequestDetailsScreenTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createEmptyComposeRule()

    private val server = FakeServer()
    private var scenario: ActivityScenario<MainActivity>? = null

    private val pending = FakeRequest(
        id = 12,
        fullName = "Сидоров Пётр Ильич",
        validFrom = "2026-10-14T10:00:00",
        validTo = "2026-10-14T16:00:00",
        status = "PENDING",
        phone = "+79991234567",
        organization = "ООО НТО",
        purpose = "Встреча с сотрудником",
        createdAt = "2026-10-10T12:31:05",
        updatedAt = "2026-10-10T15:02:44",
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
    private fun detailsRequests() = server.count("GET /api/Ab12/requests/12")
    private fun listRequests() = server.requests.count { it == "GET /api/Ab12/requests" }

    /** Прокрутить к элементу, если он в прокручиваемой области. */
    private fun node(tag: String): SemanticsNodeInteraction =
        compose.onNodeWithTag(tag).also { runCatching { it.performScrollTo() } }

    private fun click(tag: String) {
        node(tag).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
    }

    private fun openDetails(vararg items: FakeRequest = arrayOf(pending)) {
        server.items = items.toList()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(TestIds.AUTH_CODE)
        compose.onNodeWithTag(TestIds.AUTH_CODE).performTextReplacement("Ab12")
        click(TestIds.AUTH_SUBMIT)
        waitFor(TestIds.requestsPos(0))
        click(TestIds.requestsPos(0))
        waitFor(TestIds.DETAILS_BACK)
    }

    private fun waitDetailsLoaded() = waitFor(TestIds.DETAILS_STATUS)

    // --- тесты ---

    @Test
    fun showsAllFieldsInRequiredFormats_withOneRequestOnOpen() {
        openDetails()
        waitDetailsLoaded()

        node(TestIds.DETAILS_ID).assertTextEquals("12")
        node(TestIds.DETAILS_NAME).assertTextEquals("Сидоров Пётр Ильич")
        node(TestIds.DETAILS_PHONE).assertTextEquals("+79991234567")
        node(TestIds.DETAILS_ORGANIZATION).assertTextEquals("ООО НТО")
        node(TestIds.DETAILS_PURPOSE).assertTextEquals("Встреча с сотрудником")
        node(TestIds.DETAILS_VALID_FROM).assertTextEquals("14.10.2026 10:00")
        node(TestIds.DETAILS_VALID_TO).assertTextEquals("14.10.2026 16:00")
        node(TestIds.DETAILS_STATUS).assertTextEquals("На рассмотрении")
        node(TestIds.DETAILS_CREATED_AT).assertTextEquals("10.10.2026 12:31")
        node(TestIds.DETAILS_UPDATED_AT).assertTextEquals("10.10.2026 15:02")
        assertFalse(exists(TestIds.DETAILS_COMMENT))
        compose.onNodeWithTag(TestIds.DETAILS_CANCEL).assertIsDisplayed() // видна без прокрутки

        assertEquals(1, detailsRequests())
    }

    @Test
    fun rejectedShowsCommentWithoutCancelAndHidesEmptyOrganization() {
        openDetails(pending.copy(status = "REJECTED", organization = null, comment = "Сотрудник отсутствует в указанный день"))
        waitDetailsLoaded()

        node(TestIds.DETAILS_STATUS).assertTextEquals("Отклонена")
        node(TestIds.DETAILS_COMMENT).assertTextEquals("Сотрудник отсутствует в указанный день")
        assertFalse(exists(TestIds.DETAILS_ORGANIZATION))
        assertFalse(exists(TestIds.DETAILS_CANCEL))
    }

    @Test
    fun approvedHasNoCommentAndNoCancel() {
        openDetails(pending.copy(status = "APPROVED"))
        waitDetailsLoaded()
        assertFalse(exists(TestIds.DETAILS_COMMENT))
        assertFalse(exists(TestIds.DETAILS_CANCEL))
    }

    @Test
    fun cancelAsksConfirmation_dismissDoesNothing_confirmCancels_listUpdatesOnReturn() {
        openDetails()
        waitDetailsLoaded()

        click(TestIds.DETAILS_CANCEL)
        waitFor(TestIds.DETAILS_CANCEL_CONFIRM)
        compose.onNodeWithTag(TestIds.DETAILS_CANCEL_CONFIRM).assertTextEquals("Отменить заявку")
        compose.onNodeWithTag(TestIds.DETAILS_CANCEL_DISMISS).assertTextEquals("Назад")
        click(TestIds.DETAILS_CANCEL_DISMISS)
        waitGone(TestIds.DETAILS_CANCEL_CONFIRM)
        assertEquals(0, server.count("POST /api/Ab12/requests/12/cancel"))

        click(TestIds.DETAILS_CANCEL)
        waitFor(TestIds.DETAILS_CANCEL_CONFIRM)
        click(TestIds.DETAILS_CANCEL_CONFIRM)
        waitGone(TestIds.DETAILS_CANCEL)
        node(TestIds.DETAILS_STATUS).assertTextEquals("Отменена")
        assertEquals(1, server.count("POST /api/Ab12/requests/12/cancel"))

        val listBefore = listRequests()
        click(TestIds.DETAILS_BACK)
        waitFor(TestIds.REQUESTS_ADD)
        compose.waitUntil(10_000) { listRequests() == listBefore + 1 } // список перечитан при возврате
        val firstCardStatus = compose.onNode(
            hasTestTag(TestIds.REQUEST_STATUS) and hasAnyAncestor(hasTestTag(TestIds.requestsPos(0))),
        )
        compose.waitUntil(10_000) { runCatching { firstCardStatus.assertTextEquals("Отменена") }.isSuccess }
    }

    @Test
    fun conflictShowsAlreadyReviewedAndReloadsRealStatus() {
        openDetails()
        waitDetailsLoaded()
        server.update(12) { it.copy(status = "APPROVED") } // охрана успела одобрить

        click(TestIds.DETAILS_CANCEL)
        waitFor(TestIds.DETAILS_CANCEL_CONFIRM)
        click(TestIds.DETAILS_CANCEL_CONFIRM)

        waitFor(TestIds.DETAILS_CANCEL_ERROR)
        compose.waitUntil(10_000) { !exists(TestIds.DETAILS_CANCEL) } // перечитали: уже не PENDING
        node(TestIds.DETAILS_STATUS).assertTextEquals("Одобрена")
        node(TestIds.DETAILS_CANCEL_ERROR).assertTextEquals("Заявка уже рассмотрена") // осталось после перечитывания
    }

    @Test
    fun networkErrorOnCancelKeepsStatusAndAllowsRetry() {
        openDetails()
        waitDetailsLoaded()
        server.cancelFailureCode = 503

        click(TestIds.DETAILS_CANCEL)
        waitFor(TestIds.DETAILS_CANCEL_CONFIRM)
        click(TestIds.DETAILS_CANCEL_CONFIRM)
        waitFor(TestIds.DETAILS_CANCEL_ERROR)
        node(TestIds.DETAILS_STATUS).assertTextEquals("На рассмотрении")

        server.cancelFailureCode = 200
        click(TestIds.DETAILS_CANCEL)
        waitFor(TestIds.DETAILS_CANCEL_CONFIRM)
        click(TestIds.DETAILS_CANCEL_CONFIRM)
        waitGone(TestIds.DETAILS_CANCEL_ERROR)
        node(TestIds.DETAILS_STATUS).assertTextEquals("Отменена")
    }

    @Test
    fun initialLoadErrorShowsRetry_404Too() {
        server.detailsFailureCode = 404
        openDetails()
        waitFor(TestIds.DETAILS_ERROR)
        assertTrue(exists(TestIds.DETAILS_RETRY))
        assertTrue(exists(TestIds.DETAILS_REFRESH))

        server.detailsFailureCode = 500
        click(TestIds.DETAILS_RETRY)
        compose.waitUntil(10_000) { detailsRequests() == 2 }
        waitFor(TestIds.DETAILS_ERROR)

        server.detailsFailureCode = 200
        click(TestIds.DETAILS_RETRY)
        waitDetailsLoaded()
        assertFalse(exists(TestIds.DETAILS_ERROR))
    }

    @Test
    fun failedReloadKeepsDetailsWithOfflineUntilNextSuccess() {
        openDetails()
        waitDetailsLoaded()

        server.detailsFailureCode = 503
        click(TestIds.DETAILS_REFRESH)
        waitFor(TestIds.DETAILS_OFFLINE)
        node(TestIds.DETAILS_NAME).assertTextEquals("Сидоров Пётр Ильич")
        assertFalse(exists(TestIds.DETAILS_ERROR))

        server.detailsFailureCode = 200
        click(TestIds.DETAILS_REFRESH)
        waitGone(TestIds.DETAILS_OFFLINE)
    }

    @Test
    fun guardDecisionAppearsAfterManualRefresh() {
        openDetails()
        waitDetailsLoaded()
        server.update(12) { it.copy(status = "REJECTED", comment = "Нет пропуска на этаж") }

        click(TestIds.DETAILS_REFRESH)
        waitFor(TestIds.DETAILS_COMMENT)
        node(TestIds.DETAILS_STATUS).assertTextEquals("Отклонена")
        assertFalse(exists(TestIds.DETAILS_CANCEL))
    }

    @Test
    fun repeatedRefreshWhileLoadingSendsOneRequest() {
        openDetails()
        waitDetailsLoaded()
        val before = detailsRequests()

        val gate = CountDownLatch(1)
        server.detailsGate = gate
        click(TestIds.DETAILS_REFRESH)
        compose.waitUntil(10_000) { detailsRequests() == before + 1 }
        click(TestIds.DETAILS_REFRESH)
        click(TestIds.DETAILS_REFRESH)
        Thread.sleep(300)
        gate.countDown()
        compose.waitForIdle()
        assertEquals(before + 1, detailsRequests())
    }

    @Test
    fun returningFromBackgroundReloadsDetails() {
        openDetails()
        waitDetailsLoaded()
        server.update(12) { it.copy(status = "APPROVED") }

        scenario!!.moveToState(Lifecycle.State.CREATED) // ушли в фон
        scenario!!.moveToState(Lifecycle.State.RESUMED) // вернулись
        compose.waitUntil(10_000) { detailsRequests() == 2 }
        compose.waitUntil(10_000) { !exists(TestIds.DETAILS_CANCEL) }
        node(TestIds.DETAILS_STATUS).assertTextEquals("Одобрена")
    }

    @Test
    fun unauthorizedEndsSession() {
        openDetails()
        waitDetailsLoaded()
        server.knownCodes = emptySet()
        click(TestIds.DETAILS_REFRESH)
        waitFor(TestIds.AUTH_SESSION_EXPIRED)
    }

    @Test
    fun screenAndSystemBackReturnToList() {
        openDetails()
        click(TestIds.DETAILS_BACK)
        waitFor(TestIds.REQUESTS_ADD)

        click(TestIds.requestsPos(0))
        waitFor(TestIds.DETAILS_BACK)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(TestIds.REQUESTS_ADD)
    }
}
