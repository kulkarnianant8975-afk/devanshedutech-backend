package com.devanshedutech.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

public class CourseDTOs {
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CourseRequest {
        private String name;
        private String description;
        private String duration;
        private String price;
        private String category;
        private String image;
    }
    
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CourseResponse {
        private String id;
        private String name;
        private String description;
        private String duration;
        private String price;
        private String category;
        private String image;
    }
}
