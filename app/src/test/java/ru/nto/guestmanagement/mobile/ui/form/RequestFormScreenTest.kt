package ru.nto.guestmanagement.mobile.ui.form

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import org.json.JSONObject
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
import ru.nto.guestmanagement.mobile.testing.FakeServer
import ru.nto.guestmanagement.mobile.ui.TestIds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class RequestFormScreenTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createEmptyComposeRule()

    private val server = FakeServer()
    private var scenario: ActivityScenario<MainActivity>? = null

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
    /** Прокрутить к элементу, если он в прокручиваемой области (у кнопок входа и списка её нет). */
    private fun scrolledTo(tag: String) = compose.onNodeWithTag(tag).also { node -> runCatching { node.performScrollTo() } }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { exists(tag) }
    private fun waitGone(tag: String) = compose.waitUntil(10_000) { !exists(tag) }

    private fun click(tag: String) {
        scrolledTo(tag).performClick()
        compose.waitForIdle() // обработать нажатие на главном потоке до проверки условий вне UI
    }

    private fun type(tag: String, text: String) {
        scrolledTo(tag).performTextReplacement(text)
    }

    private fun openForm() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(TestIds.AUTH_CODE)
        type(TestIds.AUTH_CODE, "Ab12")
        click(TestIds.AUTH_SUBMIT)
        waitFor(TestIds.REQUESTS_ADD)
        click(TestIds.REQUESTS_ADD)
        waitFor(TestIds.FORM_FULL_NAME)
    }

    private fun fillValid() {
        type(TestIds.FORM_FULL_NAME, "  Сидоров Пётр Ильич  ")
        type(TestIds.FORM_PHONE, " +79991234567 ")
        type(TestIds.FORM_ORGANIZATION, "   ")
        type(TestIds.FORM_PURPOSE, "Встреча с сотрудником")
        type(TestIds.FORM_DATE, "14.10.2099")
        type(TestIds.FORM_TIME_FROM, "10:00")
        type(TestIds.FORM_TIME_TO, "16:00")
    }

    private val allErrorTags = FormField.entries.map { it.errorTag }

    // --- тесты ---

    @Test
    fun opensWithoutErrorsAndSubmitEnabled() {
        openForm()
        FormField.entries.forEach { compose.onNodeWithTag(it.inputTag).performScrollTo() }
        allErrorTags.forEach { assertFalse(it, exists(it)) }
        compose.onNodeWithTag(TestIds.FORM_SUBMIT).assertIsEnabled()
    }

    @Test
    fun invalidSubmitShowsAllErrorsAndSendsNothing_thenErrorsFollowEdits() {
        openForm()
        type(TestIds.FORM_FULL_NAME, "Ив")
        type(TestIds.FORM_PHONE, "+7 999 123")
        type(TestIds.FORM_ORGANIZATION, "о".repeat(151))
        type(TestIds.FORM_PURPOSE, "abc")
        type(TestIds.FORM_DATE, "31.02.2026")
        type(TestIds.FORM_TIME_FROM, "9:00")
        type(TestIds.FORM_TIME_TO, "24:00")
        click(TestIds.FORM_SUBMIT)

        allErrorTags.forEach { assertTrue(it, exists(it)) }
        assertTrue(server.createdBodies.isEmpty())

        // После первой попытки ошибки обновляются при вводе.
        type(TestIds.FORM_PHONE, "+79991234567")
        waitGone(TestIds.FORM_ERROR_PHONE)
        type(TestIds.FORM_TIME_FROM, "12:00")
        type(TestIds.FORM_TIME_TO, "11:00")
        compose.waitForIdle()
        assertFalse(exists(TestIds.FORM_ERROR_VALID_FROM)) // дата ещё неверна — «прошлое» не проверяется
        compose.onNodeWithTag(TestIds.FORM_ERROR_VALID_TO).assertTextEquals("Окончание должно быть позже начала")
    }

    @Test
    fun startInThePastIsShownUnderStartTime() {
        openForm()
        fillValid()
        type(TestIds.FORM_DATE, "01.01.2020")
        click(TestIds.FORM_SUBMIT)
        waitFor(TestIds.FORM_ERROR_VALID_FROM)
        assertFalse(exists(TestIds.FORM_ERROR_DATE))
        assertTrue(server.createdBodies.isEmpty())
    }

    @Test
    fun sendsNormalizedBodyAndReturnsToListWithNewRequest() {
        openForm()
        fillValid()
        click(TestIds.FORM_SUBMIT)
        waitFor(TestIds.REQUESTS_ADD)
        waitFor(TestIds.requestsPos(0))

        assertEquals(1, server.createdBodies.size)
        val body = JSONObject(server.createdBodies.single())
        assertEquals("Сидоров Пётр Ильич", body.getString("fullName"))
        assertEquals("+79991234567", body.getString("phone"))
        assertTrue(body.has("organization") && body.isNull("organization"))
        assertEquals("Встреча с сотрудником", body.getString("purpose"))
        assertEquals("2099-10-14T10:00:00", body.getString("validFrom"))
        assertEquals("2099-10-14T16:00:00", body.getString("validTo"))
        assertEquals(setOf("fullName", "phone", "organization", "purpose", "validFrom", "validTo"), body.keys().asSequence().toSet())

        // Список открылся заново с фильтром «Все» и перечитал данные.
        assertEquals("GET /api/Ab12/requests", server.requests.last { it.contains("/requests") && it.startsWith("GET") })
    }

    @Test
    fun loadingIndicatorAndNoDoubleSubmit() {
        openForm()
        fillValid()
        val gate = CountDownLatch(1)
        server.createGate = gate

        click(TestIds.FORM_SUBMIT)
        waitFor(TestIds.FORM_LOADING)
        compose.onNodeWithTag(TestIds.FORM_SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(TestIds.FORM_SUBMIT).performClick()
        compose.onNodeWithTag(TestIds.FORM_SUBMIT).performClick()

        gate.countDown()
        waitFor(TestIds.REQUESTS_ADD)
        assertEquals(1, server.createdBodies.size)
    }

    @Test
    fun serverFieldErrorsShownUnderFieldsAndClearedOnlyByThatField() {
        openForm()
        fillValid()
        server.createResponse = {
            400 to """{"errors":{"phone":"Номер в чёрном списке","validFrom":"Начало уже прошло","validTo":"Слишком поздно"}}"""
        }
        click(TestIds.FORM_SUBMIT)
        waitFor(TestIds.FORM_ERROR_PHONE)
        compose.onNodeWithTag(TestIds.FORM_ERROR_PHONE).assertTextEquals("Номер в чёрном списке")
        compose.onNodeWithTag(TestIds.FORM_ERROR_VALID_FROM).assertTextEquals("Начало уже прошло")
        compose.onNodeWithTag(TestIds.FORM_ERROR_VALID_TO).assertTextEquals("Слишком поздно")

        // Правка даты не сбрасывает серверные ошибки времени.
        type(TestIds.FORM_DATE, "15.10.2099")
        compose.waitForIdle()
        assertTrue(exists(TestIds.FORM_ERROR_VALID_FROM))
        assertTrue(exists(TestIds.FORM_ERROR_VALID_TO))

        type(TestIds.FORM_TIME_FROM, "10:30")
        waitGone(TestIds.FORM_ERROR_VALID_FROM)
        assertTrue(exists(TestIds.FORM_ERROR_VALID_TO))
        assertTrue(exists(TestIds.FORM_ERROR_PHONE))

        // Серверные ошибки не мешают повторной отправке.
        server.createResponse = null
        click(TestIds.FORM_SUBMIT)
        waitFor(TestIds.REQUESTS_ADD)
        assertEquals(2, server.createdBodies.size)
    }

    @Test
    fun duplicateShowsCommonMessage() {
        openForm()
        fillValid()
        server.createResponse = { 409 to """{"error":"Duplicate request"}""" }
        click(TestIds.FORM_SUBMIT)
        waitFor(TestIds.FORM_ERROR_COMMON)
        compose.onNodeWithTag(TestIds.FORM_ERROR_COMMON)
            .assertTextEquals("На это время уже есть заявка для указанного телефона")
    }

    @Test
    fun serverFailureKeepsInputAndAllowsRetry() {
        openForm()
        fillValid()
        server.createResponse = { 500 to """{"error":"boom"}""" }
        click(TestIds.FORM_SUBMIT)
        waitFor(TestIds.FORM_ERROR_COMMON)
        compose.onNodeWithTag(TestIds.FORM_FULL_NAME).assertTextContains("Сидоров Пётр Ильич", substring = true)
        compose.onNodeWithTag(TestIds.FORM_SUBMIT).assertIsEnabled()

        server.createResponse = null
        click(TestIds.FORM_SUBMIT)
        waitFor(TestIds.REQUESTS_ADD)
    }

    @Test
    fun unauthorizedOnCreateEndsSession() {
        openForm()
        fillValid()
        server.knownCodes = emptySet()
        click(TestIds.FORM_SUBMIT)
        waitFor(TestIds.AUTH_SESSION_EXPIRED)
    }

    @Test
    fun valuesSurviveRotation() {
        openForm()
        fillValid()
        scenario!!.recreate()
        waitFor(TestIds.FORM_FULL_NAME)
        compose.onNodeWithTag(TestIds.FORM_FULL_NAME).assertTextContains("Сидоров Пётр Ильич", substring = true)
        compose.onNodeWithTag(TestIds.FORM_TIME_TO).performScrollTo().assertTextContains("16:00", substring = true)
    }

    @Test
    fun backButtonReturnsToList() {
        openForm()
        click(TestIds.FORM_BACK)
        waitFor(TestIds.REQUESTS_ADD)
    }
}
