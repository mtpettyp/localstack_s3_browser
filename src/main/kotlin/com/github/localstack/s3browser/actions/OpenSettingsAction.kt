package com.github.localstack.s3browser.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * Action to open the LocalStack S3 Browser settings.
 */
class OpenSettingsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            "com.github.localstack.s3browser.settings.S3BrowserAppConfigurable"
        )
    }
}
