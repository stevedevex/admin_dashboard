package com.tao.sandbox.runtime.match;

import java.util.Optional;

/**
 * Turns a raw extracted value into the form used for lookup.
 *
 * <p>Applied identically when a key is extracted from a request and when a filename is computed
 * for the admin API. One implementation, deliberately: if the frontend computed filenames itself
 * the two would drift, and a mock would be saved under a name no request could ever resolve to.
 */
public final class Normaliser {

    private Normaliser() {}

    /**
     * @return the normalised value, or empty if the field should be treated as absent
     */
    public static Optional<String> normalise(String raw) {
        if (raw == null) {
            return Optional.empty();
        }

        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            // Empty is absent, not a key with an empty value. Otherwise an unset optional
            // parameter would resolve to a different file than an omitted one.
            return Optional.empty();
        }

        return Optional.of(stripLeadingZeros(trimmed));
    }

    /**
     * Strips leading zeros from purely numeric values.
     *
     * <p>The same identifier is zero-padded by some services and not by others, so {@code
     * 00005678} and {@code 5678} must resolve to the same mock. Only applied when the whole value
     * is digits — an identifier like {@code AC-0100} keeps its shape.
     */
    private static String stripLeadingZeros(String value) {
        if (!isAllDigits(value)) {
            return value;
        }

        int firstSignificant = 0;
        while (firstSignificant < value.length() - 1 && value.charAt(firstSignificant) == '0') {
            firstSignificant++;
        }
        return value.substring(firstSignificant);
    }

    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
