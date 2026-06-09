package com.visorcraft.radioshed.source

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SubsonicClientImplTest {

    private val server = SubsonicServer("http://localhost:4000", "user", "pass")

    @Test
    fun pingReturnsResultOnSuccess() = runTest {
        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath == "/" || request.url.encodedPath == "") {
                respond(
                    content = """{"version":"1.16.1","product":"Navidrome"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                error("Unexpected request: ${request.url}")
            }
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json()
            }
        }

        val client = SubsonicClientImpl(httpClient)
        
        try {
            val result = client.ping(server)
            assertNotNull(result, "Result should not be null")
            assertEquals("1.16.1", result.version)
            assertEquals("Navidrome", result.product)
        } catch (e: Exception) {
            throw AssertionError("Ping failed with exception: ${e.message}", e)
        }
    }

    @Test
    fun getArtistsReturnsListOnSuccess() = runTest {
        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath == "/" || request.url.encodedPath == "") {
                respond(
                    content = """{"artists":[{"id":"a1","name":"Artist 1","albumCount":5},{"id":"a2","name":"Artist 2","albumCount":3}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                error("Unexpected request: ${request.url}")
            }
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json()
            }
        }

        val client = SubsonicClientImpl(httpClient)
        val artists = client.getArtists(server)

        assertEquals(2, artists.size)
        assertEquals("Artist 1", artists[0].name)
        assertEquals("a1", artists[0].id)
    }

    @Test
    fun getAlbumsForArtistReturnsListOnSuccess() = runTest {
        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath == "/" || request.url.encodedPath == "") {
                respond(
                    content = """{"albums":[{"id":"al1","name":"Album 1","artist":"Artist 1","artistId":"a1"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                error("Unexpected request: ${request.url}")
            }
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json()
            }
        }

        val client = SubsonicClientImpl(httpClient)
        val albums = client.getAlbumsForArtist(server, "a1")

        assertEquals(1, albums.size)
        assertEquals("Album 1", albums[0].name)
        assertEquals("a1", albums[0].artistId)
    }

    @Test
    fun getSongsReturnsListOnSuccess() = runTest {
        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath == "/" || request.url.encodedPath == "") {
                respond(
                    content = """{"songs":[{"id":"s1","title":"Song 1","artist":"Artist 1","album":"Album 1"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                error("Unexpected request: ${request.url}")
            }
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json()
            }
        }

        val client = SubsonicClientImpl(httpClient)
        val songs = client.getSongs(server, "al1")

        assertEquals(1, songs.size)
        assertEquals("Song 1", songs[0].title)
        assertEquals("Artist 1", songs[0].artist)
    }

    @Test
    fun streamUrlReturnsCorrectFormat() {
        val httpClient = HttpClient(MockEngine { respondOk() }) // Dummy client
        val client = SubsonicClientImpl(httpClient)
        val url = client.streamUrl(server, "s1")
        assert(url.contains("getSong?id=s1"))
    }

    @Test
    fun coverArtUrlReturnsCorrectFormat() {
        val httpClient = HttpClient(MockEngine { respondOk() }) // Dummy client
        val client = SubsonicClientImpl(httpClient)
        val url = client.coverArtUrl(server, "ca1", 300)
        assert(url.contains("getCover?id=ca1&size=300"))
    }

    @Test
    fun pingReturnsNullOnFailure() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "Not Found",
                status = HttpStatusCode.NotFound
            )
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json()
            }
        }

        val client = SubsonicClientImpl(httpClient)
        val result = client.ping(server)

        kotlin.test.assertNull(result)
    }
}
