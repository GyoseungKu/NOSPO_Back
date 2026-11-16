package kr.nospo.support;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Versioned archives use DB column names; timestamps use UTC strings and binary fields use base64. */
public final class ArchiveRows {
    private ArchiveRows() {}
    public static byte[] read(JdbcTemplate db, ObjectMapper mapper, String sql, Object... args) {
        return mapper.writeValueAsBytes(db.query(sql, (r, n) -> {
            Map<String,Object> values = new LinkedHashMap<>();
            var metadata = r.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                Object value = r.getObject(i);
                if (metadata.getColumnType(i) == java.sql.Types.TIMESTAMP
                        || metadata.getColumnType(i) == java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                    value = value == null ? null : r.getTimestamp(i).toInstant().toString();
                else if (value instanceof java.sql.Timestamp t) value = t.toInstant().toString();
                else if (value instanceof java.sql.Blob b) value = b.getBytes(1, Math.toIntExact(b.length()));
                values.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), value);
            }
            return values;
        }, args));
    }
}
