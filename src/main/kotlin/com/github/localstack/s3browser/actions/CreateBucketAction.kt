package com.github.localstack.s3browser.actions

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
import com.github.localstack.s3browser.services.S3OperationException
import com.github.localstack.s3browser.toolwindow.S3BrowserPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * Action to create a new S3 bucket.
 * Only enabled when an InstanceRoot node is selected.
 */
class CreateBucketAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val panel = e.getData(S3BrowserPanel.S3_BROWSER_PANEL) ?: return
        val selectedNode = e.getData(S3BrowserPanel.S3_TREE_NODE) ?: return

        // Get the instance ID from the selected node
        val instanceId = when (selectedNode) {
            is S3TreeNode.InstanceRoot -> selectedNode.instanceId
            is S3TreeNode.Bucket -> selectedNode.instanceId
            else -> return
        }

        val bucketName = Messages.showInputDialog(
            project,
            "Enter bucket name:",
            "Create S3 Bucket",
            Messages.getQuestionIcon(),
            "",
            BucketNameValidator()
        )

        if (bucketName.isNullOrBlank()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                S3ClientService.getInstance().createBucket(instanceId, bucketName)
                SwingUtilities.invokeLater {
                    panel.refresh()
                    Messages.showInfoMessage(
                        project,
                        "Bucket '$bucketName' created successfully.",
                        "Bucket Created"
                    )
                }
            } catch (ex: S3OperationException) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to create bucket: ${ex.message}",
                        "Error"
                    )
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE)
        // Only enable for InstanceRoot nodes
        e.presentation.isEnabledAndVisible = node is S3TreeNode.InstanceRoot
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Validator for S3 bucket names.
 */
class BucketNameValidator : com.intellij.openapi.ui.InputValidator {
    override fun checkInput(inputString: String?): Boolean {
        if (inputString.isNullOrBlank()) return false
        // S3 bucket naming rules (simplified)
        val pattern = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
        return pattern.matches(inputString)
    }

    override fun canClose(inputString: String?): Boolean = checkInput(inputString)
}
