package com.yourapp.payment_gateway.service;

import okhttp3.*;
import org.springframework.stereotype.Service;
import java.util.Base64;

@Service
public class DarajaService {

    private final OkHttpClient client = new OkHttpClient();
    private static final String BASE_URL = "https://sandbox.safaricom.co.ke";

    /**
     * Get OAuth token from Daraja
     */
    public String getAccessToken(String consumerKey, String consumerSecret) throws Exception {
        
        String credentials = consumerKey + ":" + consumerSecret;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
        
        Request request = new Request.Builder()
            .url(BASE_URL + "/oauth/v1/generate?grant_type=client_credentials")
            .addHeader("Authorization", "Basic " + encodedCredentials)
            .build();
        
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            
            if (!response.isSuccessful()) {
                throw new Exception("Failed to get token: " + responseBody);
            }
            
            String token = extractToken(responseBody);
            System.out.println("✅ Successfully got access token: " + token);
            return token;
        }
    }
    
   private String extractToken(String json) {
    // Remove all whitespace and newlines
    String cleanJson = json.replaceAll("\\s+", " ");
    
    String searchKey = "\"access_token\": \"";
    int start = cleanJson.indexOf(searchKey);
    if (start == -1) {
        searchKey = "\"access_token\":\"";
        start = cleanJson.indexOf(searchKey);
    }
    if (start == -1) {
        throw new RuntimeException("access_token not found in response: " + json);
    }
    start += searchKey.length();
    int end = cleanJson.indexOf("\"", start);
    if (end == -1) {
        throw new RuntimeException("End quote not found in response: " + json);
    }
    String token = cleanJson.substring(start, end);
    System.out.println("🔑 Extracted token: " + token);
    return token;
}

    /**
     * Send STK Push to customer's phone
     */
    public String sendStkPush(
            String token,
            String shortcode,
            String passkey,
            double amount,
            String phoneNumber,
            String orderReference,
            String callbackUrl) throws Exception {

        // Generate password: shortcode + passkey + timestamp
        String timestamp = getCurrentTimestamp();
        String passwordString = shortcode + passkey + timestamp;
        String password = Base64.getEncoder().encodeToString(passwordString.getBytes());

        // For sandbox, use "CustomerPayBillOnline" (works for both Paybill and Till)
        String transactionType = "CustomerPayBillOnline";

        String jsonBody = String.format(
            "{"
            + "\"BusinessShortCode\":\"%s\","
            + "\"Password\":\"%s\","
            + "\"Timestamp\":\"%s\","
            + "\"TransactionType\":\"%s\","
            + "\"Amount\":\"%.2f\","
            + "\"PartyA\":\"%s\","
            + "\"PartyB\":\"%s\","
            + "\"PhoneNumber\":\"%s\","
            + "\"CallBackURL\":\"%s\","
            + "\"AccountReference\":\"%s\","
            + "\"TransactionDesc\":\"Payment for order %s\""
            + "}",
            shortcode,
            password,
            timestamp,
            transactionType,
            amount,
            phoneNumber,
            shortcode,
            phoneNumber,
            callbackUrl,
            orderReference,
            orderReference
        );

        System.out.println("📤 Sending STK Push to: " + phoneNumber + " | Amount: " + amount);

        Request request = new Request.Builder()
            .url(BASE_URL + "/mpesa/stkpush/v1/processrequest")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer " + token)
            .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                throw new Exception("STK Push failed: " + responseBody);
            }

            System.out.println("✅ STK Push sent. Response: " + responseBody);
            return extractCheckoutRequestId(responseBody);
        }
    }

    private String getCurrentTimestamp() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.format.DateTimeFormatter formatter = 
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        return now.format(formatter);
    }

    private String extractCheckoutRequestId(String json) {
        String searchKey = "\"CheckoutRequestID\":\"";
        int start = json.indexOf(searchKey);
        if (start == -1) {
            throw new RuntimeException("CheckoutRequestID not found in response: " + json);
        }
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end == -1) {
            throw new RuntimeException("End quote not found in response: " + json);
        }
        return json.substring(start, end);
    }
}