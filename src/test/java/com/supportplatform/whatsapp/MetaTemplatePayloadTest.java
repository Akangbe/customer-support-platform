package com.supportplatform.whatsapp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The Graph API template payload, positional and named, built without any network call. */
class MetaTemplatePayloadTest {

    @Test
    void positionalParamsKeepTheirExistingShape() {
        Map<String, Object> payload = MetaWhatsAppGateway.templatePayload("+14155550001", "order_shipped", "en",
                MetaWhatsAppGateway.positionalParameters(List.of("Ada", "ORD-42")), "track/ORD-42");

        assertThat(payload).isEqualTo(Map.of(
                "messaging_product", "whatsapp",
                "to", "+14155550001",
                "type", "template",
                "template", Map.of(
                        "name", "order_shipped",
                        "language", Map.of("code", "en"),
                        "components", List.of(
                                Map.of("type", "body", "parameters", List.of(
                                        Map.of("type", "text", "text", "Ada"),
                                        Map.of("type", "text", "text", "ORD-42"))),
                                Map.of("type", "button", "sub_type", "url", "index", "0",
                                        "parameters", List.of(Map.of("type", "text", "text", "track/ORD-42")))))));
    }

    @Test
    void noParamsAndNoButtonSendsNoComponents() {
        Map<String, Object> payload = MetaWhatsAppGateway.templatePayload("+14155550002", "hello", "en",
                MetaWhatsAppGateway.positionalParameters(List.of()), null);

        assertThat(payload.get("template")).isEqualTo(Map.of("name", "hello", "language", Map.of("code", "en")));
    }

    @Test
    void namedParamsCarryTheirParameterNameInTheCallersOrder() {
        Map<String, String> named = new LinkedHashMap<>();
        named.put("variable", "a payment of ₦25,000");
        named.put("ref", "TP-1");

        Map<String, Object> payload = MetaWhatsAppGateway.templatePayload("+2348000000000",
                "multipurpose_trustpady_utility", "en", MetaWhatsAppGateway.namedParameters(named), null);

        assertThat(payload.get("template")).isEqualTo(Map.of(
                "name", "multipurpose_trustpady_utility",
                "language", Map.of("code", "en"),
                "components", List.of(Map.of("type", "body", "parameters", List.of(
                        Map.of("type", "text", "parameter_name", "variable", "text", "a payment of ₦25,000"),
                        Map.of("type", "text", "parameter_name", "ref", "text", "TP-1"))))));
    }

    @Test
    void namedParamsLeaveTheUrlButtonUnchanged() {
        Map<String, Object> payload = MetaWhatsAppGateway.templatePayload("+2348000000000", "t", "en",
                MetaWhatsAppGateway.namedParameters(Map.of("variable", "x")), "orders/1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components =
                (List<Map<String, Object>>) ((Map<String, Object>) payload.get("template")).get("components");
        assertThat(components.get(1)).isEqualTo(Map.of("type", "button", "sub_type", "url", "index", "0",
                "parameters", List.of(Map.of("type", "text", "text", "orders/1"))));
    }
}
