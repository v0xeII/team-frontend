package ru.nto.guestmanagement.mobile.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Заявка в упрощённом виде — из неё сервер собирает JSON элемента списка. */
data class FakeRequest(
    val id: Long,
    val fullName: String,
    val validFrom: String,
    val validTo: String,
    val status: String = "PENDING",
) {
    fun toJson() = """{"id":$id,"fullName":"$fullName","validFrom":"$validFrom","validTo":"$validTo","status":"$status"}"""
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

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun count(prefix: String) = requests.count { it.startsWith(prefix) }

    fun start() {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val query = ex.requestURI.rawQuery
        requests += "${ex.requestMethod} ${ex.requestURI.path}" + (query?.let { "?$it" } ?: "")
        val parts = ex.requestURI.path.trim('/').split('/') // [api, {code}, auth | requests, ...]
        val code = parts.getOrNull(1).orEmpty()

        val (status, body) = when {
            parts.size < 3 || parts[0] != "api" -> 404 to """{"error":"Not found"}"""
            !Regex("[A-Za-z0-9]{4}").matches(code) -> 400 to """{"error":"Invalid code format"}"""
            code !in knownCodes -> 401 to """{"error":"Unauthorized"}"""
            parts[2] == "auth" -> {
                authGate?.await(10, TimeUnit.SECONDS)
                200 to """{"name":"Иванов Иван Иванович"}"""
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
            else -> 404 to """{"error":"Request not found"}"""
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
