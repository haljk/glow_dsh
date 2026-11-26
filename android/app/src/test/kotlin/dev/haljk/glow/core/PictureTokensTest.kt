package dev.haljk.glow.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one rewrite 直发模式 is allowed to make.
 *
 * Written against the desktop build's behaviour on purpose: two clients that disagree
 * about 「图 1」 would make the same sentence behave differently depending on where it
 * was typed.
 */
class PictureTokensTest {

    @Test
    fun `a figure number inside the range becomes a picture token`() {
        assertEquals("把<Picture 1>的人放进<Picture 2>", PictureTokens.bind("把图1的人放进图2", 2))
    }

    @Test
    fun `spacing after the word is tolerated`() {
        assertEquals("<Picture 1> 换成白底", PictureTokens.bind("图 1 换成白底", 1))
    }

    @Test
    fun `ordinal wording is accepted too`() {
        assertEquals("参考<Picture 2>的构图", PictureTokens.bind("参考第2张的构图", 3))
    }

    @Test
    fun `a number beyond the attached photos is left alone`() {
        assertEquals("把图9的人也放进来", PictureTokens.bind("把图9的人也放进来", 2))
    }

    @Test
    fun `words that merely end in 图 are not touched`() {
        val text = "按这张流程图和示意图走，别改地图"
        assertEquals(text, PictureTokens.bind(text, 2))
    }

    @Test
    fun `no references means no rewrite at all`() {
        assertEquals("把图1换掉", PictureTokens.bind("把图1换掉", 0))
    }

    @Test
    fun `text without any figure reference is returned unchanged`() {
        val text = "背景换成浅灰 studio，光线柔和一点"
        assertEquals(text, PictureTokens.bind(text, 3))
    }
}
