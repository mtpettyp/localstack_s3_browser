package com.github.localstack.s3browser.actions

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
import com.github.localstack.s3browser.services.S3OperationException
import com.github.localstack.s3browser.toolwindow.S3BrowserPanel
import com.github.localstack.s3browser.vfs.S3VirtualFileSystem
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * Action to create a new file in S3.
 */
class CreateFileAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val panel = e.getData(S3BrowserPanel.S3_BROWSER_PANEL) ?: return
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE) ?: return

        val (bucketName, prefix) = when (node) {
            is S3TreeNode.Bucket -> Pair(node.name, "")
            is S3TreeNode.Folder -> Pair(node.bucketName, node.fullPrefix)
            else -> return
        }

        val fileName = Messages.showInputDialog(
            project,
            "Enter file name:",
            "Create File",
            Messages.getQuestionIcon(),
            "",
            FileNameValidator()
        )

        if (fileName.isNullOrBlank()) return

        val key = "$prefix$fileName"

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Create empty file
                S3ClientService.getInstance().putObjectString(bucketName, key, "", null, project)

                SwingUtilities.invokeLater {
                    panel.treeModel.refreshNode(node)

                    // Open the file in editor
                    val vfs = S3VirtualFileSystem.getInstance()
                    val virtualFile = vfs.findOrCreateFile(bucketName, key, project)
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            } catch (ex: S3OperationException) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to create file: ${ex.message}",
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
 * Validator for file names.
 */
class FileNameValidator : InputValidator {
    override fun checkInput(inputString: String?): Boolean {
        if (inputString.isNullOrBlank()) return false
        return !inputString.contains("/") && !inputString.contains("\\")
    }

    override fun canClose(inputString: String?): Boolean = checkInput(inputString)
}
