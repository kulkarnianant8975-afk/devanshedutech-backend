package com.devanshedutech.controller;

import com.devanshedutech.dto.MessageDTOs.MessageRequest;
import com.devanshedutech.dto.MessageDTOs.MessageResponse;
import com.devanshedutech.model.Message;
import com.devanshedutech.repository.MessageRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageRepository messageRepository;

    public MessageController(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('admin')")
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
