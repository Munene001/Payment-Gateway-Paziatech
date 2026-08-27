package com.yourapp.payment_gateway.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/payments")
public class CallbackController {

    private static final Logger logger = LoggerFactory.getLogger(CallbackController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @Value("${nextjs.url}")
    private String NEXTJS_URL;

    @Value("${nextjs.internal.secret}")
    private String internalSecret;

    @Value("${payment.callback.secret}")
    private String expectedCallbackSecret;

    // ================================================
    // SAFARICOM DARAJA CALLBACK HANDLER
    // ================================================
    @SuppressWarnings("unchecked")
    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(
            @RequestParam(value = "secret", required = false) String secret,
            @RequestBody Map<String, Object> callback) {

        logger.info("Received callback from Safaricom");

        // Validate secret - return HTTP 401 if unauthorized
        if (expectedCallbackSecret != null && !expectedCallbackSecret.isBlank()) {
            if (secret == null || !secret.trim().equals(expectedCallbackSecret.trim())) {
                logger.error("Unauthorized callback attempt with invalid secret: {}", secret);
                return ResponseEntity.status(401).body(createErrorResponse("Unauthorized"));
            }
        }

        // Defensive extraction for Daraja JSON structure
        Map<String, Object> body = (Map<String, Object>) callback.getOrDefault("Body", Map.of());
        Map<String, Object> stkCallback = (Map<String, Object>) body.getOrDefault("stkCallback", Map.of());

        String checkoutRequestId = (String) stkCallback.get("CheckoutRequestID");
        if (checkoutRequestId == null || checkoutRequestId.isBlank()) {
            logger.error("Invalid Daraja callback structure: Missing CheckoutRequestID");
            // Return 200 OK so Safaricom does not retry broken structures indefinitely
            return ResponseEntity.ok(createErrorResponse("Invalid callback payload format"));
        }

        int resultCode = parseResultCode(stkCallback.get("ResultCode"));
        String resultDesc = (String) stkCallback.getOrDefault("ResultDesc", "");

        logger.info("Safaricom Callback: CheckoutRequestID={}, ResultCode={}", checkoutRequestId, resultCode);

        String status;
        boolean retryable;
        String displayMessage;
        String mpesaReceiptNumber = "";
        String phoneNumber = "";

        switch (resultCode) {
            case 0 -> {
                status = "COMPLETED";
                retryable = false;
                displayMessage = "Payment successful!";
                
                Map<String, Object> metadata = (Map<String, Object>) stkCallback.getOrDefault("CallbackMetadata", Map.of());
                List<Map<String, Object>> items = (List<Map<String, Object>>) metadata.getOrDefault("Item", List.of());
                for (Map<String, Object> item : items) {
                    String name = (String) item.get("Name");
                    Object value = item.get("Value");
                    if ("MpesaReceiptNumber".equals(name) && value != null) {
                        mpesaReceiptNumber = String.valueOf(value);
                    } else if ("PhoneNumber".equals(name) && value != null) {
                        phoneNumber = String.valueOf(value);
                    }
                }
            }
            case 1032 -> {
                status = "CANCELLED";
                retryable = true;
                displayMessage = "Payment cancelled by user";
                mpesaReceiptNumber = "CANCELLED";
            }
            case 1 -> {
                status = "INSUFFICIENT_FUNDS";
                retryable = true;
                displayMessage = "Insufficient funds";
                mpesaReceiptNumber = "FAILED";
            }
            case 2001 -> {
                status = "WRONG_PIN";
                retryable = true;
                displayMessage = "Wrong PIN entered";
                mpesaReceiptNumber = "FAILED";
            }
            case 1037 -> {
                status = "TIMEOUT";
                retryable = true;
                displayMessage = "Transaction timeout";
                mpesaReceiptNumber = "TIMEOUT";
            }
            case 4999 -> {
                status = "SYSTEM_ERROR";
                retryable = false;
                displayMessage = "Service temporarily unavailable";
                mpesaReceiptNumber = "SYSTEM_ERROR";
            }
            default -> {
                status = "FAILED";
                retryable = true;
                displayMessage = "Payment failed";
                mpesaReceiptNumber = "FAILED";
            }
        }

        Map<String, Object> payload = buildUnifiedPayload(
            checkoutRequestId, status, resultCode, resultDesc, 
            retryable, displayMessage, mpesaReceiptNumber, phoneNumber
        );

        CompletableFuture.runAsync(() -> forwardToNextJs(payload));

        return ResponseEntity.ok(createSuccessResponse());
    }

    // ================================================
    // KOPOKOPO CALLBACK HANDLER
    // ================================================
    @SuppressWarnings("unchecked")
    @PostMapping("/kopokopo-callback")
    public ResponseEntity<Map<String, Object>> handleKopokopoCallback(
            @RequestHeader(value = "X-KopoKopo-Signature", required = false) String signature,
            @RequestBody String rawRequestBody) {

        logger.info("Received KopoKopo Callback");

        try {
            Map<String, Object> callback = objectMapper.readValue(rawRequestBody, Map.class);

            String tillNumber = "";
            String resourceId = null;
            Long orderId = null;
            String status = null;
            String receiptNumber = "";

            if (callback.get("data") instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) callback.get("data");
                resourceId = (String) data.get("id");

                if (data.get("attributes") instanceof Map) {
                    Map<String, Object> attributes = (Map<String, Object>) data.get("attributes");
                    tillNumber = (String) attributes.getOrDefault("till_number", "");
                    status = (String) attributes.get("status");

                    if (attributes.get("metadata") instanceof Map) {
                        Map<String, Object> metadata = (Map<String, Object>) attributes.get("metadata");
                        if (metadata.get("order_id") != null) {
                            orderId = parseLong(metadata.get("order_id"));
                        }
                    }

                    if (attributes.get("event") instanceof Map) {
                        Map<String, Object> event = (Map<String, Object>) attributes.get("event");
                        if (event.get("resource") instanceof Map) {
                            Map<String, Object> resource = (Map<String, Object>) event.get("resource");
                            if (tillNumber.isBlank() && resource.get("till_number") != null) {
                                tillNumber = (String) resource.get("till_number");
                            }
                            receiptNumber = Optional.ofNullable(resource.get("reference"))
                                    .or(() -> Optional.ofNullable(resource.get("receipt_number")))
                                    .map(String::valueOf)
                                    .orElse("");
                        }
                    }

                    if (receiptNumber.isBlank() && attributes.get("reference") != null) {
                        receiptNumber = String.valueOf(attributes.get("reference"));
                    }
                }
            }

            // Fallback: Check root event object
            if (resourceId == null && callback.get("event") instanceof Map) {
                Map<String, Object> event = (Map<String, Object>) callback.get("event");
                if (event.get("resource") instanceof Map) {
                    Map<String, Object> resource = (Map<String, Object>) event.get("resource");
                    resourceId = (String) resource.get("id");
                    if (tillNumber.isBlank() && resource.get("till_number") != null) {
                        tillNumber = (String) resource.get("till_number");
                    }
                }
            }

            if (resourceId == null || resourceId.isBlank()) {
                logger.error("Resource ID extraction failed for payload: {}", rawRequestBody);
                return ResponseEntity.ok(createErrorResponse("Invalid callback structure"));
            }

            int resultCode;
            boolean retryable;
            String unifiedStatus;
            String displayMessage;

            if ("success".equalsIgnoreCase(status)) {
                unifiedStatus = "COMPLETED";
                resultCode = 0;
                retryable = false;
                displayMessage = "Payment successful!";
            } else {
                unifiedStatus = "FAILED";
                resultCode = 1;
                retryable = true;
                displayMessage = "Payment failed";
                receiptNumber = "FAILED";
            }

            Map<String, Object> payload = buildUnifiedPayload(
                resourceId, unifiedStatus, resultCode, displayMessage,
                retryable, displayMessage, receiptNumber, ""
            );

            payload.put("isKopokopo", true);
            payload.put("rawBodyString", rawRequestBody);
            payload.put("kopokopoSignature", signature != null ? signature : "");
            payload.put("tillNumber", tillNumber);
            if (orderId != null) payload.put("orderId", orderId);

            CompletableFuture.runAsync(() -> forwardToNextJs(payload));

            return ResponseEntity.ok(createSuccessResponse());

        } catch (Exception e) {
            logger.error("Error handling KopoKopo callback: {}", e.getMessage(), e);
            return ResponseEntity.ok(createErrorResponse("Internal error processing payload"));
        }
    }

    // ================================================
    // HELPERS
    // ================================================

    private Map<String, Object> buildUnifiedPayload(
            String checkoutRequestId, String status, int resultCode,
            String resultDesc, boolean retryable, String displayMessage,
            String mpesaReceiptNumber, String phoneNumber) {

        Map<String, Object> payload = new HashMap<>();
        payload.put("checkoutRequestId", checkoutRequestId);
        payload.put("status", status);
        payload.put("resultCode", resultCode);
        payload.put("resultDesc", Optional.ofNullable(resultDesc).orElse(""));
        payload.put("retryable", retryable);
        payload.put("displayMessage", Optional.ofNullable(displayMessage).orElse(""));
        payload.put("mpesaReceiptNumber", Optional.ofNullable(mpesaReceiptNumber).orElse(""));
        payload.put("phoneNumber", Optional.ofNullable(phoneNumber).orElse(""));
        return payload;
    }

    private void forwardToNextJs(Map<String, Object> payload) {
        try {
            String targetUrl = NEXTJS_URL.replaceAll("/+$", "") + "/api/shops/payments/update-order";
            String jsonBody = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Internal-Secret", Optional.ofNullable(internalSecret).orElse(""))
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.error("Next.js response failure: status={}, body={}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            logger.error("Failed to forward payload to Next.js: {}", e.getMessage());
        }
    }

    private int parseResultCode(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private Long parseLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private Map<String, Object> createSuccessResponse() {
        Map<String, Object> response = new HashMap<>();
        response.put("ResultCode", 0);
        response.put("ResultDesc", "Success");
        return response;
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("ResultCode", 1);
        response.put("ResultDesc", message);
        return response;
    }
}