package com.droid.dolphy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IrCatalogArchiveTest {

    @Test
    fun `github archive root is stripped from remote path`() {
        assertEquals(
            "TVs/Samsung/UE40.ir",
            safeIrRelativePath("Flipper-IRDB-main/TVs/Samsung/UE40.ir")
        )
    }

    @Test
    fun `case insensitive ir extension is accepted`() {
        assertEquals(
            "ACs/Daikin/FTX.IR",
            safeIrRelativePath("Flipper-IRDB-main/ACs/Daikin/FTX.IR")
        )
    }

    @Test
    fun `non ir files are ignored`() {
        assertNull(safeIrRelativePath("Flipper-IRDB-main/README.md"))
        assertNull(safeIrRelativePath("Flipper-IRDB-main/TVs/Samsung/UE40.txt"))
    }

    @Test
    fun `path traversal is rejected`() {
        assertNull(safeIrRelativePath("Flipper-IRDB-main/TVs/../../evil.ir"))
        assertNull(safeIrRelativePath("Flipper-IRDB-main/../evil.ir"))
        assertNull(safeIrRelativePath("Flipper-IRDB-main/TVs/./evil.ir"))
    }

    @Test
    fun `blank segments and missing archive root are rejected`() {
        assertNull(safeIrRelativePath("Flipper-IRDB-main/TVs//UE40.ir"))
        assertNull(safeIrRelativePath("TVs/Samsung/UE40.ir"))
    }

    @Test
    fun `backslashes are normalized before validation`() {
        assertEquals(
            "TVs/Samsung/UE40.ir",
            safeIrRelativePath("Flipper-IRDB-main\\TVs\\Samsung\\UE40.ir")
        )
    }
}
