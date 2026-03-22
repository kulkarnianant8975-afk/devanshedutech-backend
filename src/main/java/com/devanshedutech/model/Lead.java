package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "leads")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lead {
    @Id
    private String id;
    
    @Column(name = "full_name")
    private String fullName;
    
    private String email;
    
    @Column(name = "mobile_number")
    private String mobileNumber;
    
    private String education;
    
    @Column(name = "city_name")
    private String cityName;
    
    @Column(name = "course_interested")
    private String courseInterested;
    
    private String status;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @PrePersist
    public void prePersist() {
        if(createdAt == null) createdAt = LocalDateTime.now();
    }
}
