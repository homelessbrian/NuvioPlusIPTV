package com.nuvio.tv.livetv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistAddressInputTest {
    @Test
    fun `an empty field shows a prefix but saves no address`() {
        for (value in listOf("", " ", "\t\n")) {
            assertEquals("http://", playlistAddressPrefix(value))
            assertEquals("", completePlaylistAddress(value))
        }
    }

    @Test
    fun `typed hosts paths and ports receive http without changing their contents`() {
        for (address in listOf(
            "example.test",
            "example.test:8080",
            "example.test/Live/playlist.m3u8",
            "localhost:8080/live.m3u",
            "192.0.2.12:8080/list.m3u",
            "[2001:db8::1]:8080/list.m3u",
        )) {
            assertEquals(address, "http://", playlistAddressPrefix(address))
            assertEquals(address, "http://$address", completePlaylistAddress(address))
        }
    }

    @Test
    fun `pasted complete web URLs retain their scheme case path and signed query`() {
        for (address in listOf(
            "http://example.test/list.m3u",
            "https://example.test/Live/Playlist.M3U8",
            "HTTPS://EXAMPLE.test:8443/Keep/Case?token=A%2FB%2bC&name=a+b#Section",
            "HtTp://example.test/get.php?username=Test&password=x%26y%3Dz",
        )) {
            assertEquals(address, "", playlistAddressPrefix(address))
            assertEquals(address, address, completePlaylistAddress(address))
        }
    }

    @Test
    fun `a URL inside a query does not replace the outer address scheme`() {
        val bare = "example.test/list.m3u?redirect=https://nested.test/Keep?x=A%2FB"
        assertEquals("http://", playlistAddressPrefix(bare))
        assertEquals("http://$bare", completePlaylistAddress(bare))
        val complete = "https://$bare"
        assertEquals("", playlistAddressPrefix(complete))
        assertEquals(complete, completePlaylistAddress(complete))
    }

    @Test
    fun `scheme relative URLs get only the missing http scheme`() {
        for (address in listOf("//example.test/list.m3u", "//[2001:db8::1]:8080/Keep?x=%2F")) {
            assertEquals("", playlistAddressPrefix(address))
            assertEquals("http:$address", completePlaylistAddress(address))
        }
    }

    @Test
    fun `other explicit schemes remain available to existing source validation`() {
        for (address in listOf(
            "ftp://example.test/list.m3u",
            "content://media/external/list.m3u",
            "file:///storage/emulated/0/list.m3u",
            "custom+iptv.v2-test://example.test/List?key=A%2FB",
        )) {
            assertEquals(address, "", playlistAddressPrefix(address))
            assertEquals(address, address, completePlaylistAddress(address))
        }
    }

    @Test
    fun `only surrounding whitespace is removed`() {
        val complete = "https://example.test/Keep/Path?name=a+b&token=A%2FB"
        assertEquals("", playlistAddressPrefix(" \t$complete\n"))
        assertEquals(complete, completePlaylistAddress(" \t$complete\n"))
        val bare = "example.test/Keep/Path?name=a+b&token=A%2FB"
        assertEquals("http://", playlistAddressPrefix(" $bare "))
        assertEquals("http://$bare", completePlaylistAddress(" $bare "))
    }
}
