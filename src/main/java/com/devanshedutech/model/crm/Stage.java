package com.devanshedutech.model.crm;

import java.util.Locale;

/**
 * Pipeline stage, as defined in the Counsellor SOP section 1.
 *
 * <p>This replaces the old free-text {@code Lead.status} column, which was written once as the
 * literal string "New" and never changed, making conversion impossible to measure.</p>
 */
public enum Stage {
    NEW("New", 0),
    CONTACTED("Contacted", 1),
    /**
     * Came to the office without booking anything first.
     *
     * <p>A walk-in is a stronger signal than a booked demo — somebody who travelled to Parbhani
     * to look at the place has already decided it is worth their afternoon. Before this existed
     * they sat in Contacted until they registered for a demo, so the board showed a visit as
     * nothing at all, and a counsellor scanning it could not tell them from a lead who had only
     * answered the phone.</p>
     *
     * <p>Placed before Demo booked because that is the usual order: they visit, then register
     * for a demo while they are standing there.</p>
     */
    VISITED("Visited the office", 2),
    DEMO_BOOKED("Demo booked", 3),
    DEMO_DONE("Demo done", 4),
    FEE_DISCUSSION("Fee discussion", 5),
    ENROLLED("Enrolled", 6),
    LOST("Lost", -1);

    private final String label;
    private final int funnelDepth;

    Stage(String label, int funnelDepth) {
        this.label = label;
        this.funnelDepth = funnelDepth;
    }

    public String getLabel() { return label; }

    /**
     * How far along the admissions funnel this stage sits, used for conversion maths.
     *
     * <p>Lost is deliberately -1 rather than a position: it is an exit, not a step. A lead that
     * booked a demo and then went cold still reached the demo stage, so funnel rates are
     * computed from the deepest stage a lead ever reached, never from where it sits today.</p>
     */
    public int getFunnelDepth() { return funnelDepth; }

    public boolean isInFunnel() { return funnelDepth >= 0; }

    /** True once the lead has left the pipeline in either direction; no ladder runs on these. */
    public boolean isClosed() {
        return this == ENROLLED || this == LOST;
    }

    /**
     * Stages where a conversation is actively under way, so automatic decay must not fire.
     *
     * <p>Demoting somebody the day before they pay is the classic lead-decay bug, and it is
     * expensive precisely because those are the leads worth most.</p>
     */
    public boolean blocksDecay() {
        return this == DEMO_BOOKED || this == DEMO_DONE || this == FEE_DISCUSSION;
    }

    /** Tolerant parse used to migrate the legacy free-text status column. */
    public static Stage parse(String raw, Stage fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String k = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (Stage s : values()) {
            if (s.name().equals(k) || s.label.equalsIgnoreCase(raw.trim())) return s;
        }
        return fallback;
    }
}
