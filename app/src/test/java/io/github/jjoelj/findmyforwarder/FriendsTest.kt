package io.github.jjoelj.findmyforwarder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.osmdroid.util.GeoPoint
import java.io.IOException

class FriendsTest {
    @Test
    fun normalizesHandles() {
        assertEquals("+12025550143", normalizeHandle("+1202555-0143"))
        assertEquals("2025550143", normalizeHandle("202 555 0143"))
        assertEquals("name@icloud.com", normalizeHandle(" Name@iCloud.com "))
    }

    @Test
    fun parsesFriends() {
        val body = """
            {"ok":true,"friends":[
              {"handle":"a@b.com","lat":47.6,"lon":-122.3,"accuracy":0,
               "address":"Seattle","fullAddress":"1 Main St\nSeattle","timestamp":1783475925,"valid":true},
              {"handle":"+12025550143","lat":null,"lon":null,"accuracy":null,
               "address":null,"fullAddress":null,"timestamp":1783475927,"valid":false}
            ]}
        """.trimIndent()
        val friends = parseFriends(body)
        assertEquals(2, friends.size)
        assertEquals(47.6, friends[0].lat!!, 1e-9)
        assertEquals(true, friends[0].hasLocation)
        assertNull(friends[1].lat)
        assertEquals(false, friends[1].hasLocation)
    }

    @Test
    fun errorBodyThrows() {
        assertThrows(IOException::class.java) {
            parseFriends("""{"ok":false,"message":"boom"}""")
        }
    }

    @Test
    fun dedupesByHandleKeepingBestFix() {
        val stale = Friend("+1 (202) 555-0143", null, null, null, null, 100, valid = false)
        val fresh = Friend("+12025550143", 1.0, 2.0, null, null, 50, valid = true)
        val deduped = dedupeFriends(listOf(stale, fresh))
        assertEquals(1, deduped.size)
        assertEquals(true, deduped[0].hasLocation)
    }

    @Test
    fun parsesSingleFriendResponse() {
        val friends = parseFriends(
            """
                {"ok":true,"friends":[
                  {"handle":"a@b.com","lat":48.0,"lon":-123.0,
                   "address":"Victoria","fullAddress":"1 Harbour St\nVictoria",
                   "timestamp":1783475999,"valid":true}
                ]}
            """.trimIndent()
        )
        assertEquals(1, friends.size)
        assertEquals("a@b.com", friends[0].handle)
        assertEquals(48.0, friends[0].lat!!, 1e-9)
    }

    @Test
    fun parsesSingleHandleFriendsResponse() {
        val friends = parseFriends(
            """
                {"friends":[{"handle":"+12245008526","address":"Lake Barrington, IL",
                "lon":-88.167312466902573,"valid":true,"lat":42.240679031425614,
                "accuracy":0,"timestamp":1783492415,
                "fullAddress":"25949 N Oak Hill Rd\nLake Barrington, IL  60010\nUnited States"}],
                "loaded":true,"ok":true}
            """.trimIndent()
        )
        assertEquals(1, friends.size)
        assertEquals("+12245008526", friends[0].handle)
        assertEquals("Lake Barrington, IL", friends[0].address)
        assertEquals(42.240679031425614, friends[0].lat!!, 1e-9)
        assertEquals(-88.167312466902573, friends[0].lon!!, 1e-9)
        assertEquals(true, friends[0].hasLocation)
    }

    @Test
    fun parsesRefreshHandleNotFoundFailure() {
        assertEquals(
            "friend handle not found: missing@example.com",
            refreshFailureMessage(
                """{"ok":false,"message":"friend handle not found","handle":"missing@example.com"}"""
            )
        )
    }

    @Test
    fun ignoresSuccessfulRefreshResponse() {
        assertNull(refreshFailureMessage("""{"ok":true}"""))
    }

    @Test
    fun dedupesPreservingContactMetadata() {
        val current = listOf(
            Friend(
                handle = "a@b.com",
                lat = 47.0,
                lon = -122.0,
                address = "Old",
                fullAddress = null,
                timestamp = 100,
                valid = true,
                name = "Alice",
                photoUri = "content://photo",
            )
        )
        val refreshed = listOf(
            Friend("a@b.com", 48.0, -123.0, "New", "Long New", 200, valid = true, name = "Alice")
        )
        val merged = dedupeFriends(current + refreshed)
        assertEquals(1, merged.size)
        assertEquals(48.0, merged[0].lat!!, 1e-9)
        assertEquals("Alice", merged[0].name)
    }

    @Test
    fun parsesBatteryStatus() {
        val battery = parseBatteryStatus(
            """
                {"ok":true,"batteryPercent":83,"batteryLevel":0.83,
                 "charging":true,"externalPower":true}
            """.trimIndent()
        )
        assertEquals(83, battery.percent)
        assertEquals(true, battery.charging)
        assertEquals(true, battery.externalPower)
    }

    @Test
    fun densestFitGroupPicksTheBiggerCluster() {
        val seattleish = listOf(
            GeoPoint(47.6, -122.3), GeoPoint(47.7, -122.2), GeoPoint(47.5, -122.4)
        )
        val outlier = GeoPoint(-33.9, 151.2) // Sydney: can't share a 60° window
        assertEquals(seattleish, densestFitGroup(seattleish + outlier))
        // Everyone already fits: keep everyone.
        assertEquals(seattleish, densestFitGroup(seattleish))
    }

    @Test
    fun clustersOverlappingPinsOnly() {
        val positions = listOf(0 to 0, 10 to 10, 500 to 500)
        val clusters = clusterByProximity(positions, { it.first }, { it.second }, { 96 })
        assertEquals(2, clusters.size)
        assertEquals(listOf(0 to 0, 10 to 10), clusters[0])
        assertEquals(listOf(500 to 500), clusters[1])
    }

    @Test
    fun clusterFacesStayInsideThePin() {
        val faces = layoutClusterFaces(
            pinSize = 96,
            memberSizes = intArrayOf(96, 96, 96),
            rawX = intArrayOf(0, 40, 400),
            rawY = intArrayOf(0, 40, 0),
        )
        assertEquals(3, faces.size)
        faces.forEach { face ->
            val reach = Math.hypot(face.x, face.y) + face.size / 2.0
            assert(reach <= 48.0 + 1e-6) { "face escapes the pin: reach=$reach" }
        }
    }
}
