package com.supportplatform.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The stored forms below are copied from production's {@code failure_reason} column. */
class FailureExplanationTest {

    @Test
    void readsTheCodeFromEachStoredForm() {
        assertThat(FailureExplanation.from("Meta error 131026: Message undeliverable - Message Undeliverable.").code())
                .isEqualTo("131026");
        assertThat(FailureExplanation.from("HTTP 400 code=132000 subcode=- type=OAuthException fbtrace=AjPSp").code())
                .isEqualTo("132000");
        assertThat(FailureExplanation.from("400 Bad Request: \"{\"error\":{\"message\":\"(#132018) There's an issue").code())
                .isEqualTo("132018");
    }

    @Test
    void aBillingBlockIsPresentedAsAPlatformProblem() {
        FailureExplanation explanation = FailureExplanation.from(
                "Meta error 131042: Business eligibility payment issue - Message failed to send");

        assertThat(explanation.category()).isEqualTo(FailureExplanation.Category.PLATFORM);
        assertThat(explanation.message()).contains("not caused by your message");
    }

    @Test
    void neverRepeatsWhatMetaWrote() {
        String raw = "HTTP 400 code=199999 subcode=- type=OAuthException fbtrace=SECRETTRACE";

        FailureExplanation explanation = FailureExplanation.from(raw);

        assertThat(explanation.code()).isEqualTo("199999");
        assertThat(explanation.message()).doesNotContain("fbtrace").doesNotContain("SECRETTRACE");
        assertThat(FailureExplanation.from("some internal detail: token=abc").message()).doesNotContain("token");
    }

    @Test
    void handlesFailuresWithNoCode() {
        assertThat(FailureExplanation.from("WhatsApp reported delivery failure").message()).contains("did not give a reason");
        assertThat(FailureExplanation.from(null).code()).isNull();
        assertThat(FailureExplanation.from("No response from Meta (UnknownContentTypeException): Could not extract")
                .category()).isEqualTo(FailureExplanation.Category.PLATFORM);
    }
}
