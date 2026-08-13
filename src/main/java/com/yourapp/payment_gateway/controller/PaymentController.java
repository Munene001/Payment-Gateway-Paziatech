package com.yourapp.payment_gateway.controller;

import com.yourapp.payment_gateway.service.DarajaService;
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

    @Value("${payment.callback.base.url}")
    private String callbackBaseUrl;

    public PaymentController(DarajaService darajaService) {
        this.darajaService = darajaService;
    }

    @PostMapping("/stk-push")
    public Map<String, Object> stkPush(@RequestBody Map<String, String> request) {
        logger.info("STK Push request received");

        String type = request.get("type");
        String shortcode = request.get("shortcode");
        String consumerKey = request.get("consumerKey");
        String consumerSecret = request.get("consumerSecret");
        String passkey = request.get("passkey");
        String phoneNumber = request.get("phoneNumber");
        String orderReference = request.get("orderReference");

        logger.info("Order: {}, Phone: {}, Amount: {}", orderReference, phoneNumber, request.get("amount"));

        Double amount = 0.0;
        String amountStr = request.get("amount");
        if (amountStr != null && !amountStr.isEmpty()) {
            try {
                amount = Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                logger.error("Invalid amount format: {}", amountStr);
                amount = 0.0;
            }
        }

        try {
            String token = darajaService.getAccessToken(consumerKey, consumerSecret);

            String callbackUrl = callbackBaseUrl + "/api/payments/callback";

            String checkoutRequestId = darajaService.sendStkPush(
                token,
                shortcode,
                passkey,
                amount,
                phoneNumber,
                orderReference,
                callbackUrl
            );

            logger.info("STK Push successful for order {}: {}", orderReference, checkoutRequestId);

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

    @GetMapping("/test-token")
    public String testToken(
            @RequestParam String consumerKey,
            @RequestParam String consumerSecret) {
        try {
            String token = darajaService.getAccessToken(consumerKey, consumerSecret);
            return "Token obtained successfully";
        } catch (Exception e) {
            logger.error("Token test failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}