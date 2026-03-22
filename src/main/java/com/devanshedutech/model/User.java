package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    private String id;
    
    @Column(unique = true, nullable = false)
    private String email;
    
    private String password;
    
    @Column(name = "display_name")
    private String displayName;
    
    @Column(name = "photo_url", columnDefinition = "TEXT")
    private String photoUrl;
    
    private String role;
}
