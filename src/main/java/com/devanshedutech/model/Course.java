package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "courses")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Course {
    @Id
    private String id;
    
    private String name;
    
    @Column(length = 2000)
    private String description;
    
    private String duration;

    private String price;

    private String level;

    private String category;
    
    @Column(columnDefinition = "TEXT")
    private String image;

    /**
     * The name this course has in a URL, so an ad can point at /courses/data-analytics rather
     * than at a UUID.
     *
     * <p>Set once, from the name, and deliberately never changed when the course is renamed.
     * A link inside a running advertisement must not break because somebody fixed a typo in a
     * course title, and the cost of a slug that no longer matches the name is nothing.</p>
     */
    @Column(unique = true, length = 120)
    private String slug;

    /** A name turned into a URL fragment. Returns null for a name with nothing usable in it. */
    public static String slugify(String name) {
        if (name == null) return null;
        String slug = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (slug.length() > 100) slug = slug.substring(0, 100).replaceAll("-+$", "");
        return slug.isEmpty() ? null : slug;
    }
}
