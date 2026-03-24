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
    @Column(columnDefinition = "TEXT")
    private String settingValue;
}
