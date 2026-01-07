package com.github.localstack.s3browser.toolwindow

import com.github.localstack.s3browser.model.S3TreeNode
import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*
import javax.swing.Icon
import javax.swing.JTree

/**
 * Custom cell renderer for S3 tree nodes.
 */
class S3TreeCellRenderer : ColoredTreeCellRenderer() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        when (val node = value) {
            is S3TreeNode.Root -> {
                icon = AllIcons.Nodes.PpWeb
                append(node.name)
                append(" (${node.endpoint})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }

            is S3TreeNode.Bucket -> {
                icon = AllIcons.Nodes.Module
                append(node.name)
                node.creationDate?.let { date ->
                    append(" - ${formatDate(date)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }

            is S3TreeNode.Folder -> {
                icon = if (expanded) AllIcons.Nodes.FolderOpen else AllIcons.Nodes.Folder
                append(node.name)
            }

            is S3TreeNode.S3Object -> {
                icon = getFileIcon(node.name)
                append(node.name)
                append(" (${formatSize(node.size)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }

            is S3TreeNode.Loading -> {
                icon = AllIcons.Process.Step_1
                append("Loading...", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }

            is S3TreeNode.Error -> {
                icon = AllIcons.General.Error
                append(node.name, SimpleTextAttributes.ERROR_ATTRIBUTES)
            }
        }
    }

    private fun getFileIcon(fileName: String): Icon {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        return fileType.icon ?: AllIcons.FileTypes.Any_type
    }

    private fun formatSize(bytes: Long): String {
        return StringUtil.formatFileSize(bytes)
    }

    private fun formatDate(instant: Instant): String {
        return dateFormat.format(Date.from(instant))
    }
}
