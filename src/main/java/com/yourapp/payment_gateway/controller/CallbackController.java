package com.yourapp.payment_gateway.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class CallbackController {

    private static final String NEXTJS_URL = "http://localhost:3000";

    @PostMapping("/callback")
    public Map<String, Object> handleCallback(@RequestBody Map<String, Object> callback) {
        
        System.out.println("📞 Daraja Callback received:");
        System.out.println(callback);
        
        // Extract callback data
        Map<String, Object> body = (Map<String, Object>) callback.get("Body");
        Map<String, Object> stkCallback = (Map<String, Object>) body.get("stkCallback");
        
        String checkoutRequestId = (String) stkCallback.get("CheckoutRequestID");
        int resultCode = (int) stkCallback.get("ResultCode");
        String resultDesc = (String) stkCallback.get("ResultDesc");
        
        System.out.println("📞 CheckoutRequestID: " + checkoutRequestId);
        System.out.println("📞 ResultCode: " + resultCode);
        System.out.println("📞 ResultDesc: " + resultDesc);
        
        // Interpret ResultCode
        String status;
        boolean retryable;
        String displayMessage;
        
        switch (resultCode) {
            case 0:
                status = "COMPLETED";
                retryable = false;
                displayMessage = "Payment successful!";
                break;
            case 1032:
                status = "CANCELLED";
                retryable = true;
                displayMessage = "You cancelled the payment. Please try again.";
                break;
            case 1:
                status = "INSUFFICIENT_FUNDS";
                retryable = true;
                displayMessage = "Insufficient funds. Please top up and try again.";
                break;
            case 2001:
                status = "WRONG_PIN";
                retryable = true;
                displayMessage = "Wrong PIN entered. Please try again.";
                break;
            case 1037:
                status = "TIMEOUT";
                retryable = true;
                displayMessage = "Transaction timed out. Please try again.";
                break;
            default:
                status = "FAILED";
                retryable = true;
                displayMessage = "Payment failed. Please try again.";
                break;
        }
        
        System.out.println("📊 Interpreted: " + status + " | Retryable: " + retryable);
        
        // Send to Next.js (hardcoded localhost:3000)
        try {
            String nextJsUrl = NEXTJS_URL + "/api/shops/payments/update-order";
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("checkoutRequestId", checkoutRequestId);
            payload.put("status", status);
            payload.put("resultCode", resultCode);
            payload.put("resultDesc", resultDesc);
            payload.put("retryable", retryable);
            payload.put("displayMessage", displayMessage);
            
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(nextJsUrl))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(10))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload)
                ))
                .build();
            
            java.net.http.HttpResponse<String> response = client.send(request, 
                java.net.http.HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                System.out.println("✅ Next.js notified successfully");
            } else {
                System.err.println("⚠️ Next.js returned: " + response.statusCode());
            }
            
        } catch (Exception e) {
            System.err.println("❌ Failed to call Next.js: " + e.getMessage());
        }
        
        // Always return success to Daraja
        Map<String, Object> response = new HashMap<>();
        response.put("ResultCode", 0);
        response.put("ResultDesc", "Success");
        return response;
    }
}