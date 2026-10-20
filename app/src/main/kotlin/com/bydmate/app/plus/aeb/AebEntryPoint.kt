package com.bydmate.app.plus.aeb

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hands the @Singleton [AebKeeper] to the settings card (a composable, not a Hilt target). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AebEntryPoint {
    fun aebKeeper(): AebKeeper
}
