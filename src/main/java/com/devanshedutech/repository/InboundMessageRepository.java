package com.devanshedutech.repository;

import com.devanshedutech.model.InboundMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface InboundMessageRepository extends JpaRepository<InboundMessage, String> {
    long deleteByReceivedAtBefore(LocalDateTime cutoff);
}
