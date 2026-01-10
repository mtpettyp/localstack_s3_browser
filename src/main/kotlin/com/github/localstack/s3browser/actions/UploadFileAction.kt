package com.github.localstack.s3browser.actions

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
import com.github.localstack.s3browser.services.S3OperationException
import com.github.localstack.s3browser.toolwindow.S3BrowserPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import java.io.File
import javax.swing.SwingUtilities

/**
 * Action to upload files from the local filesystem to S3.
 */
class UploadFileAction : AnAction() {

    private val log = Logger.getInstance(UploadFileAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val panel = e.getData(S3BrowserPanel.S3_BROWSER_PANEL) ?: return
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE) ?: return

        val (instanceId, bucketName, prefix) = when (node) {
            is S3TreeNode.Bucket -> Triple(node.instanceId, node.name, "")
            is S3TreeNode.Folder -> Triple(node.instanceId, node.bucketName, node.fullPrefix)
            else -> return
        }

        // Open file chooser
        val descriptor = FileChooserDescriptorFactory.createAllButJarContentsDescriptor()
            .withTitle("Select Files to Upload")
            .withDescription("Select files or folders to upload to S3")

        val files = FileChooser.chooseFiles(descriptor, project, null)
        if (files.isEmpty()) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Uploading to S3", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val totalFiles = files.sumOf { countFiles(File(it.path)) }
                var uploadedFiles = 0

                try {
                    val s3Service = S3ClientService.getInstance()

                    for (vFile in files) {
                        val file = File(vFile.path)
                        uploadedFiles = uploadFileRecursively(
                            s3Service, instanceId, bucketName, prefix, file,
                            indicator, uploadedFiles, totalFiles
                        )
                    }

                    SwingUtilities.invokeLater {
                        panel.treeModel.refreshNode(node)
                        Messages.showInfoMessage(
                            project,
                            "Successfully uploaded $uploadedFiles file(s).",
                            "Upload Complete"
                        )
                    }
                } catch (ex: S3OperationException) {
                    SwingUtilities.invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Failed to upload: ${ex.message}",
                            "Upload Error"
                        )
                    }
                } catch (ex: Exception) {
                    log.warn("Upload failed", ex)
                    SwingUtilities.invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Failed to upload: ${ex.message}",
                            "Upload Error"
                        )
                    }
                }
            }
        })
    }

    private fun countFiles(file: File): Int {
        return if (file.isDirectory) {
            file.listFiles()?.sumOf { countFiles(it) } ?: 0
        } else {
            1
        }
    }

    private fun uploadFileRecursively(
        s3Service: S3ClientService,
        instanceId: String,
        bucketName: String,
        prefix: String,
        file: File,
        indicator: ProgressIndicator,
        currentCount: Int,
        totalCount: Int
    ): Int {
        var count = currentCount

        if (indicator.isCanceled) {
            throw RuntimeException("Upload cancelled")
        }

        if (file.isDirectory) {
            val newPrefix = "$prefix${file.name}/"
            s3Service.createFolder(instanceId, bucketName, newPrefix)
            file.listFiles()?.forEach { child ->
                count = uploadFileRecursively(s3Service, instanceId, bucketName, newPrefix, child, indicator, count, totalCount)
            }
        } else {
            val key = "$prefix${file.name}"
            indicator.text = "Uploading: ${file.name}"
            indicator.fraction = count.toDouble() / totalCount

            s3Service.putObject(instanceId, bucketName, key, file.readBytes(), null)
            count++
        }

        return count
    }

    override fun update(e: AnActionEvent) {
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE)
        e.presentation.isEnabledAndVisible = node is S3TreeNode.Bucket || node is S3TreeNode.Folder
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
