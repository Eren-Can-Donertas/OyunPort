package tr.edu.etu.oyunport.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Bütün entegrasyon testlerinin ortak temeli.
 *
 * <p>Gerçek bir PostgreSQL 16 konteyneri (Testcontainers) bir kez başlatılır; üretimdeki
 * {@code db/init/01-create-db-admin.sh} ile db_admin rolü ve oyunport veritabanı kurulur,
 * Spring Boot açılışında Flyway V1–V6 migration'larını db_admin ile uygular. Uygulama
 * havuzları app_customer ve app_admin rolleriyle bağlanır — üretimle aynı güvenlik modeli.
 * Mock veritabanı kullanılmaz.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"oyunport.payment.simulated-latency-ms=0", "logging.level.tr.edu.etu.oyunport=WARN"})
@Import(TestHooksConfig.class)
public abstract class IntegrationTestBase {

    public static final String DB_ADMIN_PW = "it_db_admin_pw";
    public static final String CUSTOMER_PW = "it_customer_pw";
    public static final String ADMIN_PW = "it_admin_pw";
    public static final String REPORTING_PW = "it_reporting_pw";
    public static final String LOADER_PW = "it_loader_pw";

    /** Seed admin (V6): admin@oyunport.local / Admin123! */
    public static final String SEED_ADMIN_EMAIL = "admin@oyunport.local";
    public static final String SEED_ADMIN_PASSWORD = "Admin123!";

    protected static final PostgreSQLContainer<?> PG;

    static {
        Path init = Path.of(System.getProperty("oyunport.initScript", "../db/init/01-create-db-admin.sh"));
        if (!Files.exists(init)) {
            throw new IllegalStateException("db/init betiği bulunamadı: " + init.toAbsolutePath());
        }
        PG = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("it_superuser_pw")
                .withEnv("DB_ADMIN_PASSWORD", DB_ADMIN_PW)
                .withEnv("OYUNPORT_DB_NAME", "oyunport")
                .withCopyFileToContainer(MountableFile.forHostPath(init, 0755),
                        "/docker-entrypoint-initdb.d/01-create-db-admin.sh");
        PG.start();
    }

    public static String jdbcUrl() {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/oyunport";
    }

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry r) {
        r.add("spring.flyway.url", IntegrationTestBase::jdbcUrl);
        r.add("spring.flyway.password", () -> DB_ADMIN_PW);
        r.add("spring.flyway.placeholders.appCustomerPassword", () -> CUSTOMER_PW);
        r.add("spring.flyway.placeholders.appAdminPassword", () -> ADMIN_PW);
        r.add("spring.flyway.placeholders.reportingPassword", () -> REPORTING_PW);
        r.add("spring.flyway.placeholders.dataLoaderPassword", () -> LOADER_PW);
        r.add("oyunport.db.url", IntegrationTestBase::jdbcUrl);
        r.add("oyunport.db.customer.password", () -> CUSTOMER_PW);
        r.add("oyunport.db.admin.password", () -> ADMIN_PW);
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected ArmableCheckoutHooks hooks;

    protected Api api;
    protected Fixtures fx;

    @BeforeEach
    void setUpBase() {
        api = new Api("http://localhost:" + port);
        fx = new Fixtures();
        hooks.reset();
    }

    /** Belirli bir PostgreSQL rolüyle doğrudan JDBC bağlantısı (DB düzeyi testler için). */
    public static Connection connect(String role, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), role, password);
    }

    public static Connection dbAdmin() throws SQLException {
        return connect("db_admin", DB_ADMIN_PW);
    }

    public static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** API üzerinden müşteri kaydı; token döner. */
    protected Api.Session newCustomer() {
        return newCustomer("Test Müşteri");
    }

    protected Api.Session newCustomer(String displayName) {
        String email = "cust-" + uniq() + "@test.oyunport";
        Api.Resp r = api.postAnon("/api/auth/register",
                java.util.Map.of("email", email, "displayName", displayName, "password", "Parola123!"));
        if (r.status() != 201) {
            throw new IllegalStateException("Kayıt başarısız: " + r);
        }
        return new Api.Session(r.body().get("token").asText(), r.body().at("/user/userId").asLong(), email);
    }

    protected Api.Session seedAdmin() {
        Api.Resp r = api.postAnon("/api/auth/login",
                java.util.Map.of("email", SEED_ADMIN_EMAIL, "password", SEED_ADMIN_PASSWORD));
        if (r.status() != 200) {
            throw new IllegalStateException("Admin girişi başarısız: " + r);
        }
        return new Api.Session(r.body().get("token").asText(), r.body().at("/user/userId").asLong(), SEED_ADMIN_EMAIL);
    }
}
