package com.codesight.relation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codesight.relation.constant.RelationRedisKeys;
import com.codesight.relation.mapper.UserFollowingMapper;
import com.codesight.relation.model.UserFollowing;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelationCacheServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, UserFollowing.class);
    }

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private UserFollowingMapper userFollowingMapper;

    @InjectMocks
    private RelationCacheService relationCacheService;

    private static final Long USER_A = 1001L;
    private static final Long USER_B = 1002L;

    @Test
    @DisplayName("isFollowing：缓存已存在时直接通过 Redis Set 判定")
    void testIsFollowing_CacheHit() {
        String key = RelationRedisKeys.getFollowingKey(USER_A);
        when(stringRedisTemplate.hasKey(key)).thenReturn(true);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(key, String.valueOf(USER_B))).thenReturn(true);

        boolean isFollowing = relationCacheService.isFollowing(USER_A, USER_B);

        assertThat(isFollowing).isTrue();
        verify(userFollowingMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("isFollowing：缓存未命中时从 DB 加载关注列表并回填 Redis")
    void testIsFollowing_CacheMiss_LoadFromDb() {
        String key = RelationRedisKeys.getFollowingKey(USER_A);
        when(stringRedisTemplate.hasKey(key)).thenReturn(false);

        List<UserFollowing> mockList = List.of(
                UserFollowing.builder().fromUserId(USER_A).toUserId(USER_B).build(),
                UserFollowing.builder().fromUserId(USER_A).toUserId(1003L).build()
        );
        when(userFollowingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(mockList);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(key, String.valueOf(USER_B))).thenReturn(true);

        boolean isFollowing = relationCacheService.isFollowing(USER_A, USER_B);

        assertThat(isFollowing).isTrue();
        // 验证调用了回填与设置 TTL
        verify(setOperations).add(eq(key), any(String[].class));
        verify(stringRedisTemplate).expire(eq(key), any());
    }

    @Test
    @DisplayName("isFollowing：空值防穿透，用户无任何关注时存入 -1 哨兵值")
    void testIsFollowing_EmptyList_PutSentinel() {
        String key = RelationRedisKeys.getFollowingKey(USER_A);
        when(stringRedisTemplate.hasKey(key)).thenReturn(false);
        when(userFollowingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(key, String.valueOf(USER_B))).thenReturn(false);

        boolean isFollowing = relationCacheService.isFollowing(USER_A, USER_B);

        assertThat(isFollowing).isFalse();
        verify(setOperations).add(key, RelationRedisKeys.EMPTY_SENTINEL);
        verify(stringRedisTemplate).expire(eq(key), any());
    }

    @Test
    @DisplayName("addFollowing 与 removeFollowing 正确维护 Redis Set")
    void testAddAndRemoveFollowing() {
        String key = RelationRedisKeys.getFollowingKey(USER_A);
        when(stringRedisTemplate.hasKey(key)).thenReturn(true);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

        relationCacheService.addFollowing(USER_A, USER_B);
        verify(setOperations).remove(key, RelationRedisKeys.EMPTY_SENTINEL);
        verify(setOperations).add(key, String.valueOf(USER_B));

        relationCacheService.removeFollowing(USER_A, USER_B);
        verify(setOperations).remove(key, String.valueOf(USER_B));
    }

    @Test
    @DisplayName("getFollowingUserIds：入参为 null 时直接返回空集合")
    void testGetFollowingUserIds_NullUserId() {
        Set<Long> result = relationCacheService.getFollowingUserIds(null);
        assertThat(result).isEmpty();
        verifyNoInteractions(stringRedisTemplate, userFollowingMapper);
    }

    @Test
    @DisplayName("getFollowingUserIds：正常读取关注 ID 集合并过滤空哨兵值")
    void testGetFollowingUserIds_Success() {
        String key = RelationRedisKeys.getFollowingKey(USER_A);
        when(stringRedisTemplate.hasKey(key)).thenReturn(true);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(key)).thenReturn(Set.of("1002", "1003", RelationRedisKeys.EMPTY_SENTINEL));

        Set<Long> result = relationCacheService.getFollowingUserIds(USER_A);

        assertThat(result).containsExactlyInAnyOrder(1002L, 1003L);
        assertThat(result).doesNotContain(-1L);
    }

    @Test
    @DisplayName("getFollowingUserIds：仅包含哨兵值时返回空集合")
    void testGetFollowingUserIds_OnlySentinel_ReturnsEmpty() {
        String key = RelationRedisKeys.getFollowingKey(USER_A);
        when(stringRedisTemplate.hasKey(key)).thenReturn(true);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(key)).thenReturn(Set.of(RelationRedisKeys.EMPTY_SENTINEL));

        Set<Long> result = relationCacheService.getFollowingUserIds(USER_A);

        assertThat(result).isEmpty();
    }
}
