package com.yourapp.payment_gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
public class DarajaService {

    private static final Logger logger = LoggerFactory.getLogger(DarajaService.class);
    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Static Media Types (evaluated once at class load)
    private static final MediaType JSON_MEDIA_TYPE = 
        MediaType.parse("application/json; charset=utf-8");
    private static final MediaType FORM_MEDIA_TYPE = 
        MediaType.parse("application/x-www-form-urlencoded");

    @Value("${daraja.base.url}")
    private String BASE_URL;

    // ================================================
    // TOKEN CACHING WITH THREAD SAFETY
    // ================================================
    private final Object tokenLock = new Object();
    private volatile String cachedAccessToken;
    private volatile long tokenExpiryTime;

    /**
     * Gets a Safaricom OAuth access token with thread-safe caching.
     * Tokens are cached for 55 minutes to avoid rate limits.
     */
    public String getAccessToken(String consumerKey, String consumerSecret) throws Exception {
        // 1. CHECK CACHE FIRST (fast path, no lock)
        if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiryTime) {
            logger.debug("✅ Using cached Safaricom token (expires in {} ms)", 
                        tokenExpiryTime - System.currentTimeMillis());
            return cachedAccessToken;
        }

        // 2. Cache MISS or EXPIRED → Get new token (with lock)
        synchronized (tokenLock) {
            // Double-check inside lock to prevent race conditions
            if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiryTime) {
                logger.debug("✅ Using cached Safaricom token (double-check)");
                return cachedAccessToken;
            }

            logger.info("🔄 Getting new Safaricom token");
            String newToken = requestNewToken(consumerKey, consumerSecret);

            // Store in cache with expiry (55 minutes to be safe)
            cachedAccessToken = newToken;
            tokenExpiryTime = System.currentTimeMillis() + 3300000; // 55 minutes

            logger.info("✅ Safaricom token cached for 55 minutes");
            return newToken;
        }
    }

    /**
     * Actually requests a new token from Safaricom API.
     */
    private String requestNewToken(String consumerKey, String consumerSecret) throws Exception {
        String credentials = consumerKey.trim() + ":" + consumerSecret.trim();
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());

        // Fix: Guard against trailing slash
        String url = BASE_URL.replaceAll("/+$", "") + "/oauth/v1/generate?grant_type=client_credentials";

        Request request = new Request.Builder()
            .url(url)
            .addHeader("Authorization", "Basic " + encodedCredentials)
            .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                logger.error("Failed to get token: status={}, body={}", response.code(), responseBody);
                throw new Exception("Failed to get token: " + responseBody);
            }

            String token = extractAccessToken(responseBody);
            logger.info("✅ Token obtained successfully");
            return token;
        } catch (Exception e) {
            logger.error("Error getting token: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Extracts access_token from Safaricom OAuth response using Jackson.
     * Safe parsing - handles whitespace, formatting, and key order changes.
     */
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
    // STK PUSH
    // ================================================

    public String sendStkPush(
            String token,
            String transactionType,
            String shortcode,
            String passkey,
            double amount,
            String phoneNumber,
            String orderReference,
            String callbackUrl) throws Exception {

        logger.info("Sending STK Push for order: {}, amount: {}", orderReference, amount);

        // Sanitize inputs
        String cleanShortcode = shortcode != null ? shortcode.trim() : "";
        String cleanPasskey = passkey != null ? passkey.trim() : "";
        String cleanPhone = phoneNumber != null ? phoneNumber.trim().replace("+", "") : "";
        String cleanOrderRef = orderReference != null ? orderReference.trim() : "";
        String cleanCallback = callbackUrl != null ? callbackUrl.trim() : "";

        // Ensure phone number has country code
        if (cleanPhone.startsWith("0")) {
            cleanPhone = "254" + cleanPhone.substring(1);
        } else if (!cleanPhone.startsWith("254")) {
            cleanPhone = "254" + cleanPhone;
        }

        // Determine transaction type
        String rawType = transactionType != null ? transactionType.trim().toLowerCase() : "";
        String finalTransactionType;
        String partyB;

        if ("till".equals(rawType) || "buygoods".equals(rawType) || "customerbuygoodsonline".equals(rawType)) {
            finalTransactionType = "CustomerBuyGoodsOnline";
            // For BuyGoods (Till), PartyB is the Till number
            partyB = cleanShortcode;
        } else {
            // Default to PayBill
            finalTransactionType = "CustomerPayBillOnline";
            // For PayBill, PartyB is the Business Shortcode
            partyB = cleanShortcode;
        }

        // Set timestamp explicitly to Nairobi time (UTC+3 / EAT)
        String timestamp = getCurrentTimestamp();

        // Compute Base64 Password
        String passwordString = cleanShortcode + cleanPasskey + timestamp;
        String password = Base64.getEncoder().encodeToString(passwordString.getBytes());

        // Convert amount to whole integer (M-Pesa standard)
        long roundedAmount = Math.max(1, Math.round(amount));

        // ============================================================
        // BUILD JSON PAYLOAD WITH OBJECT MAPPER (SAFE!)
        // ============================================================
        Map<String, Object> payload = new HashMap<>();
        payload.put("BusinessShortCode", cleanShortcode);
        payload.put("Password", password);
        payload.put("Timestamp", timestamp);
        payload.put("TransactionType", finalTransactionType);
        payload.put("Amount", roundedAmount);
        payload.put("PartyA", cleanPhone);
        payload.put("PartyB", partyB);
        payload.put("PhoneNumber", cleanPhone);
        payload.put("CallBackURL", cleanCallback);
        payload.put("AccountReference", cleanOrderRef);
        payload.put("TransactionDesc", "Payment for order " + cleanOrderRef);

        String jsonBody = objectMapper.writeValueAsString(payload);

        logger.debug("Outgoing Payload JSON: {}", jsonBody);

        String url = BASE_URL.replaceAll("/+$", "") + "/mpesa/stkpush/v1/processrequest";

        Request request = new Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer " + token)
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            logger.info("Daraja raw response: {}", responseBody);

            if (!response.isSuccessful()) {
                logger.error("STK Push failed: status={}, body={}", response.code(), responseBody);
                throw new Exception("STK Push failed: " + responseBody);
            }

            String checkoutRequestId = extractCheckoutRequestId(responseBody);
            logger.info("STK Push sent successfully: {}", checkoutRequestId);
            return checkoutRequestId;
        } catch (Exception e) {
            logger.error("Error sending STK Push: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Extracts CheckoutRequestID from Safaricom response using Jackson.
     * Safe parsing - handles whitespace, formatting, and key order changes.
     */
    private String extractCheckoutRequestId(String json) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode idNode = root.get("CheckoutRequestID");
            if (idNode == null || idNode.isNull()) {
                throw new RuntimeException("CheckoutRequestID not found in response");
            }
            return idNode.asText();
        } catch (Exception e) {
            logger.error("Failed to parse CheckoutRequestID from response: {}", json, e);
            throw new RuntimeException("Invalid STK Push response structure", e);
        }
    }

    /**
     * Generates a 14-digit timestamp in East Africa Time (Africa/Nairobi).
     * Strictly YYYYMMDDHHmmss format required by Safaricom Daraja API.
     */
    private String getCurrentTimestamp() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Africa/Nairobi"));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        return now.format(formatter);
    }

    /**
     * Clears the cached token (useful for testing or forced refresh).
     */
    public void clearTokenCache() {
        synchronized (tokenLock) {
            cachedAccessToken = null;
            tokenExpiryTime = 0;
            logger.info("🗑️ Safaricom token cache cleared");
        }
    }
}