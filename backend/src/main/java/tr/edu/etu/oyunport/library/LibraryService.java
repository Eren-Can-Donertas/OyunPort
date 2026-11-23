package tr.edu.etu.oyunport.library;

import org.springframework.stereotype.Service;
import tr.edu.etu.oyunport.common.ApiException;
import tr.edu.etu.oyunport.db.Db;
import tr.edu.etu.oyunport.db.Tx;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Kütüphane ve oynama oturumu simülasyonu (FR07). Oynama süreleri gerçek oyun
 * süreçlerinden toplanmaz (rapor §1.3); başlat/bitir veya dakika girerek kaydedilir.
 * Oturum yalnızca sahip olunan BASE_GAME için açılabilir (BR06; servis + trigger).
 */
@Service
public class LibraryService {

    private final Db db;

    public LibraryService(Db db) {
        this.db = db;
    }

    public record LibraryItem(long libraryId, long productId, String title, String productType, Long baseGameId,
                              Instant acquiredAt, long orderId, int completedSessions, long totalSeconds,
                              Instant lastPlayedAt, Long openSessionId, Integer myRating) {}

    public record SessionView(long sessionId, long libraryId, Instant startedAt, Instant endedAt, Long durationSeconds) {}

    private record Entry(long libraryId, long productId, boolean baseGame, Instant acquiredAt) {}

    public List<LibraryItem> list(long userId) {
        return db.customerReadOnly(userId, tx -> tx.query("""
                SELECT lp.library_id, lp.product_id, p.title, lp.acquired_at, le.order_id,
                       CASE WHEN bg.product_id IS NOT NULL THEN 'BASE_GAME' ELSE 'DLC' END AS product_type,
                       d.base_game_id, lp.completed_sessions, lp.total_seconds, lp.last_played_at,
                       (SELECT ps.session_id FROM play_session ps
                         WHERE ps.library_id = lp.library_id AND ps.ended_at IS NULL) AS open_session_id,
                       r.rating AS my_rating
                  FROM v_library_playtime lp
                  JOIN library_entry le  ON le.library_id = lp.library_id
                  JOIN product p         ON p.product_id = lp.product_id
                  LEFT JOIN base_game bg ON bg.product_id = lp.product_id
                  LEFT JOIN dlc d        ON d.product_id = lp.product_id
                  LEFT JOIN review r     ON r.user_id = lp.user_id AND r.base_game_id = lp.product_id
                 WHERE lp.user_id = ?
                 ORDER BY lp.acquired_at DESC, p.title
                """, rs -> new LibraryItem(rs.getLong("library_id"), rs.getLong("product_id"), rs.getString("title"),
                rs.getString("product_type"), Tx.getLongOrNull(rs, "base_game_id"), Tx.getInstant(rs, "acquired_at"),
                rs.getLong("order_id"), rs.getInt("completed_sessions"), rs.getLong("total_seconds"),
                Tx.getInstant(rs, "last_played_at"), Tx.getLongOrNull(rs, "open_session_id"),
                (Integer) rs.getObject("my_rating", Integer.class)), userId));
    }

    public List<SessionView> sessions(long userId, long libraryId) {
        return db.customerReadOnly(userId, tx -> {
            requireEntry(tx, libraryId);
            return tx.query("""
                    SELECT session_id, library_id, started_at, ended_at,
                           extract(epoch FROM ended_at - started_at)::bigint AS duration_seconds
                      FROM play_session WHERE library_id = ?
                     ORDER BY started_at DESC LIMIT 100
                    """, LibraryService::mapSession, libraryId);
        });
    }

    public SessionView start(long userId, long libraryId) {
        return db.customer(userId, tx -> {
            Entry e = requireBaseGameEntry(tx, libraryId);
            return tx.queryOne("""
                    INSERT INTO play_session (library_id, started_at) VALUES (?, now())
                    RETURNING session_id, library_id, started_at, ended_at, NULL::bigint AS duration_seconds
                    """, LibraryService::mapSession, e.libraryId()).orElseThrow();
        });
    }

    public SessionView end(long userId, long libraryId, long sessionId) {
        return db.customer(userId, tx -> {
            requireEntry(tx, libraryId);
            return tx.queryOne("""
                    UPDATE play_session SET ended_at = now()
                     WHERE session_id = ? AND library_id = ? AND ended_at IS NULL
                    RETURNING session_id, library_id, started_at, ended_at,
                              extract(epoch FROM ended_at - started_at)::bigint AS duration_seconds
                    """, LibraryService::mapSession, sessionId, libraryId)
                    .orElseThrow(() -> ApiException.conflict("SESSION_NOT_OPEN", "Açık bir oturum bulunamadı."));
        });
    }

    /** Demo kolaylığı: son N dakikalık tamamlanmış bir oturum kaydeder (edinimden önceye taşmaz). */
    public SessionView record(long userId, long libraryId, int minutes) {
        if (minutes < 1 || minutes > 24 * 60) {
            throw ApiException.badRequest("Süre 1 ile 1440 dakika arasında olmalıdır.");
        }
        return db.customer(userId, tx -> {
            Entry e = requireBaseGameEntry(tx, libraryId);
            return tx.queryOne("""
                    INSERT INTO play_session (library_id, started_at, ended_at)
                    VALUES (?, greatest(now() - make_interval(mins => ?), ?), now())
                    RETURNING session_id, library_id, started_at, ended_at,
                              extract(epoch FROM ended_at - started_at)::bigint AS duration_seconds
                    """, LibraryService::mapSession, e.libraryId(), minutes, e.acquiredAt()).orElseThrow();
        });
    }

    private static Entry requireEntry(Tx tx, long libraryId) throws SQLException {
        // RLS: başka kullanıcının kaydı görünmez -> 404
        return tx.queryOne("""
                SELECT le.library_id, le.product_id, (bg.product_id IS NOT NULL) AS is_base, le.acquired_at
                  FROM library_entry le LEFT JOIN base_game bg ON bg.product_id = le.product_id
                 WHERE le.library_id = ?
                """, rs -> new Entry(rs.getLong(1), rs.getLong(2), rs.getBoolean(3), Tx.getInstant(rs, "acquired_at")),
                libraryId).orElseThrow(() -> ApiException.notFound("Kütüphane kaydı bulunamadı."));
    }

    private static Entry requireBaseGameEntry(Tx tx, long libraryId) throws SQLException {
        Entry e = requireEntry(tx, libraryId);
        if (!e.baseGame()) {
            throw ApiException.unprocessable("SESSION_ONLY_FOR_BASE_GAME",
                    "[BR06] Oynama oturumu yalnızca ana oyun (BASE_GAME) için açılabilir; DLC için açılamaz.");
        }
        return e;
    }

    private static SessionView mapSession(java.sql.ResultSet rs) throws SQLException {
        return new SessionView(rs.getLong("session_id"), rs.getLong("library_id"), Tx.getInstant(rs, "started_at"),
                Tx.getInstant(rs, "ended_at"), Tx.getLongOrNull(rs, "duration_seconds"));
    }
}
