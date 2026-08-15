package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.ChatHistoryItemDto;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
public class OllamaClient {

    private final OllamaProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OllamaClient(OllamaProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isReachable() {
        try {
            listModels();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<String> listModels() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(props.getBaseUrl()) + "/api/tags"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("Ollama /api/tags failed: HTTP " + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        List<String> names = new ArrayList<>();
        JsonNode models = root.path("models");
        if (models.isArray()) {
            for (JsonNode m : models) {
                String name = m.path("name").asString(null);
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    public boolean hasModel(String wanted, List<String> installed) {
        if (wanted == null || wanted.isBlank()) {
            return false;
        }
        String w = wanted.toLowerCase();
        String baseWanted = w.contains(":") ? w.substring(0, w.indexOf(':')) : w;
        for (String name : installed) {
            String n = name.toLowerCase();
            String base = n.contains(":") ? n.substring(0, n.indexOf(':')) : n;
            if (n.equals(w) || base.equals(baseWanted)) {
                return true;
            }
        }
        return false;
    }

    public String chat(List<ChatHistoryItemDto> messages) throws IOException, InterruptedException {
        return chat(messages, props.resolveChatModel());
    }

    public String chat(List<ChatHistoryItemDto> messages, String model) throws IOException, InterruptedException {
        String useModel = pickModel(model, props.getChatModelFallback());
        ObjectNode body = mapper.createObjectNode();
        body.put("model", useModel);
        body.put("stream", false);
        ArrayNode msgs = body.putArray("messages");
        for (ChatHistoryItemDto m : messages) {
            ObjectNode item = msgs.addObject();
            item.put("role", normalizeRole(m.getRole()));
            item.put("content", m.getContent() == null ? "" : m.getContent());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(props.getBaseUrl()) + "/api/chat"))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            // Retry once with fallback if preferred Modelfile missing
            if (!useModel.equals(props.getChatModelFallback()) && props.getChatModelFallback() != null) {
                return chat(messages, props.getChatModelFallback());
            }
            throw new IOException("Ollama chat failed: HTTP " + response.statusCode() + " — " + abbreviate(response.body()));
        }
        JsonNode root = mapper.readTree(response.body());
        String content = root.path("message").path("content").asString(null);
        if (content == null || content.isBlank()) {
            content = root.path("response").asString("");
        }
        return content;
    }

    public void chatStream(List<ChatHistoryItemDto> messages, Consumer<String> onToken) throws IOException, InterruptedException {
        chatStream(messages, props.resolveChatModel(), onToken);
    }

    public void chatStream(List<ChatHistoryItemDto> messages, String model, Consumer<String> onToken) throws IOException, InterruptedException {
        String useModel = pickModel(model, props.getChatModelFallback());
        ObjectNode body = mapper.createObjectNode();
        body.put("model", useModel);
        body.put("stream", true);
        ArrayNode msgs = body.putArray("messages");
        for (ChatHistoryItemDto m : messages) {
            ObjectNode item = msgs.addObject();
            item.put("role", normalizeRole(m.getRole()));
            item.put("content", m.getContent() == null ? "" : m.getContent());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(props.getBaseUrl()) + "/api/chat"))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            if (!useModel.equals(props.getChatModelFallback()) && props.getChatModelFallback() != null) {
                chatStream(messages, props.getChatModelFallback(), onToken);
                return;
            }
            throw new IOException("Ollama chat stream failed: HTTP " + response.statusCode() + " — " + abbreviate(err));
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode root = mapper.readTree(line);
                String chunk = root.path("message").path("content").asString("");
                if (!chunk.isEmpty()) {
                    onToken.accept(chunk);
                }
                if (root.path("done").asBoolean(false)) {
                    break;
                }
            }
        }
    }

    private String pickModel(String preferred, String fallback) {
        if (preferred == null || preferred.isBlank()) {
            return fallback != null ? fallback : "qwen2.5-coder:7b";
        }
        return preferred;
    }

    public float[] embed(String text) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getEmbedModel());
        body.put("prompt", text == null ? "" : text);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(props.getBaseUrl()) + "/api/embeddings"))
                .timeout(Duration.ofSeconds(Math.min(120, props.getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("Ollama embeddings failed: HTTP " + response.statusCode() + " — " + abbreviate(response.body()));
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode emb = root.path("embedding");
        if (!emb.isArray() || emb.isEmpty()) {
            throw new IOException("Ollama embeddings returned empty vector");
        }
        float[] vec = new float[emb.size()];
        for (int i = 0; i < emb.size(); i++) {
            vec[i] = (float) emb.get(i).asDouble();
        }
        return vec;
    }

    private static String normalizeRole(String role) {
        if (role == null) {
            return "user";
        }
        String r = role.trim().toLowerCase();
        if (r.equals("assistant") || r.equals("system") || r.equals("user")) {
            return r;
        }
        return "user";
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:11434";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
