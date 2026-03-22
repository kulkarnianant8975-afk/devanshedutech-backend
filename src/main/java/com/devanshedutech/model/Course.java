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
    
    private String category;
    
    @Column(columnDefinition = "TEXT")
    private String image;
}
