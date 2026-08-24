package com.yourapp.payment_gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class KopokopoService {

    private static final Logger logger = LoggerFactory.getLogger(KopokopoService.class);
    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final MediaType JSON_MEDIA_TYPE = 
        MediaType.parse("application/json; charset=utf-8");

    @Value("${kopokopo.base.url}")
    private String KOPOKOPO_BASE_URL;

    // ================================================
    // TOKEN CACHING WITH PER-CLIENT LOCKING
    // ================================================
    private final ConcurrentHashMap<String, CachedToken> tokenMap = new ConcurrentHashMap<>();

    private static class CachedToken {
        final String token;
        final long expiryTime;

        CachedToken(String token, long expiryTime) {
            this.token = token;
            this.expiryTime = expiryTime;
        }

        boolean isValid() {
            return System.currentTimeMillis() < expiryTime;
        }
    }

    public String getAccessToken(String clientId, String clientSecret) throws Exception {
        CachedToken existing = tokenMap.get(clientId);
        if (existing != null && existing.isValid()) {
            logger.debug("✅ Using cached Kopo Kopo token for clientId: {}", clientId);
            return existing.token;
        }

        synchronized (clientId.intern()) {
            existing = tokenMap.get(clientId);
            if (existing != null && existing.isValid()) {
                return existing.token;
            }

            logger.info("🔄 Getting new Kopo Kopo token for clientId: {}", clientId);
            String newToken = requestNewToken(clientId, clientSecret);

            tokenMap.put(clientId, new CachedToken(newToken, System.currentTimeMillis() + 3300000));
            logger.info("✅ Kopo Kopo token cached for clientId: {}", clientId);
            return newToken;
        }
    }

    private String requestNewToken(String clientId, String clientSecret) throws Exception {
        String url = KOPOKOPO_BASE_URL.replaceAll("/+$", "") + "/oauth/token";

        RequestBody formBody = new FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId.trim())
                .add("client_secret", clientSecret.trim())
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(formBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                logger.error("Failed to get Kopo Kopo token: status={}, body={}", 
                            response.code(), responseBody);
                throw new Exception("Failed to get Kopo Kopo token: " + responseBody);
            }

            String token = extractAccessToken(responseBody);
            logger.info("✅ Kopo Kopo token obtained successfully for clientId: {}", clientId);
            return token;
        } catch (Exception e) {
            logger.error("Error getting Kopo Kopo token: {}", e.getMessage());
            throw e;
        }
    }

    private String extractAccessToken(String json) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode tokenNode = root.get("access_token");
            if (tokenNode == null || tokenNode.isNull()) {
                throw new RuntimeException("access_token field not found in response");
            }
            return tokenNode.asText();
        } catch (Exception e) {
            logger.error("Failed to parse access_token from response: {}", json, e);
            throw new RuntimeException("Invalid token response structure", e);
        }
    }

    // ================================================
    // STK PUSH - UPDATED WITH V2 API AND LOCATION HEADER FIX
    // ================================================

    public String sendStkPush(
            String accessToken,
            String tillNumber,
            double amount,
            String phoneNumber,
            String orderReference,
            String callbackUrl) throws Exception {

        logger.info("📤 Sending Kopo Kopo STK Push for order: {}, amount: {}", 
                    orderReference, amount);

        // Sanitize inputs
        String cleanTill = tillNumber != null ? tillNumber.trim() : "";
        String cleanPhone = phoneNumber != null ? phoneNumber.trim() : "";
        String cleanOrderRef = orderReference != null ? orderReference.trim() : "";
        String cleanCallback = callbackUrl != null ? callbackUrl.trim() : "";

        // Format phone number to E.164 (+254...)
        String formattedPhone;
        if (cleanPhone.startsWith("+")) {
            formattedPhone = cleanPhone;
        } else if (cleanPhone.startsWith("0")) {
            formattedPhone = "+254" + cleanPhone.substring(1);
        } else if (cleanPhone.startsWith("254")) {
            formattedPhone = "+" + cleanPhone;
        } else {
            formattedPhone = "+254" + cleanPhone;
        }

        long roundedAmount = Math.max(1, Math.round(amount));

        // ============================================================
        // BUILD PAYLOAD - CORRECT V2 API FORMAT
        // ============================================================
        Map<String, Object> payload = new HashMap<>();
        
        // Payment channel - MUST be "M-PESA STK Push" with space!
        payload.put("payment_channel", "M-PESA STK Push");
        payload.put("till_number", cleanTill);

        // Subscriber
        Map<String, Object> subscriber = new HashMap<>();
        subscriber.put("first_name", "Jane");
        subscriber.put("last_name", "Doe");
        subscriber.put("phone_number", formattedPhone);
        payload.put("subscriber", subscriber);

        // Amount
        Map<String, Object> amountObj = new HashMap<>();
        amountObj.put("currency", "KES");
        amountObj.put("value", roundedAmount);
        payload.put("amount", amountObj);

        // Metadata
        Map<String, String> metadata = new HashMap<>();
        metadata.put("order_reference", cleanOrderRef);
        payload.put("metadata", metadata);

        // Callback URL MUST be wrapped in _links object!
        Map<String, Object> links = new HashMap<>();
        links.put("callback_url", cleanCallback);
        payload.put("_links", links);

        String jsonBody = objectMapper.writeValueAsString(payload);

        // ============================================================
        // CORRECT ENDPOINT - V2 API
        // ============================================================
        String url = KOPOKOPO_BASE_URL.replaceAll("/+$", "") + "/api/v2/incoming_payments";

        // ============================================================
        // DETAILED LOGGING FOR DEBUGGING
        // ============================================================
        logger.info("========================================");
        logger.info("📤 STK PUSH REQUEST DETAILS");
        logger.info("========================================");
        logger.info("📍 URL: {}", url);
        logger.info("📍 Till Number: {}", cleanTill);
        logger.info("📍 Phone Number: {}", formattedPhone);
        logger.info("📍 Amount: {}", roundedAmount);
        logger.info("📍 Order Reference: {}", cleanOrderRef);
        logger.info("📍 Callback URL: {}", cleanCallback);
        logger.info("📍 Authorization: Bearer {}", accessToken.substring(0, Math.min(accessToken.length(), 20)) + "...");
        logger.info("📍 Full Payload: {}", jsonBody);
        logger.info("========================================");

        Request request = new Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + accessToken)
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            logger.info("========================================");
            logger.info("📥 STK PUSH RESPONSE");
            logger.info("========================================");
            logger.info("📍 Status: {}", response.code());
            logger.info("📍 Response Body: {}", responseBody);
            logger.info("========================================");

            if (!response.isSuccessful()) {
                logger.error("❌ Kopo Kopo STK Push failed: status={}, body={}", 
                            response.code(), responseBody);
                throw new Exception("Kopo Kopo STK Push failed: " + responseBody);
            }

            // ============================================================
            // FIX: Extract resource ID from Location header (V2 API)
            // ============================================================
            String location = response.header("Location");
            String resourceId = null;
            
            if (location != null && !location.isEmpty()) {
                resourceId = location.substring(location.lastIndexOf("/") + 1);
                logger.info("✅ Resource ID extracted from Location header: {}", resourceId);
            } else {
                // Fallback: try to parse from response body
                try {
                    resourceId = extractResourceId(responseBody);
                    logger.info("✅ Resource ID extracted from response body: {}", resourceId);
                } catch (Exception e) {
                    logger.error("Failed to extract resource ID from Location header or response body");
                    throw new Exception("Could not extract resource ID from response");
                }
            }

            logger.info("✅ Kopo Kopo STK Push sent successfully: {}", resourceId);
            return resourceId;
        } catch (Exception e) {
            logger.error("❌ Error sending Kopo Kopo STK Push: {}", e.getMessage());
            throw e;
        }
    }

    private String extractResourceId(String json) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode dataNode = root.path("data");
            JsonNode idNode = dataNode.path("id");
            
            if (idNode == null || idNode.isNull() || idNode.asText().isEmpty()) {
                throw new RuntimeException("Resource ID not found in response");
            }
            return idNode.asText();
        } catch (Exception e) {
            logger.error("Failed to parse resource ID from response: {}", json, e);
            throw new RuntimeException("Invalid incoming_payments response structure", e);
        }
    }

    // ================================================
    // WEBHOOK REGISTRATION
    // ================================================

    public boolean ensureWebhookSubscribed(
            String clientId,
            String clientSecret,
            String tillNumber,
            String webhookUrl) throws Exception {

        logger.info("🔍 Ensuring webhook subscription for till: {}", tillNumber);
        logger.info("📋 Webhook URL: {}", webhookUrl);

        String accessToken = getAccessToken(clientId, clientSecret);

        List<Map<String, Object>> existingSubscriptions = getExistingSubscriptions(accessToken, tillNumber);

        boolean alreadySubscribed = existingSubscriptions.stream()
            .anyMatch(sub -> {
                String scopeRef = (String) sub.get("scope_reference");
                String url = (String) sub.get("url");
                return tillNumber.equals(scopeRef) && webhookUrl.equals(url);
            });

        if (alreadySubscribed) {
            logger.info("✅ Webhook already exists for till: {}", tillNumber);
            return true;
        }

        logger.info("📝 Registering new webhook for till: {}", tillNumber);
        return registerWebhook(accessToken, tillNumber, webhookUrl);
    }

    private List<Map<String, Object>> getExistingSubscriptions(String accessToken, String tillNumber) throws Exception {
        String url = String.format(
            "%s/api/v2/webhook_subscriptions?event_type=%s&scope_reference=%s",
            KOPOKOPO_BASE_URL.replaceAll("/+$", ""),
            "buygoods_transaction_received",
            tillNumber
        );

        logger.debug("🔍 Fetching existing subscriptions from: {}", url);

        Request request = new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .get()
            .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            logger.info("📥 Existing subscriptions response: status={}", response.code());
            logger.debug("📥 Response body: {}", responseBody);

            if (!response.isSuccessful()) {
                logger.warn("⚠️ Failed to fetch existing subscriptions: status={}", response.code());
                return new ArrayList<>();
            }

            try {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode dataNode = root.path("data");
                List<Map<String, Object>> subscriptions = new ArrayList<>();

                if (dataNode.isArray()) {
                    for (JsonNode item : dataNode) {
                        Map<String, Object> sub = new HashMap<>();
                        sub.put("id", item.path("id").asText(null));
                        sub.put("scope_reference", item.path("scope_reference").asText(null));
                        sub.put("url", item.path("url").asText(null));
                        sub.put("event_type", item.path("event_type").asText(null));
                        sub.put("scope", item.path("scope").asText(null));
                        subscriptions.add(sub);
                    }
                }

                logger.info("📋 Found {} existing subscriptions for till: {}", subscriptions.size(), tillNumber);
                return subscriptions;

            } catch (Exception e) {
                logger.warn("⚠️ Failed to parse subscriptions response: {}", e.getMessage());
                return new ArrayList<>();
            }
        }
    }

    private boolean registerWebhook(String accessToken, String tillNumber, String webhookUrl) throws Exception {
        String url = KOPOKOPO_BASE_URL.replaceAll("/+$", "") + "/api/v2/webhook_subscriptions";

        Map<String, Object> payload = new HashMap<>();
        payload.put("event_type", "buygoods_transaction_received");
        payload.put("url", webhookUrl);
        payload.put("scope", "till");
        payload.put("scope_reference", tillNumber);

        String jsonBody = objectMapper.writeValueAsString(payload);

        logger.info("========================================");
        logger.info("📤 WEBHOOK REGISTRATION REQUEST");
        logger.info("========================================");
        logger.info("📍 URL: {}", url);
        logger.info("📍 Till Number: {}", tillNumber);
        logger.info("📍 Webhook URL: {}", webhookUrl);
        logger.info("📍 Scope: {}", payload.get("scope"));
        logger.info("📍 Scope Reference: {}", payload.get("scope_reference"));
        logger.info("📍 Event Type: {}", payload.get("event_type"));
        logger.info("📍 Payload: {}", jsonBody);
        logger.info("========================================");

        Request request = new Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + accessToken)
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            logger.info("========================================");
            logger.info("📥 WEBHOOK REGISTRATION RESPONSE");
            logger.info("========================================");
            logger.info("📍 Status: {}", response.code());
            logger.info("📍 Response Body: {}", responseBody);
            logger.info("========================================");

            if (response.isSuccessful() || response.code() == 201) {
                logger.info("✅ Webhook registered successfully for till: {}", tillNumber);
                return true;
            }

            if (response.code() == 409 || response.code() == 422) {
                logger.info("ℹ️ Webhook already exists for till: {} (status: {})", tillNumber, response.code());
                return true;
            }

            logger.error("❌ Webhook registration failed: status={}, body={}", response.code(), responseBody);
            return false;
        }
    }

    public void clearTokenCache(String clientId) {
        if (clientId != null && !clientId.isEmpty()) {
            tokenMap.remove(clientId);
            logger.info("🗑️ Kopo Kopo token cache cleared for clientId: {}", clientId);
        } else {
            tokenMap.clear();
            logger.info("🗑️ All Kopo Kopo tokens cleared");
        }
    }
}