package tr.edu.etu.oyunport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tr.edu.etu.oyunport.auth.JwtAuthFilter;
import tr.edu.etu.oyunport.auth.JwtService;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rol bazlı erişim (FR01):
 * <ul>
 *   <li>Herkese açık: kayıt/giriş, sağlık kontrolü, katalog okuma.</li>
 *   <li>CUSTOMER: sepet, sipariş, ödeme, kütüphane, yorum, istek listesi.</li>
 *   <li>ADMIN: /api/admin/** (katalog yönetimi ve raporlar).</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService, ObjectMapper mapper)
            throws Exception {
        http
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/health", "/api/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/catalog/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/me", "/api/me/**").authenticated()
                .requestMatchers("/api/**").hasRole("CUSTOMER")
                .anyRequest().permitAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> writeError(res, mapper, 401, "UNAUTHORIZED",
                        "Bu işlem için giriş yapmalısınız."))
                .accessDeniedHandler((req, res, ex) -> writeError(res, mapper, 403, "FORBIDDEN",
                        "Bu işlem için yetkiniz yok.")))
            .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeError(jakarta.servlet.http.HttpServletResponse res, ObjectMapper mapper,
                                   int status, String code, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status);
        body.put("code", code);
        body.put("message", message);
        body.put("details", java.util.List.of());
        mapper.writeValue(res.getOutputStream(), body);
    }
}
