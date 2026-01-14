package com.github.localstack.s3browser.toolwindow

import com.github.localstack.s3browser.model.S3TreeNode
import java.awt.datatransfer.DataFlavor
import java.io.Serializable

/**
 * Data class that holds S3 node information for clipboard transfers.
 * This allows S3-to-S3 copy operations without downloading files.
 */
data class S3NodeTransferData(
    val nodeType: NodeType,
    val instanceId: String,
    val bucketName: String,
    val key: String,  // For objects: the key, for folders: the prefix, for buckets: empty
    val name: String  // Display name of the item
) : Serializable {

    enum class NodeType {
        S3_OBJECT,
        FOLDER,
        BUCKET
    }

    companion object {
        private const val MIME_TYPE = "application/x-s3-browser-node"

        @JvmField
        val DATA_FLAVOR = DataFlavor(MIME_TYPE, "S3 Browser Node")

        /**
         * Creates transfer data from an S3TreeNode.
         */
        fun fromNode(node: S3TreeNode): S3NodeTransferData? {
            return when (node) {
                is S3TreeNode.S3Object -> S3NodeTransferData(
                    nodeType = NodeType.S3_OBJECT,
                    instanceId = node.instanceId,
                    bucketName = node.bucketName,
                    key = node.key,
                    name = node.name
                )
                is S3TreeNode.Folder -> S3NodeTransferData(
                    nodeType = NodeType.FOLDER,
                    instanceId = node.instanceId,
                    bucketName = node.bucketName,
                    key = node.fullPrefix,
                    name = node.name
                )
                is S3TreeNode.Bucket -> S3NodeTransferData(
                    nodeType = NodeType.BUCKET,
                    instanceId = node.instanceId,
                    bucketName = node.name,
                    key = "",
                    name = node.name
                )
                else -> null
            }
        }
    }
}
