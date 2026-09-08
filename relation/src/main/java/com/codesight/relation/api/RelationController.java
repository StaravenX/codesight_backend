package com.codesight.relation.api;

import com.codesight.common.annotation.CurrentUserId;
import com.codesight.common.annotation.RateLimit;
import com.codesight.relation.api.dto.request.BatchRelationStatusRequest;
import com.codesight.relation.api.dto.request.FollowListQueryRequest;
import com.codesight.relation.api.dto.response.FollowUserItemResponse;
import com.codesight.relation.api.dto.response.RelationCursorPageResponse;
import com.codesight.relation.api.dto.response.RelationStatusResponse;
import com.codesight.relation.service.RelationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户社交关系与图谱控制器
 */
@RestController
@RequestMapping("/api/v1/relations")
@RequiredArgsConstructor
@Validated
@Tag(name = "用户关系接口", description = "提供关注、取消关注、双向关系查询及信息流批量判定接口")
public class RelationController {

    private final RelationService relationService;

    /**
     * 关注目标用户
     *
     * @param targetUserId  被关注目标用户 ID
     * @param currentUserId 当前登录用户 ID
     */
    @PostMapping("/follow/do/{targetUserId}")
    @Operation(summary = "关注用户", description = "关注目标用户，本地事务原子双写关注与粉丝表，并异步递增计数")
    @RateLimit(windowSeconds = 10, maxRequests = 20)
    public void follow(
            @Parameter(description = "被关注目标用户 ID", required = true)
            @PathVariable("targetUserId") @NotNull Long targetUserId,
            @Parameter(hidden = true) @CurrentUserId Long currentUserId) {
        relationService.follow(currentUserId, targetUserId);
    }

    /**
     * 取消关注目标用户
     *
     * @param targetUserId  被取消目标用户 ID
     * @param currentUserId 当前登录用户 ID
     */
    @DeleteMapping("/follow/undo/{targetUserId}")
    @Operation(summary = "取消关注", description = "取消对目标用户的关注，更新状态并异步扣减计数")
    @RateLimit(windowSeconds = 10, maxRequests = 20)
    public void unfollow(
            @Parameter(description = "被取消目标用户 ID", required = true)
            @PathVariable("targetUserId") @NotNull Long targetUserId,
            @Parameter(hidden = true) @CurrentUserId Long currentUserId) {
        relationService.unfollow(currentUserId, targetUserId);
    }

    /**
     * 查询当前用户与目标用户的关系
     *
     * @param targetUserId  目标用户 ID
     * @param currentUserId 当前登录用户 ID
     * @return 关系（following / followedBy）
     */
    @GetMapping("/follow/status/{targetUserId}")
    @Operation(summary = "查询关系", description = "查询双方关系（是否关注对方、对方是否关注我）")
    public RelationStatusResponse getRelationStatus(
            @Parameter(description = "目标用户 ID", required = true)
            @PathVariable("targetUserId") @NotNull Long targetUserId,
            @Parameter(hidden = true) @CurrentUserId Long currentUserId) {
        return relationService.getRelationStatus(currentUserId, targetUserId);
    }

    /**
     * 批量查询当前用户与一组目标用户的关系
     *
     * @param request       目标用户 ID 集合请求体
     * @param currentUserId 当前登录用户 ID
     * @return Map<targetUserId, isFollowing>
     */
    @PostMapping("/follow/batch-status")
    @Operation(summary = "批量查询关注状态", description = "信息流/文章卡片作者批量关系判定")
    public Map<Long, Boolean> batchGetRelationStatus(
            @Valid @RequestBody BatchRelationStatusRequest request,
            @Parameter(hidden = true) @CurrentUserId Long currentUserId) {
        return relationService.batchGetRelationStatus(currentUserId, request.targetUserIds());
    }

    /**
     * 游标分页查询指定用户的关注列表
     *
     * @param request       列表游标分页请求参数
     * @param currentUserId 当前登录用户 ID（可为空）
     * @return 关注用户列表与下一页游标
     */
    @GetMapping("/following")
    @Operation(summary = "查询关注列表", description = "基于 Keyset 游标分页拉取目标用户的关注列表")
    public RelationCursorPageResponse<FollowUserItemResponse> listFollowing(
            @Valid FollowListQueryRequest request,
            @Parameter(hidden = true) @CurrentUserId(required = false) Long currentUserId) {
        return relationService.listFollowing(request, currentUserId);
    }

    /**
     * 游标分页查询指定用户的粉丝列表
     *
     * @param request       列表游标分页请求参数
     * @param currentUserId 当前登录用户 ID（可为空）
     * @return 粉丝用户列表与下一页游标
     */
    @GetMapping("/followers")
    @Operation(summary = "查询粉丝列表", description = "基于 Keyset 游标分页拉取目标用户的粉丝列表")
    public RelationCursorPageResponse<FollowUserItemResponse> listFollowers(
            @Valid FollowListQueryRequest request,
            @Parameter(hidden = true) @CurrentUserId(required = false) Long currentUserId) {
        return relationService.listFollowers(request, currentUserId);
    }
}
