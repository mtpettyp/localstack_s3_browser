package com.github.localstack.s3browser.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection

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
        @XCollection(style = XCollection.Style.v2)
        var instances: MutableList<LocalStackInstance> = mutableListOf(),
        var connectionTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
        var confirmDeletions: Boolean = true,
        var showHiddenFiles: Boolean = false,
        // Legacy fields for migration
        var defaultEndpoint: String = "",
        var defaultRegion: String = "",
        var accessKeyId: String = "",
        var secretAccessKey: String = ""
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
        migrateIfNeeded()
    }

    /**
     * Migrate from legacy single-instance config to multi-instance.
     */
    private fun migrateIfNeeded() {
        // If no instances but legacy fields exist, migrate
        if (myState.instances.isEmpty()) {
            val legacyEndpoint = myState.defaultEndpoint.ifEmpty { DEFAULT_ENDPOINT }
            val legacyRegion = myState.defaultRegion.ifEmpty { DEFAULT_REGION }
            val legacyAccessKey = myState.accessKeyId.ifEmpty { DEFAULT_ACCESS_KEY }
            val legacySecretKey = myState.secretAccessKey.ifEmpty { DEFAULT_SECRET_KEY }

            myState.instances.add(
                LocalStackInstance(
                    name = "LocalStack",
                    endpoint = legacyEndpoint,
                    region = legacyRegion,
                    accessKeyId = legacyAccessKey,
                    secretAccessKey = legacySecretKey
                )
            )
        }
    }

    val instances: MutableList<LocalStackInstance>
        get() {
            if (myState.instances.isEmpty()) {
                migrateIfNeeded()
            }
            return myState.instances
        }

    fun addInstance(instance: LocalStackInstance) {
        myState.instances.add(instance)
    }

    fun removeInstance(instance: LocalStackInstance) {
        myState.instances.remove(instance)
    }

    fun getInstanceById(id: String): LocalStackInstance? {
        return myState.instances.find { it.id == id }
    }

    var connectionTimeoutMs: Int
        get() = myState.connectionTimeoutMs
        set(value) { myState.connectionTimeoutMs = value }

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
