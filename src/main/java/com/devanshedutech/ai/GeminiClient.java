package com.devanshedutech.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * One place that talks to the model.
 *
 * <p>Extracted so the chatbot and the counsellor's assistant do not each carry their own copy of
 * the request shape, the key handling and the response unpacking — three things that are easy to
 * get subtly and separately wrong.</p>
 */
@Slf4j
@Component
public class GeminiClient {

    /**
     * The key travels in a header, never the query string. A RestTemplate failure — a timeout,
     * a connect error — puts the request URL into the exception message, so a key in the URL can
     * be echoed to a caller by any handler that logs or returns that message.
     */
    private static final String URL =
            "https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash:generateContent";

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${spring.gemini.api-key:}")
    private String apiKey;

    /** Whether anything here will work at all. Callers check this rather than failing mid-request. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Raised without any upstream detail, which can carry the request and therefore the key. */
    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Sends a whole conversation and returns the model's reply.
     *
     * @param systemPrompt what the model is being asked to be and to do
     * @param turns        alternating prior turns, each {@code {role, parts:[{text}]}}
     * @param message      what the person has just said
     */
    public String chat(String systemPrompt, List<Map<String, Object>> turns, String message) {
        List<Map<String, Object>> contents = new java.util.ArrayList<>();
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", systemPrompt))));
        contents.add(Map.of("role", "model", "parts", List.of(Map.of("text", "Understood."))));
        if (turns != null) contents.addAll(turns);
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", message))));
        return send(contents);
    }

    /**
     * Sends a single instruction and returns the model's text.
     *
     * @param systemPrompt what the model is being asked to be and to do
     * @param userPrompt   the material to work on
     */
    public String complete(String systemPrompt, String userPrompt) {
        return send(List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", systemPrompt))),
                Map.of("role", "model", "parts", List.of(Map.of("text", "Understood."))),
                Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))));
    }

    /** The one request this application makes to the model. */
    private String send(List<Map<String, Object>> contents) {
        if (!isConfigured()) {
            throw new AiUnavailableException(
                    "AI help is not set up on this installation. Add a Gemini API key to enable it.");
        }
        Map<String, Object> body = Map.of("contents", contents);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response =
                    restTemplate.postForObject(URL, new HttpEntity<>(body, headers), Map.class);
            String text = firstText(response);
            if (text == null || text.isBlank()) {
                // A candidate blocked by the safety filter comes back with a finishReason and no
                // parts at all, which is not an error but is also not an answer.
                throw new AiUnavailableException(
                        "The model did not return anything usable. Try again, or write it yourself.");
            }
            return text.trim();
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // Logged in full here; never surfaced, because the message carries the request.
            log.error("Gemini request failed", e);
            throw new AiUnavailableException("AI help is temporarily unavailable. Please try again shortly.");
        }
    }

    @SuppressWarnings("unchecked")
    private String firstText(Map<String, Object> response) {
        if (response == null) return null;
        Object candidates = response.get("candidates");
        if (!(candidates instanceof List<?> list) || list.isEmpty()) return null;
        if (!(list.get(0) instanceof Map<?, ?> candidate)) return null;
        Object content = ((Map<String, Object>) candidate).get("content");
        if (!(content instanceof Map<?, ?> contentMap)) return null;
        Object parts = ((Map<String, Object>) contentMap).get("parts");
        if (!(parts instanceof List<?> partList) || partList.isEmpty()) return null;
        if (!(partList.get(0) instanceof Map<?, ?> part)) return null;
        Object text = ((Map<String, Object>) part).get("text");
        return text instanceof String s ? s : null;
    }
}
