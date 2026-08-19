package com.devanshedutech.service;

import com.devanshedutech.model.Course;
import com.devanshedutech.repository.CourseRepository;
import org.springframework.stereotype.Service;

/**
 * Assigns each course the name it has in a URL.
 *
 * <p>Kept out of the controller because both creating a course and backfilling the existing ones
 * need exactly the same rule, and two copies of a uniqueness check is how duplicates appear.</p>
 */
@Service
public class CourseSlugs {

    private final CourseRepository courses;

    public CourseSlugs(CourseRepository courses) {
        this.courses = courses;
    }

    /**
     * A slug for this course that no other course already holds.
     *
     * <p>Two courses can legitimately have names that reduce to the same slug — "Python" and
     * "Python!" — so a collision appends a number rather than failing. Falling back to the id
     * keeps a course reachable even when its name has nothing usable in it at all.</p>
     */
    public String assign(Course course) {
        String base = Course.slugify(course.getName());
        if (base == null) return course.getId();

        String candidate = base;
        for (int n = 2; taken(candidate, course.getId()); n++) {
            candidate = base + "-" + n;
        }
        return candidate;
    }

    private boolean taken(String slug, String ownId) {
        return courses.findBySlug(slug).filter(c -> !c.getId().equals(ownId)).isPresent();
    }
}
