package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "hiring")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Hiring {
    @Id
    private String id;
    
    private String title;
    
    private String company;
    
    private String location;
    
    private String type;
    
    @Column(length = 4000)
    private String description;
    
    @Column(length = 4000)
    private String requirements;
    
    private String salary;
    
    private String link;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @PrePersist
    public void prePersist() {
        if(createdAt == null) createdAt = LocalDateTime.now();
    }
}
