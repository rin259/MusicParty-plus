package org.thornex.musicparty.room;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    @Test
    void hashesAndVerifiesWithoutKeepingThePlaintext() {
        String stored = PasswordHasher.hash("secret1");

        assertThat(stored).startsWith("pbkdf2$").doesNotContain("secret1");
        assertThat(PasswordHasher.matches("secret1", stored)).isTrue();
        assertThat(PasswordHasher.matches("wrong", stored)).isFalse();
    }

    @Test
    void emptyPasswordMeansNoProtection() {
        assertThat(PasswordHasher.hash(null)).isNull();
        assertThat(PasswordHasher.hash("")).isNull();
        assertThat(PasswordHasher.matches(null, null)).isTrue();
        assertThat(PasswordHasher.matches("anything", "")).isTrue();
    }

    @Test
    void differentSaltsProduceDifferentHashes() {
        String first = PasswordHasher.hash("same");
        String second = PasswordHasher.hash("same");

        assertThat(first).isNotEqualTo(second);
        assertThat(PasswordHasher.matches("same", first)).isTrue();
        assertThat(PasswordHasher.matches("same", second)).isTrue();
    }

    @Test
    void corruptedHashIsRejected() {
        assertThat(PasswordHasher.matches("x", "not-a-hash")).isFalse();
        assertThat(PasswordHasher.matches("x", "pbkdf2$1$bad$bad")).isFalse();
    }
}
