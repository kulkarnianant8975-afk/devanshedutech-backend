package com.devanshedutech.controller;

import com.devanshedutech.model.Notification;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.NotificationRepository;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.service.NotificationService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Each person's own notifications.
 *
 * <p>Scoped to the caller throughout — there is no "read someone else's notices" path, not even
 * for an administrator, because a notification list is a working queue rather than a record.</p>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository repository;
    private final NotificationService service;
    private final AccessService access;

    public NotificationController(NotificationRepository repository,
                                  NotificationService service,
                                  AccessService access) {
        this.repository = repository;
        this.service = service;
        this.access = access;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<NotificationListResponse> mine(Authentication auth,
                                                         @RequestParam(defaultValue = "30") int limit) {
        User me = access.requireUser(auth);
        return ResponseEntity.ok(NotificationListResponse.builder()
                .items(service.recent(me.getId(), limit).stream().map(this::toResponse).toList())
                .unread(service.unreadCount(me.getId()))
                .build());
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> read(@PathVariable String id, Authentication auth) {
        User me = access.requireUser(auth);
        Notification n = repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That notification no longer exists."));
        if (!me.getId().equals(n.getRecipientId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That notification no longer exists.");
        }
        service.markRead(n);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Integer>> readAll(Authentication auth) {
        User me = access.requireUser(auth);
        return ResponseEntity.ok(Map.of("marked", service.markAllRead(me.getId())));
    }

    private NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId()).kind(n.getKind()).title(n.getTitle()).body(n.getBody())
                .leadId(n.getLeadId()).read(n.isRead()).createdAt(n.getCreatedAt())
                .build();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NotificationResponse {
        private String id;
        private String kind;
        private String title;
        private String body;
        private String leadId;
        private boolean read;
        private LocalDateTime createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NotificationListResponse {
        private List<NotificationResponse> items;
        private long unread;
    }
}
