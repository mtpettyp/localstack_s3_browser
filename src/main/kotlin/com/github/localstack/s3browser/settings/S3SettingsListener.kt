package com.github.localstack.s3browser.settings

import com.intellij.util.messages.Topic

/**
 * Listener for S3 Browser settings changes.
 */
interface S3SettingsListener {
    companion object {
        val TOPIC = Topic.create("S3 Browser Settings Changed", S3SettingsListener::class.java)
    }

    /**
     * Called when settings have been applied.
     */
    fun settingsChanged()
}