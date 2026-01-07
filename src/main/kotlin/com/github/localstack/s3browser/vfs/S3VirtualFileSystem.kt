package com.github.localstack.s3browser.vfs

import com.github.localstack.s3browser.services.S3ClientService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*
import java.io.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Virtual File System implementation for S3 files.
 * Allows S3 objects to be opened and edited in IntelliJ editors.
 */
class S3VirtualFileSystem : VirtualFileSystem() {

    private val log = Logger.getInstance(S3VirtualFileSystem::class.java)
    private val fileCache = ConcurrentHashMap<String, S3VirtualFile>()

    override fun getProtocol(): String = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile? {
        return fileCache[path]
    }

    override fun refreshAndFindFileByPath(path: String): VirtualFile? {
        val file = fileCache[path]
        file?.refresh(false, false)
        return file
    }

    override fun refresh(asynchronous: Boolean) {
        // Refresh all cached files
        for (file in fileCache.values) {
            file.refresh(asynchronous, false)
        }
    }

    /**
     * Finds or creates a virtual file for an S3 object.
     */
    fun findOrCreateFile(bucketName: String, key: String, project: Project): S3VirtualFile {
        val path = "$bucketName/$key"
        return fileCache.getOrPut(path) {
            S3VirtualFile(this, bucketName, key, project)
        }
    }

    /**
     * Removes a file from the cache.
     */
    fun removeFromCache(bucketName: String, key: String) {
        val path = "$bucketName/$key"
        fileCache.remove(path)
    }

    /**
     * Clears all cached files.
     */
    fun clearCache() {
        fileCache.clear()
    }

    // Unsupported operations for read-only-ish VFS
    override fun addVirtualFileListener(listener: VirtualFileListener) {}
    override fun removeVirtualFileListener(listener: VirtualFileListener) {}
    override fun deleteFile(requestor: Any?, vFile: VirtualFile) {
        throw UnsupportedOperationException("Use S3 actions to delete files")
    }
    override fun moveFile(requestor: Any?, vFile: VirtualFile, newParent: VirtualFile) {
        throw UnsupportedOperationException("Use S3 actions to move files")
    }
    override fun renameFile(requestor: Any?, vFile: VirtualFile, newName: String) {
        throw UnsupportedOperationException("Use S3 actions to rename files")
    }
    override fun createChildFile(requestor: Any?, vDir: VirtualFile, fileName: String): VirtualFile {
        throw UnsupportedOperationException("Use S3 actions to create files")
    }
    override fun createChildDirectory(requestor: Any?, vDir: VirtualFile, dirName: String): VirtualFile {
        throw UnsupportedOperationException("Use S3 actions to create directories")
    }
    override fun copyFile(requestor: Any?, virtualFile: VirtualFile, newParent: VirtualFile, copyName: String): VirtualFile {
        throw UnsupportedOperationException("Use S3 actions to copy files")
    }
    override fun isReadOnly(): Boolean = false

    companion object {
        const val PROTOCOL = "s3"

        @JvmStatic
        fun getInstance(): S3VirtualFileSystem {
            return VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as S3VirtualFileSystem
        }
    }
}

/**
 * Virtual file representing an S3 object.
 */
class S3VirtualFile(
    private val fileSystem: S3VirtualFileSystem,
    val bucketName: String,
    val key: String,
    private val project: Project
) : VirtualFile() {

    private val log = Logger.getInstance(S3VirtualFile::class.java)

    @Volatile
    private var cachedContent: ByteArray? = null

    @Volatile
    private var modificationStamp: Long = System.currentTimeMillis()

    @Volatile
    private var dirty = false

    override fun getName(): String = key.substringAfterLast('/')

    override fun getFileSystem(): VirtualFileSystem = fileSystem

    override fun getPath(): String = "$bucketName/$key"

    override fun isWritable(): Boolean = true

    override fun isDirectory(): Boolean = false

    override fun isValid(): Boolean = true

    override fun getParent(): VirtualFile? = null

    override fun getChildren(): Array<VirtualFile>? = null

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                val content = toByteArray()
                cachedContent = content
                dirty = true
                modificationStamp = newModificationStamp
            }
        }
    }

    override fun contentsToByteArray(): ByteArray {
        cachedContent?.let { return it }

        return try {
            val content = S3ClientService.getInstance().getObjectContent(bucketName, key, project)
            cachedContent = content
            content
        } catch (e: Exception) {
            log.warn("Failed to load content for $path", e)
            ByteArray(0)
        }
    }

    override fun getTimeStamp(): Long = modificationStamp

    override fun getLength(): Long = cachedContent?.size?.toLong() ?: 0

    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {
        if (!dirty) {
            cachedContent = null
        }
        postRunnable?.run()
    }

    override fun getInputStream(): InputStream {
        return ByteArrayInputStream(contentsToByteArray())
    }

    override fun getModificationStamp(): Long = modificationStamp

    /**
     * Saves the cached content to S3.
     */
    fun saveToS3() {
        val content = cachedContent ?: return
        if (!dirty) return

        try {
            S3ClientService.getInstance().putObject(bucketName, key, content, null, project)
            dirty = false
            log.info("Saved $path to S3")
        } catch (e: Exception) {
            log.warn("Failed to save $path to S3", e)
            throw e
        }
    }

    /**
     * Returns true if the file has unsaved changes.
     */
    fun isDirty(): Boolean = dirty

    /**
     * Updates the cached content.
     */
    fun setContent(content: ByteArray) {
        cachedContent = content
        dirty = true
        modificationStamp = System.currentTimeMillis()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is S3VirtualFile) return false
        return bucketName == other.bucketName && key == other.key
    }

    override fun hashCode(): Int {
        return 31 * bucketName.hashCode() + key.hashCode()
    }
}
