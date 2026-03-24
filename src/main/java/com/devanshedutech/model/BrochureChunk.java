package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "brochure_chunks")
public class BrochureChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "setting_key", nullable = false)
    private String settingKey;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Lob
    @Column(name = "data", nullable = false)
    private byte[] data;
}
