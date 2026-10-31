package com.moveinsync.roster.validation;

import com.moveinsync.roster.domain.EventType;
import com.moveinsync.roster.domain.FieldOwner;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Plain boundary validation with stable, PII-free reason codes. */
public class RecordValidator {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[1-9][0-9]{7,14}$");
    private static final Set<String> OFFICE_CODES = Set.of("BLR", "BOM", "DEL", "HYD", "MAA", "PUN");

    public ValidationResult validate(String tenantId, String employeeId, FieldOwner owner,
                                     Integer version, EventType eventType, Map<String, Object> payload) {
        if (isBlank(tenantId) || isBlank(employeeId) || owner == null || eventType == null || version == null) {
            return ValidationResult.invalid("MISSING_REQUIRED_FIELD");
        }
        // Versions are 1-based. Zero is the "no row yet" sentinel used by resolveVersion:
        // an inbound version 0 for an unknown employee compares equal to the stored 0 and
        // is silently dropped as DUPLICATE — the employee is never created. Reject at the
        // boundary instead so the caller gets a clear INVALID_VERSION error.
        if (version < 1) {
            return ValidationResult.invalid("INVALID_VERSION");
        }
        if (payload == null) {
            return ValidationResult.invalid("MISSING_REQUIRED_FIELD");
        }
        if (eventType == EventType.UPSERT && payload.isEmpty()) {
            return ValidationResult.invalid("MISSING_REQUIRED_FIELD");
        }

        Object email = payload.get("email");
        if (email != null && (!(email instanceof String value) || !EMAIL.matcher(value).matches())) {
            return ValidationResult.invalid("INVALID_EMAIL");
        }
        Object phone = payload.get("phone");
        if (phone != null && (!(phone instanceof String value) || !PHONE.matcher(value).matches())) {
            return ValidationResult.invalid("INVALID_PHONE");
        }
        Object office = payload.get("officeCode");
        if (office != null && (!(office instanceof String value) || !OFFICE_CODES.contains(value))) {
            return ValidationResult.invalid("UNKNOWN_OFFICE_CODE");
        }
        return ValidationResult.ok();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ValidationResult(boolean valid, String reason) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
