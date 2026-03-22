package com.devanshedutech.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

public class MentorDTOs {
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MentorRequest {
        private String name;
        private String role;
        private String description;
        private String imageUrl;
        private String linkedinUrl;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MentorResponse {
        private String id;
        private String name;
        private String role;
        private String description;
        private String imageUrl;
        private String linkedinUrl;
        private LocalDateTime createdAt;
    }
}
