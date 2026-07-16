package com.example.configcenter.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 生成弱 ETag 的小工具。
 * 这里选 SHA-256 不是因为一定要“很安全”，而是它稳定、常见、拿来做签名够省心。
 */
public class EtagUtil {
    public static String weakEtagForFields(List<String> fields) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            updateLength(md, fields.size());
            for (String field : fields) {
                if (field == null) {
                    updateLength(md, -1);
                    continue;
                }
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                updateLength(md, bytes.length);
                md.update(bytes);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return "W/\"" + sb + "\"";
        } catch (Exception e) {
            throw new IllegalStateException("ETag compute failed", e);
        }
    }

    private static void updateLength(MessageDigest md, int length) {
        md.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
    }
}
