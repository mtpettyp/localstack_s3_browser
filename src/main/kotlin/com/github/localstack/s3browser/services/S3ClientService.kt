package com.github.localstack.s3browser.services

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.settings.LocalStackInstance
import com.github.localstack.s3browser.settings.S3BrowserAppSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for interacting with S3 via LocalStack.
 * This service manages S3 client creation and provides methods for S3 operations.
 * Supports multiple LocalStack instances.
 */
@Service(Service.Level.APP)
class S3ClientService : Disposable {

    private val log = Logger.getInstance(S3ClientService::class.java)
    private val clientCache = ConcurrentHashMap<String, S3Client>()

    /**
     * Gets or creates an S3 client for the given instance ID.
     */
    fun getClient(instanceId: String): S3Client {
        val settings = S3BrowserAppSettings.getInstance()
        val instance = settings.getInstanceById(instanceId)
            ?: throw S3OperationException("Instance not found: $instanceId")

        return clientCache.getOrPut(instanceId) {
            createClient(instance)
        }
    }

    /**
     * Creates a new S3 client for the given instance configuration.
     */
    private fun createClient(instance: LocalStackInstance): S3Client {
        val appSettings = S3BrowserAppSettings.getInstance()

        val credentials = AwsBasicCredentials.create(instance.accessKeyId, instance.secretAccessKey)
        val credentialsProvider = StaticCredentialsProvider.create(credentials)

        val client = S3Client.builder()
            .endpointOverride(URI.create(instance.endpoint))
            .region(Region.of(instance.region))
            .credentialsProvider(credentialsProvider)
            .forcePathStyle(true) // Required for LocalStack
            .overrideConfiguration { config ->
                config.apiCallTimeout(Duration.ofMillis(appSettings.connectionTimeoutMs.toLong()))
                config.apiCallAttemptTimeout(Duration.ofMillis(appSettings.connectionTimeoutMs.toLong()))
            }
            .build()

        log.info("Created S3 client for instance '${instance.name}' at endpoint: ${instance.endpoint}")
        return client
    }

    /**
     * Invalidates a cached client for a specific instance.
     */
    fun invalidateClient(instanceId: String) {
        clientCache.remove(instanceId)?.close()
    }

    /**
     * Invalidates all cached clients.
     */
    fun invalidateAllClients() {
        clientCache.values.forEach { it.close() }
        clientCache.clear()
    }

    // ==================== Bucket Operations ====================

    /**
     * Lists all buckets for an instance.
     */
    fun listBuckets(instanceId: String): List<S3TreeNode.Bucket> {
        return try {
            val response = getClient(instanceId).listBuckets()
            response.buckets().map { bucket ->
                S3TreeNode.Bucket(
                    name = bucket.name(),
                    instanceId = instanceId,
                    creationDate = bucket.creationDate()
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to list buckets for instance $instanceId", e)
            throw S3OperationException("Failed to list buckets: ${e.message}", e)
        }
    }

    /**
     * Creates a new bucket.
     */
    fun createBucket(instanceId: String, bucketName: String) {
        try {
            getClient(instanceId).createBucket(
                CreateBucketRequest.builder()
                    .bucket(bucketName)
                    .build()
            )
            log.info("Created bucket: $bucketName on instance $instanceId")
        } catch (e: Exception) {
            log.warn("Failed to create bucket: $bucketName on instance $instanceId", e)
            throw S3OperationException("Failed to create bucket '$bucketName': ${e.message}", e)
        }
    }

    /**
     * Deletes a bucket. The bucket must be empty.
     */
    fun deleteBucket(instanceId: String, bucketName: String) {
        try {
            getClient(instanceId).deleteBucket(
                DeleteBucketRequest.builder()
                    .bucket(bucketName)
                    .build()
            )
            log.info("Deleted bucket: $bucketName on instance $instanceId")
        } catch (e: Exception) {
            log.warn("Failed to delete bucket: $bucketName on instance $instanceId", e)
            throw S3OperationException("Failed to delete bucket '$bucketName': ${e.message}", e)
        }
    }

    /**
     * Deletes a bucket and all its contents.
     */
    fun deleteBucketRecursively(instanceId: String, bucketName: String) {
        try {
            // First, delete all objects in the bucket
            val objects = listAllObjects(instanceId, bucketName, "")
            for (obj in objects) {
                deleteObject(instanceId, bucketName, obj.key)
            }
            // Then delete the bucket
            deleteBucket(instanceId, bucketName)
            log.info("Recursively deleted bucket: $bucketName on instance $instanceId")
        } catch (e: Exception) {
            log.warn("Failed to recursively delete bucket: $bucketName on instance $instanceId", e)
            throw S3OperationException("Failed to delete bucket '$bucketName' and its contents: ${e.message}", e)
        }
    }

    // ==================== Object Operations ====================

    /**
     * Lists objects in a bucket with the given prefix.
     * Returns both folders (common prefixes) and files.
     */
    fun listObjects(
        instanceId: String,
        bucketName: String,
        prefix: String = ""
    ): Pair<List<S3TreeNode.Folder>, List<S3TreeNode.S3Object>> {
        return try {
            val request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .delimiter("/")
                .build()

            val response = getClient(instanceId).listObjectsV2(request)

            val folders = response.commonPrefixes().map { cp ->
                val folderPrefix = cp.prefix()
                val name = folderPrefix.trimEnd('/').substringAfterLast('/')
                S3TreeNode.Folder(
                    name = name,
                    instanceId = instanceId,
                    bucketName = bucketName,
                    prefix = folderPrefix
                )
            }

            val objects = response.contents()
                .filter { it.key() != prefix } // Exclude the prefix itself if it's a "folder marker"
                .map { obj ->
                    S3TreeNode.S3Object(
                        name = obj.key().substringAfterLast('/'),
                        instanceId = instanceId,
                        bucketName = bucketName,
                        key = obj.key(),
                        size = obj.size(),
                        lastModified = obj.lastModified(),
                        etag = obj.eTag()
                    )
                }

            Pair(folders, objects)
        } catch (e: Exception) {
            log.warn("Failed to list objects in $bucketName/$prefix on instance $instanceId", e)
            throw S3OperationException("Failed to list objects: ${e.message}", e)
        }
    }

    /**
     * Lists all objects in a bucket (no delimiter, recursive).
     */
    fun listAllObjects(
        instanceId: String,
        bucketName: String,
        prefix: String = ""
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

                val response = getClient(instanceId).listObjectsV2(requestBuilder.build())

                objects.addAll(response.contents().map { obj ->
                    S3TreeNode.S3Object(
                        name = obj.key().substringAfterLast('/'),
                        instanceId = instanceId,
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
            log.warn("Failed to list all objects in $bucketName/$prefix on instance $instanceId", e)
            throw S3OperationException("Failed to list objects: ${e.message}", e)
        }
    }

    /**
     * Gets the content of an object as a byte array.
     */
    fun getObjectContent(instanceId: String, bucketName: String, key: String): ByteArray {
        return try {
            val response = getClient(instanceId).getObject(
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
            log.warn("Failed to get object content: $bucketName/$key on instance $instanceId", e)
            throw S3OperationException("Failed to get object '$key': ${e.message}", e)
        }
    }

    /**
     * Gets the content of an object as a string.
     */
    fun getObjectContentAsString(instanceId: String, bucketName: String, key: String): String {
        return String(getObjectContent(instanceId, bucketName, key), Charsets.UTF_8)
    }

    /**
     * Uploads content to an S3 object.
     */
    fun putObject(
        instanceId: String,
        bucketName: String,
        key: String,
        content: ByteArray,
        contentType: String? = null
    ) {
        try {
            val requestBuilder = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)

            if (contentType != null) {
                requestBuilder.contentType(contentType)
            }

            getClient(instanceId).putObject(
                requestBuilder.build(),
                RequestBody.fromBytes(content)
            )
            log.info("Uploaded object: $bucketName/$key (${content.size} bytes) on instance $instanceId")
        } catch (e: Exception) {
            log.warn("Failed to put object: $bucketName/$key on instance $instanceId", e)
            throw S3OperationException("Failed to upload '$key': ${e.message}", e)
        }
    }

    /**
     * Uploads string content to an S3 object.
     */
    fun putObjectString(
        instanceId: String,
        bucketName: String,
        key: String,
        content: String,
        contentType: String? = "text/plain"
    ) {
        putObject(instanceId, bucketName, key, content.toByteArray(Charsets.UTF_8), contentType)
    }

    /**
     * Deletes an object.
     */
    fun deleteObject(instanceId: String, bucketName: String, key: String) {
        try {
            getClient(instanceId).deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build()
            )
            log.info("Deleted object: $bucketName/$key on instance $instanceId")
        } catch (e: Exception) {
            log.warn("Failed to delete object: $bucketName/$key on instance $instanceId", e)
            throw S3OperationException("Failed to delete '$key': ${e.message}", e)
        }
    }

    /**
     * Deletes all objects with the given prefix (folder deletion).
     */
    fun deleteObjectsWithPrefix(instanceId: String, bucketName: String, prefix: String) {
        try {
            val objects = listAllObjects(instanceId, bucketName, prefix)
            for (obj in objects) {
                deleteObject(instanceId, bucketName, obj.key)
            }
            log.info("Deleted ${objects.size} objects with prefix: $bucketName/$prefix on instance $instanceId")
        } catch (e: Exception) {
            log.warn("Failed to delete objects with prefix: $bucketName/$prefix on instance $instanceId", e)
            throw S3OperationException("Failed to delete folder '$prefix': ${e.message}", e)
        }
    }

    /**
     * Copies an object to a new location.
     */
    fun copyObject(
        instanceId: String,
        sourceBucket: String,
        sourceKey: String,
        destBucket: String,
        destKey: String
    ) {
        try {
            getClient(instanceId).copyObject(
                CopyObjectRequest.builder()
                    .sourceBucket(sourceBucket)
                    .sourceKey(sourceKey)
                    .destinationBucket(destBucket)
                    .destinationKey(destKey)
                    .build()
            )
            log.info("Copied object: $sourceBucket/$sourceKey -> $destBucket/$destKey on instance $instanceId")
        } catch (e: Exception) {
            log.warn("Failed to copy object: $sourceBucket/$sourceKey -> $destBucket/$destKey on instance $instanceId", e)
            throw S3OperationException("Failed to copy '$sourceKey' to '$destKey': ${e.message}", e)
        }
    }

    /**
     * Moves an object to a new location (copy + delete).
     */
    fun moveObject(
        instanceId: String,
        sourceBucket: String,
        sourceKey: String,
        destBucket: String,
        destKey: String
    ) {
        copyObject(instanceId, sourceBucket, sourceKey, destBucket, destKey)
        deleteObject(instanceId, sourceBucket, sourceKey)
        log.info("Moved object: $sourceBucket/$sourceKey -> $destBucket/$destKey on instance $instanceId")
    }

    /**
     * Renames an object within the same bucket.
     */
    fun renameObject(instanceId: String, bucketName: String, oldKey: String, newKey: String) {
        moveObject(instanceId, bucketName, oldKey, bucketName, newKey)
    }

    /**
     * Touches an object, updating its last modified timestamp by copying it in place.
     */
    fun touchObject(instanceId: String, bucketName: String, key: String) {
        try {
            getClient(instanceId).copyObject(
                CopyObjectRequest.builder()
                    .sourceBucket(bucketName)
                    .sourceKey(key)
                    .destinationBucket(bucketName)
                    .destinationKey(key)
                    .metadataDirective(MetadataDirective.REPLACE)
                    .build()
            )
            log.info("Touched object: $bucketName/$key on instance $instanceId")
        } catch (e: Exception) {
            log.warn("Failed to touch object: $bucketName/$key on instance $instanceId", e)
            throw S3OperationException("Failed to touch '$key': ${e.message}", e)
        }
    }

    /**
     * Creates a folder marker (empty object with trailing slash).
     */
    fun createFolder(instanceId: String, bucketName: String, folderPath: String) {
        val normalizedPath = if (folderPath.endsWith("/")) folderPath else "$folderPath/"
        putObject(instanceId, bucketName, normalizedPath, ByteArray(0), "application/x-directory")
        log.info("Created folder: $bucketName/$normalizedPath on instance $instanceId")
    }

    /**
     * Tests the connection to S3 for a specific instance.
     */
    fun testConnection(instanceId: String): Boolean {
        return try {
            getClient(instanceId).listBuckets()
            true
        } catch (e: Exception) {
            log.info("Connection test failed for instance $instanceId", e)
            false
        }
    }

    override fun dispose() {
        invalidateAllClients()
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
