package com.devanshedutech.dto;

import com.devanshedutech.model.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public class UserDTOs {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CreateUserRequest {
        @NotBlank @Email
        private String email;
        @NotBlank @Size(min = 8, message = "Password must be at least 8 characters.")
        private String password;
        @NotBlank
        private String displayName;
        private String phone;
        private Role role;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UpdateUserRequest {
        private String displayName;
        private String phone;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RoleChangeRequest {
        private Role role;
        private String reason;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ActiveChangeRequest {
        private Boolean active;
        private String reason;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PasswordResetRequest {
        @NotBlank @Size(min = 8, message = "Password must be at least 8 characters.")
        private String password;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UserResponse {
        private String id;
        private String email;
        private String displayName;
        private String photoURL;
        private String phone;
        private Role role;
        private String roleLabel;
        private boolean active;
        private boolean roleLockedByConfig;
        private LocalDateTime createdAt;
        private LocalDateTime lastLoginAt;
    }

    /** What the signed-in user may do, so the client can render the right navigation. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MeResponse {
        private String id;
        private String email;
        private String displayName;
        private String photoURL;
        private Role role;
        private String roleLabel;
        private boolean active;
        private Set<String> permissions;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RoleOption {
        private Role value;
        private String label;
        private String description;
        private boolean grantable;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AuditEntryResponse {
        private String id;
        private String actorEmail;
        private String action;
        private String targetType;
        private String targetId;
        private String detail;
        private String ipAddress;
        private LocalDateTime createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TeamResponse {
        private List<UserResponse> users;
        private List<RoleOption> roles;
    }
}
