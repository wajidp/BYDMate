package com.bydmate.app.plus.upload

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * BYDMate+ cloud upload record ids (api/openapi.yaml, "Record ids"). Car-log trips and idle
 * drains get a name-based UUIDv5, so a re-import after a wipe produces the same id; everything
 * else gets a random v4 from the triggers in [UploadSchema].
 */
internal object UploadUuid {

    /** Namespace fixed by the server spec; never change it, or every car-log trip gets a new id. */
    val NAMESPACE: UUID = UUID.fromString("53ea42d3-6a27-4009-bef0-47e864cd3d93")

    fun trip(bydId: Long): UUID = v5(NAMESPACE, "trip:byd:$bydId")

    fun idleDrain(startTs: Long): UUID = v5(NAMESPACE, "idle_drain:$startTs")

    /** RFC 4122 §4.3: SHA-1 over namespace bytes + name, version 5, IETF variant. */
    fun v5(namespace: UUID, name: String): UUID {
        val ns = ByteBuffer.allocate(16)
            .putLong(namespace.mostSignificantBits)
            .putLong(namespace.leastSignificantBits)
            .array()
        val hash = MessageDigest.getInstance("SHA-1").run {
            update(ns)
            update(name.toByteArray(Charsets.UTF_8))
            digest()
        }
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val buf = ByteBuffer.wrap(hash, 0, 16)
        return UUID(buf.long, buf.long)
    }
}
