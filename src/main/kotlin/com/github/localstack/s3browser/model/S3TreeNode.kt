package com.github.localstack.s3browser.model

import java.time.Instant

/**
 * Represents a node in the S3 tree structure.
 */
sealed class S3TreeNode {
    abstract val name: String
    abstract val path: String

    /**
     * Root node representing the LocalStack connection.
     */
    data class Root(
        val endpoint: String
    ) : S3TreeNode() {
        override val name: String = "LocalStack S3"
        override val path: String = ""
    }

    /**
     * Represents an S3 bucket.
     */
    data class Bucket(
        override val name: String,
        val creationDate: Instant? = null
    ) : S3TreeNode() {
        override val path: String = name
    }

    /**
     * Represents a virtual folder (common prefix) in S3.
     */
    data class Folder(
        override val name: String,
        val bucketName: String,
        val prefix: String
    ) : S3TreeNode() {
        override val path: String = "$bucketName/$prefix"

        val fullPrefix: String
            get() = if (prefix.endsWith("/")) prefix else "$prefix/"
    }

    /**
     * Represents an S3 object (file).
     */
    data class S3Object(
        override val name: String,
        val bucketName: String,
        val key: String,
        val size: Long = 0,
        val lastModified: Instant? = null,
        val etag: String? = null
    ) : S3TreeNode() {
        override val path: String = "$bucketName/$key"

        val extension: String
            get() = name.substringAfterLast('.', "")

        fun isTextFile(): Boolean {
            val textExtensions = setOf(
                "txt", "md", "json", "xml", "yaml", "yml", "html", "htm", "css", "js", "ts",
                "kt", "java", "py", "rb", "go", "rs", "c", "cpp", "h", "hpp", "sh", "bash",
                "sql", "csv", "tsv", "log", "conf", "cfg", "ini", "properties", "gradle",
                "toml", "env", "gitignore", "dockerfile", "makefile"
            )
            return extension.lowercase() in textExtensions || extension.isBlank()
        }
    }

    /**
     * Represents a loading placeholder node.
     */
    data class Loading(
        val parent: S3TreeNode
    ) : S3TreeNode() {
        override val name: String = "Loading..."
        override val path: String = "${parent.path}/loading"
    }

    /**
     * Represents an error node.
     */
    data class Error(
        val message: String,
        val parent: S3TreeNode? = null
    ) : S3TreeNode() {
        override val name: String = "Error: $message"
        override val path: String = parent?.path?.let { "$it/error" } ?: "error"
    }
}

/**
 * Utility functions for S3 paths.
 */
object S3PathUtils {
    /**
     * Extracts the parent prefix from a key.
     */
    fun getParentPrefix(key: String): String {
        val normalized = key.trimEnd('/')
        val lastSlash = normalized.lastIndexOf('/')
        return if (lastSlash >= 0) normalized.substring(0, lastSlash + 1) else ""
    }

    /**
     * Extracts the name (last segment) from a key.
     */
    fun getName(key: String): String {
        val normalized = key.trimEnd('/')
        val lastSlash = normalized.lastIndexOf('/')
        return if (lastSlash >= 0) normalized.substring(lastSlash + 1) else normalized
    }

    /**
     * Builds a tree structure from a flat list of keys.
     */
    fun buildFolderStructure(keys: List<String>, prefix: String = ""): Set<String> {
        val folders = mutableSetOf<String>()
        for (key in keys) {
            if (key.startsWith(prefix) && key != prefix) {
                val remaining = key.substring(prefix.length)
                val slashIndex = remaining.indexOf('/')
                if (slashIndex > 0) {
                    folders.add(prefix + remaining.substring(0, slashIndex + 1))
                }
            }
        }
        return folders
    }
}
