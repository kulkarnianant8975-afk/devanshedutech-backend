package com.devanshedutech.repository;

import com.devanshedutech.model.AssetLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetLinkRepository extends JpaRepository<AssetLink, String> {
    List<AssetLink> findByLeadIdOrderByLastOpenedAtDesc(String leadId);
    Optional<AssetLink> findByLeadIdAndAssetKey(String leadId, String assetKey);
}
