package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Something a counsellor sends a student: a syllabus, a fee sheet, a project video, a booking
 * link.
 *
 * <p>Assets point at content rather than storing it. A course brochure already lives in this
 * database and is served by the settings endpoints, so an asset for it is a reference; a video
 * or a form is a URL. That keeps one copy of each file rather than a second one here that can
 * drift from the first.</p>
 */
@Entity
@Table(name = "assets", indexes = {
    @Index(name = "idx_asset_key", columnList = "asset_key", unique = true),
    @Index(name = "idx_asset_course", columnList = "course_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Asset {

    @Id
    private String id;

    /** Stable name used by packs, so a pack keeps working when an asset is renamed. */
    @Column(name = "asset_key", nullable = false, unique = true, length = 60)
    private String key;

    @Column(nullable = false)
    private String name;

    /** PDF, VIDEO, LINK or IMAGE — only used to label it for the counsellor. */
    @Column(nullable = false, length = 16)
    private String type;

    /**
     * Where it lives. A relative path is served by this application; anything else is external.
     * May contain {{course_id}}, which is filled in from the lead so one asset covers every
     * course's brochure rather than needing eight near-identical rows.
     */
    @Column(nullable = false, length = 1000)
    private String url;

    /** Set when the asset is specific to one course rather than general. */
    @Column(name = "course_id")
    private String courseId;

    /** Human-readable size, shown so a counsellor knows before sending an 11 MB video. */
    @Column(name = "size_label", length = 20)
    private String sizeLabel;

    /**
     * Whether this also belongs on the public website.
     *
     * <p>Off by default, and deliberately so: the library holds fee sheets and internal notes
     * alongside testimonials, and a flag that defaulted to on would put the first of those in
     * front of the public the moment somebody uploaded it.</p>
     *
     * <p>Only videos use this today, on the Student Reviews page. It lives here rather than in a
     * second table because the alternative is uploading every testimonial twice — once for
     * counsellors to send and once for the website — and the second copy is the one that goes
     * stale.</p>
     */
    @Column(name = "show_on_website", nullable = false)
    @Builder.Default
    private boolean showOnWebsite = false;

    /**
     * The exact size, which decides how the thing can be delivered.
     *
     * <p>WhatsApp carries a video of up to sixteen megabytes inside the message and refuses
     * anything larger. A bigger file is not useless — it is streamed from a link instead — but
     * the send path has to know which it is dealing with, and a formatted string like "180.4 MB"
     * is not something to make that decision on.</p>
     */
    @Column(name = "size_bytes")
    private Long sizeBytes;

    /**
     * Whether opening it is recorded against the lead. Only meaningful for links this
     * application serves — an external URL cannot be observed, and pretending otherwise would
     * put a number on the screen that means nothing.
     */
    private Boolean tracked;

    private Boolean active;

    public boolean isActive() { return active == null || active; }
    public boolean isTracked() { return Boolean.TRUE.equals(tracked); }
}
