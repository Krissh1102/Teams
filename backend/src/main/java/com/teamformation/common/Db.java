package com.teamformation.common;

import java.security.MessageDigest;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.SqlTypeValue;

/** Small JDBC helpers shared by the services. */
public final class Db {
    private Db() {}

    /** Bind a List<String> as a PostgreSQL text[] parameter. */
    public static SqlTypeValue textArray(Collection<String> values) {
        String[] arr = values.toArray(new String[0]);
        return (ps, idx, sqlType, typeName) -> ps.setArray(idx, ps.getConnection().createArrayOf("text", arr));
    }

    public static List<String> strings(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        if (a == null) return List.of();
        Object[] raw = (Object[]) a.getArray();
        List<String> out = new ArrayList<>(raw.length);
        for (Object o : raw) out.add((String) o);
        return out;
    }

    public static UUID uuid(ResultSet rs, String col) throws SQLException { return rs.getObject(col, UUID.class); }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    /** Trim, lowercase, drop blanks/duplicates, cap the count. Used for skills, interests and "looking for". */
    public static List<String> normalize(List<String> in, int max) {
        if (in == null) return List.of();
        return in.stream().filter(s -> s != null).map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).distinct().limit(max).toList();
    }

    public static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
