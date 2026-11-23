package tr.edu.etu.oyunport.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tr.edu.etu.oyunport.common.ApiException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Açık JDBC transaction sınırları.
 *
 * <pre>
 *   BEGIN                         (setAutoCommit(false))
 *   SELECT set_config('app.user_id', :uid, true)    -- SET LOCAL: yalnızca bu transaction
 *   ... iş birimi ...
 *   COMMIT  | hata durumunda ROLLBACK
 * </pre>
 *
 * Kullanıcı kimliği havuzdaki bağlantıya oturum düzeyinde değil, transaction düzeyinde
 * yazılır; bağlantı havuza döndüğünde başka kullanıcıya sızmaz (rapor §7.2).
 */
@Component
public class Db {

    private static final Logger log = LoggerFactory.getLogger(Db.class);

    private final DataSource customerPool;
    private final DataSource adminPool;
    private final SqlErrorTranslator translator;

    public Db(@Qualifier("customer") DataSource customerPool,
              @Qualifier("admin") DataSource adminPool,
              SqlErrorTranslator translator) {
        this.customerPool = customerPool;
        this.adminPool = adminPool;
        this.translator = translator;
    }

    /** app_customer havuzunda transaction. userId null ise anonim (yalnızca katalog). */
    public <T> T customer(Long userId, TxWork<T> work) {
        return inTransaction(customerPool, "customer", userId, false, work);
    }

    public <T> T customerReadOnly(Long userId, TxWork<T> work) {
        return inTransaction(customerPool, "customer", userId, true, work);
    }

    /** app_admin havuzunda transaction (katalog DML, rapor view'ları). */
    public <T> T admin(Long userId, TxWork<T> work) {
        return inTransaction(adminPool, "admin", userId, false, work);
    }

    public <T> T adminReadOnly(Long userId, TxWork<T> work) {
        return inTransaction(adminPool, "admin", userId, true, work);
    }

    private <T> T inTransaction(DataSource ds, String poolName, Long userId, boolean readOnly, TxWork<T> work) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            c.setReadOnly(readOnly);
            log.debug("[{}] BEGIN uid={} readOnly={}", poolName, userId, readOnly);
            try {
                if (userId != null) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.user_id', ?, true)")) {
                        ps.setString(1, Long.toString(userId));
                        ps.execute();
                    }
                }
                T result = work.run(new Tx(c));
                c.commit();
                log.debug("[{}] COMMIT uid={}", poolName, userId);
                return result;
            } catch (Throwable e) {
                safeRollback(c, poolName, userId, e);
                throw e;
            } finally {
                c.setReadOnly(false);
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw translator.translate(e);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void safeRollback(Connection c, String poolName, Long userId, Throwable cause) {
        try {
            c.rollback();
            log.debug("[{}] ROLLBACK uid={} cause={}", poolName, userId, cause.toString());
        } catch (SQLException rollbackError) {
            cause.addSuppressed(rollbackError);
        }
    }
}
