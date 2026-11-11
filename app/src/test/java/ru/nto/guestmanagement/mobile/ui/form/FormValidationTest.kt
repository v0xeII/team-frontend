package ru.nto.guestmanagement.mobile.ui.form

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.nto.guestmanagement.mobile.R

class FormValidationTest {
    private val now = LocalDateTime.of(2026, 10, 14, 9, 30, 0)

    private val valid = FormInput(
        fullName = "Сидоров Пётр Ильич",
        phone = "+79991234567",
        organization = "ООО НТО",
        purpose = "Встреча с сотрудником",
        date = "14.10.2026",
        timeFrom = "10:00",
        timeTo = "16:00",
    )

    private fun errors(input: FormInput, at: LocalDateTime = now) = validateForm(input, at).errors

    @Test
    fun validInputBuildsNormalizedBody() {
        val result = validateForm(
            valid.copy(fullName = "  Сидоров Пётр Ильич ", phone = " +79991234567 ", organization = "   "),
            now,
        )
        assertTrue(result.errors.isEmpty())
        val body = result.body!!
        assertEquals("Сидоров Пётр Ильич", body.fullName)
        assertEquals("+79991234567", body.phone)
        assertNull(body.organization)
        assertEquals("2026-10-14T10:00:00", body.validFrom)
        assertEquals("2026-10-14T16:00:00", body.validTo)
    }

    @Test
    fun emptyFormReportsEveryRequiredField() {
        val e = errors(FormInput())
        assertEquals(
            setOf(
                FormField.FULL_NAME, FormField.PHONE, FormField.PURPOSE,
                FormField.DATE, FormField.VALID_FROM, FormField.VALID_TO,
            ),
            e.keys,
        )
    }

    @Test
    fun stringLengthBoundariesAfterTrim() {
        assertFalse(FormField.FULL_NAME in errors(valid.copy(fullName = "  Ивано  "))) // 5 символов
        assertTrue(FormField.FULL_NAME in errors(valid.copy(fullName = "  Иван  "))) // 4
        assertFalse(FormField.FULL_NAME in errors(valid.copy(fullName = "а".repeat(150))))
        assertTrue(FormField.FULL_NAME in errors(valid.copy(fullName = "а".repeat(151))))
        assertFalse(FormField.ORGANIZATION in errors(valid.copy(organization = "о".repeat(150))))
        assertTrue(FormField.ORGANIZATION in errors(valid.copy(organization = "о".repeat(151))))
        assertFalse(FormField.PURPOSE in errors(valid.copy(purpose = "ц".repeat(200))))
        assertTrue(FormField.PURPOSE in errors(valid.copy(purpose = "ц".repeat(201))))
        assertTrue(FormField.PURPOSE in errors(valid.copy(purpose = "цель")))
    }

    @Test
    fun phoneFormat() {
        assertFalse(FormField.PHONE in errors(valid.copy(phone = "  +79991234567  ")))
        listOf("89991234567", "+7 999 123 45 67", "+7-999-123-45-67", "+7999123456", "+799912345678", "+7999123456٧")
            .forEach { assertTrue(it, FormField.PHONE in errors(valid.copy(phone = it))) }
    }

    @Test
    fun nonExistentOrMalformedDates() {
        listOf("31.02.2026", "29.02.2027", "14.10.26", "1.10.2026", "14/10/2026", "32.01.2026", "00.01.2026")
            .forEach { assertEquals(it, R.string.form_error_date, errors(valid.copy(date = it))[FormField.DATE]) }
        assertNotNull(parseFormDate("29.02.2028")) // високосный год
    }

    @Test
    fun malformedTimes() {
        listOf("24:00", "9:00", "10:60", "1000", "10.00").forEach {
            assertEquals(it, R.string.form_error_time_from_format, errors(valid.copy(timeFrom = it))[FormField.VALID_FROM])
            assertEquals(it, R.string.form_error_time_to_format, errors(valid.copy(timeTo = it))[FormField.VALID_TO])
        }
        assertNotNull(parseFormTime("00:00"))
        assertNotNull(parseFormTime("23:59"))
    }

    @Test
    fun startNotBeforeNow_exactNowAllowed() {
        val at = LocalDateTime.of(2026, 10, 14, 10, 0, 0)
        assertFalse(FormField.VALID_FROM in errors(valid, at)) // ровно сейчас — можно
        val later = LocalDateTime.of(2026, 10, 14, 10, 0, 1)
        assertEquals(R.string.form_error_time_from_past, errors(valid, later)[FormField.VALID_FROM])
        assertEquals(
            R.string.form_error_time_from_past,
            errors(valid.copy(date = "13.10.2026"))[FormField.VALID_FROM],
        )
    }

    @Test
    fun endMustBeAfterStart() {
        assertEquals(R.string.form_error_time_to_order, errors(valid.copy(timeTo = "10:00"))[FormField.VALID_TO])
        assertEquals(R.string.form_error_time_to_order, errors(valid.copy(timeTo = "09:00"))[FormField.VALID_TO])
        assertFalse(FormField.VALID_TO in errors(valid.copy(timeTo = "10:01")))
    }

    @Test
    fun invalidDateStillChecksTimeOrderButNotPast() {
        val e = errors(valid.copy(date = "31.02.2026", timeFrom = "12:00", timeTo = "11:00"))
        assertEquals(R.string.form_error_date, e[FormField.DATE])
        assertFalse(FormField.VALID_FROM in e)
        assertEquals(R.string.form_error_time_to_order, e[FormField.VALID_TO])
    }
}
