package tr.edu.etu.oyunport.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * İki ayrı HikariCP bağlantı havuzu (rapor §7.2):
 * <ul>
 *   <li><b>customer-pool</b> — PostgreSQL rolü {@code app_customer}; RLS ile yalnızca oturum sahibinin verisi.</li>
 *   <li><b>admin-pool</b> — PostgreSQL rolü {@code app_admin}; katalog DML ve rapor view'ları.</li>
 * </ul>
 * Şema sahibi {@code db_admin} yalnızca Flyway tarafından kullanılır, uygulama havuzuna girmez.
 * Havuzlar tembel başlar: roller Flyway migration'ı ile oluşturulduktan sonra ilk istekte bağlanılır.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @Qualifier("customer")
    public DataSource customerDataSource(OyunPortProperties props) {
        return pool("customer-pool", props.db().url(), props.db().customer());
    }

    @Bean
    @Qualifier("admin")
    public DataSource adminDataSource(OyunPortProperties props) {
        return pool("admin-pool", props.db().url(), props.db().admin());
    }

    private static HikariDataSource pool(String name, String url, OyunPortProperties.PoolProps p) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName(name);
        ds.setJdbcUrl(url);
        ds.setUsername(p.username());
        ds.setPassword(p.password());
        ds.setMaximumPoolSize(p.maxPoolSize());
        ds.setMinimumIdle(Math.min(2, p.maxPoolSize()));
        ds.setAutoCommit(true);
        ds.setConnectionTimeout(10_000);
        ds.addDataSourceProperty("ApplicationName", "oyunport-" + name);
        ds.addDataSourceProperty("reWriteBatchedInserts", "true");
        return ds;
    }
}
