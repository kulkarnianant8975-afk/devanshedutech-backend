package com.devanshedutech.config;

import com.devanshedutech.model.Course;
import com.devanshedutech.repository.CourseRepository;
import com.devanshedutech.service.CourseSlugs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Gives the courses that already exist their URL name.
 *
 * <p>Only fills in a blank slug. A course that already has one keeps it, so a link in a running
 * advertisement survives every redeploy — which is the entire reason the column exists.</p>
 */
@Slf4j
@Configuration
public class CourseSlugBackfill {

    @Bean
    @Order(7)
    public ApplicationRunner backfillCourseSlugs(CourseRepository courses, CourseSlugs slugs) {
        return args -> {
            int filled = 0;
            for (Course course : courses.findAll()) {
                if (course.getSlug() != null && !course.getSlug().isBlank()) continue;
                course.setSlug(slugs.assign(course));
                courses.save(course);
                filled++;
            }
            if (filled > 0) log.info("Gave {} course(s) a URL name.", filled);
        };
    }
}
