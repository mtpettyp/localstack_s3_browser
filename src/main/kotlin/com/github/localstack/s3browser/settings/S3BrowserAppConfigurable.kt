package com.github.localstack.s3browser.settings

import com.github.localstack.s3browser.services.S3ClientService
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Application-level settings configurable for LocalStack S3 Browser.
 */
class S3BrowserAppConfigurable : Configurable {

    private var panel: JPanel? = null
    private var endpointField: JBTextField? = null
    private var regionField: JBTextField? = null
    private var accessKeyField: JBTextField? = null
    private var secretKeyField: JBTextField? = null
    private var timeoutSpinner: JSpinner? = null
    private var autoRefreshCheckbox: JBCheckBox? = null
    private var autoRefreshCell: Cell<JBCheckBox>? = null
    private var autoRefreshIntervalSpinner: JSpinner? = null
    private var confirmDeletionsCheckbox: JBCheckBox? = null
    private var showHiddenFilesCheckbox: JBCheckBox? = null

    override fun getDisplayName(): String = "LocalStack S3 Browser"

    override fun createComponent(): JComponent {
        val settings = S3BrowserAppSettings.getInstance()

        panel = panel {
            group("Connection Settings") {
                row("Default Endpoint:") {
                    endpointField = textField()
                        .columns(COLUMNS_LARGE)
                        .comment("LocalStack S3 endpoint URL (e.g., http://localhost:4566)")
                        .component
                    endpointField?.text = settings.defaultEndpoint
                }
                row("Default Region:") {
                    regionField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .comment("AWS region for S3 operations")
                        .component
                    regionField?.text = settings.defaultRegion
                }
                row("Access Key ID:") {
                    accessKeyField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .comment("AWS Access Key ID (use 'test' for LocalStack)")
                        .component
                    accessKeyField?.text = settings.accessKeyId
                }
                row("Secret Access Key:") {
                    secretKeyField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .comment("AWS Secret Access Key (use 'test' for LocalStack)")
                        .component
                    secretKeyField?.text = settings.secretAccessKey
                }
                row("Connection Timeout:") {
                    timeoutSpinner = spinner(1000..60000, 500)
                        .comment("Connection timeout in milliseconds")
                        .component
                    timeoutSpinner?.value = settings.connectionTimeoutMs
                    cell(JBLabel("ms"))
                }
            }

            group("Refresh Settings") {
                row {
                    autoRefreshCell = checkBox("Enable auto-refresh")
                        .comment("Automatically refresh the bucket list periodically")
                    autoRefreshCheckbox = autoRefreshCell!!.component
                    autoRefreshCheckbox?.isSelected = settings.autoRefreshEnabled
                }
                row("Refresh interval:") {
                    autoRefreshIntervalSpinner = spinner(5..300, 5)
                        .enabledIf(autoRefreshCell!!.selected)
                        .component
                    autoRefreshIntervalSpinner?.value = settings.autoRefreshIntervalSeconds
                    cell(JBLabel("seconds"))
                }
            }

            group("Behavior") {
                row {
                    confirmDeletionsCheckbox = checkBox("Confirm before deleting files and buckets")
                        .component
                    confirmDeletionsCheckbox?.isSelected = settings.confirmDeletions
                }
                row {
                    showHiddenFilesCheckbox = checkBox("Show hidden files (starting with .)")
                        .component
                    showHiddenFilesCheckbox?.isSelected = settings.showHiddenFiles
                }
            }
        }

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = S3BrowserAppSettings.getInstance()
        return endpointField?.text != settings.defaultEndpoint ||
                regionField?.text != settings.defaultRegion ||
                accessKeyField?.text != settings.accessKeyId ||
                secretKeyField?.text != settings.secretAccessKey ||
                timeoutSpinner?.value != settings.connectionTimeoutMs ||
                autoRefreshCheckbox?.isSelected != settings.autoRefreshEnabled ||
                autoRefreshIntervalSpinner?.value != settings.autoRefreshIntervalSeconds ||
                confirmDeletionsCheckbox?.isSelected != settings.confirmDeletions ||
                showHiddenFilesCheckbox?.isSelected != settings.showHiddenFiles
    }

    override fun apply() {
        val settings = S3BrowserAppSettings.getInstance()
        settings.defaultEndpoint = endpointField?.text ?: S3BrowserAppSettings.DEFAULT_ENDPOINT
        settings.defaultRegion = regionField?.text ?: S3BrowserAppSettings.DEFAULT_REGION
        settings.accessKeyId = accessKeyField?.text ?: S3BrowserAppSettings.DEFAULT_ACCESS_KEY
        settings.secretAccessKey = secretKeyField?.text ?: S3BrowserAppSettings.DEFAULT_SECRET_KEY
        settings.connectionTimeoutMs = timeoutSpinner?.value as? Int ?: S3BrowserAppSettings.DEFAULT_TIMEOUT_MS
        settings.autoRefreshEnabled = autoRefreshCheckbox?.isSelected ?: false
        settings.autoRefreshIntervalSeconds = autoRefreshIntervalSpinner?.value as? Int ?: 30
        settings.confirmDeletions = confirmDeletionsCheckbox?.isSelected ?: true
        settings.showHiddenFiles = showHiddenFilesCheckbox?.isSelected ?: false

        // Invalidate cached client when credentials change
        S3ClientService.getInstance().invalidateClient()
    }

    override fun reset() {
        val settings = S3BrowserAppSettings.getInstance()
        endpointField?.text = settings.defaultEndpoint
        regionField?.text = settings.defaultRegion
        accessKeyField?.text = settings.accessKeyId
        secretKeyField?.text = settings.secretAccessKey
        timeoutSpinner?.value = settings.connectionTimeoutMs
        autoRefreshCheckbox?.isSelected = settings.autoRefreshEnabled
        autoRefreshIntervalSpinner?.value = settings.autoRefreshIntervalSeconds
        confirmDeletionsCheckbox?.isSelected = settings.confirmDeletions
        showHiddenFilesCheckbox?.isSelected = settings.showHiddenFiles
    }

    override fun disposeUIResources() {
        panel = null
        endpointField = null
        regionField = null
        accessKeyField = null
        secretKeyField = null
        timeoutSpinner = null
        autoRefreshCheckbox = null
        autoRefreshCell = null
        autoRefreshIntervalSpinner = null
        confirmDeletionsCheckbox = null
        showHiddenFilesCheckbox = null
    }
}
