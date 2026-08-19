package com.devanshedutech.controller;

import com.devanshedutech.model.Broadcast;
import com.devanshedutech.model.User;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.service.BroadcastService;
import com.devanshedutech.service.BroadcastService.Segment;
import com.devanshedutech.service.LeadLifecycleService.Actor;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Announcements to dormant leads.
 *
 * <p>Sending requires the lead-assign permission rather than plain edit: a broadcast reaches
 * hundreds of people at once and cannot be recalled, so it belongs with the manager who owns
 * the pipeline rather than with everyone who can log a call.</p>
 */
@RestController
@RequestMapping("/api/broadcasts")
public class BroadcastController {

    private final BroadcastService broadcasts;
    private final AccessService access;

    public BroadcastController(BroadcastService broadcasts, AccessService access) {
        this.broadcasts = broadcasts;
        this.access = access;
    }

    @GetMapping("/segments")
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<Map<String, Object>> segments() {
        return ResponseEntity.ok(Map.of(
                "segments", Arrays.stream(Segment.values()).map(broadcasts::preview).toList(),
                "canSend", broadcasts.canSend()));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<List<BroadcastResponse>> recent() {
        return ResponseEntity.ok(broadcasts.recent().stream().map(this::toResponse).toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_LEAD_ASSIGN')")
    public ResponseEntity<BroadcastResponse> send(@RequestBody SendBroadcastRequest request,
                                                  Authentication auth) {
        Segment segment;
        try {
            segment = Segment.valueOf(request.getSegment());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a group to send to.");
        }
        User me = access.requireUser(auth);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(broadcasts.send(
                request.getTitle(), request.getMessage(), segment,
                new Actor(me.getId(), me.displayNameOrEmail()))));
    }

    private BroadcastResponse toResponse(Broadcast b) {
        return BroadcastResponse.builder()
                .id(b.getId()).title(b.getTitle()).message(b.getMessage())
                .segment(b.getSegment()).status(b.getStatus())
                .recipientCount(b.getRecipientCount()).sentCount(b.getSentCount())
                .failedCount(b.getFailedCount()).createdByName(b.getCreatedByName())
                .createdAt(b.getCreatedAt()).sentAt(b.getSentAt())
                .build();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SendBroadcastRequest {
        private String title;
        private String message;
        private String segment;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BroadcastResponse {
        private String id;
        private String title;
        private String message;
        private String segment;
        private String status;
        private Integer recipientCount;
        private Integer sentCount;
        private Integer failedCount;
        private String createdByName;
        private LocalDateTime createdAt;
        private LocalDateTime sentAt;
    }
}
