package com.devanshedutech.channel;

/**
 * Turns a number as a person typed it into one WhatsApp will accept.
 *
 * <p>Stored numbers are whatever a student entered on a form: "+91 98765 43210",
 * "098765-43210", "9876543210". A wa.me path containing a space or a plus is simply broken, and
 * an API call with one is rejected — so every channel normalises its own input rather than
 * trusting the caller to have done it.</p>
 */
final class PhoneNumbers {

    private static final String DEFAULT_COUNTRY_CODE = "91";

    private PhoneNumbers() {}

    /** Digits only, with a country code. Returns null when there is nothing usable. */
    static String toWhatsApp(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;

        // A leading zero is how the number is written domestically and is not part of it.
        if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (digits.length() < 10) return null;

        return digits.length() == 10 ? DEFAULT_COUNTRY_CODE + digits : digits;
    }
}
