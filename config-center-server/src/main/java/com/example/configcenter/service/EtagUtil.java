package com.example.configcenter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 生成弱 ETag 的小工具。
 * 这里选 SHA-256 不是因为一定要“很安全”，而是它稳定、常见、拿来做签名够省心。
 */
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
