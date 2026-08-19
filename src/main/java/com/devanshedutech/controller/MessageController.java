package com.devanshedutech.controller;

import com.devanshedutech.dto.MessageDTOs.MessageRequest;
import com.devanshedutech.dto.MessageDTOs.MessageResponse;
import com.devanshedutech.model.Message;
import com.devanshedutech.dto.LeadDTOs.LeadRequest;
import com.devanshedutech.model.crm.LeadSource;
import com.devanshedutech.repository.MessageRepository;
import com.devanshedutech.service.LeadCaptureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageRepository messageRepository;
    private final LeadCaptureService capture;

    public MessageController(MessageRepository messageRepository, LeadCaptureService capture) {
        this.messageRepository = messageRepository;
        this.capture = capture;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_LEAD_VIEW_ALL')")
    public ResponseEntity<List<MessageResponse>> getAllMessages() {
        return ResponseEntity.ok(
                messageRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                        .stream().map(this::mapToResponse).collect(Collectors.toList())
        );
    }

    @PostMapping
    public ResponseEntity<MessageResponse> createMessage(@RequestBody MessageRequest request) {
        Message message = Message.builder()
                .id(UUID.randomUUID().toString())
                .fullName(request.getFullName())
                .email(request.getEmail())
                .mobile(request.getMobile())
                .message(request.getMessage())
                .build();
        Message saved = messageRepository.save(message);

        // The contact form is an enquiry, and it was going nowhere. Messages were saved to
        // their own inbox and never entered the pipeline, so nobody was assigned, no follow-up
        // was scheduled, and the enquiry did not appear in any conversion figure. It is now
        // captured as a lead as well; the message record stays, because the enquiry text itself
        // is worth keeping alongside the pipeline entry.
        //
        // A message with no usable number is left as a message only. A lead nobody can call is
        // not a lead, and putting one in the queue wastes a counsellor's morning.
        try {
            capture.capture(LeadRequest.builder()
                    .fullName(request.getFullName())
                    .email(request.getEmail())
                    .mobileNumber(request.getMobile())
                    .notes(request.getMessage())
                    .sourceDetail("Contact form on the website")
                    .build(), LeadSource.CONTACT_FORM);
        } catch (RuntimeException e) {
            // The student's message is already saved. Failing their submission because the
            // pipeline entry could not be created would lose the enquiry entirely.
            log.warn("Contact message {} saved, but it could not be captured as a lead: {}",
                    saved.getId(), e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(saved));
    }

    private MessageResponse mapToResponse(Message message) {
        return MessageResponse.builder()
                .id(message.getId())
                .fullName(message.getFullName())
                .email(message.getEmail())
                .mobile(message.getMobile())
                .message(message.getMessage())
                .createdAt(message.getCreatedAt())
                .build();
    }
}
