package com.github.localstack.s3browser.actions

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.toolwindow.S3BrowserPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

/**
 * Action to copy the S3 path to clipboard.
 */
class CopyPathAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE) ?: return

        val s3Path = when (node) {
            is S3TreeNode.Bucket -> "s3://${node.name}/"
            is S3TreeNode.Folder -> "s3://${node.bucketName}/${node.fullPrefix}"
            is S3TreeNode.S3Object -> "s3://${node.bucketName}/${node.key}"
            else -> return
        }

        CopyPasteManager.getInstance().setContents(StringSelection(s3Path))
    }

    override fun update(e: AnActionEvent) {
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE)
        e.presentation.isEnabledAndVisible = node is S3TreeNode.Bucket ||
                node is S3TreeNode.Folder ||
                node is S3TreeNode.S3Object
    }
}
