package com.github.localstack.s3browser.services

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.settings.S3BrowserAppSettings
import com.github.localstack.s3browser.settings.S3BrowserProjectSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Duration

/**
 * Service for interacting with S3 via LocalStack.
 * This service manages S3 client creation and provides methods for S3 operations.
 */
@Service(Service.Level.APP)
class S3ClientService : Disposable {

    private val log = Logger.getInstance(S3ClientService::class.java)
    private var cachedClient: S3Client? = null
    private var cachedEndpoint: String? = null
    private var cachedRegion: String? = null

    /**
     * Gets or creates an S3 client for the given project.
     */
    fun getClient(project: Project? = null): S3Client {
        val endpoint: String
        val region: String

        if (project != null) {
            val projectSettings = S3BrowserProjectSettings.getInstance(project)
            endpoint = projectSettings.getEffectiveEndpoint()
            region = projectSettings.getEffectiveRegion()
        } else {
            val appSettings = S3BrowserAppSettings.getInstance()
            endpoint = appSettings.defaultEndpoint
            region = appSettings.defaultRegion
        }

        // Return cached client if settings haven't changed
        if (cachedClient != null && cachedEndpoint == endpoint && cachedRegion == region) {
            return cachedClient!!
        }

        // Close existing client if any
        cachedClient?.close()

        val appSettings = S3BrowserAppSettings.getInstance()

        cachedClient = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .forcePathStyle(true) // Required for LocalStack
            .overrideConfiguration { config ->
                config.apiCallTimeout(Duration.ofMillis(appSettings.connectionTimeoutMs.toLong()))
                config.apiCallAttemptTimeout(Duration.ofMillis(appSettings.connectionTimeoutMs.toLong()))
            }
            .build()

        cachedEndpoint = endpoint
        cachedRegion = region

        log.info("Created S3 client for endpoint: $endpoint, region: $region")
        return cachedClient!!
    }

    /**
     * Invalidates the cached client, forcing recreation on next access.
     */
    fun invalidateClient() {
        cachedClient?.close()
        cachedClient = null
        cachedEndpoint = null
        cachedRegion = null
    }

    // ==================== Bucket Operations ====================

    /**
     * Lists all buckets.
     */
    fun listBuckets(project: Project? = null): List<S3TreeNode.Bucket> {
        return try {
            val response = getClient(project).listBuckets()
            response.buckets().map { bucket ->
                S3TreeNode.Bucket(
                    name = bucket.name(),
                    creationDate = bucket.creationDate()
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to list buckets", e)
            throw S3OperationException("Failed to list buckets: ${e.message}", e)
        }
    }

    /**
     * Creates a new bucket.
     */
    fun createBucket(bucketName: String, project: Project? = null) {
        try {
            getClient(project).createBucket(
                CreateBucketRequest.builder()
                    .bucket(bucketName)
                    .build()
            )
            log.info("Created bucket: $bucketName")
        } catch (e: Exception) {
            log.warn("Failed to create bucket: $bucketName", e)
            throw S3OperationException("Failed to create bucket '$bucketName': ${e.message}", e)
        }
    }

    /**
     * Deletes a bucket. The bucket must be empty.
     */
    fun deleteBucket(bucketName: String, project: Project? = null) {
        try {
            getClient(project).deleteBucket(
                DeleteBucketRequest.builder()
                    .bucket(bucketName)
                    .build()
            )
            log.info("Deleted bucket: $bucketName")
        } catch (e: Exception) {
            log.warn("Failed to delete bucket: $bucketName", e)
            throw S3OperationException("Failed to delete bucket '$bucketName': ${e.message}", e)
        }
    }

    /**
     * Deletes a bucket and all its contents.
     */
    fun deleteBucketRecursively(bucketName: String, project: Project? = null) {
        try {
            // First, delete all objects in the bucket
            val objects = listAllObjects(bucketName, "", project)
            for (obj in objects) {
                deleteObject(bucketName, obj.key, project)
            }
            // Then delete the bucket
            deleteBucket(bucketName, project)
            log.info("Recursively deleted bucket: $bucketName")
        } catch (e: Exception) {
            log.warn("Failed to recursively delete bucket: $bucketName", e)
            throw S3OperationException("Failed to delete bucket '$bucketName' and its contents: ${e.message}", e)
        }
    }

    // ==================== Object Operations ====================

    /**
     * Lists objects in a bucket with the given prefix.
     * Returns both folders (common prefixes) and files.
     */
    fun listObjects(
        bucketName: String,
        prefix: String = "",
        project: Project? = null
    ): Pair<List<S3TreeNode.Folder>, List<S3TreeNode.S3Object>> {
        return try {
            val request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .delimiter("/")
                .build()

            val response = getClient(project).listObjectsV2(request)

            val folders = response.commonPrefixes().map { cp ->
                val folderPrefix = cp.prefix()
                val name = folderPrefix.trimEnd('/').substringAfterLast('/')
                S3TreeNode.Folder(
                    name = name,
                    bucketName = bucketName,
                    prefix = folderPrefix
                )
            }

            val objects = response.contents()
                .filter { it.key() != prefix } // Exclude the prefix itself if it's a "folder marker"
                .map { obj ->
                    S3TreeNode.S3Object(
                        name = obj.key().substringAfterLast('/'),
                        bucketName = bucketName,
                        key = obj.key(),
                        size = obj.size(),
                        lastModified = obj.lastModified(),
                        etag = obj.eTag()
                    )
                }

            Pair(folders, objects)
        } catch (e: Exception) {
            log.warn("Failed to list objects in $bucketName/$prefix", e)
            throw S3OperationException("Failed to list objects: ${e.message}", e)
        }
    }

    /**
     * Lists all objects in a bucket (no delimiter, recursive).
     */
    fun listAllObjects(
        bucketName: String,
        prefix: String = "",
        project: Project? = null
    ): List<S3TreeNode.S3Object> {
        return try {
            val objects = mutableListOf<S3TreeNode.S3Object>()
            var continuationToken: String? = null

            do {
                val requestBuilder = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)

                if (continuationToken != null) {
                    requestBuilder.continuationToken(continuationToken)
                }

                val response = getClient(project).listObjectsV2(requestBuilder.build())

                objects.addAll(response.contents().map { obj ->
                    S3TreeNode.S3Object(
                        name = obj.key().substringAfterLast('/'),
                        bucketName = bucketName,
                        key = obj.key(),
                        size = obj.size(),
                        lastModified = obj.lastModified(),
                        etag = obj.eTag()
                    )
                })

                continuationToken = if (response.isTruncated) response.nextContinuationToken() else null
            } while (continuationToken != null)

            objects
        } catch (e: Exception) {
            log.warn("Failed to list all objects in $bucketName/$prefix", e)
            throw S3OperationException("Failed to list objects: ${e.message}", e)
        }
    }

    /**
     * Gets the content of an object as a byte array.
     */
    fun getObjectContent(bucketName: String, key: String, project: Project? = null): ByteArray {
        return try {
            val response = getClient(project).getObject(
                GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build()
            )

            val outputStream = ByteArrayOutputStream()
            response.use { input ->
                input.copyTo(outputStream)
            }
            outputStream.toByteArray()
        } catch (e: Exception) {
            log.warn("Failed to get object content: $bucketName/$key", e)
            throw S3OperationException("Failed to get object '$key': ${e.message}", e)
        }
    }

    /**
     * Gets the content of an object as a string.
     */
    fun getObjectContentAsString(bucketName: String, key: String, project: Project? = null): String {
        return String(getObjectContent(bucketName, key, project), Charsets.UTF_8)
    }

    /**
     * Uploads content to an S3 object.
     */
    fun putObject(
        bucketName: String,
        key: String,
        content: ByteArray,
        contentType: String? = null,
        project: Project? = null
    ) {
        try {
            val requestBuilder = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)

            if (contentType != null) {
                requestBuilder.contentType(contentType)
            }

            getClient(project).putObject(
                requestBuilder.build(),
                RequestBody.fromBytes(content)
            )
            log.info("Uploaded object: $bucketName/$key (${content.size} bytes)")
        } catch (e: Exception) {
            log.warn("Failed to put object: $bucketName/$key", e)
            throw S3OperationException("Failed to upload '$key': ${e.message}", e)
        }
    }

    /**
     * Uploads string content to an S3 object.
     */
    fun putObjectString(
        bucketName: String,
        key: String,
        content: String,
        contentType: String? = "text/plain",
        project: Project? = null
    ) {
        putObject(bucketName, key, content.toByteArray(Charsets.UTF_8), contentType, project)
    }

    /**
     * Deletes an object.
     */
    fun deleteObject(bucketName: String, key: String, project: Project? = null) {
        try {
            getClient(project).deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build()
            )
            log.info("Deleted object: $bucketName/$key")
        } catch (e: Exception) {
            log.warn("Failed to delete object: $bucketName/$key", e)
            throw S3OperationException("Failed to delete '$key': ${e.message}", e)
        }
    }

    /**
     * Deletes all objects with the given prefix (folder deletion).
     */
    fun deleteObjectsWithPrefix(bucketName: String, prefix: String, project: Project? = null) {
        try {
            val objects = listAllObjects(bucketName, prefix, project)
            for (obj in objects) {
                deleteObject(bucketName, obj.key, project)
            }
            log.info("Deleted ${objects.size} objects with prefix: $bucketName/$prefix")
        } catch (e: Exception) {
            log.warn("Failed to delete objects with prefix: $bucketName/$prefix", e)
            throw S3OperationException("Failed to delete folder '$prefix': ${e.message}", e)
        }
    }

    /**
     * Copies an object to a new location.
     */
    fun copyObject(
        sourceBucket: String,
        sourceKey: String,
        destBucket: String,
        destKey: String,
        project: Project? = null
    ) {
        try {
            getClient(project).copyObject(
                CopyObjectRequest.builder()
                    .sourceBucket(sourceBucket)
                    .sourceKey(sourceKey)
                    .destinationBucket(destBucket)
                    .destinationKey(destKey)
                    .build()
            )
            log.info("Copied object: $sourceBucket/$sourceKey -> $destBucket/$destKey")
        } catch (e: Exception) {
            log.warn("Failed to copy object: $sourceBucket/$sourceKey -> $destBucket/$destKey", e)
            throw S3OperationException("Failed to copy '$sourceKey' to '$destKey': ${e.message}", e)
        }
    }

    /**
     * Moves an object to a new location (copy + delete).
     */
    fun moveObject(
        sourceBucket: String,
        sourceKey: String,
        destBucket: String,
        destKey: String,
        project: Project? = null
    ) {
        copyObject(sourceBucket, sourceKey, destBucket, destKey, project)
        deleteObject(sourceBucket, sourceKey, project)
        log.info("Moved object: $sourceBucket/$sourceKey -> $destBucket/$destKey")
    }

    /**
     * Renames an object within the same bucket.
     */
    fun renameObject(bucketName: String, oldKey: String, newKey: String, project: Project? = null) {
        moveObject(bucketName, oldKey, bucketName, newKey, project)
    }

    /**
     * Creates a folder marker (empty object with trailing slash).
     */
    fun createFolder(bucketName: String, folderPath: String, project: Project? = null) {
        val normalizedPath = if (folderPath.endsWith("/")) folderPath else "$folderPath/"
        putObject(bucketName, normalizedPath, ByteArray(0), "application/x-directory", project)
        log.info("Created folder: $bucketName/$normalizedPath")
    }

    /**
     * Tests the connection to S3.
     */
    fun testConnection(project: Project? = null): Boolean {
        return try {
            getClient(project).listBuckets()
            true
        } catch (e: Exception) {
            log.info("Connection test failed", e)
            false
        }
    }

    override fun dispose() {
        cachedClient?.close()
        cachedClient = null
    }

    companion object {
        @JvmStatic
        fun getInstance(): S3ClientService {
            return ApplicationManager.getApplication().getService(S3ClientService::class.java)
        }
    }
}

/**
 * Exception thrown when an S3 operation fails.
 */
class S3OperationException(message: String, cause: Throwable? = null) : Exception(message, cause)
