package ru.nto.guestmanagement.mobile.ui.form

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import ru.nto.guestmanagement.mobile.R
import ru.nto.guestmanagement.mobile.data.CreateRequestBody
import ru.nto.guestmanagement.mobile.data.toApi
import ru.nto.guestmanagement.mobile.ui.TestIds

/**
 * Поля формы. Одно перечисление описывает и поле ввода, и место показа ошибки:
 * VALID_FROM — это поле «время начала» и ошибки `validFrom`, VALID_TO — «время окончания» и `validTo`.
 * [apiKey] — ключ в `400 {"errors": {...}}`; у даты своего серверного ключа нет.
 */
enum class FormField(val apiKey: String?, val inputTag: String, val errorTag: String) {
    FULL_NAME("fullName", TestIds.FORM_FULL_NAME, TestIds.FORM_ERROR_FULL_NAME),
    PHONE("phone", TestIds.FORM_PHONE, TestIds.FORM_ERROR_PHONE),
    ORGANIZATION("organization", TestIds.FORM_ORGANIZATION, TestIds.FORM_ERROR_ORGANIZATION),
    PURPOSE("purpose", TestIds.FORM_PURPOSE, TestIds.FORM_ERROR_PURPOSE),
    DATE(null, TestIds.FORM_DATE, TestIds.FORM_ERROR_DATE),
    VALID_FROM("validFrom", TestIds.FORM_TIME_FROM, TestIds.FORM_ERROR_VALID_FROM),
    VALID_TO("validTo", TestIds.FORM_TIME_TO, TestIds.FORM_ERROR_VALID_TO);

    companion object {
        fun byApiKey(key: String): FormField? = entries.firstOrNull { it.apiKey == key }
    }
}

/** Сырые значения полей — ровно то, что ввёл пользователь. */
data class FormInput(
    val fullName: String = "",
    val phone: String = "",
    val organization: String = "",
    val purpose: String = "",
    val date: String = "",
    val timeFrom: String = "",
    val timeTo: String = "",
) {
    operator fun get(field: FormField): String = when (field) {
        FormField.FULL_NAME -> fullName
        FormField.PHONE -> phone
        FormField.ORGANIZATION -> organization
        FormField.PURPOSE -> purpose
        FormField.DATE -> date
        FormField.VALID_FROM -> timeFrom
        FormField.VALID_TO -> timeTo
    }

    fun with(field: FormField, value: String): FormInput = when (field) {
        FormField.FULL_NAME -> copy(fullName = value)
        FormField.PHONE -> copy(phone = value)
        FormField.ORGANIZATION -> copy(organization = value)
        FormField.PURPOSE -> copy(purpose = value)
        FormField.DATE -> copy(date = value)
        FormField.VALID_FROM -> copy(timeFrom = value)
        FormField.VALID_TO -> copy(timeTo = value)
    }
}

/** Результат проверки: ошибки полей (ресурсы строк) и, если ошибок нет, готовое тело запроса. */
data class ValidationResult(
    val errors: Map<FormField, Int>,
    val body: CreateRequestBody?,
)

// STRICT: несуществующие даты (31.02, 29.02 невисокосного года) не «подправляются», а дают ошибку.
// uuuu вместо yyyy: в STRICT-режиме yyyy требует эру и ничего бы не разобрал.
private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

// Формат проверяем ещё и регуляркой: ровно две цифры дня/месяца/часа, четыре цифры года, только ASCII-цифры.
// Так «9:00», «14.10.26» и «+2026» отсекаются ещё до разбора.
private val DATE_SHAPE = Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")
private val TIME_SHAPE = Regex("[0-9]{2}:[0-9]{2}")
private val PHONE_SHAPE = Regex("\\+7[0-9]{10}")

fun parseFormDate(text: String): LocalDate? {
    if (!DATE_SHAPE.matches(text)) return null
    return try {
        LocalDate.parse(text, DATE_FORMAT)
    } catch (e: DateTimeParseException) {
        null
    }
}

fun parseFormTime(text: String): LocalTime? {
    if (!TIME_SHAPE.matches(text)) return null
    return try {
        LocalTime.parse(text, TIME_FORMAT) // 24:00 и 23:60 — ошибка
    } catch (e: DateTimeParseException) {
        null
    }
}

/**
 * Клиентская проверка по правилам п. 3.4 — тем же, что на сервере.
 * [now] — текущее время из `container.timeProvider` (никогда не LocalDateTime.now()).
 */
fun validateForm(input: FormInput, now: LocalDateTime): ValidationResult {
    val errors = mutableMapOf<FormField, Int>()

    // Строки обрезаем ДО проверки длины — и отправляем тоже обрезанные.
    val fullName = input.fullName.trim()
    val phone = input.phone.trim()
    val organization = input.organization.trim()
    val purpose = input.purpose.trim()

    if (fullName.length !in 5..150) errors[FormField.FULL_NAME] = R.string.form_error_full_name
    if (!PHONE_SHAPE.matches(phone)) errors[FormField.PHONE] = R.string.form_error_phone
    if (organization.length > 150) errors[FormField.ORGANIZATION] = R.string.form_error_organization
    if (purpose.length !in 5..200) errors[FormField.PURPOSE] = R.string.form_error_purpose

    val date = parseFormDate(input.date.trim())
    val from = parseFormTime(input.timeFrom.trim())
    val to = parseFormTime(input.timeTo.trim())

    if (date == null) errors[FormField.DATE] = R.string.form_error_date
    if (from == null) errors[FormField.VALID_FROM] = R.string.form_error_time_from_format
    if (to == null) errors[FormField.VALID_TO] = R.string.form_error_time_to_format

    // Сравниваем только успешно разобранные значения.
    if (date != null && from != null && date.atTime(from).isBefore(now)) {
        // «Ровно сейчас» допустимо: ошибка только если начало строго раньше текущего момента.
        errors[FormField.VALID_FROM] = R.string.form_error_time_from_past
    }
    if (from != null && to != null && !to.isAfter(from)) {
        // Дата одна на оба времени, поэтому «тот же день» выполняется сам собой; остаётся «позже начала».
        errors[FormField.VALID_TO] = R.string.form_error_time_to_order
    }

    if (errors.isNotEmpty() || date == null || from == null || to == null) {
        return ValidationResult(errors, null)
    }
    return ValidationResult(
        errors = emptyMap(),
        body = CreateRequestBody(
            fullName = fullName,
            phone = phone,
            organization = organization.ifEmpty { null }, // пустая организация уходит как null
            purpose = purpose,
            validFrom = date.atTime(from).toApi(), // с секундами: 2026-10-14T10:00:00
            validTo = date.atTime(to).toApi(),
        ),
    )
}
