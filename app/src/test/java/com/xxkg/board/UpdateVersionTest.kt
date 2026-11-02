package com.xxkg.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateVersionTest {

    @Test
    fun `解析发布 tag 的版本号`() {
        assertEquals(20100, UpdateChecker.parseVersionCode("v2.1.0"))
        assertEquals(20100, UpdateChecker.parseVersionCode("2.1.0"))
        assertEquals(20100, UpdateChecker.parseVersionCode("v2.1"))
        assertEquals(20000, UpdateChecker.parseVersionCode("v2.0.0"))
        assertEquals(20101, UpdateChecker.parseVersionCode("v2.1.1"))
    }

    @Test
    fun `非法 tag 返回 null`() {
        assertNull(UpdateChecker.parseVersionCode("v2"))
        assertNull(UpdateChecker.parseVersionCode("abc"))
        assertNull(UpdateChecker.parseVersionCode(""))
    }
}
