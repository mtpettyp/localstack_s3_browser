package com.github.localstack.s3browser.settings

import java.util.UUID

/**
 * Represents a LocalStack instance configuration.
 */
data class LocalStackInstance(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "LocalStack S3",
    var endpoint: String = "http://localhost:4566",
    var region: String = "us-east-1",
    var accessKeyId: String = "test",
    var secretAccessKey: String = "test"
) {
    /**
     * Creates a copy with a new ID.
     */
    fun copyWithNewId(): LocalStackInstance = copy(id = UUID.randomUUID().toString())

    companion object {
        /**
         * Creates a default LocalStack instance.
         */
        fun createDefault(): LocalStackInstance = LocalStackInstance()
    }
}
