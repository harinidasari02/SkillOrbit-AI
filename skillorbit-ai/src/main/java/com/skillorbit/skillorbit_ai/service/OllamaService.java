package com.skillorbit.skillorbit_ai.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillorbit.skillorbit_ai.config.OllamaProperties;

/**
 * Thin client for a local Ollama server. Used ONLY to put an already-made rule decision into words.
 * Every failure (server down, timeout, bad JSON) yields an empty result so callers fall back to the
 * deterministic text; nothing in the application depends on the model being available.
 */
@Service
public class OllamaService {

    private final OllamaProperties properties;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaService(OllamaProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public Optional<String> tryGenerate(String prompt) {
        try {
            String body = mapper.writeValueAsString(
                    Map.of("model", properties.getModel(), "prompt", prompt, "stream", false));
            String base = properties.getApiUrl().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/generate"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            JsonNode text = mapper.readTree(response.body()).get("response");
            if (text == null || text.isNull() || text.asText().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(text.asText().trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
