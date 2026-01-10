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
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ui.Messages
import java.io.File
import javax.swing.SwingUtilities

/**
 * Action to download S3 objects to the local filesystem.
 */
class DownloadAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE) ?: return

        when (node) {
            is S3TreeNode.S3Object -> downloadFile(project, node)
            is S3TreeNode.Folder -> downloadFolder(project, node)
            is S3TreeNode.Bucket -> downloadBucket(project, node)
            else -> return
        }
    }

    private fun downloadFile(project: com.intellij.openapi.project.Project, node: S3TreeNode.S3Object) {
        val descriptor = FileSaverDescriptor(
            "Download S3 Object",
            "Choose location to save '${node.name}'",
            getExtension(node.name)
        )

        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, node.name) ?: return

        val targetFile = wrapper.file

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val content = S3ClientService.getInstance().getObjectContent(
                    node.instanceId,
                    node.bucketName,
                    node.key
                )
                targetFile.writeBytes(content)

                SwingUtilities.invokeLater {
                    Messages.showInfoMessage(
                        project,
                        "Downloaded '${node.name}' to ${targetFile.absolutePath}",
                        "Download Complete"
                    )
                }
            } catch (ex: S3OperationException) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to download file: ${ex.message}",
                        "Download Error"
                    )
                }
            }
        }
    }

    private fun downloadFolder(project: com.intellij.openapi.project.Project, node: S3TreeNode.Folder) {
        val descriptor = FileSaverDescriptor(
            "Download S3 Folder",
            "Choose location to save folder '${node.name}'",
            ""
        )

        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, node.name) ?: return

        val targetDir = wrapper.file

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                downloadFolderRecursively(
                    node.instanceId,
                    node.bucketName,
                    node.fullPrefix,
                    targetDir
                )

                SwingUtilities.invokeLater {
                    Messages.showInfoMessage(
                        project,
                        "Downloaded folder '${node.name}' to ${targetDir.absolutePath}",
                        "Download Complete"
                    )
                }
            } catch (ex: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to download folder: ${ex.message}",
                        "Download Error"
                    )
                }
            }
        }
    }

    private fun downloadBucket(project: com.intellij.openapi.project.Project, node: S3TreeNode.Bucket) {
        val descriptor = FileSaverDescriptor(
            "Download S3 Bucket",
            "Choose location to save bucket '${node.name}'",
            ""
        )

        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, node.name) ?: return

        val targetDir = wrapper.file

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                downloadFolderRecursively(
                    node.instanceId,
                    node.name,
                    "",
                    targetDir
                )

                SwingUtilities.invokeLater {
                    Messages.showInfoMessage(
                        project,
                        "Downloaded bucket '${node.name}' to ${targetDir.absolutePath}",
                        "Download Complete"
                    )
                }
            } catch (ex: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to download bucket: ${ex.message}",
                        "Download Error"
                    )
                }
            }
        }
    }

    private fun downloadFolderRecursively(
        instanceId: String,
        bucketName: String,
        prefix: String,
        targetDir: File
    ) {
        targetDir.mkdirs()

        val s3Service = S3ClientService.getInstance()
        val objects = s3Service.listAllObjects(instanceId, bucketName, prefix)

        for (obj in objects) {
            // Calculate relative path from the prefix
            val relativePath = if (prefix.isNotEmpty()) {
                obj.key.removePrefix(prefix)
            } else {
                obj.key
            }

            if (relativePath.isEmpty() || relativePath.endsWith("/")) {
                continue // Skip folder markers
            }

            val targetFile = File(targetDir, relativePath)
            targetFile.parentFile?.mkdirs()

            val content = s3Service.getObjectContent(instanceId, bucketName, obj.key)
            targetFile.writeBytes(content)
        }
    }

    private fun getExtension(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex > 0) fileName.substring(dotIndex + 1) else ""
    }

    override fun update(e: AnActionEvent) {
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE)
        e.presentation.isEnabledAndVisible = node is S3TreeNode.S3Object ||
                node is S3TreeNode.Folder ||
                node is S3TreeNode.Bucket
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
