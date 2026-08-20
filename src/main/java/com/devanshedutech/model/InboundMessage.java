package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One message received from a student, kept only so it is never handled twice.
 *
 * <p>Meta redelivers a webhook whenever it does not get a prompt 200 — after a timeout, a
 * restart, or a slow database. Without this, one student saying "hi" during a deploy becomes
 * two leads, two auto-replies and a confused student. WhatsApp gives every message a unique
 * id, so recording that id is the whole defence.</p>
 */
@Entity
@Table(name = "inbound_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InboundMessage {

    /** WhatsApp's own message id, the {@code wamid.…} string. */
    @Id
    @Column(length = 128)
    private String messageId;

    @Column(length = 32)
    private String fromPhone;

    private String leadId;

    private LocalDateTime receivedAt;
}
