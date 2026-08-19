package com.devanshedutech.model.crm;

import java.util.Locale;

/** The SOP's "Status (background)" column: Final-year / Engineering / Polytechnic / BCA / BCS / MCA / Other. */
public enum StudentBackground {
    FINAL_YEAR("Final-year"),
    ENGINEERING("Engineering"),
    POLYTECHNIC("Polytechnic"),
    BCA("BCA"),
    BCS("BCS"),
    MCA("MCA"),
    OTHER("Other");

    private final String label;

    StudentBackground(String label) { this.label = label; }

    public String getLabel() { return label; }

    /**
     * Best-effort read of the legacy free-text {@code education} column so existing leads
     * are not left blank. Falls back to OTHER rather than guessing wrongly.
     */
    public static StudentBackground parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String k = raw.trim().toUpperCase(Locale.ROOT);
        if (k.contains("FINAL")) return FINAL_YEAR;
        if (k.contains("MCA")) return MCA;
        if (k.contains("BCA")) return BCA;
        if (k.contains("BCS") || k.contains("B.SC") || k.contains("BSC")) return BCS;
        if (k.contains("POLY") || k.contains("DIPLOMA")) return POLYTECHNIC;
        if (k.contains("ENGG") || k.contains("ENGINEER") || k.contains("B.E") || k.contains("BE ")
                || k.contains("BTECH") || k.contains("B.TECH")) return ENGINEERING;
        return OTHER;
    }
}
