package com.yourapp.payment_gateway.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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

    @SuppressWarnings("unchecked")
    @PostMapping("/callback")
    public Map<String, Object> handleCallback(@RequestBody Map<String, Object> callback) {
        logger.info("Received callback from Safaricom");
        
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
        Integer resultCodeObj = (Integer) stkCallback.get("ResultCode");
        int resultCode = resultCodeObj != null ? resultCodeObj : -1;
        String resultDesc = (String) stkCallback.get("ResultDesc");
        
        logger.info("Callback: CheckoutRequestID={}, ResultCode={}", checkoutRequestId, resultCode);
        
        String status;
        boolean retryable;
        String displayMessage;
        
        switch (resultCode) {
            case 0:
                status = "COMPLETED";
                retryable = false;
                displayMessage = "Payment successful!";
                logger.info("Payment successful for {}", checkoutRequestId);
                break;
            case 1032:
                status = "CANCELLED";
                retryable = true;
                displayMessage = "Payment cancelled by user";
                logger.warn("Payment cancelled by user: {}", checkoutRequestId);
                break;
            case 1:
                status = "INSUFFICIENT_FUNDS";
                retryable = true;
                displayMessage = "Insufficient funds";
                logger.warn("Insufficient funds: {}", checkoutRequestId);
                break;
            case 2001:
                status = "WRONG_PIN";
                retryable = true;
                displayMessage = "Wrong PIN entered";
                logger.warn("Wrong PIN: {}", checkoutRequestId);
                break;
            case 1037:
                status = "TIMEOUT";
                retryable = true;
                displayMessage = "Transaction timeout";
                logger.warn("Transaction timeout: {}", checkoutRequestId);
                break;
            default:
                status = "FAILED";
                retryable = true;
                displayMessage = "Payment failed";
                logger.error("Payment failed with code {}: {}", resultCode, checkoutRequestId);
                break;
        }
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("checkoutRequestId", checkoutRequestId);
        payload.put("status", status);
        payload.put("resultCode", resultCode);
        payload.put("resultDesc", resultDesc);
        payload.put("retryable", retryable);
        payload.put("displayMessage", displayMessage);

        CompletableFuture.runAsync(() -> forwardToNextJs(payload));

        Map<String, Object> response = new HashMap<>();
        response.put("ResultCode", 0);
        response.put("ResultDesc", "Success");
        logger.info("Callback acknowledged for {}", checkoutRequestId);
        return response;
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
                logger.info("Successfully forwarded to Next.js");
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