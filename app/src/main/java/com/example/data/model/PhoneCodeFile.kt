package com.example.data.model

import android.net.Uri

enum class PhoneStorageSource {
    APP_WORKSPACE,
    SAF_FOLDER,
}

data class PhoneCodeFile(
    val id: String,
    val displayName: String,
    val uri: Uri,
    val language: CodeLanguage,
    val sizeBytes: Long,
    val lastModified: Long,
    val source: PhoneStorageSource,
    val localPath: String? = null,
) {
    val isEditableText: Boolean
        get() = sizeBytes <= 2 * 1024 * 1024
}
