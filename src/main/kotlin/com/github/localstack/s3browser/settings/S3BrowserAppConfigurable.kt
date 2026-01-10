package com.github.localstack.s3browser.settings

import com.github.localstack.s3browser.services.S3ClientService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.AddEditRemovePanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Application-level settings configurable for LocalStack S3 Browser.
 */
class S3BrowserAppConfigurable : Configurable {

    private var panel: JPanel? = null
    private var instancesPanel: InstancesPanel? = null
    private var timeoutSpinner: JSpinner? = null
    private var confirmDeletionsCheckbox: JBCheckBox? = null

    override fun getDisplayName(): String = "LocalStack S3 Browser"

    override fun createComponent(): JComponent {
        val settings = S3BrowserAppSettings.getInstance()

        panel = JPanel(BorderLayout())

        // Create instances panel
        instancesPanel = InstancesPanel(settings.instances.toMutableList())

        // Create settings panel
        val settingsPanel = panel {
            group("Instances") {
                row {
                    cell(instancesPanel!!)
                        .align(Align.FILL)
                }.resizableRow()
            }

            group("Connection Settings") {
                row("Connection Timeout:") {
                    timeoutSpinner = spinner(1000..60000, 500)
                        .comment("Connection timeout in milliseconds")
                        .component
                    timeoutSpinner?.value = settings.connectionTimeoutMs
                    cell(JBLabel("ms"))
                }
            }

            group("Behavior") {
                row {
                    confirmDeletionsCheckbox = checkBox("Confirm before deleting files and buckets")
                        .component
                    confirmDeletionsCheckbox?.isSelected = settings.confirmDeletions
                }
            }
        }

        panel!!.add(settingsPanel, BorderLayout.CENTER)
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = S3BrowserAppSettings.getInstance()
        return instancesPanel?.isModified(settings.instances) == true ||
                timeoutSpinner?.value != settings.connectionTimeoutMs ||
                confirmDeletionsCheckbox?.isSelected != settings.confirmDeletions
    }

    override fun apply() {
        val settings = S3BrowserAppSettings.getInstance()

        // Update instances
        settings.state.instances.clear()
        instancesPanel?.getData()?.let { settings.state.instances.addAll(it) }

        settings.connectionTimeoutMs = timeoutSpinner?.value as? Int ?: S3BrowserAppSettings.DEFAULT_TIMEOUT_MS
        settings.confirmDeletions = confirmDeletionsCheckbox?.isSelected ?: true

        // Invalidate cached clients when settings change
        S3ClientService.getInstance().invalidateAllClients()

        // Notify listeners that settings have changed
        ApplicationManager.getApplication().messageBus
            .syncPublisher(S3SettingsListener.TOPIC)
            .settingsChanged()
    }

    override fun reset() {
        val settings = S3BrowserAppSettings.getInstance()
        instancesPanel?.resetData(settings.instances.toMutableList())
        timeoutSpinner?.value = settings.connectionTimeoutMs
        confirmDeletionsCheckbox?.isSelected = settings.confirmDeletions
    }

    override fun disposeUIResources() {
        panel = null
        instancesPanel = null
        timeoutSpinner = null
        confirmDeletionsCheckbox = null
    }

    /**
     * Panel for managing LocalStack instances.
     */
    private class InstancesPanel(
        initialData: MutableList<LocalStackInstance>
    ) : AddEditRemovePanel<LocalStackInstance>(
        InstanceTableModel(),
        initialData,
        "Configured LocalStack instances"
    ) {

        override fun addItem(): LocalStackInstance? {
            val existingNames = data.map { it.name }.toSet()
            val dialog = InstanceDialog(null, existingNames)
            if (dialog.showAndGet()) {
                return dialog.getInstance()
            }
            return null
        }

        override fun removeItem(item: LocalStackInstance): Boolean {
            val result = Messages.showYesNoDialog(
                "Are you sure you want to remove instance '${item.name}'?",
                "Remove Instance",
                Messages.getQuestionIcon()
            )
            return result == Messages.YES
        }

        override fun editItem(item: LocalStackInstance): LocalStackInstance? {
            // Exclude current item's name from uniqueness check
            val existingNames = data.filter { it.id != item.id }.map { it.name }.toSet()
            val dialog = InstanceDialog(item, existingNames)
            if (dialog.showAndGet()) {
                return dialog.getInstance()
            }
            return null
        }

        fun isModified(original: List<LocalStackInstance>): Boolean {
            val current = data
            if (current.size != original.size) return true
            return current.zip(original).any { (c, o) ->
                c.id != o.id || c.name != o.name || c.endpoint != o.endpoint ||
                        c.region != o.region || c.accessKeyId != o.accessKeyId ||
                        c.secretAccessKey != o.secretAccessKey
            }
        }

        fun resetData(instances: MutableList<LocalStackInstance>) {
            data.clear()
            data.addAll(instances)
        }
    }

    /**
     * Table model for displaying instances.
     */
    private class InstanceTableModel : AddEditRemovePanel.TableModel<LocalStackInstance>() {
        override fun getColumnCount(): Int = 3

        override fun getColumnName(columnIndex: Int): String = when (columnIndex) {
            0 -> "Name"
            1 -> "Endpoint"
            2 -> "Region"
            else -> ""
        }

        override fun getField(item: LocalStackInstance, columnIndex: Int): Any = when (columnIndex) {
            0 -> item.name
            1 -> item.endpoint
            2 -> item.region
            else -> ""
        }
    }

    /**
     * Dialog for adding/editing an instance.
     */
    private class InstanceDialog(
        private val existingInstance: LocalStackInstance?,
        private val existingNames: Set<String>
    ) : DialogWrapper(true) {

        private var nameField: JBTextField? = null
        private var endpointField: JBTextField? = null
        private var regionField: JBTextField? = null
        private var accessKeyField: JBTextField? = null
        private var secretKeyField: JBTextField? = null

        init {
            title = if (existingInstance == null) "Add LocalStack Instance" else "Edit LocalStack Instance"
            init()
        }

        override fun doValidate(): ValidationInfo? {
            val name = nameField?.text?.trim() ?: ""
            if (name.isBlank()) {
                return ValidationInfo("Name cannot be empty", nameField)
            }
            if (name in existingNames) {
                return ValidationInfo("An instance with this name already exists", nameField)
            }
            val endpoint = endpointField?.text?.trim() ?: ""
            if (endpoint.isBlank()) {
                return ValidationInfo("Endpoint cannot be empty", endpointField)
            }
            return null
        }

        override fun createCenterPanel(): JComponent {
            val result = panel {
                row("Name:") {
                    nameField = textField()
                        .columns(COLUMNS_LARGE)
                        .comment("Display name for this instance")
                        .component
                    nameField?.text = existingInstance?.name ?: "LocalStack"
                }
                row("Endpoint:") {
                    endpointField = textField()
                        .columns(COLUMNS_LARGE)
                        .comment("S3 endpoint URL (e.g., http://localhost:4566)")
                        .component
                    endpointField?.text = existingInstance?.endpoint ?: S3BrowserAppSettings.DEFAULT_ENDPOINT
                }
                row("Region:") {
                    regionField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .comment("AWS region")
                        .component
                    regionField?.text = existingInstance?.region ?: S3BrowserAppSettings.DEFAULT_REGION
                }
                row("Access Key ID:") {
                    accessKeyField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .comment("AWS Access Key ID (use 'test' for LocalStack)")
                        .component
                    accessKeyField?.text = existingInstance?.accessKeyId ?: S3BrowserAppSettings.DEFAULT_ACCESS_KEY
                }
                row("Secret Access Key:") {
                    secretKeyField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .comment("AWS Secret Access Key (use 'test' for LocalStack)")
                        .component
                    secretKeyField?.text = existingInstance?.secretAccessKey ?: S3BrowserAppSettings.DEFAULT_SECRET_KEY
                }
            }

            // Add document listeners to trigger revalidation on text changes
            val revalidateListener = object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = initValidation()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = initValidation()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = initValidation()
            }
            nameField?.document?.addDocumentListener(revalidateListener)
            endpointField?.document?.addDocumentListener(revalidateListener)

            return result
        }

        fun getInstance(): LocalStackInstance {
            return LocalStackInstance(
                id = existingInstance?.id ?: java.util.UUID.randomUUID().toString(),
                name = nameField?.text ?: "LocalStack",
                endpoint = endpointField?.text ?: S3BrowserAppSettings.DEFAULT_ENDPOINT,
                region = regionField?.text ?: S3BrowserAppSettings.DEFAULT_REGION,
                accessKeyId = accessKeyField?.text ?: S3BrowserAppSettings.DEFAULT_ACCESS_KEY,
                secretAccessKey = secretKeyField?.text ?: S3BrowserAppSettings.DEFAULT_SECRET_KEY
            )
        }
    }
}
