package com.devanshedutech.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CourseSlugTest {

    @ParameterizedTest
    @DisplayName("a course name becomes something that reads well in an advertisement")
    @CsvSource({
            "'Data Analytics',                     data-analytics",
            "'Full Stack Web Development',         full-stack-web-development",
            "'Foundation C/C++',                   foundation-c-c",
            "'Gen AI  &  Prompt Engineering',      gen-ai-prompt-engineering",
            "'  Software Testing  ',               software-testing",
            "'Communication & Soft Skills',        communication-soft-skills",
            "'Python 3.12',                        python-3-12",
    })
    void namesBecomeReadableSlugs(String name, String expected) {
        assertEquals(expected, Course.slugify(name));
    }

    @ParameterizedTest
    @DisplayName("a name with nothing usable in it produces no slug rather than an empty one")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "---", "!!!", "&&&"})
    void unusableNamesProduceNull(String name) {
        // An empty slug would make /courses/ resolve to a course, which is worse than no slug.
        assertNull(Course.slugify(name));
    }

    @Test
    @DisplayName("a very long name is cut short without leaving a trailing dash")
    void longNamesAreTrimmedCleanly() {
        String slug = Course.slugify("Advanced ".repeat(30) + "Programme");
        assertTrue(slug.length() <= 100, "was " + slug.length());
        assertFalse(slug.endsWith("-"), slug);
    }
}
