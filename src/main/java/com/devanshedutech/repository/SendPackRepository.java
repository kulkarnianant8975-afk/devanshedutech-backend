package com.devanshedutech.repository;

import com.devanshedutech.model.SendPack;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SendPackRepository extends JpaRepository<SendPack, String> {
    Optional<SendPack> findByKey(String key);
    List<SendPack> findAllByOrderByNameAsc();
    long countByKey(String key);
}
