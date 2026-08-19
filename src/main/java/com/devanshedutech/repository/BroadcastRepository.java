package com.devanshedutech.repository;

import com.devanshedutech.model.Broadcast;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BroadcastRepository extends JpaRepository<Broadcast, String> {
    List<Broadcast> findTop50ByOrderByCreatedAtDesc();
}
