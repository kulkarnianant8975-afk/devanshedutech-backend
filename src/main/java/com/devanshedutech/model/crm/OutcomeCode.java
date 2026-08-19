package com.devanshedutech.model.crm;

/**
 * What actually happened on a contact, and what the system does about it.
 *
 * <p>Each of these is one of the nine situations enumerated in Counsellor SOP section 6. Rather
 * than leaving the counsellor to remember the prescribed follow-up, the rules are attached to
 * the outcome: picking one writes the activity, moves the stage, books the next touch, and
 * queues the right message pack. That is what makes the SOP self-enforcing instead of a
 * document nobody opens.</p>
 */
public enum OutcomeCode {

    /** SOP 4 — the guidance call happened. */
    CONNECTED("Connected", Stage.CONTACTED, null, 1, false, true, new int[]{}),

    /** SOP 6.1 — no pick-up. Fire the DNP WhatsApp immediately and retry at a different hour. */
    NO_ANSWER("No answer (DNP)", null, null, 1, false, true, new int[]{}),

    /** SOP 6.2 — "I'll think about it". The real reason must be recorded. */
    THINKING("Thinking about it", Stage.CONTACTED, null, 2, true, true, new int[]{}),

    /** SOP 6.3 — "I need to ask my parents". Follow up on day 1 AND day 3, never open-ended. */
    PARENTS("Asking parents", Stage.CONTACTED, null, 1, false, true, new int[]{1, 3}),

    /** SOP 6.4 — fee objection. Never discount on the spot; move them toward the free demo. */
    FEE_OBJECTION("Fees too high", Stage.FEE_DISCUSSION, null, 2, true, true, new int[]{}),

    /** SOP 6.5 — comparing other institutes. Calm confidence, then a demo invite. */
    COMPARING("Comparing other institutes", Stage.CONTACTED, null, 2, false, true, new int[]{}),

    /** SOP 6.6 — went quiet after being interested. Re-engage with value, not guilt. */
    SILENT("Went silent", null, Grade.COLD, 3, false, false, new int[]{}),

    /** SOP 4 step 5 — a demo or campus visit was booked with a specific date and time. */
    DEMO_BOOKED("Demo booked", Stage.DEMO_BOOKED, Grade.HOT, 1, false, false, new int[]{}),

    /** SOP 6.7 — attended the demo. Day 1 and day 3 follow-ups are where enrolments are won. */
    DEMO_ATTENDED("Demo attended", Stage.DEMO_DONE, Grade.HOT, 1, false, false, new int[]{1, 3}),

    /** SOP 6.9 — ready to enrol. */
    READY_TO_ENROL("Ready to enrol", Stage.ENROLLED, Grade.HOT, 7, false, false, new int[]{7}),

    /** SOP 6.8 — a firm no. Marked Lost with a reason; never deleted, they may return. */
    NOT_INTERESTED("Not interested", Stage.LOST, null, null, true, false, new int[]{}),

    /** Bad data. Archived without pretending it was a real rejection. */
    WRONG_NUMBER("Wrong number", Stage.LOST, null, null, false, false, new int[]{});

    private final String label;
    private final Stage stage;
    private final Grade grade;
    private final Integer nextTouchDays;
    private final boolean requiresReason;
    private final boolean countsAsAttempt;
    private final int[] extraFollowUpDays;

    OutcomeCode(String label, Stage stage, Grade grade, Integer nextTouchDays,
                boolean requiresReason, boolean countsAsAttempt, int[] extraFollowUpDays) {
        this.label = label;
        this.stage = stage;
        this.grade = grade;
        this.nextTouchDays = nextTouchDays;
        this.requiresReason = requiresReason;
        this.countsAsAttempt = countsAsAttempt;
        this.extraFollowUpDays = extraFollowUpDays;
    }

    public String getLabel() { return label; }

    /** Stage to move to, or null to leave the stage alone. */
    public Stage getStage() { return stage; }

    /** Grade to force, or null to leave grading to the counsellor and the ladder. */
    public Grade getGrade() { return grade; }

    /** Days from today for the next touch, or null when the lead is closing. */
    public Integer getNextTouchDays() { return nextTouchDays; }

    /** True when a free-text reason must be supplied — the SOP refuses vague exits. */
    public boolean isRequiresReason() { return requiresReason; }

    /** True when this outcome burns one of the three permitted call attempts. */
    public boolean isCountsAsAttempt() { return countsAsAttempt; }

    /** Additional follow-ups to schedule, in days — the D+1/D+3 pairs the playbook says everyone forgets. */
    public int[] getExtraFollowUpDays() { return extraFollowUpDays.clone(); }

    public boolean isLosing() { return stage == Stage.LOST; }
}
