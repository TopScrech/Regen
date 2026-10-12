package dev.screch.regen;

import java.security.SecureRandom;

public final class SeedResolver {
    private static final SecureRandom RANDOM = new SecureRandom();

    private SeedResolver() {}

    public static long random() {
        return RANDOM.nextLong();
    }

    public static long parse(String seed) {
        String value = seed.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("The seed must not be blank");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return value.hashCode();
        }
    }
}
