package com.example.rag.util;


import org.apache.commons.codec.binary.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * HmacUtil（工具类）
 */
public class HmacUtil {

    public static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Hex.encodeHexString(raw);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 calculation failed", e);
        }
    }
}
