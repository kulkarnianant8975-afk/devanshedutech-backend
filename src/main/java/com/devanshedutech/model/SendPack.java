package com.devanshedutech.model;

import lombok.*;
import jakarta.persistence.*;

import java.util.Arrays;
import java.util.List;

/**
 * A covering message plus the files that go with it, sent as one action.
 *
 * <p>This is the unit a counsellor actually works in. Rather than remembering which four things
 * to attach after a guidance call, they pick the pack and the message and its attachments go
 * together — which is what the SOP's "send the syllabus and fees immediately" step means in
 * practice.</p>
 */
@Entity
@Table(name = "send_packs", indexes = @Index(name = "idx_pack_key", columnList = "pack_key", unique = true))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendPack {

    @Id
    private String id;

    @Column(name = "pack_key", nullable = false, unique = true, length = 40)
    private String key;

    @Column(nullable = false)
    private String name;

    /** Which SOP situation this answers, shown so the counsellor can see why it exists. */
    @Column(length = 200)
    private String situation;

    /** The message itself, with {{first_name}}-style placeholders. */
    @Column(name = "cover_template", nullable = false, columnDefinition = "TEXT")
    private String coverTemplate;

    /**
     * Ordered asset keys, comma-separated.
     *
     * <p>A join table would be more orthodox, but this is a short ordered list edited rarely and
     * read as a unit, and a delimited column keeps the ordering obvious rather than depending on
     * a position column nobody remembers to maintain.</p>
     */
    @Column(name = "asset_keys", length = 500)
    private String assetKeys;

    /** True only for packs allowed to send with no counsellor involved. */
    @Column(name = "auto_send")
    private Boolean autoSend;

    private Boolean active;

    public List<String> assets() {
        if (assetKeys == null || assetKeys.isBlank()) return List.of();
        return Arrays.stream(assetKeys.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public boolean isAutoSend() { return Boolean.TRUE.equals(autoSend); }
    public boolean isActive() { return active == null || active; }
}
