package com.devanshedutech.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

public class HiringDTOs {
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class HiringRequest {
        private String title;
        private String company;
        private String location;
        private String type;
        private String description;
        private String requirements;
        private String salary;
        private String link;
    }
    
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class HiringResponse {
        private String id;
        private String title;
        private String company;
        private String location;
        private String type;
        private String description;
        private String requirements;
        private String salary;
        private String link;
        private LocalDateTime createdAt;
    }
}
