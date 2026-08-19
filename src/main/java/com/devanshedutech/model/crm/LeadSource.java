package com.devanshedutech.model.crm;

import java.util.Locale;

/**
 * Where the enquiry came from — SOP section 1, and the basis of the playbook's headline
 * question: which channel actually produces admissions, not just leads.
 *
 * <p>Nothing captured this before, so the metric was unbuildable regardless of how the
 * dashboard was written.</p>
 */
public enum LeadSource {
    WHATSAPP("WhatsApp"),
    INSTAGRAM_AD("Instagram ad"),
    FACEBOOK_AD("Facebook ad"),
    COLLEGE_SEMINAR("College seminar"),
    REFERRAL("Referral"),
    WALK_IN("Walk-in"),
    GOOGLE_SEARCH("Google search"),
    WEBSITE_FORM("Website form"),
    CONTACT_FORM("Contact form"),
    WEBSITE_CHATBOT("Website chatbot"),
    PHONE_CALL("Phone call"),
    OTHER("Other");

    private final String label;

    LeadSource(String label) { this.label = label; }

    public String getLabel() { return label; }

    public static LeadSource parse(String raw, LeadSource fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String k = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (LeadSource s : values()) {
            if (s.name().equals(k) || s.label.equalsIgnoreCase(raw.trim())) return s;
        }
        return fallback;
    }

    /** Maps a utm_source / Meta referral value onto a source, for automatic attribution. */
    public static LeadSource fromUtm(String utmSource, String utmMedium) {
        String s = utmSource == null ? "" : utmSource.toLowerCase(Locale.ROOT);
        String m = utmMedium == null ? "" : utmMedium.toLowerCase(Locale.ROOT);
        boolean paid = m.contains("cpc") || m.contains("paid") || m.contains("ad");
        if (s.contains("instagram") || s.contains("ig")) return paid ? INSTAGRAM_AD : INSTAGRAM_AD;
        if (s.contains("facebook") || s.contains("fb") || s.contains("meta")) return FACEBOOK_AD;
        if (s.contains("google")) return GOOGLE_SEARCH;
        if (s.contains("whatsapp") || s.contains("wa")) return WHATSAPP;
        if (s.contains("referral")) return REFERRAL;
        return null;
    }
}
