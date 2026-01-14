package com.github.localstack.s3browser.actions

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
import com.github.localstack.s3browser.toolwindow.S3BrowserPanel
import com.github.localstack.s3browser.toolwindow.S3NodeTransferData
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.SwingUtilities

/**
 * Action to paste files/folders from the system clipboard to S3.
 * Supports pasting:
 * - Local files from the filesystem
 * - S3 objects copied from within the browser (S3-to-S3 copy)
 */
class PasteAction : AnAction() {

    private val log = Logger.getInstance(PasteAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.getData(S3BrowserPanel.S3_BROWSER_PANEL) ?: return
        val targetNode = e.getData(S3BrowserPanel.S3_TREE_NODE) ?: return

        val (instanceId, bucketName, prefix) = getTargetLocation(targetNode) ?: return

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val transferable = clipboard.getContents(null) ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                when {
                    // Check for S3 node data first (internal copy)
                    transferable.isDataFlavorSupported(S3NodeTransferData.DATA_FLAVOR) -> {
                        val s3Data = transferable.getTransferData(S3NodeTransferData.DATA_FLAVOR) as S3NodeTransferData
                        pasteS3Node(s3Data, instanceId, bucketName, prefix)
                    }
                    // Fall back to file list (external paste or after download)
                    transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                        @Suppress("UNCHECKED_CAST")
                        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        pasteLocalFiles(files, instanceId, bucketName, prefix)
                    }
                    else -> {
                        log.info("Clipboard does not contain supported data for paste")
                        return@executeOnPooledThread
                    }
                }

                // Refresh the target node after paste
                SwingUtilities.invokeLater {
                    when (targetNode) {
                        is S3TreeNode.Bucket -> panel.treeModel.refreshNode(targetNode)
                        is S3TreeNode.Folder -> panel.treeModel.refreshNode(targetNode)
                        is S3TreeNode.S3Object -> {
                            // Refresh parent
                            val parentPath = panel.getSelectedPath()?.parentPath
                            val parentNode = parentPath?.lastPathComponent as? S3TreeNode
                            if (parentNode != null) {
                                panel.treeModel.refreshNode(parentNode)
                            } else {
                                panel.refresh()
                            }
                        }
                        else -> panel.refresh()
                    }
                }
            } catch (ex: Exception) {
                log.warn("Failed to paste from clipboard", ex)
            }
        }
    }

    private fun getTargetLocation(node: S3TreeNode): Triple<String, String, String>? {
        return when (node) {
            is S3TreeNode.Bucket -> Triple(node.instanceId, node.name, "")
            is S3TreeNode.Folder -> Triple(node.instanceId, node.bucketName, node.fullPrefix)
            is S3TreeNode.S3Object -> {
                val parentPrefix = node.key.substringBeforeLast("/", "")
                Triple(node.instanceId, node.bucketName, if (parentPrefix.isEmpty()) "" else "$parentPrefix/")
            }
            else -> null
        }
    }

    private fun pasteS3Node(
        s3Data: S3NodeTransferData,
        destInstanceId: String,
        destBucket: String,
        destPrefix: String
    ) {
        val s3Service = S3ClientService.getInstance()

        when (s3Data.nodeType) {
            S3NodeTransferData.NodeType.S3_OBJECT -> {
                val newKey = destPrefix + s3Data.name
                s3Service.copyObject(
                    s3Data.instanceId,
                    s3Data.bucketName,
                    s3Data.key,
                    destBucket,
                    newKey
                )
                log.info("Pasted S3 object: ${s3Data.bucketName}/${s3Data.key} -> $destBucket/$newKey")
            }
            S3NodeTransferData.NodeType.FOLDER -> {
                copyFolderRecursively(
                    s3Service,
                    s3Data.instanceId,
                    s3Data.bucketName,
                    s3Data.key,  // folder prefix
                    destInstanceId,
                    destBucket,
                    destPrefix + s3Data.name + "/"
                )
                log.info("Pasted S3 folder: ${s3Data.bucketName}/${s3Data.key} -> $destBucket/$destPrefix${s3Data.name}/")
            }
            S3NodeTransferData.NodeType.BUCKET -> {
                // Copy all contents of the bucket to the destination
                copyFolderRecursively(
                    s3Service,
                    s3Data.instanceId,
                    s3Data.bucketName,
                    "",
                    destInstanceId,
                    destBucket,
                    destPrefix + s3Data.name + "/"
                )
                log.info("Pasted S3 bucket contents: ${s3Data.bucketName} -> $destBucket/$destPrefix${s3Data.name}/")
            }
        }
    }

    private fun copyFolderRecursively(
        s3Service: S3ClientService,
        srcInstanceId: String,
        srcBucket: String,
        srcPrefix: String,
        destInstanceId: String,
        destBucket: String,
        destPrefix: String
    ) {
        val objects = s3Service.listAllObjects(srcInstanceId, srcBucket, srcPrefix)

        for (obj in objects) {
            val relativePath = if (srcPrefix.isNotEmpty()) {
                obj.key.removePrefix(srcPrefix)
            } else {
                obj.key
            }

            if (relativePath.isEmpty()) continue

            val destKey = destPrefix + relativePath

            // For cross-instance copies, we need to download and re-upload
            if (srcInstanceId != destInstanceId) {
                val content = s3Service.getObjectContent(srcInstanceId, srcBucket, obj.key)
                s3Service.putObject(destInstanceId, destBucket, destKey, content, null)
            } else {
                s3Service.copyObject(srcInstanceId, srcBucket, obj.key, destBucket, destKey)
            }
        }
    }

    private fun pasteLocalFiles(
        files: List<File>,
        instanceId: String,
        bucketName: String,
        prefix: String
    ) {
        val s3Service = S3ClientService.getInstance()

        for (file in files) {
            uploadFileRecursively(s3Service, instanceId, bucketName, prefix, file)
        }
        log.info("Pasted ${files.size} local file(s) to $bucketName/$prefix")
    }

    private fun uploadFileRecursively(
        s3Service: S3ClientService,
        instanceId: String,
        bucketName: String,
        prefix: String,
        file: File
    ) {
        if (file.isDirectory) {
            val newPrefix = "$prefix${file.name}/"
            s3Service.createFolder(instanceId, bucketName, newPrefix)
            file.listFiles()?.forEach { child ->
                uploadFileRecursively(s3Service, instanceId, bucketName, newPrefix, child)
            }
        } else {
            val key = "$prefix${file.name}"
            s3Service.putObject(instanceId, bucketName, key, file.readBytes(), null)
        }
    }

    override fun update(e: AnActionEvent) {
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE)

        // Can paste to buckets, folders, or next to objects
        val canPaste = node is S3TreeNode.Bucket ||
                node is S3TreeNode.Folder ||
                node is S3TreeNode.S3Object

        // Check if clipboard has pasteable content
        val hasPasteableContent = if (canPaste) {
            try {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val transferable = clipboard.getContents(null)
                transferable?.isDataFlavorSupported(DataFlavor.javaFileListFlavor) == true ||
                        transferable?.isDataFlavorSupported(S3NodeTransferData.DATA_FLAVOR) == true
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }

        e.presentation.isEnabledAndVisible = canPaste && hasPasteableContent
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
