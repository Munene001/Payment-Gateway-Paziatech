package com.yourapp.payment_gateway.controller;

import com.yourapp.payment_gateway.service.DarajaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final DarajaService darajaService;

    @Value("${payment.callback.base.url}")
    private String callbackBaseUrl;

    public PaymentController(DarajaService darajaService) {
        this.darajaService = darajaService;
    }

    @PostMapping("/stk-push")
    public Map<String, Object> stkPush(@RequestBody Map<String, String> request) {

        // Extract fields
        String type = request.get("type");
        String shortcode = request.get("shortcode");
        String consumerKey = request.get("consumerKey");
        String consumerSecret = request.get("consumerSecret");
        String passkey = request.get("passkey");
        String phoneNumber = request.get("phoneNumber");
        String orderReference = request.get("orderReference");

        // Parse amount
        Double amount = 0.0;
        String amountStr = request.get("amount");
        if (amountStr != null && !amountStr.isEmpty()) {
            try {
                amount = Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                amount = 0.0;
            }
        }

        // Log what we received
        System.out.println("📱 STK Push Request:");
        System.out.println("   Type: " + type);
        System.out.println("   Shortcode: " + shortcode);
        System.out.println("   Amount: " + amount);
        System.out.println("   Phone: " + phoneNumber);
        System.out.println("   Order: " + orderReference);
        System.out.println("   Consumer Key: " + consumerKey);
        System.out.println("   Consumer Secret: " + consumerSecret);
        System.out.println("   Passkey: " + passkey);

        try {
            // 1. Get token from Daraja
            String token = darajaService.getAccessToken(consumerKey, consumerSecret);
            System.out.println("✅ Got token: " + token);

            // 2. Build callback URL from base + path
            String callbackUrl = callbackBaseUrl + "/api/payments/callback";

            // 3. Send STK Push
            String checkoutRequestId = darajaService.sendStkPush(
                token,
                shortcode,
                passkey,
                amount,
                phoneNumber,
                orderReference,
                callbackUrl
            );

            System.out.println("✅ STK Push sent. CheckoutRequestID: " + checkoutRequestId);

            // 4. Return success response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("CheckoutRequestID", checkoutRequestId);
            response.put("ResponseCode", "0");
            response.put("ResponseDescription", "Success. Request accepted for processing");

            return response;

        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return errorResponse;
        }
    }

    @GetMapping("/test-token")
    public String testToken(
            @RequestParam String consumerKey,
            @RequestParam String consumerSecret) {
        try {
            String token = darajaService.getAccessToken(consumerKey, consumerSecret);
            return "✅ Token: " + token;
        } catch (Exception e) {
            return "❌ Error: " + e.getMessage();
        }
    }
}