package com.moveinsync.roster.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a stable SHA-256 fingerprint of the HR-owned fields of an {@link Employee}.
 *
 * <p>The hash is stored in the {@code hr_content_hash} column and used by the
 * reconciliation engine to identify changed rows without loading all columns:
 * if the hash matches, the row is UNCHANGED; if it differs, the row is CHANGED.
 *
 * <p>Design choices:
 * <ul>
 *   <li>Null fields contribute an empty string so the hash is defined even before HR
 *       data arrives (e.g., when a transport event creates the row first).
 *   <li>The separator {@code \u0000} (NUL byte) prevents the value of one field from
 *       bleeding into the next (e.g. {@code "ab"} + {@code "c"} ≠ {@code "a"} + {@code "bc"}).
 *   <li>Fields are concatenated in a fixed canonical order — changing the order would
 *       invalidate all stored hashes.
 * </ul>
 *
 * <p>This is a utility class (static methods only) — in C++ it would be a namespace
 * with free functions.  The private constructor prevents instantiation.
 */
public final class ContentHash {

    private ContentHash() {}

    /** Returns the lowercase hex SHA-256 digest of the HR-owned fields. */
    public static String compute(Employee emp) {
        String input = String.join("\u0000",
                nullToEmpty(emp.name()),
                nullToEmpty(emp.email()),
                nullToEmpty(emp.managerId()),
                nullToEmpty(emp.officeCode()),
                emp.status() != null ? emp.status().name() : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // HexFormat (Java 17+): converts byte[] to lowercase hex string.
            // In C++ you'd iterate over bytes and sprintf("%02x", b).
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java SE specification; this branch is unreachable.
            throw new IllegalStateException("SHA-256 not available — JVM is non-compliant", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
