package com.bydmate.app.plus.aeb

import com.bydmate.app.data.backup.BackupManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AebBackupTest {

    private val excluded = BackupManager.EXCLUDED_PREFS_KEYS.getValue(AebKeeper.PREFS_NAME)

    @Test fun `the Keep AEB off switch travels in a backup`() {
        assertTrue(AebKeeper.PREFS_NAME in BackupManager.PREFS_FILES)
        // Older archives lack the file: restore must leave the current switch alone then.
        assertTrue(AebKeeper.PREFS_NAME in BackupManager.PREFS_FILES_ADDED_LATER)
        assertFalse(BackupManager.isExcluded(excluded, AebKeeper.KEY_ENABLED))
        // The "mark_*" prefix must never swallow the switch if keys are renamed.
        assertFalse(AebKeeper.KEY_ENABLED.startsWith("mark_"))
    }

    /** A restored start mark would make the first start after a restore look like the same drive. */
    @Test fun `the journal and the start marks stay behind`() {
        listOf(
            AebKeeper.KEY_JOURNAL,
            AebKeeper.KEY_MARK_ELAPSED,
            AebKeeper.KEY_MARK_WALL,
            AebKeeper.KEY_MARK_KM,
        ).forEach { assertTrue(it, BackupManager.isExcluded(excluded, it)) }
    }
}
