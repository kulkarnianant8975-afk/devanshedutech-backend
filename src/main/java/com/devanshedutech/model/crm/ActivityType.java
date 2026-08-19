package com.devanshedutech.model.crm;

/**
 * Kinds of entry in a lead's activity log.
 *
 * <p>SOP section 1, rule 2: "if it's not written in the pipeline, it didn't happen". Last-touch
 * is therefore derived from this log rather than being a column someone has to remember to
 * update.</p>
 */
public enum ActivityType {
    CAPTURE("Lead captured"),
    CALL("Call"),
    WHATSAPP("WhatsApp"),
    EMAIL("Email"),
    DEMO("Demo"),
    NOTE("Note"),
    STAGE_CHANGE("Stage changed"),
    GRADE_CHANGE("Grade changed"),
    ASSIGNMENT("Assignment"),
    SYSTEM("System");

    private final String label;

    ActivityType(String label) { this.label = label; }

    public String getLabel() { return label; }
}
