package com.github.localstack.s3browser.actions

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
import com.github.localstack.s3browser.services.S3OperationException
import com.github.localstack.s3browser.settings.S3BrowserAppSettings
import com.github.localstack.s3browser.toolwindow.S3BrowserPanel
import com.github.localstack.s3browser.vfs.S3VirtualFileSystem
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * Action to delete a file or folder from S3.
 */
class DeleteAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val panel = e.getData(S3BrowserPanel.S3_BROWSER_PANEL) ?: return
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE) ?: return

        if (node !is S3TreeNode.S3Object && node !is S3TreeNode.Folder) {
            return
        }

        val settings = S3BrowserAppSettings.getInstance()

        val itemName = when (node) {
            is S3TreeNode.S3Object -> "file '${node.name}'"
            is S3TreeNode.Folder -> "folder '${node.name}' and all its contents"
            else -> return
        }

        if (settings.confirmDeletions) {
            val result = Messages.showYesNoDialog(
                project,
                "Delete $itemName?",
                "Confirm Delete",
                Messages.getWarningIcon()
            )

            if (result != Messages.YES) return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val s3Service = S3ClientService.getInstance()

                when (node) {
                    is S3TreeNode.S3Object -> {
                        // Close the file in editor if open
                        SwingUtilities.invokeLater {
                            val vfs = S3VirtualFileSystem.getInstance()
                            vfs.findFileByPath("${node.bucketName}/${node.key}")?.let { file ->
                                FileEditorManager.getInstance(project).closeFile(file)
                            }
                            vfs.removeFromCache(node.bucketName, node.key)
                        }
                        s3Service.deleteObject(node.bucketName, node.key, project)
                    }
                    is S3TreeNode.Folder -> {
                        s3Service.deleteObjectsWithPrefix(node.bucketName, node.fullPrefix, project)
                    }
                    else -> return@executeOnPooledThread
                }

                SwingUtilities.invokeLater {
                    // Refresh parent node
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
                        "Failed to delete: ${ex.message}",
                        "Error"
                    )
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE)
        e.presentation.isEnabledAndVisible = node is S3TreeNode.S3Object || node is S3TreeNode.Folder
    }
}
