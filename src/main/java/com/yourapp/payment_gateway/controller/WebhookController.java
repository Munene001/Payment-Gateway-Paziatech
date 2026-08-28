package com.yourapp.payment_gateway.controller;

import com.yourapp.payment_gateway.service.KopokopoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);
    private final KopokopoService kopokopoService;

    @Value("${nextjs.internal.secret}")
    private String internalSecret;

    @Value("${payment.callback.base.url}")
    private String callbackBaseUrl;

    @Value("${payment.callback.secret}")
    private String callbackSecret;

    public WebhookController(KopokopoService kopokopoService) {
        this.kopokopoService = kopokopoService;
    }

    @PostMapping("/register")
    public Map<String, Object> registerWebhook(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestBody Map<String, String> request) {

        // Security: Validate internal API key
        if (internalSecret == null || !internalSecret.equals(apiKey)) {
            logger.error("Unauthorized webhook registration attempt");
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Unauthorized: Invalid internal API key");
            return errorResponse;
        }

        String clientId = request.get("clientId");
        String clientSecret = request.get("clientSecret");
        String tillNumber = request.get("tillNumber");

        // Validate required fields (webhookUrl is built by Spring Boot)
        if (clientId == null || clientSecret == null || tillNumber == null) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Missing required fields: clientId, clientSecret, tillNumber");
            return errorResponse;
        }

        // Build webhook URL using Spring Boot's own properties
        // FIX: Removed ?secret= parameter for Kopokopo production
        String cleanBaseUrl = callbackBaseUrl != null ? callbackBaseUrl.replaceAll("/+$", "") : "";
        String webhookUrl = cleanBaseUrl + "/api/payments/kopokopo-callback";

        try {
            boolean registered = kopokopoService.ensureWebhookSubscribed(
                clientId,
                clientSecret,
                tillNumber,
                webhookUrl
            );

            Map<String, Object> response = new HashMap<>();
            if (registered) {
                response.put("success", true);
                response.put("message", "Webhook registered successfully");
            } else {
                logger.error("Webhook registration failed for till: {}", tillNumber);
                response.put("success", false);
                response.put("error", "Webhook registration failed");
            }
            return response;

        } catch (Exception e) {
            logger.error("Webhook registration error for till {}: {}", tillNumber, e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Webhook registration error: " + e.getMessage());
            return errorResponse;
        }
    }
}