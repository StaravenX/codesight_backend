package com.codesight.relation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.counter.event.CounterEvent;
import com.codesight.counter.event.CounterEventProducer;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.relation.api.dto.response.RelationStatusResponse;
import com.codesight.relation.mapper.UserFollowerMapper;
import com.codesight.relation.mapper.UserFollowingMapper;
import com.codesight.relation.model.UserFollower;
import com.codesight.relation.model.UserFollowing;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelationServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, UserFollowing.class);
        TableInfoHelper.initTableInfo(assistant, UserFollower.class);
    }

    @Mock
    private UserFollowingMapper userFollowingMapper;

    @Mock
    private UserFollowerMapper userFollowerMapper;

    @Mock
    private RelationCacheService relationCacheService;

    @Mock
    private CounterEventProducer counterEventProducer;

    @InjectMocks
    private RelationService relationService;

    private static final Long USER_A = 1001L;
    private static final Long USER_B = 1002L;

    @Test
    @DisplayName("关注自己应该抛出 CANNOT_FOLLOW_SELF 异常")
    void testFollow_Self_ShouldThrowBusinessException() {
        assertThatThrownBy(() -> relationService.follow(USER_A, USER_A))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.CANNOT_FOLLOW_SELF.getMsg());

        verifyNoInteractions(userFollowingMapper, userFollowerMapper, relationCacheService, counterEventProducer);
    }

    @Test
    @DisplayName("关注人数达到上限（5000人）应抛出 FOLLOW_LIMIT_EXCEEDED 异常")
    void testFollow_LimitExceeded_ShouldThrowException() {
        when(userFollowingMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5000L);

        assertThatThrownBy(() -> relationService.follow(USER_A, USER_B))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.FOLLOW_LIMIT_EXCEEDED.getMsg());

        verify(userFollowingMapper, never()).insert(any(UserFollowing.class));
        verifyNoInteractions(counterEventProducer);
    }

    @Test
    @DisplayName("首次关注成功：物理双写落库、同步缓存并投递 2 条 Kafka 计数变更事件")
    void testFollow_Success_FirstTime_ShouldInsertAndPublishEvents() {
        when(relationCacheService.isFollowing(USER_A, USER_B)).thenReturn(false);
        when(userFollowingMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(10L);

        boolean result = relationService.follow(USER_A, USER_B);

        assertThat(result).isTrue();
        verify(userFollowingMapper).insert(any(UserFollowing.class));
        verify(userFollowerMapper).insert(any(UserFollower.class));
        verify(relationCacheService).addFollowing(USER_A, USER_B);

        // 验证捕获 2 条 Kafka 计数事件
        ArgumentCaptor<CounterEvent> eventCaptor = ArgumentCaptor.forClass(CounterEvent.class);
        verify(counterEventProducer, times(2)).publish(eventCaptor.capture());

        List<CounterEvent> events = eventCaptor.getAllValues();
        // 目标作者 B 粉丝数 +1
        CounterEvent targetFollowerEvent = events.getFirst();
        assertThat(targetFollowerEvent.entityType()).isEqualTo(CounterSchema.EntityType.USER);
        assertThat(targetFollowerEvent.entityId()).isEqualTo(String.valueOf(USER_B));
        assertThat(targetFollowerEvent.metric()).isEqualTo(CounterSchema.UserMetric.FOLLOWERS.getCode());
        assertThat(targetFollowerEvent.delta()).isEqualTo(1);

        // 发起者 A 关注数 +1
        CounterEvent authorFollowingEvent = events.get(1);
        assertThat(authorFollowingEvent.entityType()).isEqualTo(CounterSchema.EntityType.USER);
        assertThat(authorFollowingEvent.entityId()).isEqualTo(String.valueOf(USER_A));
        assertThat(authorFollowingEvent.metric()).isEqualTo(CounterSchema.UserMetric.FOLLOWINGS.getCode());
        assertThat(authorFollowingEvent.delta()).isEqualTo(1);
    }

    @Test
    @DisplayName("重复关注天然幂等：缓存判定已关注时直接快速返回成功，不查库、不落库、不发事件")
    void testFollow_Idempotent_AlreadyFollowing_ShouldNotPublishEvents() {
        when(relationCacheService.isFollowing(USER_A, USER_B)).thenReturn(true);

        boolean result = relationService.follow(USER_A, USER_B);

        assertThat(result).isTrue();
        verify(userFollowingMapper, never()).selectCount(any());
        verify(userFollowingMapper, never()).insert(any(UserFollowing.class));
        verify(relationCacheService, never()).addFollowing(any(), any());
        verifyNoInteractions(counterEventProducer);
    }

    @Test
    @DisplayName("取消关注成功：物理删除双表记录、移除缓存并投递 2 条扣减计数事件")
    void testUnfollow_Success_ShouldDeleteAndPublishEvents() {
        when(relationCacheService.isFollowing(USER_A, USER_B)).thenReturn(true);
        when(userFollowingMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        when(userFollowerMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(1);

        boolean result = relationService.unfollow(USER_A, USER_B);

        assertThat(result).isTrue();
        verify(relationCacheService).removeFollowing(USER_A, USER_B);

        ArgumentCaptor<CounterEvent> eventCaptor = ArgumentCaptor.forClass(CounterEvent.class);
        verify(counterEventProducer, times(2)).publish(eventCaptor.capture());

        List<CounterEvent> events = eventCaptor.getAllValues();
        // 目标作者 B 粉丝数 -1
        assertThat(events.getFirst().entityId()).isEqualTo(String.valueOf(USER_B));
        assertThat(events.get(0).metric()).isEqualTo(CounterSchema.UserMetric.FOLLOWERS.getCode());
        assertThat(events.get(0).delta()).isEqualTo(-1);

        // 发起者 A 关注数 -1
        assertThat(events.get(1).entityId()).isEqualTo(String.valueOf(USER_A));
        assertThat(events.get(1).metric()).isEqualTo(CounterSchema.UserMetric.FOLLOWINGS.getCode());
        assertThat(events.get(1).delta()).isEqualTo(-1);
    }

    @Test
    @DisplayName("重复取关天然幂等：缓存判定未关注时直接快速返回成功，不查库、不删库、不发事件")
    void testUnfollow_Idempotent_ShouldNotPublishEvents() {
        when(relationCacheService.isFollowing(USER_A, USER_B)).thenReturn(false);

        boolean result = relationService.unfollow(USER_A, USER_B);

        assertThat(result).isTrue();
        verify(userFollowingMapper, never()).delete(any());
        verify(relationCacheService, never()).removeFollowing(any(), any());
        verifyNoInteractions(counterEventProducer);
    }

    @Test
    @DisplayName("取关自己直接返回 true")
    void testUnfollow_Self_ShouldReturnTrueDirectly() {
        boolean result = relationService.unfollow(USER_A, USER_A);
        assertThat(result).isTrue();
        verifyNoInteractions(userFollowingMapper, userFollowerMapper, counterEventProducer);
    }

    @Test
    @DisplayName("关系判定测试（双方互关 / 单向 / 无关）")
    void testGetRelationStatus() {
        // 1. A 关注 B 且 B 关注 A
        when(relationCacheService.isFollowing(USER_A, USER_B)).thenReturn(true);
        when(relationCacheService.isFollowing(USER_B, USER_A)).thenReturn(true);

        RelationStatusResponse mutualStatus = relationService.getRelationStatus(USER_A, USER_B);
        assertThat(mutualStatus.following()).isTrue();
        assertThat(mutualStatus.followedBy()).isTrue();

        // 2. A 关注 B 但 B 未关注 A
        when(relationCacheService.isFollowing(USER_B, USER_A)).thenReturn(false);
        RelationStatusResponse singleStatus = relationService.getRelationStatus(USER_A, USER_B);
        assertThat(singleStatus.following()).isTrue();
        assertThat(singleStatus.followedBy()).isFalse();

        // 3. 自己与自己 => 全 false
        RelationStatusResponse selfStatus = relationService.getRelationStatus(USER_A, USER_A);
        assertThat(selfStatus.following()).isFalse();
        assertThat(selfStatus.followedBy()).isFalse();
    }

    @Test
    @DisplayName("批量关系状态查询正确委托给缓存层")
    void testBatchGetRelationStatus() {
        List<Long> targets = List.of(USER_B, 1003L);
        when(relationCacheService.batchGetRelationStatus(USER_A, targets))
                .thenReturn(Map.of(USER_B, true, 1003L, false));

        Map<Long, Boolean> result = relationService.batchGetRelationStatus(USER_A, targets);
        assertThat(result).hasSize(2);
        assertThat(result.get(USER_B)).isTrue();
        assertThat(result.get(1003L)).isFalse();

        // 空列表直接返回空 Map
        Map<Long, Boolean> emptyResult = relationService.batchGetRelationStatus(USER_A, Collections.emptyList());
        assertThat(emptyResult).isEmpty();
    }
}
