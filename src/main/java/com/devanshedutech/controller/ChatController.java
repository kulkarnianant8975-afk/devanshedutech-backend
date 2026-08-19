package com.devanshedutech.controller;

import com.devanshedutech.service.ChatService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    /** This endpoint is public and calls a metered third-party API, so it is rate-limited per client IP. */
    private static final int MAX_REQUESTS_PER_WINDOW = 15;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_HISTORY_TURNS = 20;

    private final ChatService chatService;
    private final com.devanshedutech.service.ChatLeadCapture leadCapture;

    private final Cache<String, AtomicInteger> requestCounts = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .maximumSize(10_000)
            .build();

    public ChatController(ChatService chatService,
                          com.devanshedutech.service.ChatLeadCapture leadCapture) {
        this.chatService = chatService;
        this.leadCapture = leadCapture;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        if (isRateLimited(clientIp(httpRequest))) {
            return ResponseEntity.status(429)
                    .body(Map.of("error", "Too many messages. Please wait a moment and try again."));
        }

        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message cannot be empty."));
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is too long."));
        }

        List<Map<String, Object>> history = request.getHistory();
        if (history != null && history.size() > MAX_HISTORY_TURNS) {
            history = history.subList(history.size() - MAX_HISTORY_TURNS, history.size());
        }

        // A number left in the chat becomes a lead. Done before the model call so a student
        // who gives their number and then closes the tab is still reachable, and wrapped
        // separately because a capture failure must never cost them their answer.
        try {
            leadCapture.capture(message, userMessagesIn(history))
                    .ifPresent(lead -> log.info("Chatbot captured lead {}", lead.getId()));
        } catch (Exception e) {
            log.warn("Could not capture a lead from the chat: {}", e.getMessage());
        }

        try {
            String botResponse = chatService.getAiResponse(message, history);
            return ResponseEntity.ok(Map.of("text", botResponse));
        } catch (Exception e) {
            // Never return e.getMessage() here: upstream exception text can carry the
            // Gemini request detail. Log it, return a fixed message.
            log.error("Chat request failed", e);
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Chat is temporarily unavailable. Please try again shortly."));
        }
    }

    /** What the student themselves has said, so the note on the lead is their words, not the bot's. */
    @SuppressWarnings("unchecked")
    private List<String> userMessagesIn(List<Map<String, Object>> history) {
        if (history == null) return List.of();
        List<String> said = new java.util.ArrayList<>();
        for (Map<String, Object> turn : history) {
            if (!"user".equals(turn.get("role"))) continue;
            Object parts = turn.get("parts");
            if (parts instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> part) {
                Object text = ((Map<String, Object>) part).get("text");
                if (text instanceof String t && !t.isBlank()) said.add(t);
            }
        }
        return said;
    }

    private boolean isRateLimited(String ip) {
        AtomicInteger count = requestCounts.get(ip, k -> new AtomicInteger());
        return count.incrementAndGet() > MAX_REQUESTS_PER_WINDOW;
    }

    private String clientIp(HttpServletRequest request) {
        // Caddy sets X-Forwarded-For; take the first hop, which is the real client.
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @Data
    public static class ChatRequest {
        private String message;
        private List<Map<String, Object>> history;
    }
}
