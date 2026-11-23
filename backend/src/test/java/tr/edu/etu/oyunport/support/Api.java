package tr.edu.etu.oyunport.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Gerçek HTTP üzerinden REST çağrıları (Spring Security, controller, JDBC ve PostgreSQL birlikte çalışır). */
public final class Api {

    public record Resp(int status, JsonNode body) {
        public String code() {
            return body.path("code").asText(null);
        }

        public String message() {
            return body.path("message").asText(null);
        }
    }

    public record Session(String token, long userId, String email) {}

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String base;

    public Api(String base) {
        this.base = base;
    }

    public Resp get(String path, Session s) {
        return send("GET", path, s == null ? null : s.token(), null);
    }

    public Resp post(String path, Session s, Object body) {
        return send("POST", path, s == null ? null : s.token(), body);
    }

    public Resp postAnon(String path, Object body) {
        return send("POST", path, null, body);
    }

    public Resp put(String path, Session s, Object body) {
        return send("PUT", path, s == null ? null : s.token(), body);
    }

    public Resp delete(String path, Session s) {
        return send("DELETE", path, s == null ? null : s.token(), null);
    }

    private Resp send(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json");
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = r.body() == null || r.body().isBlank() ? NullNode.getInstance() : MAPPER.readTree(r.body());
            return new Resp(r.statusCode(), node);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
