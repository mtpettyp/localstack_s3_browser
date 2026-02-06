package com.github.localstack.s3browser.vfs

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

/**
 * Shows a warning banner in the editor when an S3 object no longer exists in the bucket.
 */
class S3EditorNotificationProvider : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?>? {
        if (file !is S3VirtualFile || !file.objectMissing) return null

        return Function { fileEditor ->
            val panel = EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Warning)
            panel.text = "This S3 object no longer exists in bucket '${file.bucketName}'"
            panel.createActionLabel("Close") {
                FileEditorManager.getInstance(project).closeFile(file)
            }
            panel
        }
    }
}
