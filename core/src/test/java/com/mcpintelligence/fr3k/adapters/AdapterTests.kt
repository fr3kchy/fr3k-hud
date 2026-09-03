package com.mcpintelligence.fr3k.adapters

import com.mcpintelligence.fr3k.adapters.morphe.MorphePatchRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterTests {

    @Test
    fun morphe_patch_loads_and_verifies() {
        val raw = """
            { "id": "p1", "name": "P", "target_package": "x.y", "supported_versions": ["1.0"],
              "fingerprint": "abc", "menu_items": ["a", "b"] }
        """.trimIndent()
        val patch = MorphePatchRepository().loadFromJson(raw)
        assertEquals("p1", patch.id)
        assertTrue(MorphePatchRepository().matches(patch, "1.0"))
        assertFalse(MorphePatchRepository().matches(patch, "1.1"))
        assertTrue(MorphePatchRepository().verify(patch, "abc"))
        assertFalse(MorphePatchRepository().verify(patch, "xxx"))
    }
}