package com.github.localstack.s3browser.actions

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
import com.github.localstack.s3browser.services.S3OperationException
import com.github.localstack.s3browser.toolwindow.S3BrowserPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * Action to create a new folder in S3.
 */
class CreateFolderAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val panel = e.getData(S3BrowserPanel.S3_BROWSER_PANEL) ?: return
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE) ?: return

        val (bucketName, prefix) = when (node) {
            is S3TreeNode.Bucket -> Pair(node.name, "")
            is S3TreeNode.Folder -> Pair(node.bucketName, node.fullPrefix)
            else -> return
        }

        val folderName = Messages.showInputDialog(
            project,
            "Enter folder name:",
            "Create Folder",
            Messages.getQuestionIcon(),
            "",
            FolderNameValidator()
        )

        if (folderName.isNullOrBlank()) return

        val folderPath = "$prefix$folderName/"

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                S3ClientService.getInstance().createFolder(bucketName, folderPath, project)
                SwingUtilities.invokeLater {
                    panel.treeModel.refreshNode(node)
                }
            } catch (ex: S3OperationException) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to create folder: ${ex.message}",
                        "Error"
                    )
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE)
        e.presentation.isEnabledAndVisible = node is S3TreeNode.Bucket || node is S3TreeNode.Folder
    }
}

/**
 * Validator for folder names.
 */
class FolderNameValidator : InputValidator {
    override fun checkInput(inputString: String?): Boolean {
        if (inputString.isNullOrBlank()) return false
        // Folder names should not contain certain characters
        return !inputString.contains("/") && !inputString.contains("\\")
    }

    override fun canClose(inputString: String?): Boolean = checkInput(inputString)
}
