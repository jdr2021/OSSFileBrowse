package net.jdr2021.utils;

import java.util.Base64;

/**
 * @version 1.0
 * @Author jdr
 * @Date 2024-5-25 1:28
 * @注释
 */

public class Base64Utils {

    // Base64 编码
    public static String encode(String plainText) {
        return Base64.getEncoder().encodeToString(plainText.getBytes());
    }

    // Base64 解码
    public static String decode(String base64Text) {
        byte[] decodedBytes = Base64.getDecoder().decode(base64Text);
        return new String(decodedBytes);
    }

}
