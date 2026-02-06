package com.github.localstack.s3browser.actions

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
import com.github.localstack.s3browser.toolwindow.S3BrowserPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.SwingUtilities

/**
 * Action to create a ZIP archive from selected S3 objects, folders, or buckets.
 */
class CreateZipAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE) ?: return

        when (node) {
            is S3TreeNode.S3Object -> createZipFromObject(project, node)
            is S3TreeNode.Folder -> createZipFromFolder(project, node)
            is S3TreeNode.Bucket -> createZipFromBucket(project, node)
            else -> return
        }
    }

    private fun createZipFromObject(project: Project, node: S3TreeNode.S3Object) {
        val defaultName = getZipName(node.name)
        val targetFile = promptSaveLocation(project, defaultName) ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val s3Service = S3ClientService.getInstance()
                val content = s3Service.getObjectContent(node.instanceId, node.bucketName, node.key)

                ZipOutputStream(targetFile.outputStream()).use { zos ->
                    zos.putNextEntry(ZipEntry(node.name))
                    zos.write(content)
                    zos.closeEntry()
                }

                showSuccess(project, targetFile)
            } catch (ex: Exception) {
                showError(project, ex)
            }
        }
    }

    private fun createZipFromFolder(project: Project, node: S3TreeNode.Folder) {
        val includeSubdirs = askIncludeSubdirectories(project) ?: return
        val defaultName = getZipName(node.name)
        val targetFile = promptSaveLocation(project, defaultName) ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val s3Service = S3ClientService.getInstance()
                val objects = if (includeSubdirs) {
                    s3Service.listAllObjects(node.instanceId, node.bucketName, node.fullPrefix)
                } else {
                    s3Service.listObjects(node.instanceId, node.bucketName, node.fullPrefix).second
                }

                ZipOutputStream(targetFile.outputStream()).use { zos ->
                    for (obj in objects) {
                        val relativePath = obj.key.removePrefix(node.fullPrefix)
                        if (relativePath.isEmpty() || relativePath.endsWith("/")) continue

                        zos.putNextEntry(ZipEntry(relativePath))
                        val content = s3Service.getObjectContent(node.instanceId, node.bucketName, obj.key)
                        zos.write(content)
                        zos.closeEntry()
                    }
                }

                showSuccess(project, targetFile)
            } catch (ex: Exception) {
                showError(project, ex)
            }
        }
    }

    private fun createZipFromBucket(project: Project, node: S3TreeNode.Bucket) {
        val includeSubdirs = askIncludeSubdirectories(project) ?: return
        val defaultName = getZipName(node.name)
        val targetFile = promptSaveLocation(project, defaultName) ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val s3Service = S3ClientService.getInstance()
                val objects = if (includeSubdirs) {
                    s3Service.listAllObjects(node.instanceId, node.name, "")
                } else {
                    s3Service.listObjects(node.instanceId, node.name, "").second
                }

                ZipOutputStream(targetFile.outputStream()).use { zos ->
                    for (obj in objects) {
                        if (obj.key.isEmpty() || obj.key.endsWith("/")) continue

                        zos.putNextEntry(ZipEntry(obj.key))
                        val content = s3Service.getObjectContent(node.instanceId, node.name, obj.key)
                        zos.write(content)
                        zos.closeEntry()
                    }
                }

                showSuccess(project, targetFile)
            } catch (ex: Exception) {
                showError(project, ex)
            }
        }
    }

    /**
     * Asks the user whether to include subdirectories recursively.
     * Returns true/false, or null if the user cancelled the dialog.
     */
    private fun askIncludeSubdirectories(project: Project): Boolean? {
        val result = Messages.showYesNoCancelDialog(
            project,
            "Include files from subdirectories recursively?",
            "Create Zip",
            "Yes",
            "No",
            "Cancel",
            Messages.getQuestionIcon()
        )
        return when (result) {
            Messages.YES -> true
            Messages.NO -> false
            else -> null
        }
    }

    private fun promptSaveLocation(project: Project, defaultName: String): File? {
        val descriptor = FileSaverDescriptor(
            "Create Zip",
            "Choose location to save the zip file",
            "zip"
        )
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, defaultName) ?: return null
        return wrapper.file
    }

    private fun getZipName(name: String): String {
        val baseName = name.trimEnd('/')
        return "$baseName.zip"
    }

    private fun showSuccess(project: Project, file: File) {
        SwingUtilities.invokeLater {
            Messages.showInfoMessage(
                project,
                "Created zip file: ${file.absolutePath}",
                "Create Zip Complete"
            )
        }
    }

    private fun showError(project: Project, ex: Exception) {
        SwingUtilities.invokeLater {
            Messages.showErrorDialog(
                project,
                "Failed to create zip: ${ex.message}",
                "Create Zip Error"
            )
        }
    }

    override fun update(e: AnActionEvent) {
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE)
        e.presentation.isEnabledAndVisible = node is S3TreeNode.S3Object ||
                node is S3TreeNode.Folder ||
                node is S3TreeNode.Bucket
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
