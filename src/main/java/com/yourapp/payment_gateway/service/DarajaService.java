package com.yourapp.payment_gateway.service;

import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Base64;
import java.util.Locale;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class DarajaService {

    private static final Logger logger = LoggerFactory.getLogger(DarajaService.class);
    private final OkHttpClient client = new OkHttpClient();
    
    @Value("${daraja.base.url}")
    private String BASE_URL;

    public String getAccessToken(String consumerKey, String consumerSecret) throws Exception {
        logger.info("Getting OAuth token");
        
        String credentials = consumerKey.trim() + ":" + consumerSecret.trim();
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
        
        String url = BASE_URL + "/oauth/v1/generate?grant_type=client_credentials";
        
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
            
            String token = extractToken(responseBody);
            logger.info("Token obtained successfully");
            return token;
        } catch (Exception e) {
            logger.error("Error getting token: {}", e.getMessage());
            throw e;
        }
    }
    
    private String extractToken(String json) {
        String cleanJson = json.replaceAll("\\s+", " ");
        
        String searchKey = "\"access_token\": \"";
        int start = cleanJson.indexOf(searchKey);
        if (start == -1) {
            searchKey = "\"access_token\":\"";
            start = cleanJson.indexOf(searchKey);
        }
        if (start == -1) {
            logger.error("access_token not found in response");
            throw new RuntimeException("access_token not found in response");
        }
        start += searchKey.length();
        int end = cleanJson.indexOf("\"", start);
        if (end == -1) {
            logger.error("End quote not found in response");
            throw new RuntimeException("End quote not found in response");
        }
        return cleanJson.substring(start, end);
    }

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

        // 1. Sanitize inputs
        String cleanShortcode = shortcode != null ? shortcode.trim() : "";
        String cleanPasskey = passkey != null ? passkey.trim() : "";
        String cleanPhone = phoneNumber != null ? phoneNumber.trim().replace("+", "") : "";
        String cleanOrderRef = orderReference != null ? orderReference.trim() : "";
        String cleanCallback = callbackUrl != null ? callbackUrl.trim() : "";

        // 2. Automatically translate DB types ('paybill' / 'till') to Safaricom's exact strings
        String rawType = transactionType != null ? transactionType.trim().toLowerCase() : "";
        String finalTransactionType;

        if ("till".equals(rawType) || "buygoods".equals(rawType) || "customerbuygoodsonline".equals(rawType)) {
            finalTransactionType = "CustomerBuyGoodsOnline";
        } else {
            // Default to PayBill for "paybill", null, or any other value
            finalTransactionType = "CustomerPayBillOnline";
        }

        // 3. Set timestamp explicitly to Nairobi time (UTC+3 / EAT)
        String timestamp = getCurrentTimestamp();

        // 4. Compute Base64 Password
        String passwordString = cleanShortcode + cleanPasskey + timestamp;
        String password = Base64.getEncoder().encodeToString(passwordString.getBytes());

        // 5. Convert amount to whole integer (M-Pesa standard)
        long roundedAmount = Math.max(1, Math.round(amount));

        // 6. Construct JSON using Locale.US (prevents comma decimals like 1,00 on EU servers)
        String jsonBody = String.format(
            Locale.US,
            "{"
            + "\"BusinessShortCode\":\"%s\","
            + "\"Password\":\"%s\","
            + "\"Timestamp\":\"%s\","
            + "\"TransactionType\":\"%s\","
            + "\"Amount\":%d,"
            + "\"PartyA\":\"%s\","
            + "\"PartyB\":\"%s\","
            + "\"PhoneNumber\":\"%s\","
            + "\"CallBackURL\":\"%s\","
            + "\"AccountReference\":\"%s\","
            + "\"TransactionDesc\":\"Payment for order %s\""
            + "}",
            cleanShortcode,
            password,
            timestamp,
            finalTransactionType,
            roundedAmount,
            cleanPhone,
            cleanShortcode,
            cleanPhone,
            cleanCallback,
            cleanOrderRef,
            cleanOrderRef
        );

        logger.info("Outgoing Payload JSON: {}", jsonBody);

        String url = BASE_URL + "/mpesa/stkpush/v1/processrequest";

        Request request = new Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer " + token)
            .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
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
     * Generates a 14-digit timestamp in East Africa Time (Africa/Nairobi).
     * Strictly YYYYMMDDHHmmss format required by Safaricom Daraja API.
     */
    private String getCurrentTimestamp() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Africa/Nairobi"));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        return now.format(formatter);
    }

    private String extractCheckoutRequestId(String json) {
        String searchKey = "\"CheckoutRequestID\":\"";
        int start = json.indexOf(searchKey);
        if (start == -1) {
            logger.error("CheckoutRequestID not found in response");
            throw new RuntimeException("CheckoutRequestID not found in response");
        }
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end == -1) {
            logger.error("End quote not found in response");
            throw new RuntimeException("End quote not found in response");
        }
        return json.substring(start, end);
    }
}