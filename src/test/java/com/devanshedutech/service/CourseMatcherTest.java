package com.devanshedutech.service;

import com.devanshedutech.model.Course;
import com.devanshedutech.repository.CourseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseMatcherTest {

    private CourseMatcher matcher;

    /** The institute's real catalogue, which is what makes the ambiguous cases ambiguous. */
    @BeforeEach
    void setUp() {
        CourseRepository courses = mock(CourseRepository.class);
        when(courses.findAll()).thenReturn(List.of(
                course("1", "Foundation using C, C++"),
                course("2", "Full Stack Web Development"),
                course("3", "Full Stack Python Development"),
                course("4", "Full Stack Java Development"),
                course("5", "Software Testing"),
                course("6", "Digital Marketing"),
                course("7", "Soft Skills"),
                course("8", "Communication Skills")));
        matcher = new CourseMatcher(courses);
    }

    private static Course course(String id, String name) {
        return Course.builder().id(id).name(name).build();
    }

    @ParameterizedTest
    @DisplayName("a student naming a course is understood, however they phrase it")
    @CsvSource({
            "'I want do java Developer course',            Full Stack Java Development",
            "'i am interested in java',                    Full Stack Java Development",
            "'python course fees?',                        Full Stack Python Development",
            "'Full Stack Web Development',                 Full Stack Web Development",
            "'do you teach software testing',              Software Testing",
            "'digital marketing ka batch kab hai',         Digital Marketing",
            "'i want to learn WEB development',            Full Stack Web Development",
    })
    void namedCoursesAreMatched(String message, String expected) {
        assertEquals(expected, matcher.match(message).orElseThrow().getName(), message);
    }

    @ParameterizedTest
    @DisplayName("an ambiguous message matches nothing rather than guessing")
    @ValueSource(strings = {
            "i want to do full stack",
            "which development course is best",
            "tell me about your courses",
            "i want to join your class",
            "what is the fee",
            "hii",
    })
    void ambiguityMatchesNothing(String message) {
        // Filing a student under the wrong course is worse than leaving it blank: a counsellor
        // rings them about Python when they asked about Java, and the student concludes nobody
        // was listening. "Full stack" describes three of these courses.
        assertTrue(matcher.match(message).isEmpty(), message + " should not have matched");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "!!!", "??"})
    @DisplayName("nothing usable matches nothing")
    void emptyInputIsSafe(String message) {
        assertTrue(matcher.match(message).isEmpty());
    }

    @Test
    @DisplayName("java is not matched from the word javascript")
    void wholeWordsOnly() {
        // Substring matching would file a JavaScript enquiry under the Java course.
        assertTrue(matcher.match("i know javascript already").isEmpty());
    }

    @Test
    @DisplayName("the words shared by every course identify none of them")
    void genericWordsAreNotDistinctive() {
        assertFalse(matcher.distinctiveTokens("Full Stack Java Development").contains("full"));
        assertFalse(matcher.distinctiveTokens("Full Stack Java Development").contains("stack"));
        assertFalse(matcher.distinctiveTokens("Full Stack Java Development").contains("development"));
        assertTrue(matcher.distinctiveTokens("Full Stack Java Development").contains("java"),
                "java is the only word that tells it from the Python one");
    }

    @Test
    @DisplayName("an exact course name wins even when its words are common")
    void exactNamesWinOutright() {
        assertEquals("Full Stack Python Development",
                matcher.match("I saw Full Stack Python Development on your site")
                        .orElseThrow().getName());
    }

    @Test
    @DisplayName("an empty catalogue matches nothing rather than failing")
    void anEmptyCatalogueIsSafe() {
        CourseRepository empty = mock(CourseRepository.class);
        when(empty.findAll()).thenReturn(List.of());
        assertTrue(new CourseMatcher(empty).match("i want java").isEmpty());
    }
}
