package com.reconnect.config;

public class AppConfig {
    private static final String DEFAULT_API_URL = "http://localhost:8080";

    public static String getApiUrl() {
        String apiUrl = System.getenv("API_URL");
        return apiUrl != null ? apiUrl : DEFAULT_API_URL;
    }
} 