package com.yourapp.payment_gateway.controller;

import com.yourapp.payment_gateway.service.DarajaService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final DarajaService darajaService;

    // Constructor injection
    public PaymentController(DarajaService darajaService) {
        this.darajaService = darajaService;
    }

    @PostMapping("/stk-push")
    public void stkPush(@RequestBody Map<String, String> request) {

        // Extract fields
        String type = request.get("type");
        String shortcode = request.get("shortcode");
        String consumerKey = request.get("consumerKey");
        String consumerSecret = request.get("consumerSecret");
        String passkey = request.get("passkey");
        String phoneNumber = request.get("phoneNumber");
        String orderReference = request.get("orderReference");
        String businessNumber = request.get("businessNumber");
        String tillNumber = request.get("tillNumber");
        String accountNumber = request.get("accountNumber");

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

        // Log everything
        System.out.println("📱 STK Push Request:");
        System.out.println("   Type: " + type);
        System.out.println("   Shortcode: " + shortcode);
        System.out.println("   Amount: " + amount);
        System.out.println("   Phone: " + phoneNumber);
        System.out.println("   Order: " + orderReference);
        System.out.println("   Consumer Key: " + consumerKey);
        System.out.println("   Consumer Secret: " + consumerSecret);
        System.out.println("   Passkey: " + passkey);
        System.out.println("   Business Number: " + businessNumber);
        System.out.println("   Till Number: " + tillNumber);
        System.out.println("   Account Number: " + accountNumber);
    }

    // Test endpoint for getting token
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