package com.codesight.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.article.api.dto.request.ArticleCreateRequest;
import com.codesight.article.api.dto.request.ArticlePatchRequest;
import com.codesight.article.api.dto.response.ArticleCreateResponse;
import com.codesight.article.api.dto.response.ArticleDetailResponse;
import com.codesight.article.api.dto.response.ArticlePatchResponse;
import com.codesight.article.mapper.*;
import com.codesight.article.model.dto.ArticleDetailStatic;
import com.codesight.article.model.entity.*;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import com.codesight.article.util.MarkdownParseResult;
import com.codesight.article.util.MarkdownParser;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 文章核心业务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;
    private final ArticleTagRelMapper articleTagRelMapper;
    private final CategoryTagRelMapper categoryTagRelMapper;
    private final CounterService counterService;
    private final ArticleCacheService articleCacheService;
    private final RecommendRankService recommendRankService;

    @Transactional(rollbackFor = Exception.class)
    public ArticleCreateResponse createArticle(ArticleCreateRequest request, Long authorId) {

        // 1. 校验一级技术分类是否存在
        Category category = categoryMapper.selectById(request.categoryId());
        if (category == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }

        // 2. 校验关联标签（若提供）
        validateTagIds(request.tagIds(), request.categoryId());

        // 3. 调用 AST 引擎提炼摘要、字数、预估阅读时长与目录树
        MarkdownParseResult parseResult = MarkdownParser.parse(request.contentMd());
        String summary = (request.summary() != null && !request.summary().isBlank())
                ? request.summary().trim()
                : parseResult.getSummary();
        int wordCount = parseResult.getWordCount();
        int readTimeMinutes = parseResult.getReadTimeMinutes();

        // 4. 判定草稿还是直接公开发布
        boolean isDraft = Boolean.TRUE.equals(request.isDraft());
        ArticleStatus status = isDraft ? ArticleStatus.DRAFT : ArticleStatus.PUBLISHED;
        Instant now = Instant.now();
        Instant publishTime = isDraft ? null : now;

        // 5. 构建并插入文章主表（预计算字段全量存盘）
        Article article = Article.builder()
                .authorId(authorId)
                .categoryId(request.categoryId())
                .title(request.title().trim())
                .summary(summary)
                .coverUrl(request.coverUrl())
                .contentMd(request.contentMd())
                .wordCount(wordCount)
                .readTimeMinutes(readTimeMinutes)
                .toc(parseResult.getToc())
                .viewCount(0L)
                .isTop(false)
                .visible(ArticleVisible.PUBLIC)
                .status(status)
                .publishTime(publishTime)
                .build();

        articleMapper.insert(article);
        Long articleId = article.getId();

        // 6. 保存标签多对多关联关系
        saveArticleTags(articleId, request.tagIds());

        // 7. 若公开发布，加入推荐候选池
        if (status == ArticleStatus.PUBLISHED && article.getVisible() == ArticleVisible.PUBLIC) {
            recommendRankService.addOrIncrScore(articleId, 0.0);
        }

        log.info("文章创建成功: articleId={}, authorId={}, status={}", articleId, authorId, status);

        return new ArticleCreateResponse(articleId, status);
    }

    @Transactional(rollbackFor = Exception.class)
    public ArticlePatchResponse updateArticle(Long articleId, ArticlePatchRequest request, Long authorId) {
        // 1. 检查文章是否存在
        Article article = articleMapper.selectById(articleId);
        if (article == null || article.getStatus() == ArticleStatus.DELETED) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }

        // 2. 权限校验：只允许作者本人修改
        if (!article.getAuthorId().equals(authorId)) {
            throw new BusinessException(ErrorCode.ARTICLE_FORBIDDEN);
        }

        // 3. 分类校验（若修改分类）
        if (request.categoryId() != null) {
            Category category = categoryMapper.selectById(request.categoryId());
            if (category == null) {
                throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
            }
            article.setCategoryId(request.categoryId());
        }

        // 4. 标题更新
        if (request.title() != null && !request.title().isBlank()) {
            article.setTitle(request.title().trim());
        }

        // 5. 正文更新（自动重新计算 AST 字数、阅读时长、TOC 目录树与摘要）
        if (request.contentMd() != null) {
            article.setContentMd(request.contentMd());
            MarkdownParseResult parseResult = MarkdownParser.parse(request.contentMd());
            article.setWordCount(parseResult.getWordCount());
            article.setReadTimeMinutes(parseResult.getReadTimeMinutes());
            article.setToc(parseResult.getToc());
            if (request.summary() == null || request.summary().isBlank()) {
                article.setSummary(parseResult.getSummary());
            }
        }

        // 6. 显式摘要更新
        if (request.summary() != null && !request.summary().isBlank()) {
            article.setSummary(request.summary().trim());
        }

        // 7. 封面、置顶、可见性更新
        if (request.coverUrl() != null) {
            article.setCoverUrl(request.coverUrl());
        }
        if (request.isTop() != null) {
            article.setIsTop(request.isTop());
        }
        if (request.visible() != null) {
            article.setVisible(request.visible());
        }

        // 8. 状态流转处理（草稿首次公开发布记录 publishTime）
        if (request.status() != null) {
            if (article.getStatus() == ArticleStatus.DRAFT && request.status() == ArticleStatus.PUBLISHED) {
                article.setPublishTime(Instant.now());
                recommendRankService.addOrIncrScore(articleId, 0.0);
            } else if (request.status() == ArticleStatus.OFFLINE || request.status() == ArticleStatus.DELETED) {
                recommendRankService.removeArticle(articleId);
            }
            article.setStatus(request.status());
        }

        // 9. 更新文章标签关联关系
        if (request.tagIds() != null) {
            validateTagIds(request.tagIds(), article.getCategoryId());
            articleTagRelMapper.delete(new LambdaQueryWrapper<ArticleTagRel>()
                    .eq(ArticleTagRel::getArticleId, articleId));
            saveArticleTags(articleId, request.tagIds());
        }

        articleMapper.updateById(article);
        // 更新数据库后，删除缓存
        articleCacheService.evictCache(articleId);
        log.info("文章更新成功: articleId={}, authorId={}, status={}", articleId, authorId, article.getStatus());

        return new ArticlePatchResponse(articleId, article.getStatus());
    }

    /**
     * 获取文章详情（虚拟线程并发聚合）
     *
     * @param id     目标文章 ID
     * @param userId 当前登录用户 ID（可为空）
     * @return 文章详情全量响应体
     */
    public ArticleDetailResponse getDetail(Long id, Long userId) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 1. 获取静态元数据
            Future<ArticleDetailStatic> staticFuture = executor.submit(() ->
                    articleCacheService.getStaticDetail(id)
            );

            // 2. 获取实时计数
            Future<Map<CounterSchema.MetricItem, Long>> countsFuture = executor.submit(() ->
                    counterService.getCounts(CounterSchema.EntityType.ARTICLE, String.valueOf(id))
            );

            // 3. 获取互动状态
            Future<Boolean> isLikedFuture = executor.submit(() ->
                    (userId != null && userId > 0) &&
                    counterService.isSet(CounterSchema.EntityType.ARTICLE, String.valueOf(id), CounterSchema.ArticleMetric.LIKE, userId)
            );
            Future<Boolean> isFavoritedFuture = executor.submit(() ->
                    (userId != null && userId > 0) &&
                    counterService.isSet(CounterSchema.EntityType.ARTICLE, String.valueOf(id), CounterSchema.ArticleMetric.FAVORITE, userId)
            );

            // 获取静态数据并校验存在性
            ArticleDetailStatic staticDto = staticFuture.get();
            if (staticDto == null) {
                throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND, "文章不存在或已被删除");
            }

            // 获取计数与互动状态
            Map<CounterSchema.MetricItem, Long> counts = countsFuture.get();
            Boolean isLiked = isLikedFuture.get();
            Boolean isFavorited = isFavoritedFuture.get();

            // 组合结果
            ArticleDetailResponse response = new ArticleDetailResponse();
            BeanUtils.copyProperties(staticDto, response);

            response.setViewCount(counts.getOrDefault(CounterSchema.ArticleMetric.VIEWS, 0L));
            response.setLikeCount(counts.getOrDefault(CounterSchema.ArticleMetric.LIKE, 0L));
            response.setCommentCount(counts.getOrDefault(CounterSchema.ArticleMetric.COMMENT, 0L));
            response.setFavoriteCount(counts.getOrDefault(CounterSchema.ArticleMetric.FAVORITE, 0L));
            response.setIsLiked(isLiked);
            response.setIsFavorited(isFavorited);

            return response;

        } catch (InterruptedException | ExecutionException e) {
            if (e.getCause() instanceof BusinessException be) {
                throw be;
            }
            log.error("获取文章详情并发聚合失败: articleId={}", id, e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "获取文章详情失败");
        }
    }

    /**
     * 校验传入的标签 ID 列表合法性
     */
    private void validateTagIds(List<Long> tagIds, Long categoryId) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        if (tagIds.size() > 5) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文章关联标签最多不超过5个");
        }
        if (categoryId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文章所属技术分类不能为空");
        }

        Long validCount = categoryTagRelMapper.selectCount(new LambdaQueryWrapper<CategoryTagRel>()
                .eq(CategoryTagRel::getCategoryId, categoryId)
                .in(CategoryTagRel::getTagId, tagIds));

        if (validCount == null || validCount != tagIds.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "存在非法或不属于当前分类的技术标签");
        }
    }

    /**
     * 批量插入文章与标签关联关系
     */
    private void saveArticleTags(Long articleId, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        for (Long tagId : tagIds) {
            ArticleTagRel rel = ArticleTagRel.builder()
                    .articleId(articleId)
                    .tagId(tagId)
                    .build();
            articleTagRelMapper.insert(rel);
        }
    }
}
