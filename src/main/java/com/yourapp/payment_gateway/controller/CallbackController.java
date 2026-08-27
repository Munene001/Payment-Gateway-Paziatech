package com.yourapp.payment_gateway.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
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
    // SAFARICOM CALLBACK HANDLER
    // ================================================
    @SuppressWarnings("unchecked")
    @PostMapping("/callback")
    public Map<String, Object> handleCallback(
            @RequestParam(value = "secret", required = false) String secret,
            @RequestBody Map<String, Object> callback) {

        logger.info("Received callback from Safaricom");

        if (secret == null || !secret.trim().equals(expectedCallbackSecret != null ? expectedCallbackSecret.trim() : "")) {
            logger.error("Unauthorized callback attempt with invalid or missing secret: {}", secret);
            return createErrorResponse("Unauthorized");
        }

        Object bodyObj = callback.get("Body");
        if (!(bodyObj instanceof Map)) {
            logger.error("Invalid callback structure: Body is missing");
            return createErrorResponse("Invalid callback structure");
        }
        Map<String, Object> body = (Map<String, Object>) bodyObj;

        Object stkCallbackObj = body.get("stkCallback");
        if (!(stkCallbackObj instanceof Map)) {
            logger.error("Invalid callback structure: stkCallback is missing");
            return createErrorResponse("Invalid stkCallback structure");
        }
        Map<String, Object> stkCallback = (Map<String, Object>) stkCallbackObj;

        String checkoutRequestId = (String) stkCallback.get("CheckoutRequestID");

        int resultCode = -1;
        Object resultCodeObj = stkCallback.get("ResultCode");
        if (resultCodeObj instanceof Number) {
            resultCode = ((Number) resultCodeObj).intValue();
        } else if (resultCodeObj instanceof String) {
            try {
                resultCode = Integer.parseInt((String) resultCodeObj);
            } catch (NumberFormatException ignored) {}
        }

        String resultDesc = (String) stkCallback.get("ResultDesc");

        logger.info("Safaricom Callback: CheckoutRequestID={}, ResultCode={}", checkoutRequestId, resultCode);

        String status;
        boolean retryable;
        String displayMessage;
        String mpesaReceiptNumber = "";
        String phoneNumber = "";

        switch (resultCode) {
            case 0:
                status = "COMPLETED";
                retryable = false;
                displayMessage = "Payment successful!";

                Object metadataObj = stkCallback.get("CallbackMetadata");
                if (metadataObj instanceof Map) {
                    Map<String, Object> metadata = (Map<String, Object>) metadataObj;
                    Object itemsObj = metadata.get("Item");
                    if (itemsObj instanceof List) {
                        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                        for (Map<String, Object> item : items) {
                            String name = (String) item.get("Name");
                            Object value = item.get("Value");
                            if ("MpesaReceiptNumber".equals(name)) {
                                mpesaReceiptNumber = value != null ? String.valueOf(value) : "";
                            } else if ("PhoneNumber".equals(name)) {
                                phoneNumber = value != null ? String.valueOf(value) : "";
                            }
                        }
                    }
                }
                logger.info("Payment successful for {}. Receipt: {}", checkoutRequestId, mpesaReceiptNumber);
                break;

            case 1032:
                status = "CANCELLED";
                retryable = true;
                displayMessage = "Payment cancelled by user";
                mpesaReceiptNumber = "CANCELLED";
                logger.warn("Payment cancelled by user: {}", checkoutRequestId);
                break;

            case 1:
                status = "INSUFFICIENT_FUNDS";
                retryable = true;
                displayMessage = "Insufficient funds";
                mpesaReceiptNumber = "FAILED";
                logger.warn("Insufficient funds: {}", checkoutRequestId);
                break;

            case 2001:
                status = "WRONG_PIN";
                retryable = true;
                displayMessage = "Wrong PIN entered";
                mpesaReceiptNumber = "FAILED";
                logger.warn("Wrong PIN: {}", checkoutRequestId);
                break;

            case 1037:
                status = "TIMEOUT";
                retryable = true;
                displayMessage = "Transaction timeout";
                mpesaReceiptNumber = "TIMEOUT";
                logger.warn("Transaction timeout: {}", checkoutRequestId);
                break;

            case 4999:
                status = "SYSTEM_ERROR";
                retryable = false;
                displayMessage = "Service temporarily unavailable";
                mpesaReceiptNumber = "SYSTEM_ERROR";
                logger.error("System configuration error 4999 for request: {}", checkoutRequestId);
                break;

            default:
                status = "FAILED";
                retryable = true;
                displayMessage = "Payment failed";
                mpesaReceiptNumber = "FAILED";
                logger.error("Payment failed with code {}: {}", resultCode, checkoutRequestId);
                break;
        }

        Map<String, Object> payload = buildUnifiedPayload(
            checkoutRequestId, status, resultCode, resultDesc, 
            retryable, displayMessage, mpesaReceiptNumber, phoneNumber
        );

        CompletableFuture.runAsync(() -> forwardToNextJs(payload));

        Map<String, Object> response = new HashMap<>();
        response.put("ResultCode", 0);
        response.put("ResultDesc", "Success");
        logger.info("Safaricom callback acknowledged for {}", checkoutRequestId);
        return response;
    }

    // ================================================
    // KOPOKOPO CALLBACK HANDLER - FIXED
    // ================================================
    @SuppressWarnings("unchecked")
    @PostMapping("/kopokopo-callback")
    public Map<String, Object> handleKopokopoCallback(
            @RequestHeader(value = "X-KopoKopo-Signature", required = false) String signature,
            @RequestBody String rawRequestBody) {

        logger.info("========================================");
        logger.info("📥 KOPOKOPO CALLBACK RECEIVED!");
        logger.info("Time: {}", java.time.LocalDateTime.now());
        logger.info("Signature: {}", signature);
        logger.info("Raw Body: {}", rawRequestBody);
        logger.info("========================================");

        try {
            Map<String, Object> callback = objectMapper.readValue(rawRequestBody, Map.class);

            String tillNumber = "";
            String resourceId = null;
            Long orderId = null;
            String status = null;
            String receiptNumber = "";

            // Parse Data Envelope
            if (callback.get("data") instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) callback.get("data");
                if (data.containsKey("id")) {
                    resourceId = (String) data.get("id");
                }

                if (data.get("attributes") instanceof Map) {
                    Map<String, Object> attributes = (Map<String, Object>) data.get("attributes");
                    
                    if (attributes.get("till_number") != null) {
                        tillNumber = (String) attributes.get("till_number");
                    }
                    if (attributes.get("status") != null) {
                        status = (String) attributes.get("status");
                    }

                    // Metadata extraction
                    if (attributes.get("metadata") instanceof Map) {
                        Map<String, Object> metadata = (Map<String, Object>) attributes.get("metadata");
                        if (metadata.get("order_id") != null) {
                            orderId = ((Number) metadata.get("order_id")).longValue();
                        }
                    }

                    // Event extraction
                    if (attributes.get("event") instanceof Map) {
                        Map<String, Object> event = (Map<String, Object>) attributes.get("event");
                        if (event.get("resource") instanceof Map) {
                            Map<String, Object> resource = (Map<String, Object>) event.get("resource");
                            
                            if (tillNumber.isEmpty() && resource.get("till_number") != null) {
                                tillNumber = (String) resource.get("till_number");
                            }

                            // Kopo Kopo uses "reference" as the M-Pesa receipt number
                            if (resource.get("reference") != null) {
                                receiptNumber = String.valueOf(resource.get("reference"));
                            } else if (resource.get("receipt_number") != null) {
                                receiptNumber = String.valueOf(resource.get("receipt_number"));
                            }
                        }
                    }

                    if (receiptNumber.isEmpty() && attributes.get("reference") != null) {
                        receiptNumber = String.valueOf(attributes.get("reference"));
                    }
                }
            }

            // Fallback: Check root event object (K2Connect alternative format)
            if (resourceId == null && callback.get("event") instanceof Map) {
                Map<String, Object> event = (Map<String, Object>) callback.get("event");
                if (event.get("resource") instanceof Map) {
                    Map<String, Object> resource = (Map<String, Object>) event.get("resource");
                    if (resource.get("id") != null) {
                        resourceId = (String) resource.get("id");
                    }
                    if (tillNumber.isEmpty() && resource.get("till_number") != null) {
                        tillNumber = (String) resource.get("till_number");
                    }
                }
            }

            if (resourceId == null || resourceId.isEmpty()) {
                logger.error("Failed to extract resourceId from Kopo Kopo callback");
                return createErrorResponse("Invalid callback: resourceId not found");
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
                displayMessage = "Payment failed. Please try again.";
                receiptNumber = "FAILED";
            }

            Map<String, Object> payload = buildUnifiedPayload(
                resourceId,
                unifiedStatus,
                resultCode,
                displayMessage,
                retryable,
                displayMessage,
                receiptNumber,
                ""
            );

            payload.put("isKopokopo", true);
            payload.put("rawBodyString", rawRequestBody);
            payload.put("kopokopoSignature", signature != null ? signature : "");
            payload.put("tillNumber", tillNumber);
            
            if (orderId != null) {
                payload.put("orderId", orderId);
            }

            logger.info("Kopo Kopo Callback: resourceId={}, status={}, till={}, receipt={}, orderId={}", 
                        resourceId, status, tillNumber, receiptNumber, orderId);

            CompletableFuture.runAsync(() -> forwardToNextJs(payload));

            Map<String, Object> response = new HashMap<>();
            response.put("ResultCode", 0);
            response.put("ResultDesc", "Success");
            logger.info("✅ Kopo Kopo callback acknowledged for {}", resourceId);
            return response;

        } catch (Exception e) {
            logger.error("Error processing Kopo Kopo callback: {}", e.getMessage(), e);
            return createErrorResponse("Internal error");
        }
    }

    // ================================================
    // SHARED HELPER METHODS
    // ================================================

    private Map<String, Object> buildUnifiedPayload(
            String checkoutRequestId,
            String status,
            int resultCode,
            String resultDesc,
            boolean retryable,
            String displayMessage,
            String mpesaReceiptNumber,
            String phoneNumber) {

        Map<String, Object> payload = new HashMap<>();
        payload.put("checkoutRequestId", checkoutRequestId);
        payload.put("status", status);
        payload.put("resultCode", resultCode);
        payload.put("resultDesc", resultDesc != null ? resultDesc : "");
        payload.put("retryable", retryable);
        payload.put("displayMessage", displayMessage != null ? displayMessage : "");
        payload.put("mpesaReceiptNumber", mpesaReceiptNumber != null ? mpesaReceiptNumber : "");
        payload.put("phoneNumber", phoneNumber != null ? phoneNumber : "");
        return payload;
    }

    private void forwardToNextJs(Map<String, Object> payload) {
        try {
            String nextJsUrl = NEXTJS_URL.replaceAll("/+$", "") + "/api/shops/payments/update-order";
            String jsonBody = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(nextJsUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Internal-Secret", internalSecret != null ? internalSecret : "")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.error("Next.js error: status={}, body={}", response.statusCode(), response.body());
            } else {
                logger.info("Successfully forwarded to Next.js for checkoutRequestId={}", payload.get("checkoutRequestId"));
            }

        } catch (Exception e) {
            logger.error("Failed to forward to Next.js: {}", e.getMessage());
        }
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("ResultCode", 1);
        errorResponse.put("ResultDesc", message);
        return errorResponse;
    }
}