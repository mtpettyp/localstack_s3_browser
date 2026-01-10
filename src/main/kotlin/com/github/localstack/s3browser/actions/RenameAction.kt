package com.github.localstack.s3browser.actions

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
import com.github.localstack.s3browser.services.S3OperationException
import com.github.localstack.s3browser.toolwindow.S3BrowserPanel
import com.github.localstack.s3browser.vfs.S3VirtualFileSystem
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * Action to rename a file or folder in S3.
 */
class RenameAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val panel = e.getData(S3BrowserPanel.S3_BROWSER_PANEL) ?: return
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE) ?: return

        when (node) {
            is S3TreeNode.S3Object -> renameFile(e, node, panel)
            is S3TreeNode.Folder -> renameFolder(e, node, panel)
            else -> return
        }
    }

    private fun renameFile(e: AnActionEvent, node: S3TreeNode.S3Object, panel: S3BrowserPanel) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val instanceId = node.instanceId

        val newName = Messages.showInputDialog(
            project,
            "Enter new name:",
            "Rename File",
            Messages.getQuestionIcon(),
            node.name,
            FileNameValidator()
        )

        if (newName.isNullOrBlank() || newName == node.name) return

        val parentPrefix = if (node.key.contains("/")) {
            node.key.substringBeforeLast("/") + "/"
        } else {
            ""
        }
        val newKey = "$parentPrefix$newName"

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val s3Service = S3ClientService.getInstance()

                // Close the file if open
                SwingUtilities.invokeLater {
                    val vfs = S3VirtualFileSystem.getInstance()
                    vfs.findFileByPath("${node.bucketName}/${node.key}")?.let { file ->
                        FileEditorManager.getInstance(project).closeFile(file)
                    }
                    vfs.removeFromCache(node.bucketName, node.key)
                }

                s3Service.renameObject(instanceId, node.bucketName, node.key, newKey)

                SwingUtilities.invokeLater {
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
                        "Failed to rename file: ${ex.message}",
                        "Error"
                    )
                }
            }
        }
    }

    private fun renameFolder(e: AnActionEvent, node: S3TreeNode.Folder, panel: S3BrowserPanel) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val instanceId = node.instanceId

        val newName = Messages.showInputDialog(
            project,
            "Enter new folder name:",
            "Rename Folder",
            Messages.getQuestionIcon(),
            node.name,
            FolderNameValidator()
        )

        if (newName.isNullOrBlank() || newName == node.name) return

        val parentPrefix = if (node.prefix.trimEnd('/').contains("/")) {
            node.prefix.trimEnd('/').substringBeforeLast("/") + "/"
        } else {
            ""
        }
        val oldPrefix = node.fullPrefix
        val newPrefix = "$parentPrefix$newName/"

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val s3Service = S3ClientService.getInstance()

                // Get all objects with the old prefix
                val objects = s3Service.listAllObjects(instanceId, node.bucketName, oldPrefix)

                // Copy each object to new location and delete old
                for (obj in objects) {
                    val newKey = obj.key.replaceFirst(oldPrefix, newPrefix)
                    s3Service.moveObject(instanceId, node.bucketName, obj.key, node.bucketName, newKey)
                }

                SwingUtilities.invokeLater {
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
                        "Failed to rename folder: ${ex.message}",
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

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Validator for renamed items.
 */
private class RenameValidator : InputValidator {
    override fun checkInput(inputString: String?): Boolean {
        if (inputString.isNullOrBlank()) return false
        return !inputString.contains("/") && !inputString.contains("\\")
    }

    override fun canClose(inputString: String?): Boolean = checkInput(inputString)
}
