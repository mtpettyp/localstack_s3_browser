package com.github.localstack.s3browser.actions

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.toolwindow.S3BrowserPanel
import com.github.localstack.s3browser.toolwindow.S3FileTransferable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import java.awt.Toolkit

/**
 * Action to copy S3 files/folders to the system clipboard.
 * The copied files can then be pasted into the local filesystem.
 */
class CopyAction : AnAction() {

    private val log = Logger.getInstance(CopyAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.getData(S3BrowserPanel.S3_BROWSER_PANEL) ?: return
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE) ?: return

        if (node !is S3TreeNode.S3Object && node !is S3TreeNode.Folder && node !is S3TreeNode.Bucket) {
            return
        }

        val transferable = S3FileTransferable(node, panel)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
        log.info("Copied ${node.name} to clipboard")
    }

    override fun update(e: AnActionEvent) {
        val node = e.getData(S3BrowserPanel.S3_TREE_NODE)
        e.presentation.isEnabledAndVisible = node is S3TreeNode.S3Object ||
                node is S3TreeNode.Folder ||
                node is S3TreeNode.Bucket
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
