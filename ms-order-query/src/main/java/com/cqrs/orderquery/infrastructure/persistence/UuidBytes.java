package com.cqrs.orderquery.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;

public final class UuidBytes {
    private UuidBytes() {}
    public static byte[] from(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    public static UUID to(byte[] value) { var b=ByteBuffer.wrap(value); return new UUID(b.getLong(),b.getLong()); }
}
