package com.sqlcopilot.studio.service.rag.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

@Component
public class OpenAiCompatRagHttpClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public OpenAiCompatRagHttpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode postJson(String endpointUrl,
                             JsonNode payload,
                             String apiKey,
                             Duration timeout,
                             String sceneName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .timeout(resolveTimeout(timeout))
                .header("Authorization", "Bearer " + safe(apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(
                    500,
                    sceneName + "接口返回状态码: " + response.statusCode() + " body=" + shorten(response.body(), 260)
                );
            }
            return objectMapper.readTree(Objects.toString(response.body(), ""));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, sceneName + "请求失败: " + safe(ex.getMessage()));
        }
    }

    private Duration resolveTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return Duration.ofSeconds(30);
        }
        return timeout;
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
    }

    private String shorten(String input, int maxLen) {
        String normalized = Objects.toString(input, "").replaceAll("\\s+", " ").trim();
        int targetLen = Math.max(32, maxLen);
        if (normalized.length() <= targetLen) {
            return normalized;
        }
        return normalized.substring(0, targetLen - 3) + "...";
    }
}
