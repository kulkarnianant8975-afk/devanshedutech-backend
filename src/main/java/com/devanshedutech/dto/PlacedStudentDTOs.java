package com.devanshedutech.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

public class PlacedStudentDTOs {
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PlacedStudentRequest {
        private String name;
        private String company;
        private String role;
        private String salaryPackage;
        private String testimonial;
        private String imageUrl;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PlacedStudentResponse {
        private String id;
        private String name;
        private String company;
        private String role;
        private String salaryPackage;
        private String testimonial;
        private String imageUrl;
        private LocalDateTime createdAt;
    }
}
