package com.devanshedutech.repository;

import com.devanshedutech.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(String recipientId, Pageable pageable);

    long countByRecipientIdAndReadAtIsNull(String recipientId);

    boolean existsByRecipientIdAndDedupeKey(String recipientId, String dedupeKey);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.recipientId = :recipient and n.readAt is null")
    int markAllRead(@Param("recipient") String recipientId, @Param("now") LocalDateTime now);

    /** Housekeeping: read notices older than the cutoff are not worth keeping. */
    @Modifying
    @Query("delete from Notification n where n.readAt is not null and n.createdAt < :cutoff")
    int deleteReadBefore(@Param("cutoff") LocalDateTime cutoff);

    List<Notification> findByLeadIdOrderByCreatedAtDesc(String leadId);
}
