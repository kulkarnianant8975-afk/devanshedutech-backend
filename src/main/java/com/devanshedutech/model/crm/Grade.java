package com.devanshedutech.model.crm;

import java.util.Locale;

/**
 * Lead temperature, as defined in the Counsellor SOP section 3.
 *
 * <p>Hot means ready within about two weeks — asking about fees, batch dates, or a demo. Warm
 * means interested but still comparing. Cold means browsing, and per the SOP a cold lead is
 * never manually chased.</p>
 *
 * <p>A lead moves between grades as the conversation changes; the SOP is explicit that a cold
 * lead who suddenly asks about batch dates becomes hot and should be reacted to fast.</p>
 */
public enum Grade {
    HOT("Hot"),
    WARM("Warm"),
    COLD("Cold");

    private final String label;

    Grade(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** Cold leads receive announcements only — no counsellor time, per SOP section 3. */
    public boolean isBroadcastOnly() {
        return this == COLD;
    }

    /**
     * The lane a lead falls to when it finishes this one without converting.
     *
     * <p>Null for {@link #COLD}: there is nowhere further down, so a lead that completes the
     * cold lane is closed as lost rather than demoted — and kept, because students return.</p>
     */
    public Grade demoteTo() {
        return switch (this) {
            case HOT -> WARM;
            case WARM -> COLD;
            case COLD -> null;
        };
    }

    /** The lane a lead is promoted to when it re-engages; null if already at the top. */
    public Grade promoteTo() {
        return switch (this) {
            case COLD -> WARM;
            case WARM -> HOT;
            case HOT -> null;
        };
    }

    public static Grade parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String k = raw.trim().toUpperCase(Locale.ROOT);
        for (Grade g : values()) {
            if (g.name().equals(k) || g.label.equalsIgnoreCase(raw.trim())) return g;
        }
        return null;
    }
}
