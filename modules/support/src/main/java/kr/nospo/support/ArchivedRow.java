package kr.nospo.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Decodes v1 archive fields. Callers select explicit columns; archive content is never SQL. */
public final class ArchivedRow {
    private ArchivedRow() {}
    public static JsonNode part(ObjectMapper mapper,JsonNode root,String name) {
        return mapper.readTree(Base64.getDecoder().decode(root.path(name).asString()));
    }
    public static String text(JsonNode row,String name) {var v=row.path(name);return v.isNull()||v.isMissingNode()?null:v.asString();}
    public static byte[] binary(JsonNode row,String name) {String v=text(row,name);return v==null?null:Base64.getDecoder().decode(v);}
    public static java.sql.Timestamp time(JsonNode row,String name) {String v=text(row,name);return v==null?null:SqlIds.at(Instant.parse(v));}
}
