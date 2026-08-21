package com.devanshedutech.service;

import com.devanshedutech.model.Course;
import com.devanshedutech.repository.CourseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Works out which course a student means from what they actually wrote.
 *
 * <p>"I want do java Developer course" is a clear statement of intent, and until now it was
 * recorded as a line of text and nothing else — the lead's course stayed empty, so every message
 * pack said "the course", no course brochure could be attached, and the question of which course
 * actually fills batches stayed unanswerable.</p>
 *
 * <p>Matching is deliberately conservative. Filing a student under the wrong course is worse
 * than leaving it blank: a counsellor rings them about Python when they asked about Java, and
 * the student concludes nobody was listening. So an ambiguous message matches nothing.</p>
 */
@Slf4j
@Service
public class CourseMatcher {

    /**
     * Words that appear across so many course names that they identify nothing.
     *
     * <p>Every full-stack course here contains "full", "stack" and "development". Matching on
     * those would make "I want full stack" resolve to whichever one happened to be first in the
     * list, which is a coin toss presented as a fact.</p>
     */
    private static final Set<String> TOO_COMMON = Set.of(
            "full", "stack", "development", "developer", "course", "courses", "using", "with",
            "and", "the", "for", "program", "programme", "training", "class", "classes",
            "batch", "learning", "advanced", "basic", "foundation");

    /** Below this length a token matches far too much — "c" appears inside almost any word. */
    private static final int MIN_TOKEN = 3;

    private final CourseRepository courses;

    public CourseMatcher(CourseRepository courses) {
        this.courses = courses;
    }

    /**
     * The course this message is about, if exactly one is a clear fit.
     *
     * <p>Returns empty rather than a best guess whenever two courses fit equally well, which is
     * the case that matters: "full stack" describes three of them.</p>
     */
    public Optional<Course> match(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String haystack = normalise(text);
        List<Course> all = courses.findAll();

        // An exact name in the message wins outright, however common its words are.
        for (Course c : all) {
            if (c.getName() != null && haystack.contains(normalise(c.getName()))) {
                return Optional.of(c);
            }
        }

        Course best = null;
        int bestScore = 0;
        boolean tied = false;

        for (Course c : all) {
            int score = 0;
            for (String token : distinctiveTokens(c.getName())) {
                if (containsWord(haystack, token)) score++;
            }
            if (score == 0) continue;
            if (score > bestScore) {
                best = c;
                bestScore = score;
                tied = false;
            } else if (score == bestScore) {
                tied = true;
            }
        }

        if (best == null || tied) {
            if (tied) log.debug("Course wording was ambiguous, so nothing was assumed: {}", text);
            return Optional.empty();
        }
        return Optional.of(best);
    }

    /** The words in a course name that actually tell it apart from the others. */
    List<String> distinctiveTokens(String name) {
        if (name == null) return List.of();
        Set<String> tokens = new LinkedHashSet<>(Arrays.asList(normalise(name).split("\\s+")));
        List<String> out = new ArrayList<>();
        for (String t : tokens) {
            if (t.length() >= MIN_TOKEN && !TOO_COMMON.contains(t)) out.add(t);
        }
        return out;
    }

    /**
     * Whole-word containment.
     *
     * <p>Substring matching would find "java" inside "javascript" and file a student asking about
     * one under the other.</p>
     */
    private boolean containsWord(String haystack, String word) {
        return (" " + haystack + " ").contains(" " + word + " ");
    }

    private String normalise(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+ ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
