package com.codesight.relation.util;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 关系列表 Keyset 游标编解码工具
 * <p>
 * 基于 (createdTime, targetUserId) 复合键生成不可变时间游标，
 * 解决传统 limit offset 深翻页性能衰减与数据漂移问题。
 */
@Slf4j
@UtilityClass
public class RelationCursor {

    /**
     * 解码后的游标结构体
     *
     * @param createdTime  基准记录创建时间
     * @param targetUserId 基准记录目标用户 ID（可为空，用于旧版纯时间戳兼容）
     */
    public record DecodedCursor(Instant createdTime, Long targetUserId) {
    }

    /**
     * 将创建时间与目标用户 ID 编码为 URL 安全的 Base64 游标
     *
     * @param createdTime  创建时间
     * @param targetUserId 目标用户 ID
     * @return Base64 游标字符串
     */
    public static String encode(Instant createdTime, Long targetUserId) {
        if (createdTime == null || targetUserId == null) {
            return null;
        }
        String raw = createdTime.toEpochMilli() + ":" + targetUserId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码游标字符串
     *
     * @param cursorStr 前端传入的游标字符串
     * @return DecodedCursor，若无效或为空则返回 null
     */
    public static DecodedCursor decode(String cursorStr) {
        if (cursorStr == null || cursorStr.isBlank()) {
            return null;
        }

        // 1. 尝试按 Base64 URL 格式解码
        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(cursorStr.trim());
            String raw = new String(decodedBytes, StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length >= 2) {
                long epochMilli = Long.parseLong(parts[0]);
                long targetUserId = Long.parseLong(parts[1]);
                return new DecodedCursor(Instant.ofEpochMilli(epochMilli), targetUserId);
            }
        } catch (Exception ignored) {
            // 降级尝试处理纯时间戳格式
        }

        // 2. 兼容纯数字毫秒时间戳格式（如 "1731480000000"）
        try {
            long epochMilli = Long.parseLong(cursorStr.trim());
            return new DecodedCursor(Instant.ofEpochMilli(epochMilli), null);
        } catch (NumberFormatException e) {
            log.warn("无效的关系分页游标: {}", cursorStr);
            return null;
        }
    }

    /**
     * 为 MyBatis-Plus LambdaQueryWrapper 统一装配 Keyset 游标分页条件与倒序排序
     *
     * @param qw         查询构造器
     * @param cursorStr  客户端传入的游标字符串
     * @param pageSize   单页条数
     * @param timeColumn 时间主排序列
     * @param idColumn   次级唯一 ID 排序列
     * @param <T>        实体泛型
     */
    public static <T> void applyPagination(
            LambdaQueryWrapper<T> qw,
            String cursorStr,
            int pageSize,
            SFunction<T, Instant> timeColumn,
            SFunction<T, Long> idColumn) {
        DecodedCursor decoded = decode(cursorStr);
        if (decoded != null) {
            Instant cursorTime = decoded.createdTime();
            Long cursorTargetId = decoded.targetUserId();
            if (cursorTargetId != null) {
                qw.and(w -> w.lt(timeColumn, cursorTime)
                        .or(sub -> sub.eq(timeColumn, cursorTime)
                                .lt(idColumn, cursorTargetId)));
            } else {
                qw.lt(timeColumn, cursorTime);
            }
        }

        qw.orderByDesc(timeColumn)
                .orderByDesc(idColumn)
                .last("LIMIT " + (pageSize + 1));
    }
}
