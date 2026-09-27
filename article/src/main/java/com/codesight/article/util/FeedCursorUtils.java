package com.codesight.article.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 信息流通用游标编解码工具
 */
@Slf4j
@UtilityClass
public class FeedCursorUtils {

    public static final String CURSOR_PREFIX_RECOMMENDED = "rec";
    public static final String CURSOR_PREFIX_NEWEST = "new";
    public static final String CURSOR_PREFIX_BUFFER = "buf";

    public record FeedCursor(long value, long articleId) {}

    /**
     * 解析游标（格式：{prefix}:{value}:{articleId} 的 Base64 URL 字符串）
     */
    public static FeedCursor parseCursor(String cursorStr, String prefix) {
        if (cursorStr == null || cursorStr.isBlank()) {
            return null;
        }

        if (cursorStr.chars().allMatch(Character::isDigit)) {
            return null;
        }

        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursorStr), StandardCharsets.UTF_8);
            if (decoded.startsWith(prefix + ":")) {
                decoded = decoded.substring(prefix.length() + 1);
                String[] parts = decoded.split(":");
                if (parts.length >= 2) {
                    return new FeedCursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
                }
            }
        } catch (IllegalArgumentException ignored) {
        } catch (Exception e) {
            log.warn("解析游标失败，cursorStr: {}, prefix: {}", cursorStr, prefix, e);
        }
        return null;
    }

    /**
     * 构建游标
     */
    public static String buildCursor(String prefix, long value, long articleId) {
        String raw = prefix + ":" + value + ":" + articleId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构建 Buffer 游标（Base64 URL 编码）
     */
    public static String buildBufferCursor(int offset) {
        String raw = CURSOR_PREFIX_BUFFER + ":" + offset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析 Buffer 游标，获取 offset（兼容纯数字）
     */
    public static int parseBufferCursor(String cursorStr) {
        if (cursorStr == null || cursorStr.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursorStr), StandardCharsets.UTF_8);
            if (decoded.startsWith(CURSOR_PREFIX_BUFFER + ":")) {
                return Integer.parseInt(decoded.substring(CURSOR_PREFIX_BUFFER.length() + 1));
            }
        } catch (Exception ignored) {
        }
        try {
            return Integer.parseInt(cursorStr.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
