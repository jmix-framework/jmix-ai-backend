package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.CheckDef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Fingerprints persisted with check runs and used to group compatible analytics cohorts. */
final class CheckFingerprints {

    // set to the date of the change whenever the canonicalization/hashing scheme changes; dated
    // (not "vN") so it cannot be confused with Jmix versions, and never reuse a value — runs
    // stamped by the old scheme would look comparable to new ones
    private static final String DEFINITION_PREFIX = "definitions-fingerprint-version-2026-07-26";
    private static final int SHORT_HASH_LENGTH = 12;

    private CheckFingerprints() {
    }

    static String forDefinitions(List<CheckDef> definitions) {
        List<String> canonicalDefinitions = definitions.stream()
                .map(CheckFingerprints::canonicalDefinition)
                .sorted(Comparator.naturalOrder())
                .toList();
        MessageDigest digest = sha256();
        for (String definition : canonicalDefinitions) {
            byte[] bytes = definition.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
        }
        return DEFINITION_PREFIX + "-" + shortHex(digest.digest());
    }

    static String shortHash(String value) {
        MessageDigest digest = sha256();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return shortHex(digest.digest());
    }

    private static String canonicalDefinition(CheckDef checkDef) {
        // jmixVersion was not part of the legacy digest; run version is a separate cohort dimension.
        return canonicalField(checkDef.getId() != null ? checkDef.getId().toString() : null)
                + canonicalField(checkDef.getCategory())
                + canonicalField(checkDef.getQuestion())
                + canonicalField(checkDef.getAnswer());
    }

    private static String canonicalField(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String shortHex(byte[] hash) {
        return HexFormat.of().formatHex(hash, 0, SHORT_HASH_LENGTH / 2);
    }
}
