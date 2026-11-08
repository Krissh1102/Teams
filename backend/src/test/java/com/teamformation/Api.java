package com.teamformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Minimal real-HTTP client (thread-safe, supports PATCH) used by every integration test. */
public final class Api {
    public record Resp(int status, JsonNode body, java.net.http.HttpHeaders headers) {
        public String code() { return body.has("code") ? body.get("code").asText() : null; }
        public boolean replayed() { return headers.firstValue("Idempotent-Replay").isPresent(); }
    }

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ObjectMapper om = new ObjectMapper();
    private final String base;

    public Api(int port) { this.base = "http://localhost:" + port; }

    public Resp send(String method, String path, String token, Object body, String idemKey) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
            if (token != null) b.header("Authorization", "Bearer " + token);
            if (idemKey != null) b.header("Idempotency-Key", idemKey);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = r.body() == null || r.body().isBlank() ? NullNode.instance : om.readTree(r.body());
            return new Resp(r.statusCode(), node, r.headers());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Resp get(String path, String token)                       { return send("GET", path, token, null, null); }
    public Resp post(String path, String token, Object body)         { return send("POST", path, token, body, null); }
    public Resp post(String path, String token, Object body, String key) { return send("POST", path, token, body, key); }
    public Resp patch(String path, String token, Object body)        { return send("PATCH", path, token, body, null); }
    public Resp delete(String path, String token)                    { return send("DELETE", path, token, null, null); }
}
