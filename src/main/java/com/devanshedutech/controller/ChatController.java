package com.devanshedutech.controller;

import com.devanshedutech.service.ChatService;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        try {
            String botResponse = chatService.getAiResponse(request.getMessage(), request.getHistory());
            return ResponseEntity.ok(Map.of("text", botResponse));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @Data
    public static class ChatRequest {
        private String message;
        private List<Map<String, Object>> history;
    }
}
