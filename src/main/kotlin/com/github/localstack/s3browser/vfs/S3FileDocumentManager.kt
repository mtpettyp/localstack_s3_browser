package com.github.localstack.s3browser.vfs

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.vfs.VirtualFile

/**
 * Listener to handle document save events for S3 files.
 * This ensures that when a user saves a document, it gets uploaded to S3.
 */
class S3FileDocumentManagerListener : FileDocumentManagerListener {

    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document)
        if (file is S3VirtualFile) {
            // Update the cached content before save
            val content = document.text.toByteArray(Charsets.UTF_8)
            file.setContent(content)
            // Save to S3
            file.saveToS3()
        }
    }

    override fun beforeAllDocumentsSaving() {
        // Handle bulk save - iterate through all open S3 files
        val fileDocumentManager = FileDocumentManager.getInstance()
        for (document in fileDocumentManager.unsavedDocuments) {
            val file = fileDocumentManager.getFile(document)
            if (file is S3VirtualFile && file.isDirty()) {
                val content = document.text.toByteArray(Charsets.UTF_8)
                file.setContent(content)
                file.saveToS3()
            }
        }
    }
}
