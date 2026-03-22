package com.devanshedutech.repository;

import com.devanshedutech.model.Lead;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;

public interface LeadRepository extends JpaRepository<Lead, String> {
    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
