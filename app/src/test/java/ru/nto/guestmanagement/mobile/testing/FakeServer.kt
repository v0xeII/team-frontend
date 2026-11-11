package ru.nto.guestmanagement.mobile.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Заявка на фейковом сервере: из неё собираются и элемент списка, и объект деталей. */
data class FakeRequest(
    val id: Long,
    val fullName: String,
    val validFrom: String,
    val validTo: String,
    val status: String = "PENDING",
    val phone: String = "+79991234567",
    val organization: String? = "ООО НТО",
    val purpose: String = "Встреча с сотрудником",
    val comment: String? = null,
    val createdAt: String = "2026-10-10T12:31:05",
    val updatedAt: String = "2026-10-10T12:31:05",
) {
    fun toJson() = """{"id":$id,"fullName":"$fullName","validFrom":"$validFrom","validTo":"$validTo","status":"$status"}"""

    fun toDetailsJson(): String = JSONObject()
        .put("id", id)
        .put("fullName", fullName)
        .put("phone", phone)
        .put("organization", organization ?: JSONObject.NULL)
        .put("purpose", purpose)
        .put("validFrom", validFrom)
        .put("validTo", validTo)
        .put("status", status)
        .put("comment", comment ?: JSONObject.NULL)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .toString()
}

/**
 * Минимальный сервер для локальных UI-тестов на JDK-шном HttpServer (новые зависимости добавлять нельзя).
 * Отвечает на пользовательские маршруты по упрощённым правилам; тест может менять поведение через поля.
 */
class FakeServer {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Все полученные запросы в виде "GET /api/Ab12/requests?status=PENDING". */
    val requests = CopyOnWriteArrayList<String>()

    /** Известные коды сотрудников. Убрать код из набора — следующий запрос получит 401. */
    @Volatile var knownCodes: Set<String> = setOf("Ab12")

    /** Если задан — ответ на /auth ждёт, пока тест не вызовет countDown() (проверка индикатора загрузки). */
    @Volatile var authGate: CountDownLatch? = null

    /** Заявки сотрудника; сервер фильтрует их по параметру status. */
    @Volatile var items: List<FakeRequest> = emptyList()

    /** Если не 200 — список отвечает этим кодом (имитация сбоя сервера). */
    @Volatile var listFailureCode: Int = 200

    /** Если задан — ответ на список ждёт countDown(). */
    @Volatile var listGate: CountDownLatch? = null

    /** Тела всех POST-запросов создания заявки (сырой JSON). */
    val createdBodies = CopyOnWriteArrayList<String>()

    /** Подменить ответ на создание: по телу запроса вернуть (код, JSON). `null` — создать заявку как обычно. */
    @Volatile var createResponse: ((String) -> Pair<Int, String>)? = null

    /** Если задан — ответ на создание ждёт countDown(). */
    @Volatile var createGate: CountDownLatch? = null

    /** Если не 200 — детали отвечают этим кодом. */
    @Volatile var detailsFailureCode: Int = 200

    /** Если задан — ответ на детали ждёт countDown(). */
    @Volatile var detailsGate: CountDownLatch? = null

    /** Если не 200 — отмена отвечает этим кодом (имитация сбоя), иначе работает по правилам переходов. */
    @Volatile var cancelFailureCode: Int = 200

    private var nextId = 100L

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun count(prefix: String) = requests.count { it.startsWith(prefix) }

    fun start() {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    fun stop() = server.stop(0)

    /** Изменить заявку «за спиной» приложения — например, решение охраны. */
    @Synchronized
    fun update(id: Long, change: (FakeRequest) -> FakeRequest) {
        items = items.map { if (it.id == id) change(it) else it }
    }

    /** Обычное создание: заявка в статусе PENDING добавляется в список, ответ — объект деталей. */
    @Synchronized
    private fun createPending(body: String): Pair<Int, String> {
        val json = JSONObject(body)
        val created = FakeRequest(
            id = nextId++,
            fullName = json.getString("fullName"),
            validFrom = json.getString("validFrom"),
            validTo = json.getString("validTo"),
            phone = json.getString("phone"),
            organization = if (json.isNull("organization")) null else json.getString("organization"),
            purpose = json.getString("purpose"),
        )
        items = items + created
        return 201 to created.toDetailsJson()
    }

    @Synchronized
    private fun cancel(id: Long): Pair<Int, String> {
        val found = items.firstOrNull { it.id == id } ?: return 404 to """{"error":"Request not found"}"""
        if (found.status != "PENDING") return 409 to """{"error":"Invalid status transition"}"""
        update(id) { it.copy(status = "CANCELLED", updatedAt = "2026-10-11T09:15:00") }
        return 200 to items.first { it.id == id }.toDetailsJson()
    }

    private fun handle(ex: HttpExchange) {
        val query = ex.requestURI.rawQuery
        requests += "${ex.requestMethod} ${ex.requestURI.path}" + (query?.let { "?$it" } ?: "")
        // [api, {code}, auth] | [api, {code}, requests] | [api, {code}, requests, {id}] | [..., {id}, cancel]
        val parts = ex.requestURI.path.trim('/').split('/')
        val code = parts.getOrNull(1).orEmpty()
        val id = parts.getOrNull(3)?.toLongOrNull()

        val (status, body) = when {
            parts.size < 3 || parts[0] != "api" -> 404 to """{"error":"Not found"}"""
            !Regex("[A-Za-z0-9]{4}").matches(code) -> 400 to """{"error":"Invalid code format"}"""
            code !in knownCodes -> 401 to """{"error":"Unauthorized"}"""
            parts[2] == "auth" -> {
                authGate?.await(10, TimeUnit.SECONDS)
                200 to """{"name":"Иванов Иван Иванович"}"""
            }
            parts[2] == "requests" && parts.size == 3 && ex.requestMethod == "POST" -> {
                val requestBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                createdBodies += requestBody
                createGate?.await(10, TimeUnit.SECONDS)
                createResponse?.invoke(requestBody) ?: createPending(requestBody)
            }
            parts[2] == "requests" && parts.size == 3 -> {
                listGate?.await(10, TimeUnit.SECONDS)
                if (listFailureCode != 200) {
                    listFailureCode to """{"error":"Internal error"}"""
                } else {
                    val filter = query?.removePrefix("status=")
                    val found = items.filter { filter == null || it.status == filter }
                    200 to found.joinToString(",", "[", "]") { it.toJson() }
                }
            }
            parts[2] == "requests" && parts.size == 4 && id != null && ex.requestMethod == "GET" -> {
                detailsGate?.await(10, TimeUnit.SECONDS)
                val found = items.firstOrNull { it.id == id }
                when {
                    detailsFailureCode != 200 -> detailsFailureCode to """{"error":"Failure"}"""
                    found == null -> 404 to """{"error":"Request not found"}"""
                    else -> 200 to found.toDetailsJson()
                }
            }
            parts.size == 5 && parts[4] == "cancel" && id != null && ex.requestMethod == "POST" -> {
                if (cancelFailureCode != 200) cancelFailureCode to """{"error":"Failure"}""" else cancel(id)
            }
            else -> 404 to """{"error":"Request not found"}"""
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
