package com.devanshedutech.repository;

import com.devanshedutech.model.Hiring;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HiringRepository extends JpaRepository<Hiring, String> {
}
