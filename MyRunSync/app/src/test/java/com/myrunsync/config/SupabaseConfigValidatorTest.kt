package com.myrunsync.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SupabaseConfigValidatorTest {

    @Test
    fun `normalizeUrl trims whitespace and trailing slashes`() {
        assertEquals(
            "https://example.supabase.co",
            SupabaseConfigValidator.normalizeUrl("  https://example.supabase.co///  ")
        )
    }

    @Test
    fun `normalizeAnonKey trims whitespace`() {
        assertEquals(
            "sb_publishable_abc123",
            SupabaseConfigValidator.normalizeAnonKey("  sb_publishable_abc123 \n")
        )
    }

    @Test
    fun `validate rejects blank url`() {
        val error = SupabaseConfigValidator.validate("   ", "eyJ123")
        assertNotNull(error)
        assertEquals("Supabase URL을 입력해 주세요.", error)
    }

    @Test
    fun `validate rejects url without http or https scheme`() {
        val error = SupabaseConfigValidator.validate("example.supabase.co", "eyJ123")
        assertNotNull(error)
        assertEquals("Supabase URL은 https:// 로 시작해야 합니다.", error)
    }

    @Test
    fun `validate rejects blank anon key`() {
        val error = SupabaseConfigValidator.validate("https://example.supabase.co", "  ")
        assertNotNull(error)
        assertEquals("Supabase Anon Key를 입력해 주세요.", error)
    }

    @Test
    fun `validate accepts valid https and http urls with non-blank key`() {
        assertNull(SupabaseConfigValidator.validate(" https://example.supabase.co/ ", " eyJ123 "))
        assertNull(SupabaseConfigValidator.validate("http://127.0.0.1:54321", "anon-key"))
    }
}
