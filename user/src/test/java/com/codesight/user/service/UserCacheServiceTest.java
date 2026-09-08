package com.codesight.user.service;

import cn.hutool.json.JSONUtil;
import com.codesight.user.User;
import com.codesight.user.UserCacheService;
import com.codesight.user.UserMapper;
import com.codesight.user.UserBaseInfo;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserCacheServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    private Cache<Long, UserBaseInfo> userBaseLocalCache;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserMapper userMapper;

    private UserCacheService userCacheService;

    @BeforeEach
    void setUp() {
        userBaseLocalCache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();

        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        userCacheService = new UserCacheService(userBaseLocalCache, stringRedisTemplate, userMapper);
    }

    @Test
    @DisplayName("单查测试：L1 本地缓存命中，直接返回且不触发 Redis 与 DB")
    void testGetUserBaseInfo_L1Hit() {
        UserBaseInfo cached = new UserBaseInfo(101L, "Alice", "avatar1.png", null, null, null);
        userBaseLocalCache.put(101L, cached);

        UserBaseInfo result = userCacheService.getUserBaseInfo(101L);

        assertThat(result).isNotNull();
        assertThat(result.nickname()).isEqualTo("Alice");
        verifyNoInteractions(stringRedisTemplate, userMapper);
    }

    @Test
    @DisplayName("单查测试：L1 未命中，L2 Redis 命中并回填 L1")
    void testGetUserBaseInfo_L2Hit() {
        UserBaseInfo redisObj = new UserBaseInfo(102L, "Bob", "avatar2.png", null, null, null);
        when(valueOperations.multiGet(List.of("user:base:102"))).thenReturn(List.of(JSONUtil.toJsonStr(redisObj)));

        UserBaseInfo result = userCacheService.getUserBaseInfo(102L);

        assertThat(result).isNotNull();
        assertThat(result.nickname()).isEqualTo("Bob");
        assertThat(userBaseLocalCache.getIfPresent(102L)).isNotNull();
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("单查测试：L1/L2 皆未命中，L3 DB 兜底并回填 Redis 与 L1")
    void testGetUserBaseInfo_L3DbFallback() {
        User dbUser = User.builder().id(103L).nickname("Charlie").avatar("avatar3.png").build();
        when(valueOperations.multiGet(List.of("user:base:103"))).thenReturn(Collections.singletonList(null));
        when(userMapper.selectList(any())).thenReturn(List.of(dbUser));

        UserBaseInfo result = userCacheService.getUserBaseInfo(103L);

        assertThat(result).isNotNull();
        assertThat(result.nickname()).isEqualTo("Charlie");
        assertThat(userBaseLocalCache.getIfPresent(103L)).isNotNull();
        verify(valueOperations).set(eq("user:base:103"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("批查测试：全命中 L1 缓存，0 次网络 I/O 极速返回")
    void testBatchGetUserBaseInfo_AllL1Hit() {
        userBaseLocalCache.put(1L, new UserBaseInfo(1L, "U1", "a1.png", null, null, null));
        userBaseLocalCache.put(2L, new UserBaseInfo(2L, "U2", "a2.png", null, null, null));

        Map<Long, UserBaseInfo> result = userCacheService.batchGetUserBaseInfo(List.of(1L, 2L));

        assertThat(result).hasSize(2);
        assertThat(result.get(1L).nickname()).isEqualTo("U1");
        assertThat(result.get(2L).nickname()).isEqualTo("U2");
        verifyNoInteractions(stringRedisTemplate, userMapper);
    }

    @Test
    @DisplayName("批查测试：部分 L1 命中 + 部分 L2 MGET 命中 + 部分 L3 DB 兜底 + 空值防穿透")
    void testBatchGetUserBaseInfo_HybridLevels() {
        // ID 1 在 L1
        userBaseLocalCache.put(1L, new UserBaseInfo(1L, "U1", "a1.png", null, null, null));

        // ID 2 在 L2, ID 3 在 DB, ID 4 完全不存在
        List<Long> queryIds = List.of(1L, 2L, 3L, 4L);

        // Redis multiGet 模拟
        UserBaseInfo u2 = new UserBaseInfo(2L, "U2", "a2.png", null, null, null);
        when(valueOperations.multiGet(List.of("user:base:2", "user:base:3", "user:base:4")))
                .thenReturn(Arrays.asList(JSONUtil.toJsonStr(u2), null, null));

        // DB 模拟 (只查到了 ID 3)
        User u3 = User.builder().id(3L).nickname("U3").avatar("a3.png").build();
        when(userMapper.selectList(any())).thenReturn(List.of(u3));

        Map<Long, UserBaseInfo> result = userCacheService.batchGetUserBaseInfo(queryIds);

        assertThat(result).hasSize(3);
        assertThat(result.get(1L).nickname()).isEqualTo("U1");
        assertThat(result.get(2L).nickname()).isEqualTo("U2");
        assertThat(result.get(3L).nickname()).isEqualTo("U3");
        assertThat(result.get(4L)).isNull();

        // 验证 L1 回填
        assertThat(userBaseLocalCache.getIfPresent(2L)).isNotNull();
        assertThat(userBaseLocalCache.getIfPresent(3L)).isNotNull();

        // 验证 DB 结果回填 Redis
        verify(valueOperations).set(eq("user:base:3"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        // 验证空值标记防穿透写入 Redis
        verify(valueOperations).set(eq("user:base:4"), eq("{}"), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("淘汰测试：双淘汰 L1 与 L2")
    void testEvictUser() {
        userBaseLocalCache.put(888L, new UserBaseInfo(888L, "Target", "avatar.png", null, null, null));

        userCacheService.evictUser(888L);

        assertThat(userBaseLocalCache.getIfPresent(888L)).isNull();
        verify(stringRedisTemplate).delete("user:base:888");
    }
}
