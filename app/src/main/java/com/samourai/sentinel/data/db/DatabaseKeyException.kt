package com.samourai.sentinel.data.db

/**
 * The on-disk database could not be opened with the stored key.
 * The message is fixed so it never includes key bytes or row contents.
 */
class DatabaseKeyException(
    message: String = "Could not open the local database.",
    cause: Throwable? = null
) : RuntimeException(message, cause)
