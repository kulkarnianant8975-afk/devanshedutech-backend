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
    
    @Lob
    @Column(name = "setting_value")
    private byte[] settingValue;
}
