package com.devanshedutech.model.crm;

/**
 * Why a lead was lost. Required whenever a lead moves to {@link Stage#LOST}, because
 * "we don't know" is the answer that stops the institute from improving.
 */
public enum LostReason {
    FEES("Fees — out of budget"),
    CHOSE_COMPETITOR("Chose another institute"),
    NO_RESPONSE("No response after the full cycle"),
    WRONG_FIT("Wrong fit for their goal"),
    TIMING("Timing — may return for a later intake"),
    DISTANCE("Distance or travel"),
    HIGHER_STUDIES("Went for higher studies"),
    WRONG_NUMBER("Wrong or invalid number"),
    OTHER("Other");

    private final String label;

    LostReason(String label) { this.label = label; }

    public String getLabel() { return label; }
}
