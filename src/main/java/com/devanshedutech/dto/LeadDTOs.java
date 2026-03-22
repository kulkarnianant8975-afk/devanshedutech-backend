package com.devanshedutech.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

public class LeadDTOs {
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeadRequest {
        private String fullName;
        private String email;
        private String mobileNumber;
        private String education;
        private String cityName;
        private String courseInterested;
    }
    
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeadStatusUpdate {
        private String status;
    }
    
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeadResponse {
        private String id;
        private String fullName;
        private String email;
        private String mobileNumber;
        private String education;
        private String cityName;
        private String courseInterested;
        private String status;
        private LocalDateTime createdAt;
    }
}
