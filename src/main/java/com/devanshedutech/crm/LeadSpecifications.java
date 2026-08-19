package com.devanshedutech.crm;

import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.model.crm.Stage;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Reusable pipeline filters.
 *
 * <p>Each returns null when it does not apply, so callers can chain them and only the filters
 * actually asked for reach the query. {@link #ownedBy(String)} is the important one: the
 * controller derives it from the caller's permissions, so a counsellor's query is narrowed to
 * their own leads in the database rather than in the response.</p>
 */
public final class LeadSpecifications {

    private static final List<Stage> CLOSED = List.of(Stage.ENROLLED, Stage.LOST);

    private LeadSpecifications() {}

    /** Restricts to one owner. Null means no restriction, which only privileged roles get. */
    public static Specification<Lead> ownedBy(String ownerId) {
        if (ownerId == null) return null;
        return (root, q, cb) -> cb.equal(root.get("assignedToId"), ownerId);
    }

    public static Specification<Lead> unassigned() {
        return (root, q, cb) -> cb.isNull(root.get("assignedToId"));
    }

    public static Specification<Lead> stageIs(Stage stage) {
        if (stage == null) return null;
        return (root, q, cb) -> cb.equal(root.get("stage"), stage);
    }

    public static Specification<Lead> gradeIs(Grade grade) {
        if (grade == null) return null;
        return (root, q, cb) -> cb.equal(root.get("grade"), grade);
    }

    /** Leads still being worked: not enrolled, not lost, not opted out. */
    public static Specification<Lead> open() {
        return (root, q, cb) -> cb.and(
                cb.not(root.get("stage").in(CLOSED)),
                cb.or(cb.isNull(root.get("optedOut")), cb.isFalse(root.get("optedOut"))));
    }

    public static Specification<Lead> awaitingFirstReply() {
        return (root, q, cb) -> cb.and(
                cb.equal(root.get("stage"), Stage.NEW),
                cb.isNull(root.get("firstRespondedAt")),
                cb.or(cb.isNull(root.get("optedOut")), cb.isFalse(root.get("optedOut"))));
    }

    public static Specification<Lead> nextTouchOn(LocalDate day) {
        return (root, q, cb) -> cb.equal(root.get("nextTouchOn"), day);
    }

    public static Specification<Lead> nextTouchBefore(LocalDate day) {
        return (root, q, cb) -> cb.lessThan(root.get("nextTouchOn"), day);
    }

    /**
     * The SOP violation the end-of-day check looks for: an active Hot or Warm lead with no
     * future date. Cold leads are exempt because the SOP says they are never manually chased.
     */
    public static Specification<Lead> blankNextTouch() {
        return (root, q, cb) -> cb.and(
                cb.isNull(root.get("nextTouchOn")),
                cb.or(cb.isNull(root.get("grade")), cb.notEqual(root.get("grade"), Grade.COLD)));
    }

    /** Free-text search across the fields a counsellor actually remembers about a student. */
    public static Specification<Lead> matching(String query) {
        if (query == null || query.isBlank()) return null;
        String needle = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        String digits = query.replaceAll("\\D", "");
        return (root, q, cb) -> {
            Predicate byText = cb.or(
                    cb.like(cb.lower(root.get("fullName")), needle),
                    cb.like(cb.lower(root.get("cityName")), needle),
                    cb.like(cb.lower(root.get("courseInterested")), needle),
                    cb.like(cb.lower(root.get("email")), needle));
            if (digits.length() >= 4) {
                return cb.or(byText, cb.like(root.get("phoneNormalized"), "%" + digits + "%"));
            }
            return byText;
        };
    }

    /**
     * Leads who may receive an announcement.
     *
     * <p>Opting out is absolute: a student who asked to stop is excluded from every segment,
     * with no way to select them back in. That is both the decent thing and the practical one —
     * messaging people who asked you not to is what destroys a WhatsApp number's standing.</p>
     */
    public static Specification<Lead> broadcastable() {
        return (root, q, cb) -> cb.and(
                cb.or(cb.isNull(root.get("optedOut")), cb.isFalse(root.get("optedOut"))),
                cb.isNotNull(root.get("phoneNormalized")));
    }

    /** Cold leads: no counsellor time, announcements only, per SOP section 3. */
    public static Specification<Lead> cold() {
        return (root, q, cb) -> cb.equal(root.get("grade"), Grade.COLD);
    }

    /** Day-21 wrap-ups and closed leads kept for the next intake. */
    public static Specification<Lead> updatesOnly() {
        return (root, q, cb) -> cb.isTrue(root.get("updatesOnly"));
    }

    public static Specification<Lead> stageIn(List<Stage> stages) {
        return (root, q, cb) -> root.get("stage").in(stages);
    }

    /** Chains the filters that apply, ignoring the nulls. */
    @SafeVarargs
    public static Specification<Lead> all(Specification<Lead>... specs) {
        Specification<Lead> combined = null;
        for (Specification<Lead> s : specs) {
            if (s == null) continue;
            combined = (combined == null) ? s : combined.and(s);
        }
        return combined;
    }
}
