package com.devanshedutech.repository;

import com.devanshedutech.model.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, String> {
    Optional<Asset> findByKey(String key);
    List<Asset> findByKeyIn(List<String> keys);
    long countByKey(String key);
}
