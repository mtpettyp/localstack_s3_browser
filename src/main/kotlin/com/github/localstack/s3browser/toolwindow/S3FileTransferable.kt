package com.github.localstack.s3browser.toolwindow

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
import com.intellij.openapi.diagnostic.Logger
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

/**
 * Transferable implementation for S3 files.
 * Downloads files to a temp directory when dragged out.
 */
class S3FileTransferable(
    private val node: S3TreeNode,
    private val panel: S3BrowserPanel
) : Transferable {

    private val log = Logger.getInstance(S3FileTransferable::class.java)

    companion object {
        private val supportedFlavors = arrayOf(DataFlavor.javaFileListFlavor)
    }

    override fun getTransferDataFlavors(): Array<DataFlavor> = supportedFlavors

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean {
        return flavor == DataFlavor.javaFileListFlavor
    }

    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) {
            throw UnsupportedFlavorException(flavor)
        }

        return when (node) {
            is S3TreeNode.S3Object -> listOf(downloadFile(node))
            is S3TreeNode.Folder -> listOf(downloadFolder(node))
            is S3TreeNode.Bucket -> listOf(downloadBucket(node))
            else -> emptyList<File>()
        }
    }

    private fun downloadFile(obj: S3TreeNode.S3Object): File {
        val tempDir = createTempDirectory()
        val targetFile = File(tempDir, obj.name)

        try {
            val content = S3ClientService.getInstance().getObjectContent(
                obj.instanceId,
                obj.bucketName,
                obj.key
            )
            targetFile.writeBytes(content)
            log.info("Downloaded ${obj.key} to ${targetFile.absolutePath} for drag operation")
        } catch (e: Exception) {
            log.warn("Failed to download file for drag: ${obj.key}", e)
        }

        return targetFile
    }

    private fun downloadFolder(folder: S3TreeNode.Folder): File {
        val tempDir = createTempDirectory()
        val targetDir = File(tempDir, folder.name)
        targetDir.mkdirs()

        try {
            downloadFolderRecursively(
                folder.instanceId,
                folder.bucketName,
                folder.fullPrefix,
                targetDir
            )
            log.info("Downloaded folder ${folder.fullPrefix} to ${targetDir.absolutePath} for drag operation")
        } catch (e: Exception) {
            log.warn("Failed to download folder for drag: ${folder.fullPrefix}", e)
        }

        return targetDir
    }

    private fun downloadBucket(bucket: S3TreeNode.Bucket): File {
        val tempDir = createTempDirectory()
        val targetDir = File(tempDir, bucket.name)
        targetDir.mkdirs()

        try {
            downloadFolderRecursively(
                bucket.instanceId,
                bucket.name,
                "",
                targetDir
            )
            log.info("Downloaded bucket ${bucket.name} to ${targetDir.absolutePath} for drag operation")
        } catch (e: Exception) {
            log.warn("Failed to download bucket for drag: ${bucket.name}", e)
        }

        return targetDir
    }

    private fun downloadFolderRecursively(
        instanceId: String,
        bucketName: String,
        prefix: String,
        targetDir: File
    ) {
        val s3Service = S3ClientService.getInstance()
        val objects = s3Service.listAllObjects(instanceId, bucketName, prefix)

        for (obj in objects) {
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

    private fun createTempDirectory(): File {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "s3-browser-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        tempDir.deleteOnExit()
        return tempDir
    }
}
