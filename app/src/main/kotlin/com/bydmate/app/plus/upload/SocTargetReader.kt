package com.bydmate.app.plus.upload

import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient

/**
 * Reads the owner's SOC target on a hybrid: the level the engine holds the battery to, set on the
 * Energy screen (15..70 %) or chosen by the car in AI-Energy Management. One getInt through the
 * daemon's batch read, nothing written. Setting.SET_DR_SOC_TARGET answered 43 and 50 in the
 * owner's two dumps of 2026-10-25, matching what the screen showed.
 */
internal class SocTargetReader(private val helper: HelperClient) {

    /** The target in percent, or null when the daemon is unreachable or the car answers a sentinel. */
    suspend fun read(): Int? {
        val reply = helper.readBatch(listOf(BatchReadItem(TX_GET_INT, DEV_SETTING, FID_SOC_TARGET))) ?: return null
        val (status, value) = reply.singleOrNull() ?: return null
        return value.takeIf { status == 0 && it in 0..PERCENT_MAX }
    }

    internal companion object {
        const val TX_GET_INT = 5
        const val DEV_SETTING = 1023
        const val FID_SOC_TARGET = 873463848
        private const val PERCENT_MAX = 100
    }
}
