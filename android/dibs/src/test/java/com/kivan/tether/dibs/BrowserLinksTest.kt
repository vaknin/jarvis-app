package com.kivan.tether.dibs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserLinksTest {
    @Test
    fun claudeHostsGoToTheBrowser() {
        assertTrue(isClaudeLink("https://claude.ai/artifact/KEF8NckdLpSGJagxA14wWC"))
        assertTrue(isClaudeLink("https://www.claude.ai/chat/1"))
        assertTrue(isClaudeLink("https://CLAUDE.AI/x"))
        assertTrue(isClaudeLink("https://claude.com/product"))
        assertTrue(isClaudeLink("https://support.claude.com/en"))
    }

    @Test
    fun otherLinksStayAsTheyAre() {
        assertFalse(isClaudeLink("https://github.com/login/device"))
        assertFalse(isClaudeLink("https://notclaude.ai/x"))
        assertFalse(isClaudeLink("https://claude.ai.evil.example/x"))
        assertFalse(isClaudeLink("https://example.com/?u=claude.ai"))
        assertFalse(isClaudeLink("not a url"))
    }
}
