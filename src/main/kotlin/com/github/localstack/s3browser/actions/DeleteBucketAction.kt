package com.github.localstack.s3browser.actions

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
import com.github.localstack.s3browser.services.S3OperationException
import com.github.localstack.s3browser.settings.S3BrowserAppSettings
import com.github.localstack.s3browser.toolwindow.S3BrowserPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * Action to delete an S3 bucket.
 */
class DeleteBucketAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val panel = e.getData(S3BrowserPanel.S3_BROWSER_PANEL) ?: return
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE) as? S3TreeNode.Bucket ?: return

        val settings = S3BrowserAppSettings.getInstance()

        if (settings.confirmDeletions) {
            val result = Messages.showYesNoDialog(
                project,
                "Delete bucket '${node.name}'?\n\nThis will also delete all objects in the bucket.",
                "Delete Bucket",
                "Delete",
                "Cancel",
                Messages.getWarningIcon()
            )

            if (result != Messages.YES) return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                S3ClientService.getInstance().deleteBucketRecursively(node.name, project)
                SwingUtilities.invokeLater {
                    panel.refresh()
                    Messages.showInfoMessage(
                        project,
                        "Bucket '${node.name}' deleted successfully.",
                        "Bucket Deleted"
                    )
                }
            } catch (ex: S3OperationException) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to delete bucket: ${ex.message}",
                        "Error"
                    )
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE)
        e.presentation.isEnabledAndVisible = node is S3TreeNode.Bucket
    }
}
