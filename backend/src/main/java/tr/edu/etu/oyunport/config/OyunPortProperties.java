package tr.edu.etu.oyunport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oyunport")
public record OyunPortProperties(DbProps db, JwtProps jwt) {

    public record DbProps(String url, PoolProps customer, PoolProps admin) {}

    public record PoolProps(String username, String password, int maxPoolSize) {}

    public record JwtProps(String secret, long ttlMinutes) {}
}
