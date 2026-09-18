package com.codesight.article.service;

import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
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

    public record FeedCursor(long value, long articleId) {}

    /**
     * 解析游标（格式：{prefix}:{value}:{articleId} 的 Base64 URL 字符串）
     */
    public static FeedCursor parseCursor(String cursorStr, String prefix) {
        if (cursorStr == null || cursorStr.isBlank()) {
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
            } else {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "游标前缀格式错误");
            }
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
}
