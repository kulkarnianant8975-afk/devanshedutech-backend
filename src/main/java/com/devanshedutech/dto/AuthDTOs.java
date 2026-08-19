package com.devanshedutech.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

public class AuthDTOs {
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LoginRequest {
        private String email;
        private String password;
    }
    
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RegisterRequest {
        private String email;
        private String password;
        private String displayName;
    }
    
    /**
     * The signed-in user. The original five fields are unchanged so existing clients keep
     * working; the additions tell the client what this person may actually do, so navigation
     * can be driven by permissions instead of by string-matching a role name.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UserResponse {
        private String id;
        private String email;
        private String displayName;
        private String photoURL;
        private String role;

        private String roleLabel;
        private boolean active;
        private java.util.Set<String> permissions;
    }
}
