package com.github.localstack.s3browser.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level settings for LocalStack S3 Browser.
 * These settings are shared across all projects.
 */
@Service(Service.Level.APP)
@State(
    name = "LocalStackS3BrowserAppSettings",
    storages = [Storage("LocalStackS3Browser.xml")]
)
class S3BrowserAppSettings : PersistentStateComponent<S3BrowserAppSettings.State> {

    private var myState = State()

    data class State(
        var defaultEndpoint: String = DEFAULT_ENDPOINT,
        var defaultRegion: String = DEFAULT_REGION,
        var accessKeyId: String = DEFAULT_ACCESS_KEY,
        var secretAccessKey: String = DEFAULT_SECRET_KEY,
        var connectionTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
        var autoRefreshEnabled: Boolean = false,
        var autoRefreshIntervalSeconds: Int = 30,
        var confirmDeletions: Boolean = true,
        var showHiddenFiles: Boolean = false
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    var defaultEndpoint: String
        get() = myState.defaultEndpoint
        set(value) { myState.defaultEndpoint = value }

    var defaultRegion: String
        get() = myState.defaultRegion
        set(value) { myState.defaultRegion = value }

    var accessKeyId: String
        get() = myState.accessKeyId
        set(value) { myState.accessKeyId = value }

    var secretAccessKey: String
        get() = myState.secretAccessKey
        set(value) { myState.secretAccessKey = value }

    var connectionTimeoutMs: Int
        get() = myState.connectionTimeoutMs
        set(value) { myState.connectionTimeoutMs = value }

    var autoRefreshEnabled: Boolean
        get() = myState.autoRefreshEnabled
        set(value) { myState.autoRefreshEnabled = value }

    var autoRefreshIntervalSeconds: Int
        get() = myState.autoRefreshIntervalSeconds
        set(value) { myState.autoRefreshIntervalSeconds = value }

    var confirmDeletions: Boolean
        get() = myState.confirmDeletions
        set(value) { myState.confirmDeletions = value }

    var showHiddenFiles: Boolean
        get() = myState.showHiddenFiles
        set(value) { myState.showHiddenFiles = value }

    companion object {
        const val DEFAULT_ENDPOINT = "http://localhost:4566"
        const val DEFAULT_REGION = "us-east-1"
        const val DEFAULT_ACCESS_KEY = "test"
        const val DEFAULT_SECRET_KEY = "test"
        const val DEFAULT_TIMEOUT_MS = 5000

        @JvmStatic
        fun getInstance(): S3BrowserAppSettings {
            return ApplicationManager.getApplication().getService(S3BrowserAppSettings::class.java)
        }
    }
}
