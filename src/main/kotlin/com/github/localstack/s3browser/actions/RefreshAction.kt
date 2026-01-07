package com.github.localstack.s3browser.actions

import com.github.localstack.s3browser.toolwindow.S3BrowserPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action to refresh the S3 browser tree.
 */
class RefreshAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.getData(S3BrowserPanel.S3_BROWSER_PANEL) ?: return
        panel.refresh()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(S3BrowserPanel.S3_BROWSER_PANEL) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
