package com.github.localstack.s3browser.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Project-level settings for LocalStack S3 Browser.
 * These settings are specific to each project and override application-level settings.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "LocalStackS3BrowserProjectSettings",
    storages = [Storage("localStackS3Browser.xml")]
)
class S3BrowserProjectSettings : PersistentStateComponent<S3BrowserProjectSettings.State> {

    private var myState = State()

    data class State(
        var useProjectSettings: Boolean = false,
        var endpoint: String = "",
        var region: String = "",
        var defaultBucket: String = "",
        var expandedPaths: MutableSet<String> = mutableSetOf()
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    var useProjectSettings: Boolean
        get() = myState.useProjectSettings
        set(value) { myState.useProjectSettings = value }

    var endpoint: String
        get() = myState.endpoint
        set(value) { myState.endpoint = value }

    var region: String
        get() = myState.region
        set(value) { myState.region = value }

    var defaultBucket: String
        get() = myState.defaultBucket
        set(value) { myState.defaultBucket = value }

    var expandedPaths: MutableSet<String>
        get() = myState.expandedPaths
        set(value) { myState.expandedPaths = value }

    /**
     * Gets the effective endpoint, considering project override.
     */
    fun getEffectiveEndpoint(): String {
        return if (useProjectSettings && endpoint.isNotBlank()) {
            endpoint
        } else {
            S3BrowserAppSettings.getInstance().instances.firstOrNull()?.endpoint
                ?: S3BrowserAppSettings.DEFAULT_ENDPOINT
        }
    }

    /**
     * Gets the effective region, considering project override.
     */
    fun getEffectiveRegion(): String {
        return if (useProjectSettings && region.isNotBlank()) {
            region
        } else {
            S3BrowserAppSettings.getInstance().instances.firstOrNull()?.region
                ?: S3BrowserAppSettings.DEFAULT_REGION
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): S3BrowserProjectSettings {
            return project.getService(S3BrowserProjectSettings::class.java)
        }
    }
}
