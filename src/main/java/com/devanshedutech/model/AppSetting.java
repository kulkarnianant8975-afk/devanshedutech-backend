package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppSetting {
    @Id
    private String settingKey;
    
    // NOTE: Do NOT use @Lob here — same reason as BrochureChunk.data.
    // Hibernate 6 maps @Lob byte[] to 'oid', but the column is 'bytea'.
    @Column(name = "setting_value", columnDefinition = "bytea")
    private byte[] settingValue;
}
