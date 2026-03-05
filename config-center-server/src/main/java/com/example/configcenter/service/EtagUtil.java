package com.example.configcenter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class EtagUtil {
    public static String weakEtag(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return "W/\"" + sb + "\"";
        } catch (Exception e) {
            throw new IllegalStateException("ETag compute failed", e);
        }
    }
}