package com.devanshedutech.repository;

import com.devanshedutech.model.BrochureChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BrochureChunkRepository extends JpaRepository<BrochureChunk, Long> {
    List<BrochureChunk> findBySettingKeyOrderByChunkIndexAsc(String settingKey);
    void deleteBySettingKey(String settingKey);
}
