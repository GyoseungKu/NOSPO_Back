package kr.nospo.support;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

public final class SqlIds {
    private SqlIds() {}

    public static byte[] id(UUID id) {
        return id == null
                ? null
                : ByteBuffer.allocate(16)
                        .putLong(id.getMostSignificantBits())
                        .putLong(id.getLeastSignificantBits())
                        .array();
    }

    public static UUID uuid(byte[] bytes) {
        if (bytes == null) return null;
        var b = ByteBuffer.wrap(bytes);
        return new UUID(b.getLong(), b.getLong());
    }

    public static Timestamp at(Instant time) {
        return time == null ? null : Timestamp.from(time);
    }
}
