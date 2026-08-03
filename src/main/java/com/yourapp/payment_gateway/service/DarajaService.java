package com.yourapp.payment_gateway.service;

import okhttp3.*;
import org.springframework.stereotype.Service;
import java.util.Base64;

@Service
public class DarajaService {

    private final OkHttpClient client = new OkHttpClient();
    
    // Just use the sandbox URL directly for now
    private static final String BASE_URL = "https://sandbox.safaricom.co.ke";

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
            System.out.println("✅ Successfully got access token");
            return token;
        }
    }
    
    private String extractToken(String json) {
        int start = json.indexOf("\"access_token\":\"") + 16;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}