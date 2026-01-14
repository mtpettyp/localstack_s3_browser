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
 * Action to touch (update last modified timestamp) an S3 object.
 */
class TouchAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val panel = e.getData(S3BrowserPanel.S3_BROWSER_PANEL) ?: return
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE) ?: return

        if (node !is S3TreeNode.S3Object) {
            return
        }

        val instanceId = node.instanceId

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val s3Service = S3ClientService.getInstance()
                s3Service.touchObject(instanceId, node.bucketName, node.key)

                SwingUtilities.invokeLater {
                    // Refresh parent node to show updated timestamp
                    val parentPath = panel.getSelectedPath()?.parentPath
                    val parentNode = parentPath?.lastPathComponent as? S3TreeNode
                    if (parentNode != null) {
                        panel.treeModel.refreshNode(parentNode)
                    } else {
                        panel.refresh()
                    }
                }
            } catch (ex: S3OperationException) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to touch file: ${ex.message}",
                        "Error"
                    )
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE)
        e.presentation.isEnabledAndVisible = node is S3TreeNode.S3Object
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
