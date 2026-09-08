package com.codesight.relation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.counter.event.CounterEvent;
import com.codesight.counter.event.CounterEventProducer;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.relation.api.dto.request.FollowListQueryRequest;
import com.codesight.relation.api.dto.response.FollowUserItemResponse;
import com.codesight.relation.api.dto.response.RelationCursorPageResponse;
import com.codesight.relation.api.dto.response.RelationStatusResponse;
import com.codesight.relation.mapper.UserFollowerMapper;
import com.codesight.relation.mapper.UserFollowingMapper;
import com.codesight.relation.model.UserFollower;
import com.codesight.relation.model.UserFollowing;
import com.codesight.relation.util.RelationCursor;
import com.codesight.user.User;
import com.codesight.user.UserBaseInfo;
import com.codesight.user.UserCacheService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    @Mock
    private UserFollowingMapper userFollowingMapper;

    @Mock
    private UserFollowerMapper userFollowerMapper;

    @Mock
    private UserCacheService userCacheService;

    @Mock
    private RelationCacheService relationCacheService;

    @Mock
    private CounterService counterService;

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

    @Test
    @DisplayName("关注列表游标分页：第一页且存在更多数据（hasMore=true），正确组装画像、计数与未登录互动态")
    void testListFollowing_FirstPage_HasMore() {
        Instant now = Instant.now();
        UserFollowing r1 = UserFollowing.builder().fromUserId(USER_A).toUserId(1002L).createdTime(now).build();
        UserFollowing r2 = UserFollowing.builder().fromUserId(USER_A).toUserId(1003L).createdTime(now.minusSeconds(10)).build();
        UserFollowing r3 = UserFollowing.builder().fromUserId(USER_A).toUserId(1004L).createdTime(now.minusSeconds(20)).build();

        // 请求 limit=2，数据库返回 3 条表示还有更多
        when(userFollowingMapper.selectList(any())).thenReturn(List.of(r1, r2, r3));

        // Mock 用户基础画像
        UserBaseInfo u1 = new UserBaseInfo(1002L, "张三", "avatar1.jpg", null, null, null);
        UserBaseInfo u2 = new UserBaseInfo(1003L, "李四", "avatar2.jpg", null, null, null);
        when(userCacheService.batchGetUserBaseInfo(any())).thenReturn(Map.of(1002L, u1, 1003L, u2));

        // Mock 计数中台
        when(counterService.batchGetCounts(eq(CounterSchema.EntityType.USER), any()))
                .thenReturn(Map.of(
                        "1002", Map.of(CounterSchema.UserMetric.FOLLOWERS, 88L, CounterSchema.UserMetric.FOLLOWINGS, 10L),
                        "1003", Map.of(CounterSchema.UserMetric.FOLLOWERS, 12L, CounterSchema.UserMetric.FOLLOWINGS, 50L)
                ));

        // 未登录态拉取（currentUserId = null）
        FollowListQueryRequest req1 = FollowListQueryRequest.builder().userId(USER_A).limit(2).build();
        RelationCursorPageResponse<FollowUserItemResponse> response = relationService.listFollowing(req1, null);

        assertThat(response.hasMore()).isTrue();
        assertThat(response.items()).hasSize(2);
        assertThat(response.nextCursor()).isNotNull();

        FollowUserItemResponse item1 = response.items().getFirst();
        assertThat(item1.userId()).isEqualTo(1002L);
        assertThat(item1.nickname()).isEqualTo("张三");
        assertThat(item1.avatar()).isEqualTo("avatar1.jpg");
        assertThat(item1.followerCount()).isEqualTo(88L);
        assertThat(item1.followingCount()).isEqualTo(10L);
        assertThat(item1.followedByMe()).isFalse();

        FollowUserItemResponse item2 = response.items().get(1);
        assertThat(item2.userId()).isEqualTo(1003L);
        assertThat(item2.nickname()).isEqualTo("李四");

        // 验证 nextCursor 可以被正确反解回最后一项（r2）的创建时间与 ID
        RelationCursor.DecodedCursor decoded = RelationCursor.decode(response.nextCursor());
        assertThat(decoded).isNotNull();
        assertThat(decoded.createdTime().toEpochMilli()).isEqualTo(r2.getCreatedTime().toEpochMilli());
        assertThat(decoded.targetUserId()).isEqualTo(1003L);
    }

    @Test
    @DisplayName("关注列表游标分页：带游标下一页且最后一页（hasMore=false），正确组装登录用户互动态")
    void testListFollowing_LastPage_LoggedIn_ShouldAssembleFollowedByMe() {
        Instant now = Instant.now();
        String cursor = RelationCursor.encode(now, 1003L);

        UserFollowing r3 = UserFollowing.builder().fromUserId(USER_A).toUserId(1004L).createdTime(now.minusSeconds(20)).build();
        // 查出来只有 1 条 <= limit(2)，说明没有更多了
        when(userFollowingMapper.selectList(any())).thenReturn(List.of(r3));

        UserBaseInfo u3 = new UserBaseInfo(1004L, "王五", "avatar3.jpg", null, null, null);
        when(userCacheService.batchGetUserBaseInfo(any())).thenReturn(Map.of(1004L, u3));

        when(counterService.batchGetCounts(eq(CounterSchema.EntityType.USER), any()))
                .thenReturn(Map.of("1004", Map.of(CounterSchema.UserMetric.FOLLOWERS, 0L, CounterSchema.UserMetric.FOLLOWINGS, 0L)));

        // 登录态拉取（currentUserId = USER_B），mock 关注状态为 true
        when(relationCacheService.batchGetRelationStatus(USER_B, List.of(1004L)))
                .thenReturn(Map.of(1004L, true));

        FollowListQueryRequest req2 = FollowListQueryRequest.builder().userId(USER_A).limit(2).cursor(cursor).build();
        RelationCursorPageResponse<FollowUserItemResponse> response = relationService.listFollowing(req2, USER_B);

        assertThat(response.hasMore()).isFalse();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.items()).hasSize(1);

        FollowUserItemResponse item = response.items().getFirst();
        assertThat(item.userId()).isEqualTo(1004L);
        assertThat(item.nickname()).isEqualTo("王五");
        assertThat(item.followedByMe()).isTrue();
    }

    @Test
    @DisplayName("粉丝列表游标分页：查询与组装成功")
    void testListFollowers_Success() {
        Instant now = Instant.now();
        UserFollower f1 = UserFollower.builder().toUserId(USER_A).fromUserId(2001L).createdTime(now).build();

        when(userFollowerMapper.selectList(any())).thenReturn(List.of(f1));

        UserBaseInfo u1 = new UserBaseInfo(2001L, "粉丝小李", "fan.jpg", null, null, null);
        when(userCacheService.batchGetUserBaseInfo(any())).thenReturn(Map.of(2001L, u1));

        when(counterService.batchGetCounts(eq(CounterSchema.EntityType.USER), any()))
                .thenReturn(Map.of("2001", Map.of(CounterSchema.UserMetric.FOLLOWERS, 100L, CounterSchema.UserMetric.FOLLOWINGS, 20L)));

        FollowListQueryRequest req3 = FollowListQueryRequest.builder().userId(USER_A).limit(10).build();
        RelationCursorPageResponse<FollowUserItemResponse> response = relationService.listFollowers(req3, null);

        assertThat(response.hasMore()).isFalse();
        assertThat(response.items()).hasSize(1);
        FollowUserItemResponse item = response.items().getFirst();
        assertThat(item.userId()).isEqualTo(2001L);
        assertThat(item.nickname()).isEqualTo("粉丝小李");
        assertThat(item.followerCount()).isEqualTo(100L);
    }

    @Test
    @DisplayName("游标编解码工具测试：支持 Base64 与纯时间戳向下兼容")
    void testRelationCursorUtil() {
        Instant now = Instant.ofEpochMilli(1731480000000L);
        Long targetId = 10086L;

        // 1. 标准编码与解码
        String cursor = RelationCursor.encode(now, targetId);
        assertThat(cursor).isNotNull();

        RelationCursor.DecodedCursor decoded = RelationCursor.decode(cursor);
        assertThat(decoded).isNotNull();
        assertThat(decoded.createdTime()).isEqualTo(now);
        assertThat(decoded.targetUserId()).isEqualTo(targetId);

        // 2. 兼容纯数字时间戳
        RelationCursor.DecodedCursor legacy = RelationCursor.decode("1731480000000");
        assertThat(legacy).isNotNull();
        assertThat(legacy.createdTime()).isEqualTo(now);
        assertThat(legacy.targetUserId()).isNull();

        // 3. 空值与非法格式防护
        assertThat(RelationCursor.decode(null)).isNull();
        assertThat(RelationCursor.decode("   ")).isNull();
        assertThat(RelationCursor.decode("invalid-string")).isNull();
    }

    @Test
    @DisplayName("关注列表空数据测试：查无数据返回空分页包装")
    void testListFollowing_EmptyList() {
        FollowListQueryRequest request = FollowListQueryRequest.builder()
                .userId(USER_A)
                .limit(20)
                .cursor(null)
                .build();

        when(userFollowingMapper.selectList(any())).thenReturn(Collections.emptyList());

        RelationCursorPageResponse<FollowUserItemResponse> response = relationService.listFollowing(request, null);
        assertThat(response).isNotNull();
        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).isFalse();
    }
}
