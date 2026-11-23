package tr.edu.etu.oyunport.db;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Açık bir JDBC transaction'ı üzerinde PreparedStatement yardımcıları.
 * Bütün değerler bind parametresi olarak gönderilir; SQL metnine kullanıcı girdisi eklenmez.
 */
public final class Tx {

    private final Connection connection;

    Tx(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        List<T> rows = query(sql, mapper, params);
        if (rows.size() > 1) {
            throw new SQLException("Tek satır beklenirken " + rows.size() + " satır döndü");
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public long queryLong(String sql, Object... params) throws SQLException {
        return queryOne(sql, rs -> rs.getLong(1), params)
                .orElseThrow(() -> new SQLException("Sonuç bulunamadı: " + sql));
    }

    public int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** {@code INSERT ... RETURNING <id>} gibi tek BIGINT döndüren ifadeler. */
    public long insertReturningLong(String sql, Object... params) throws SQLException {
        return queryLong(sql, params);
    }

    public Array bigintArray(Collection<Long> values) throws SQLException {
        return connection.createArrayOf("bigint", values.toArray(Long[]::new));
    }

    private void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;
            switch (p) {
                case null -> ps.setNull(idx, Types.NULL);
                case Instant instant -> ps.setObject(idx, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
                case BigDecimal bd -> ps.setBigDecimal(idx, bd);
                case Collection<?> c -> ps.setArray(idx, connection.createArrayOf("bigint", c.toArray()));
                default -> ps.setObject(idx, p);
            }
        }
    }

    // ---- ResultSet yardımcıları ----

    public static Long getLongOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public static Instant getInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    public static List<String> getStringList(ResultSet rs, String col) throws SQLException {
        Array arr = rs.getArray(col);
        if (arr == null) {
            return List.of();
        }
        Object[] values = (Object[]) arr.getArray();
        List<String> out = new ArrayList<>(values.length);
        for (Object v : values) {
            out.add(String.valueOf(v));
        }
        return out;
    }

    public static List<Long> getLongList(ResultSet rs, String col) throws SQLException {
        Array arr = rs.getArray(col);
        if (arr == null) {
            return List.of();
        }
        Object[] values = (Object[]) arr.getArray();
        List<Long> out = new ArrayList<>(values.length);
        for (Object v : values) {
            out.add(((Number) v).longValue());
        }
        return out;
    }
}
