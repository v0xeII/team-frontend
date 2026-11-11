package ru.nto.guestmanagement.mobile.ui.login

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import ru.nto.guestmanagement.mobile.testing.FakeServer
import ru.nto.guestmanagement.mobile.ui.TestIds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoginScreenTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createEmptyComposeRule()

    private val server = FakeServer()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        server.start()
        // До запуска Activity: контейнер создаётся лениво и берёт адрес и каталог DataStore отсюда.
        Constants.HOST = server.url
        DataStorePaths.dir = tmp.newFolder("datastore")
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.stop()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun exists(tag: String) =
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { exists(tag) }
    private fun waitGone(tag: String) = compose.waitUntil(10_000) { !exists(tag) }

    private fun typeCode(code: String) = compose.onNodeWithTag(TestIds.AUTH_CODE).performTextReplacement(code)

    @Test
    fun submitEnabledOnlyForValidCodeFormat() {
        launch()
        waitFor(TestIds.AUTH_CODE)
        val submit = compose.onNodeWithTag(TestIds.AUTH_SUBMIT)

        submit.assertIsNotEnabled() // пустое поле
        typeCode("abc"); submit.assertIsNotEnabled()
        typeCode("abcd"); submit.assertIsEnabled()
        typeCode("abcde"); submit.assertIsNotEnabled()
        typeCode("ab c"); submit.assertIsNotEnabled()
        typeCode("ab-1"); submit.assertIsNotEnabled()
        typeCode("абвг"); submit.assertIsNotEnabled() // кириллица — не латиница
        typeCode("A1z9"); submit.assertIsEnabled()
    }

    @Test
    fun unknownCodeShowsErrorClearedOnEditAndRetryIsPossible() {
        launch()
        waitFor(TestIds.AUTH_CODE)

        typeCode("Zz99")
        compose.onNodeWithTag(TestIds.AUTH_SUBMIT).performClick()
        waitFor(TestIds.AUTH_ERROR)

        typeCode("Zz98") // редактирование очищает ошибку
        waitGone(TestIds.AUTH_ERROR)

        typeCode("Zz99") // тот же код можно отправить снова
        compose.onNodeWithTag(TestIds.AUTH_SUBMIT).assertIsEnabled().performClick()
        waitFor(TestIds.AUTH_ERROR)
        assertEquals(2, server.count("GET /api/Zz99/auth"))
    }

    @Test
    fun loadingBlocksSecondRequest() {
        val gate = CountDownLatch(1)
        server.authGate = gate
        launch()
        waitFor(TestIds.AUTH_CODE)

        typeCode("Ab12")
        compose.onNodeWithTag(TestIds.AUTH_SUBMIT).performClick()
        waitFor(TestIds.AUTH_LOADING)
        compose.onNodeWithTag(TestIds.AUTH_SUBMIT).assertIsNotEnabled() // кнопка на экране, но недоступна
        compose.onNodeWithTag(TestIds.AUTH_SUBMIT).performClick()

        gate.countDown()
        waitGone(TestIds.AUTH_CODE) // ушли в список
        assertEquals(1, server.count("GET /api/Ab12/auth"))
    }

    @Test
    fun savedCodeSkipsLoginAfterRestartWithoutAuthRequest() {
        launch()
        waitFor(TestIds.AUTH_CODE)
        typeCode("Ab12")
        compose.onNodeWithTag(TestIds.AUTH_SUBMIT).performClick()
        waitGone(TestIds.AUTH_CODE)
        assertEquals(1, server.count("GET /api/Ab12/auth"))

        // «Перезапуск»: закрываем Activity и открываем заново. Стек навигации начинается с экрана входа,
        // а LoginScreen должен сам увидеть сохранённый код и уйти в список.
        scenario!!.close()
        launch()
        Thread.sleep(500) // даём DataStore прочитать файл
        compose.waitForIdle()

        assertFalse(exists(TestIds.AUTH_CODE))
        assertEquals(1, server.count("GET /api/Ab12/auth")) // повторного /auth нет
        assertTrue(server.requests.none { it.contains("/auth") && !it.contains("Ab12") })
    }
}
