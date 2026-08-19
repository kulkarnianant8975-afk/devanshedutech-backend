package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One student's link to one asset, so opening it can be counted.
 *
 * <p>A student who opens the syllabus three times in an evening is telling you something a
 * counsellor cannot otherwise know. Until now that signal did not exist: assets were sent as
 * plain URLs and what happened to them afterwards was invisible.</p>
 *
 * <p>Each link is issued to a specific lead, which is what makes the open meaningful. A shared
 * URL would only say that somebody, somewhere, looked at the syllabus.</p>
 */
@Entity
@Table(name = "asset_links", indexes = {
    @Index(name = "idx_asset_link_lead", columnList = "lead_id"),
    @Index(name = "idx_asset_link_lead_asset", columnList = "lead_id,asset_key")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetLink {

    /** The random part of the URL. Long enough not to be worth guessing. */
    @Id
    @Column(length = 32)
    private String token;

    @Column(name = "lead_id", nullable = false)
    private String leadId;

    @Column(name = "asset_key", nullable = false, length = 60)
    private String assetKey;

    /** Where this link sends the student. Resolved once, at send time. */
    @Column(nullable = false, length = 1000)
    private String targetUrl;

    @Column(name = "asset_name")
    private String assetName;

    private LocalDateTime createdAt;

    @Column(name = "open_count")
    private Integer openCount;

    private LocalDateTime firstOpenedAt;

    private LocalDateTime lastOpenedAt;

    public int opens() { return openCount == null ? 0 : openCount; }
}
