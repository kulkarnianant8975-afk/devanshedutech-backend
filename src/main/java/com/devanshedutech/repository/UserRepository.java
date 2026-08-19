package com.devanshedutech.repository;

import com.devanshedutech.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmailIgnoreCase(String email);

    List<User> findAllByOrderByDisplayNameAsc();

    /**
     * Staff who can own a lead. Excludes deactivated accounts and non-staff sign-ups, so the
     * assignment dropdown can never hand work to someone who cannot act on it.
     */
    @Query("select u from User u where (u.active is null or u.active = true) "
         + "and u.role in ('SUPER_ADMIN','ADMIN','MANAGER','SALES_EXECUTIVE') "
         + "order by u.displayName asc")
    List<User> findAssignableStaff();

    long countByRole(String role);
}
