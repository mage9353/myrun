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

    @Test
    fun `validateZone2 rejects blank non-numeric out-of-range and inverted bounds`() {
        assertNotNull(SupabaseConfigValidator.validateZone2("", "133"))
        assertNotNull(SupabaseConfigValidator.validateZone2("abc", "133"))
        assertNotNull(SupabaseConfigValidator.validateZone2("35", "133"))
        assertNotNull(SupabaseConfigValidator.validateZone2("114", "250"))
        assertNotNull(SupabaseConfigValidator.validateZone2("140", "130"))
    }

    @Test
    fun `validateZone2 and parseZone2 accept valid bounds`() {
        assertNull(SupabaseConfigValidator.validateZone2(" 114 ", " 133 "))
        assertEquals(114 to 133, SupabaseConfigValidator.parseZone2(" 114 ", " 133 "))
        assertEquals(120 to 120, SupabaseConfigValidator.parseZone2("120", "120"))
    }
}
