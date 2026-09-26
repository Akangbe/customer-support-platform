package com.supportplatform.notification;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What libphonenumber makes of the numbers that matter here. The Nigerian
 * cases are shaped like the two recipients behind Trustpady's 131026
 * failures in September 2026 (digits changed): one real number without
 * WhatsApp, which this cannot catch, and one with two digits too many,
 * which it must.
 */
class RecipientNumberCheckTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void aNigerianMobileWithTheRightNumberOfDigitsIsValid() {
        assertThat(RecipientNumberCheck.problemWith("+2349031234173")).isEmpty();
        assertThat(RecipientNumberCheck.problemWith("+2348031234567")).isEmpty();
    }

    @Test
    void aNigerianMobileWithTwoDigitsTooManyIsNot() {
        assertThat(RecipientNumberCheck.problemWith("+234808084000004"))
                .hasValueSatisfying(problem -> assertThat(problem)
                        .contains("NG (+234)")
                        .contains("extra or missing digits"));
    }

    @Test
    void anUnusedCountryCodeIsNamedAsSuch() {
        assertThat(RecipientNumberCheck.problemWith("+99912345678"))
                .hasValue("its country code is not in use");
    }

    @Test
    void numbersTheTestSuiteAndOtherCountriesUseAreValid() {
        assertThat(RecipientNumberCheck.problemWith("+14155552671")).isEmpty();
        assertThat(RecipientNumberCheck.problemWith("+447911123456")).isEmpty();
    }

    @Test
    void observeModeNeverBlocks() {
        RecipientNumberCheck check = new RecipientNumberCheck("observe");

        assertThatCode(() -> check.check(TENANT, "+234808084000004", "any_template")).doesNotThrowAnyException();
    }

    @Test
    void enforceModeRejectsAnInvalidNumberAndPassesAValidOne() {
        RecipientNumberCheck check = new RecipientNumberCheck("enforce");

        assertThatThrownBy(() -> check.check(TENANT, "+234808084000004", "any_template"))
                .isInstanceOf(InvalidRecipientNumberException.class)
                .hasMessageContaining("not a valid phone number");
        assertThatCode(() -> check.check(TENANT, "+2349031234173", "any_template")).doesNotThrowAnyException();
    }
}
