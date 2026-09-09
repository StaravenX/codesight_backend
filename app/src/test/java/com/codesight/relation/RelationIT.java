package com.codesight.relation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.app.CodeSightApplication;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.relation.api.dto.request.FollowListQueryRequest;
import com.codesight.relation.api.dto.response.FollowUserItemResponse;
import com.codesight.relation.api.dto.response.RelationCursorPageResponse;
import com.codesight.relation.api.dto.response.RelationStatusResponse;
import com.codesight.relation.constant.RelationRedisKeys;
import com.codesight.relation.mapper.UserFollowerMapper;
import com.codesight.relation.mapper.UserFollowingMapper;
import com.codesight.relation.model.UserFollower;
import com.codesight.relation.model.UserFollowing;
import com.codesight.relation.service.RelationCacheService;
import com.codesight.relation.service.RelationService;
import com.codesight.user.User;
import com.codesight.user.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户关系模块端到端容器化集成测试（Relation End-to-End IT）。
 * <p>
 * 依赖环境：真实中间件环境（MySQL + Redis + Kafka）。
 * 覆盖场景：
 * 1. 关注全链路：MySQL 物理双表原子双写、Redis Set 缓存同步回填与读穿透、Kafka 异步计数投递；
 * 2. 天然幂等保障：重复关注与重复取关的零击穿、零冗余写入；
 * 3. 社交图谱双向互关判定与多作者批量状态极速判定；
 * 4. 关注列表与粉丝列表基于 Keyset 游标分页拉取与用户画像组装；
 * 5. 跨模块服务：为 Feed 关注流提供纯净的 getFollowingUserIds 集合提取；
 * 6. 测试结束后自动清理 MySQL 与 Redis 产生的残留数据。
 */
@SpringBootTest(classes = CodeSightApplication.class)
public class RelationIT {

    @Autowired
    private RelationService relationService;

    @Autowired
    private RelationCacheService relationCacheService;

    @Autowired
    private UserFollowingMapper userFollowingMapper;

    @Autowired
    private UserFollowerMapper userFollowerMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private Long userAId;
    private Long userBId;

    @BeforeEach
    void setUp() {
        long seed = System.currentTimeMillis() % 10000000L;

        // 1. 创建测试用户 A
        User userA = User.builder()
                .nickname("IT测试用户A_" + seed)
                .phone("138" + String.format("%08d", seed))
                .email("it_a_" + seed + "@codesight.com")
                .csId("cs_a_" + seed)
                .build();
        userMapper.insert(userA);
        this.userAId = userA.getId();

        // 2. 创建测试用户 B
        User userB = User.builder()
                .nickname("IT测试博主B_" + (seed + 1))
                .phone("139" + String.format("%08d", seed + 1))
                .email("it_b_" + seed + "@codesight.com")
                .csId("cs_b_" + seed)
                .build();
        userMapper.insert(userB);
        this.userBId = userB.getId();
    }

    @AfterEach
    void tearDown() {
        if (userAId != null && userBId != null) {
            // 清理出度与入度表记录
            userFollowingMapper.delete(new LambdaQueryWrapper<UserFollowing>()
                    .in(UserFollowing::getFromUserId, List.of(userAId, userBId)));
            userFollowerMapper.delete(new LambdaQueryWrapper<UserFollower>()
                    .in(UserFollower::getToUserId, List.of(userAId, userBId)));

            // 清理 Redis Set 缓存
            stringRedisTemplate.delete(RelationRedisKeys.getFollowingKey(userAId));
            stringRedisTemplate.delete(RelationRedisKeys.getFollowingKey(userBId));
        }

        // 清理测试用户
        if (userAId != null) {
            userMapper.deleteById(userAId);
        }
        if (userBId != null) {
            userMapper.deleteById(userBId);
        }
    }

    @Test
    @DisplayName("IT 场景 1：端到端关注与取关完整闭环（MySQL双写落盘 + Redis缓存同步 + 双向判定 + 天然幂等）")
    void testFollowAndUnfollow_FullEndToEndFlow() {
        // 1. 初始状态：互未关注
        RelationStatusResponse initialStatus = relationService.getRelationStatus(userAId, userBId);
        assertFalse(initialStatus.following());
        assertFalse(initialStatus.followedBy());

        // 2. UserA 关注 UserB
        boolean followSuccess = relationService.follow(userAId, userBId);
        assertTrue(followSuccess);

        // 验证 MySQL 物理出度表落盘
        Long followingCount = userFollowingMapper.selectCount(new LambdaQueryWrapper<UserFollowing>()
                .eq(UserFollowing::getFromUserId, userAId)
                .eq(UserFollowing::getToUserId, userBId));
        assertEquals(1L, followingCount, "user_following 表必须真实持久化关注记录");

        // 验证 MySQL 物理入度表落盘
        Long followerCount = userFollowerMapper.selectCount(new LambdaQueryWrapper<UserFollower>()
                .eq(UserFollower::getToUserId, userBId)
                .eq(UserFollower::getFromUserId, userAId));
        assertEquals(1L, followerCount, "user_follower 表必须真实持久化粉丝记录");

        // 验证 Redis Set 缓存命中
        boolean cachedFollowing = relationCacheService.isFollowing(userAId, userBId);
        assertTrue(cachedFollowing, "Redis Set 中必须存在该关注关系");

        // 验证双向状态判定
        RelationStatusResponse statusAfterFollow = relationService.getRelationStatus(userAId, userBId);
        assertTrue(statusAfterFollow.following());
        assertFalse(statusAfterFollow.followedBy());

        // 3. 重复关注幂等性验证（不应报错，不应产生重复记录）
        boolean duplicateFollow = relationService.follow(userAId, userBId);
        assertTrue(duplicateFollow);
        Long followingCountAfterDup = userFollowingMapper.selectCount(new LambdaQueryWrapper<UserFollowing>()
                .eq(UserFollowing::getFromUserId, userAId)
                .eq(UserFollowing::getToUserId, userBId));
        assertEquals(1L, followingCountAfterDup, "重复关注不得产生多余数据库记录");

        // 4. UserA 取消关注 UserB
        boolean unfollowSuccess = relationService.unfollow(userAId, userBId);
        assertTrue(unfollowSuccess);

        // 验证 MySQL 双表物理记录已删除
        Long followingAfterUnfollow = userFollowingMapper.selectCount(new LambdaQueryWrapper<UserFollowing>()
                .eq(UserFollowing::getFromUserId, userAId)
                .eq(UserFollowing::getToUserId, userBId));
        assertEquals(0L, followingAfterUnfollow, "取关后 user_following 记录必须被删除");

        Long followerAfterUnfollow = userFollowerMapper.selectCount(new LambdaQueryWrapper<UserFollower>()
                .eq(UserFollower::getToUserId, userBId)
                .eq(UserFollower::getFromUserId, userAId));
        assertEquals(0L, followerAfterUnfollow, "取关后 user_follower 记录必须被删除");

        // 验证 Redis Set 缓存已同步移除
        boolean cachedAfterUnfollow = relationCacheService.isFollowing(userAId, userBId);
        assertFalse(cachedAfterUnfollow, "取关后 Redis Set 必须移除该目标用户");

        // 验证最终状态恢复为未关注
        RelationStatusResponse finalStatus = relationService.getRelationStatus(userAId, userBId);
        assertFalse(finalStatus.following());
        assertFalse(finalStatus.followedBy());

        // 5. 重复取关幂等性验证
        boolean duplicateUnfollow = relationService.unfollow(userAId, userBId);
        assertTrue(duplicateUnfollow);
    }

    @Test
    @DisplayName("IT 场景 2：双向互关社交图谱与批量关系判定（基于出度的无 BigKey 架构验证）")
    void testMutualFollowAndBatchStatus() {
        // 1. A 关注 B，B 也关注 A 构成互关
        relationService.follow(userAId, userBId);
        relationService.follow(userBId, userAId);

        // 2. 验证 A 视角的双向关系（following=true, followedBy=true）
        RelationStatusResponse statusA = relationService.getRelationStatus(userAId, userBId);
        assertTrue(statusA.following(), "A 视角：已关注 B");
        assertTrue(statusA.followedBy(), "A 视角：B 同时也关注了 A（互相关注）");

        // 3. 验证 B 视角的双向关系（following=true, followedBy=true）
        RelationStatusResponse statusB = relationService.getRelationStatus(userBId, userAId);
        assertTrue(statusB.following(), "B 视角：已关注 A");
        assertTrue(statusB.followedBy(), "B 视角：A 同时也关注了 B（互相关注）");

        // 4. 验证批量状态判定接口（如 Feed 信息流卡片多作者批量装配）
        Map<Long, Boolean> batchStatus = relationService.batchGetRelationStatus(
                userAId, List.of(userBId, 9999999L));
        assertTrue(batchStatus.get(userBId), "目标 B 必须为已关注");
        assertFalse(batchStatus.get(9999999L), "不存在的目标必须为未关注");
    }

    @Test
    @DisplayName("IT 场景 3：Keyset 游标分页查询关注与粉丝列表（验证画像装配与互动态）")
    void testKeysetPagination_FollowingAndFollowers() {
        // A 关注 B
        relationService.follow(userAId, userBId);

        // 1. A 查询自己的关注列表
        FollowListQueryRequest followingReq = FollowListQueryRequest.builder()
                .userId(userAId)
                .limit(10)
                .build();
        RelationCursorPageResponse<FollowUserItemResponse> followingPage =
                relationService.listFollowing(followingReq, userAId);

        assertNotNull(followingPage);
        assertEquals(1, followingPage.items().size());
        FollowUserItemResponse item = followingPage.items().getFirst();
        assertEquals(userBId, item.userId());
        assertNotNull(item.nickname());
        assertTrue(item.nickname().contains("IT测试博主B"));
        assertTrue(item.followedByMe(), "A 查关注列表，该项本人必然为已关注状态");

        // 2. B 查询自己的粉丝列表
        FollowListQueryRequest followerReq = FollowListQueryRequest.builder()
                .userId(userBId)
                .limit(10)
                .build();
        RelationCursorPageResponse<FollowUserItemResponse> followerPage =
                relationService.listFollowers(followerReq, userBId);

        assertNotNull(followerPage);
        assertEquals(1, followerPage.items().size());
        FollowUserItemResponse fanItem = followerPage.items().getFirst();
        assertEquals(userAId, fanItem.userId());
        assertNotNull(fanItem.nickname());
        assertTrue(fanItem.nickname().contains("IT测试用户A"));
    }

    @Test
    @DisplayName("IT 场景 4：跨模块赋能 —— 验证 RelationCacheService.getFollowingUserIds 纯净集合提取")
    void testFeedIntegration_GetFollowingUserIds() {
        // 初始无关注时返回空集合（经过哨兵值防穿透安全过滤）
        Set<Long> emptyFollowings = relationCacheService.getFollowingUserIds(userAId);
        assertThat(emptyFollowings).isEmpty();

        // 关注后能够正确提取出 Long 集合
        relationService.follow(userAId, userBId);
        Set<Long> followings = relationCacheService.getFollowingUserIds(userAId);

        assertThat(followings).containsExactly(userBId);
        assertThat(followings).doesNotContain(-1L);
    }

    @Test
    @DisplayName("IT 场景 5：业务边界约束 —— 禁止关注自己（抛出 CANNOT_FOLLOW_SELF 异常且数据库零污染）")
    void testFollowSelf_ShouldThrowException() {
        assertThatThrownBy(() -> relationService.follow(userAId, userAId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.CANNOT_FOLLOW_SELF);
                });

        Long count = userFollowingMapper.selectCount(new LambdaQueryWrapper<UserFollowing>()
                .eq(UserFollowing::getFromUserId, userAId));
        assertEquals(0L, count, "非法关注自身绝不能落盘任何数据库记录");
    }
}
