package com.devanshedutech.repository;

import com.devanshedutech.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, String targetId);

    List<AuditLog> findByActorIdOrderByCreatedAtDesc(String actorId);

    long countByActionAndCreatedAtAfter(String action, LocalDateTime after);
}
