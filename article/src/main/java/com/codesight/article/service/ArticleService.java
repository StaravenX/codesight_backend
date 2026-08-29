package com.codesight.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.article.api.dto.request.ArticleCreateRequest;
import com.codesight.article.api.dto.request.ArticlePatchRequest;
import com.codesight.article.api.dto.response.ArticleCreateResponse;
import com.codesight.article.api.dto.response.ArticlePatchResponse;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.mapper.ArticleTagRelMapper;
import com.codesight.article.mapper.CategoryMapper;
import com.codesight.article.mapper.TagMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.entity.ArticleTagRel;
import com.codesight.article.model.entity.Category;
import com.codesight.article.model.entity.Tag;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import com.codesight.article.util.MarkdownParseResult;
import com.codesight.article.util.MarkdownParser;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 文章核心业务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final ArticleTagRelMapper articleTagRelMapper;

    @Transactional(rollbackFor = Exception.class)
    public ArticleCreateResponse createArticle(ArticleCreateRequest request, Long authorId) {

        // 1. 校验一级技术分类是否存在
        Category category = categoryMapper.selectById(request.getCategoryId());
        if (category == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }

        // 2. 校验关联标签（若提供）
        validateTagIds(request.getTagIds());

        // 3. 调用 AST 引擎提炼摘要与字数
        MarkdownParseResult parseResult = MarkdownParser.parse(request.getContentMd());
        String summary = (request.getSummary() != null && !request.getSummary().isBlank())
                ? request.getSummary().trim()
                : parseResult.getSummary();
        int wordCount = parseResult.getWordCount();

        // 4. 判定草稿还是直接公开发布
        boolean isDraft = Boolean.TRUE.equals(request.getIsDraft());
        ArticleStatus status = isDraft ? ArticleStatus.DRAFT : ArticleStatus.PUBLISHED;
        Instant now = Instant.now();
        Instant publishTime = isDraft ? null : now;

        // 5. 构建并插入文章主表
        Article article = Article.builder()
                .authorId(authorId)
                .categoryId(request.getCategoryId())
                .title(request.getTitle().trim())
                .summary(summary)
                .coverUrl(request.getCoverUrl())
                .contentMd(request.getContentMd())
                .wordCount(wordCount)
                .viewCount(0L)
                .isTop(false)
                .visible(ArticleVisible.PUBLIC)
                .status(status)
                .publishTime(publishTime)
                .build();

        articleMapper.insert(article);
        Long articleId = article.getId();

        // 6. 保存标签多对多关联关系
        saveArticleTags(articleId, request.getTagIds());

        log.info("文章创建成功: articleId={}, authorId={}, status={}", articleId, authorId, status);

        return ArticleCreateResponse.builder()
                .id(articleId)
                .status(status)
                .build();
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
        if (request.getCategoryId() != null) {
            Category category = categoryMapper.selectById(request.getCategoryId());
            if (category == null) {
                throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
            }
            article.setCategoryId(request.getCategoryId());
        }

        // 4. 标题更新
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            article.setTitle(request.getTitle().trim());
        }

        // 5. 正文更新（自动重新计算 AST 字数与摘要）
        if (request.getContentMd() != null) {
            article.setContentMd(request.getContentMd());
            MarkdownParseResult parseResult = MarkdownParser.parse(request.getContentMd());
            article.setWordCount(parseResult.getWordCount());
            if (request.getSummary() == null || request.getSummary().isBlank()) {
                article.setSummary(parseResult.getSummary());
            }
        }

        // 6. 显式摘要更新
        if (request.getSummary() != null && !request.getSummary().isBlank()) {
            article.setSummary(request.getSummary().trim());
        }

        // 7. 封面、置顶、可见性更新
        if (request.getCoverUrl() != null) {
            article.setCoverUrl(request.getCoverUrl());
        }
        if (request.getIsTop() != null) {
            article.setIsTop(request.getIsTop());
        }
        if (request.getVisible() != null) {
            article.setVisible(request.getVisible());
        }

        // 8. 状态流转处理（草稿首次公开发布记录 publishTime）
        if (request.getStatus() != null) {
            if (article.getStatus() == ArticleStatus.DRAFT && request.getStatus() == ArticleStatus.PUBLISHED) {
                article.setPublishTime(Instant.now());
            }
            article.setStatus(request.getStatus());
        }

        articleMapper.updateById(article);

        // 9. 更新标签关联关系（若提供 tagIds）
        if (request.getTagIds() != null) {
            validateTagIds(request.getTagIds());
            articleTagRelMapper.delete(new LambdaQueryWrapper<ArticleTagRel>()
                    .eq(ArticleTagRel::getArticleId, articleId));
            saveArticleTags(articleId, request.getTagIds());
        }

        log.info("文章更新成功: articleId={}, authorId={}, status={}", articleId, authorId, article.getStatus());

        return ArticlePatchResponse.builder()
                .id(articleId)
                .status(article.getStatus())
                .build();
    }

    /**
     * 校验传入的标签 ID 列表合法性
     */
    private void validateTagIds(List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        if (tagIds.size() > 5) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        List<Tag> tags = tagMapper.selectByIds(tagIds);
        if (tags.size() != tagIds.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
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
