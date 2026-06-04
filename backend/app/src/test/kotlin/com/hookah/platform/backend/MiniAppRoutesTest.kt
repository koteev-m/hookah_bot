package com.hookah.platform.backend

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.io.File
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiniAppRoutesTest {
    @Test
    fun `miniapp root proxies to configured dev server`() =
        withMiniAppDevServer { devServerUrl ->
            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "db.jdbcUrl" to "",
                            "miniapp.devServerUrl" to devServerUrl,
                        )
                }
                application { module() }

                val response = client.get("/miniapp/")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("Vite Mini App /miniapp/index.html"))
            }
        }

    @Test
    fun `miniapp path without trailing slash redirects to root`() =
        withMiniAppDevServer { devServerUrl ->
            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "db.jdbcUrl" to "",
                            "miniapp.devServerUrl" to devServerUrl,
                        )
                }
                application { module() }

                val noRedirectClient =
                    createClient {
                        followRedirects = false
                    }
                val response = noRedirectClient.get("/miniapp")
                assertEquals(HttpStatusCode.Found, response.status)
                assertEquals("/miniapp/", response.headers[HttpHeaders.Location])
            }
        }

    @Test
    fun `miniapp dev proxy keeps index and vite asset paths`() =
        withMiniAppDevServer { devServerUrl ->
            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "db.jdbcUrl" to "",
                            "miniapp.devServerUrl" to devServerUrl,
                        )
                }
                application { module() }

                val index = client.get("/miniapp/index.html")
                assertEquals(HttpStatusCode.OK, index.status)
                assertTrue(index.bodyAsText().contains("Vite Mini App /miniapp/index.html"))

                val viteClient = client.get("/miniapp/@vite/client")
                assertEquals(HttpStatusCode.OK, viteClient.status)
                assertTrue(viteClient.bodyAsText().contains("Vite Mini App /miniapp/@vite/client"))

                val source = client.get("/miniapp/src/main.ts")
                assertEquals(HttpStatusCode.OK, source.status)
                assertTrue(source.bodyAsText().contains("Vite Mini App /miniapp/src/main.ts"))
            }
        }

    @Test
    fun `miniapp root serves static index when static dir is configured`() {
        val staticDir = createTempDirectory("miniapp-static").toFile()
        try {
            File(staticDir, "index.html").writeText("<!doctype html><title>Static Mini App</title>")
            val assetsDir = File(staticDir, "assets").apply { mkdirs() }
            File(assetsDir, "app.js").writeText("console.log('static miniapp asset')")

            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "db.jdbcUrl" to "",
                            "miniapp.staticDir" to staticDir.absolutePath,
                        )
                }
                application { module() }

                val response = client.get("/miniapp/")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("Static Mini App"))

                val index = client.get("/miniapp/index.html")
                assertEquals(HttpStatusCode.OK, index.status)
                assertTrue(index.bodyAsText().contains("Static Mini App"))

                val asset = client.get("/miniapp/assets/app.js")
                assertEquals(HttpStatusCode.OK, asset.status)
                assertTrue(asset.bodyAsText().contains("static miniapp asset"))
            }
        } finally {
            staticDir.deleteRecursively()
        }
    }

    private fun withMiniAppDevServer(block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.respondHtml("<!doctype html><title>Vite Mini App ${exchange.requestURI.path}</title>")
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respondHtml(body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { output ->
            output.write(bytes)
        }
    }
}
