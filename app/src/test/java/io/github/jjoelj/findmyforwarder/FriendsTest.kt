package io.github.jjoelj.findmyforwarder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
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
    fun refreshesFriendsLocationsWhenNoPreviousTrigger() {
        assertEquals(true, shouldRefreshFriendsLocations(nowMillis = 1_000, lastRefreshTriggeredAtMillis = 0))
    }

    @Test
    fun refreshesFriendsLocationsAfterInterval() {
        assertEquals(
            true,
            shouldRefreshFriendsLocations(
                nowMillis = 61_000,
                lastRefreshTriggeredAtMillis = 1_000,
                intervalMillis = 60_000,
            )
        )
    }

    @Test
    fun skipsFriendsLocationsRefreshInsideInterval() {
        assertEquals(
            false,
            shouldRefreshFriendsLocations(
                nowMillis = 60_999,
                lastRefreshTriggeredAtMillis = 1_000,
                intervalMillis = 60_000,
            )
        )
    }

    @Test
    fun ordersFriendHandlesToRefreshOldestFirst() {
        val fresh = Friend("fresh@example.com", 1.0, 2.0, null, null, 200, valid = true)
        val stale = Friend("+12025550143", 3.0, 4.0, null, null, 100, valid = true)
        assertEquals(listOf("+12025550143", "fresh@example.com"), friendHandlesToRefresh(listOf(fresh, stale)))
    }

    @Test
    fun choosesNoFriendHandlesWhenListIsEmpty() {
        assertEquals(emptyList<String>(), friendHandlesToRefresh(emptyList()))
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
    fun parsesFriendRefreshTimes() {
        assertEquals(
            mapOf("a@b.com" to 100L),
            parseFriendRefreshTimes("""{"a@b.com":100}""")
        )
    }

    @Test
    fun detectsRecentlyRefreshedFriend() {
        assertEquals(
            true,
            friendWasRefreshedRecently(
                handle = " A@B.com ",
                refreshTimes = mapOf("a@b.com" to 1_000L),
                nowMillis = 120_999L,
                intervalMillis = 120_000L,
            )
        )
        assertEquals(
            false,
            friendWasRefreshedRecently(
                handle = "a@b.com",
                refreshTimes = mapOf("a@b.com" to 1_000L),
                nowMillis = 121_001L,
                intervalMillis = 120_000L,
            )
        )
    }

    @Test
    fun parsesSingleFriendRefreshResponse() {
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
    fun mergesRefreshedFriendPreservingContactMetadata() {
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
            Friend("a@b.com", 48.0, -123.0, "New", "Long New", 200, valid = true)
        )
        val merged = mergeRefreshedFriends(current, refreshed)
        assertEquals(1, merged.size)
        assertEquals(48.0, merged[0].lat!!, 1e-9)
        assertEquals("Alice", merged[0].name)
        assertEquals("content://photo", merged[0].photoUri)
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
}
