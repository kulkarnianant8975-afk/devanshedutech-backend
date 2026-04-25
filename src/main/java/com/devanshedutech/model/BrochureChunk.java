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

    // NOTE: Do NOT use @Lob here. Hibernate 6 maps @Lob byte[] to PostgreSQL 'oid',
    // but the actual DB column is 'bytea'. The mismatch causes a startup schema error:
    // "column data cannot be cast automatically to type OID".
    // Explicit columnDefinition = "bytea" keeps Hibernate aligned with the real DB type.
    @Column(name = "data", nullable = false, columnDefinition = "bytea")
    private byte[] data;
}
