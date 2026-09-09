package com.codesight.relation.api;

import com.codesight.relation.api.dto.request.BatchRelationStatusRequest;
import com.codesight.relation.api.dto.request.FollowListQueryRequest;
import com.codesight.relation.api.dto.response.FollowUserItemResponse;
import com.codesight.relation.api.dto.response.RelationCursorPageResponse;
import com.codesight.relation.api.dto.response.RelationStatusResponse;
import com.codesight.relation.service.RelationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RelationController 社交关系控制层接口单元与切片测试")
class RelationControllerTest {

    @Mock
    private RelationService relationService;

    @InjectMocks
    private RelationController relationController;

    private static final Long CURRENT_USER_ID = 1001L;
    private static final Long TARGET_USER_ID = 2001L;

    @Test
    @DisplayName("测试关注接口 - 参数正确透传至业务层")
    void testFollow() {
        doReturn(true).when(relationService).follow(CURRENT_USER_ID, TARGET_USER_ID);

        relationController.follow(TARGET_USER_ID, CURRENT_USER_ID);

        verify(relationService, times(1)).follow(CURRENT_USER_ID, TARGET_USER_ID);
    }

    @Test
    @DisplayName("测试取消关注接口 - 参数正确透传至业务层")
    void testUnfollow() {
        doReturn(true).when(relationService).unfollow(CURRENT_USER_ID, TARGET_USER_ID);

        relationController.unfollow(TARGET_USER_ID, CURRENT_USER_ID);

        verify(relationService, times(1)).unfollow(CURRENT_USER_ID, TARGET_USER_ID);
    }

    @Test
    @DisplayName("测试查询双向关系接口 - 正确返回关系判定响应体")
    void testGetRelationStatus() {
        RelationStatusResponse mockResponse = new RelationStatusResponse(true, true);
        when(relationService.getRelationStatus(CURRENT_USER_ID, TARGET_USER_ID)).thenReturn(mockResponse);

        RelationStatusResponse response = relationController.getRelationStatus(TARGET_USER_ID, CURRENT_USER_ID);

        assertNotNull(response);
        assertThat(response.following()).isTrue();
        assertThat(response.followedBy()).isTrue();
        verify(relationService, times(1)).getRelationStatus(CURRENT_USER_ID, TARGET_USER_ID);
    }

    @Test
    @DisplayName("测试批量关系查询接口 - 集合正确透传与响应映射")
    void testBatchGetRelationStatus() {
        List<Long> targetIds = List.of(2001L, 2002L);
        BatchRelationStatusRequest request = new BatchRelationStatusRequest(targetIds);
        Map<Long, Boolean> mockMap = Map.of(2001L, true, 2002L, false);

        when(relationService.batchGetRelationStatus(CURRENT_USER_ID, targetIds)).thenReturn(mockMap);

        Map<Long, Boolean> result = relationController.batchGetRelationStatus(request, CURRENT_USER_ID);

        assertNotNull(result);
        assertThat(result).hasSize(2);
        assertThat(result.get(2001L)).isTrue();
        assertThat(result.get(2002L)).isFalse();
        verify(relationService, times(1)).batchGetRelationStatus(CURRENT_USER_ID, targetIds);
    }

    @Test
    @DisplayName("测试游标分页查询关注列表 - 透传分页参数并返回标准游标结构")
    void testListFollowing() {
        FollowListQueryRequest request = FollowListQueryRequest.builder()
                .userId(CURRENT_USER_ID)
                .cursor("cursor_123")
                .limit(20)
                .build();

        FollowUserItemResponse item = FollowUserItemResponse.builder()
                .userId(TARGET_USER_ID)
                .nickname("极客作者")
                .avatar("https://example.com/avatar.jpg")
                .followerCount(100L)
                .followingCount(50L)
                .followedByMe(true)
                .build();

        RelationCursorPageResponse<FollowUserItemResponse> mockPage =
                new RelationCursorPageResponse<>(List.of(item), "cursor_456", true);

        when(relationService.listFollowing(any(FollowListQueryRequest.class), eq(CURRENT_USER_ID)))
                .thenReturn(mockPage);

        RelationCursorPageResponse<FollowUserItemResponse> response =
                relationController.listFollowing(request, CURRENT_USER_ID);

        assertNotNull(response);
        assertThat(response.items()).hasSize(1);
        assertThat(response.nextCursor()).isEqualTo("cursor_456");
        assertThat(response.hasMore()).isTrue();
        verify(relationService, times(1)).listFollowing(request, CURRENT_USER_ID);
    }

    @Test
    @DisplayName("测试游标分页查询粉丝列表 - 透传分页参数并返回标准游标结构")
    void testListFollowers() {
        FollowListQueryRequest request = FollowListQueryRequest.builder()
                .userId(CURRENT_USER_ID)
                .limit(20)
                .build();

        RelationCursorPageResponse<FollowUserItemResponse> mockPage =
                new RelationCursorPageResponse<>(Collections.emptyList(), null, false);

        when(relationService.listFollowers(any(FollowListQueryRequest.class), eq(CURRENT_USER_ID)))
                .thenReturn(mockPage);

        RelationCursorPageResponse<FollowUserItemResponse> response =
                relationController.listFollowers(request, CURRENT_USER_ID);

        assertNotNull(response);
        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).isFalse();
        verify(relationService, times(1)).listFollowers(request, CURRENT_USER_ID);
    }
}
