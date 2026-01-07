package com.github.localstack.s3browser.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Project-level settings configurable for LocalStack S3 Browser.
 */
class S3BrowserProjectConfigurable(private val project: Project) : Configurable {

    private var panel: JPanel? = null
    private var useProjectSettingsCheckbox: JBCheckBox? = null
    private var useProjectSettingsCell: Cell<JBCheckBox>? = null
    private var endpointField: JBTextField? = null
    private var regionField: JBTextField? = null
    private var defaultBucketField: JBTextField? = null

    override fun getDisplayName(): String = "Project Settings"

    override fun createComponent(): JComponent {
        val settings = S3BrowserProjectSettings.getInstance(project)
        val appSettings = S3BrowserAppSettings.getInstance()

        panel = panel {
            row {
                useProjectSettingsCell = checkBox("Use project-specific settings")
                    .comment("Override application-level settings for this project")
                useProjectSettingsCheckbox = useProjectSettingsCell!!.component
                useProjectSettingsCheckbox?.isSelected = settings.useProjectSettings
            }

            group("Project Connection Settings") {
                row("Endpoint:") {
                    endpointField = textField()
                        .columns(COLUMNS_LARGE)
                        .comment("Leave empty to use application default: ${appSettings.defaultEndpoint}")
                        .enabledIf(useProjectSettingsCell!!.selected)
                        .component
                    endpointField?.text = settings.endpoint
                }
                row("Region:") {
                    regionField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .comment("Leave empty to use application default: ${appSettings.defaultRegion}")
                        .enabledIf(useProjectSettingsCell!!.selected)
                        .component
                    regionField?.text = settings.region
                }
            }

            group("Project Preferences") {
                row("Default Bucket:") {
                    defaultBucketField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .comment("Bucket to expand by default when opening the tool window")
                        .component
                    defaultBucketField?.text = settings.defaultBucket
                }
            }

            row {
                comment("Note: Project settings allow you to connect to different LocalStack instances per project.")
            }
        }

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = S3BrowserProjectSettings.getInstance(project)
        return useProjectSettingsCheckbox?.isSelected != settings.useProjectSettings ||
                endpointField?.text != settings.endpoint ||
                regionField?.text != settings.region ||
                defaultBucketField?.text != settings.defaultBucket
    }

    override fun apply() {
        val settings = S3BrowserProjectSettings.getInstance(project)
        settings.useProjectSettings = useProjectSettingsCheckbox?.isSelected ?: false
        settings.endpoint = endpointField?.text ?: ""
        settings.region = regionField?.text ?: ""
        settings.defaultBucket = defaultBucketField?.text ?: ""
    }

    override fun reset() {
        val settings = S3BrowserProjectSettings.getInstance(project)
        useProjectSettingsCheckbox?.isSelected = settings.useProjectSettings
        endpointField?.text = settings.endpoint
        regionField?.text = settings.region
        defaultBucketField?.text = settings.defaultBucket
    }

    override fun disposeUIResources() {
        panel = null
        useProjectSettingsCheckbox = null
        useProjectSettingsCell = null
        endpointField = null
        regionField = null
        defaultBucketField = null
    }
}
