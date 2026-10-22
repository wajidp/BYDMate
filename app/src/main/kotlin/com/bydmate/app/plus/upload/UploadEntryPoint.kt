package com.bydmate.app.plus.upload

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hands the @Singleton [CloudUploader] to the settings card (a composable, not a Hilt target). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UploadEntryPoint {
    fun cloudUploader(): CloudUploader
}
