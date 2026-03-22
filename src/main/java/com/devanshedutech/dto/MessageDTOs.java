package com.devanshedutech.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

public class MessageDTOs {
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MessageRequest {
        private String fullName;
        private String email;
        private String mobile;
        private String message;
    }
    
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MessageResponse {
        private String id;
        private String fullName;
        private String email;
        private String mobile;
        private String message;
        private LocalDateTime createdAt;
    }
}
