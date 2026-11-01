package com.kivan.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForumPathTest {
    @Test
    fun aLinkOnTheHostKeepsPathQueryAndFragment() {
        assertEquals("/jarvis-pages/wall-design.html", Forum.path("http://jarvis.localhost:8080/jarvis-pages/wall-design.html"))
        assertEquals("/t/42/3?u=aviv#post_3", Forum.path("http://jarvis.localhost:8080/t/42/3?u=aviv#post_3"))
    }

    @Test
    fun theHostAloneIsTheRoot() {
        assertEquals("/", Forum.path("http://jarvis.localhost:8080"))
        assertEquals("/", Forum.path("http://jarvis.localhost:8080/"))
        assertEquals("/?a=1", Forum.path("http://jarvis.localhost:8080?a=1"))
        assertEquals("/#top", Forum.path("http://jarvis.localhost:8080#top"))
    }

    @Test
    fun anyOtherLinkIsLeftAlone() {
        for (url in listOf(
            "https://example.com/t/42",
            "https://jarvis.localhost:8080/t/42",
            "http://jarvis.localhost:80801/t/42",
            "http://jarvis.localhost:8080.example.com/t/42",
            "http://example.com/?to=http://jarvis.localhost:8080/t/42",
        )) assertNull(url, Forum.path(url))
    }
}
