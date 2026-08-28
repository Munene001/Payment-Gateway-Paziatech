package com.yourapp.payment_gateway.controller;

import com.yourapp.payment_gateway.service.DarajaService;
import com.yourapp.payment_gateway.service.KopokopoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);
    
    private final DarajaService darajaService;
    private final KopokopoService kopokopoService;

    @Value("${payment.callback.base.url}")
    private String callbackBaseUrl;

    @Value("${payment.callback.secret}")
    private String callbackSecret;

    public PaymentController(DarajaService darajaService, KopokopoService kopokopoService) {
        this.darajaService = darajaService;
        this.kopokopoService = kopokopoService;
    }

    // ================================================
    // SAFARICOM STK PUSH (EXISTING - NO CHANGES)
    // ================================================
    @PostMapping("/stk-push")
    public Map<String, Object> stkPush(@RequestBody Map<String, String> request) {
        String type = request.get("type");
        String transactionType = (type != null && !type.isBlank()) ? type.trim() : "CustomerPayBillOnline";
        
        String shortcode = request.get("shortcode") != null ? request.get("shortcode").trim() : "";
        String consumerKey = request.get("consumerKey") != null ? request.get("consumerKey").trim() : "";
        String consumerSecret = request.get("consumerSecret") != null ? request.get("consumerSecret").trim() : "";
        String passkey = request.get("passkey") != null ? request.get("passkey").trim() : "";
        String orderReference = request.get("orderReference") != null ? request.get("orderReference").trim() : "";
        
        String phoneNumber = request.get("phoneNumber") != null ? request.get("phoneNumber").trim().replace("+", "") : "";

        Double amount = 0.0;
        String amountStr = request.get("amount");
        if (amountStr != null && !amountStr.isEmpty()) {
            try {
                amount = Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                logger.error("Invalid amount format: {}", amountStr);
            }
        }

        if (amount <= 0) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Invalid or missing payment amount. Amount must be greater than 0.");
            return errorResponse;
        }

        try {
            String token = darajaService.getAccessToken(consumerKey, consumerSecret);

            String cleanBaseUrl = callbackBaseUrl.endsWith("/") 
                    ? callbackBaseUrl.substring(0, callbackBaseUrl.length() - 1) 
                    : callbackBaseUrl;

            String cleanSecret = callbackSecret != null ? callbackSecret.trim() : "";
            String callbackUrl = cleanBaseUrl + "/api/payments/callback?secret=" + cleanSecret;

            String checkoutRequestId = darajaService.sendStkPush(
                token,
                transactionType,
                shortcode,
                passkey,
                amount,
                phoneNumber,
                orderReference,
                callbackUrl
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("CheckoutRequestID", checkoutRequestId);
            response.put("ResponseCode", "0");
            response.put("ResponseDescription", "Success. Request accepted for processing");

            return response;

        } catch (Exception e) {
            logger.error("STK Push error for order {}: {}", orderReference, e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return errorResponse;
        }
    }

    // ================================================
    // KOPOKOPO STK PUSH (UPDATED - REMOVED SECRET FROM URL)
    // ================================================
    @PostMapping("/kopokopo-stk-push")
    public Map<String, Object> kopokopoStkPush(@RequestBody Map<String, String> request) {
        String clientId = request.get("clientId") != null ? request.get("clientId").trim() : "";
        String clientSecret = request.get("clientSecret") != null ? request.get("clientSecret").trim() : "";
        String tillNumber = request.get("tillNumber") != null ? request.get("tillNumber").trim() : "";
        String orderReference = request.get("orderReference") != null ? request.get("orderReference").trim() : "";
        String phoneNumber = request.get("phoneNumber") != null ? request.get("phoneNumber").trim().replace("+", "") : "";

        Double amount = 0.0;
        String amountStr = request.get("amount");
        if (amountStr != null && !amountStr.isEmpty()) {
            try {
                amount = Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                logger.error("Invalid amount format: {}", amountStr);
            }
        }

        if (amount <= 0) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Invalid or missing payment amount. Amount must be greater than 0.");
            return errorResponse;
        }

        if (clientId.isEmpty() || clientSecret.isEmpty() || tillNumber.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Missing required fields: clientId, clientSecret, or tillNumber");
            return errorResponse;
        }

        try {
            // Get access token (with caching!)
            String accessToken = kopokopoService.getAccessToken(clientId, clientSecret);

            String cleanBaseUrl = callbackBaseUrl.endsWith("/") 
                    ? callbackBaseUrl.substring(0, callbackBaseUrl.length() - 1) 
                    : callbackBaseUrl;

            // FIX: Removed ?secret= parameter for Kopokopo production
            String callbackUrl = cleanBaseUrl + "/api/payments/kopokopo-callback";

            String resourceId = kopokopoService.sendStkPush(
                accessToken,
                tillNumber,
                amount,
                phoneNumber,
                orderReference,
                callbackUrl
            );

            // Return in unified format (matches Safaricom format for Next.js)
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("CheckoutRequestID", resourceId);
            response.put("MerchantRequestID", "");
            response.put("ResponseCode", "0");
            response.put("ResponseDescription", "Success. Request accepted for processing");

            return response;

        } catch (Exception e) {
            logger.error("Kopo Kopo STK Push error for order {}: {}", orderReference, e.getMessage());
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
            String token = darajaService.getAccessToken(consumerKey.trim(), consumerSecret.trim());
            return "Token obtained successfully";
        } catch (Exception e) {
            logger.error("Token test failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @GetMapping("/test-kopokopo-token")
    public String testKopokopoToken(
            @RequestParam String clientId,
            @RequestParam String clientSecret) {
        try {
            String token = kopokopoService.getAccessToken(clientId.trim(), clientSecret.trim());
            return "Kopo Kopo token obtained successfully";
        } catch (Exception e) {
            logger.error("Kopo Kopo token test failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}