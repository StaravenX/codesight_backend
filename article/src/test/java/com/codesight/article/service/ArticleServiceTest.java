package com.codesight.article.service;

import com.codesight.article.api.dto.request.ArticleCreateRequest;
import com.codesight.article.api.dto.request.ArticlePatchRequest;
import com.codesight.article.api.dto.response.ArticleCreateResponse;
import com.codesight.article.api.dto.response.ArticleDetailResponse;
import com.codesight.article.api.dto.response.ArticlePatchResponse;
import com.codesight.article.mapper.*;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.entity.ArticleTagRel;
import com.codesight.article.model.entity.Category;
import com.codesight.article.model.entity.Tag;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ArticleServiceTest {

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private TagMapper tagMapper;

    @Mock
    private ArticleTagRelMapper articleTagRelMapper;

    @Mock
    private CategoryTagRelMapper categoryTagRelMapper;

    @Mock
    private CounterService counterService;

    @InjectMocks
    private ArticleService articleService;

    private Category mockCategory;
    private Tag mockTag;

    @BeforeEach
    void setUp() {
        mockCategory = Category.builder()
                .id(1L)
                .name("后端架构")
                .slug("backend")
                .build();

        mockTag = Tag.builder()
                .id(10L)
                .name("Java")
                .build();
    }

    @Test
    @DisplayName("测试直接公开发布文章：自动 AST 提炼摘要、字数并落库")
    void testCreateArticle_DirectPublish() {
        when(categoryMapper.selectById(1L)).thenReturn(mockCategory);
        when(categoryTagRelMapper.selectCount(any())).thenReturn(1L);

        // 模拟 MyBatis-Plus insert 写入 ID
        doAnswer(invocation -> {
            Article arg = invocation.getArgument(0);
            arg.setId(1001L);
            return 1;
        }).when(articleMapper).insert(any(Article.class));

        ArticleCreateRequest request = ArticleCreateRequest.builder()
                .title("Spring Boot 3 深度实战")
                .contentMd("# 一、核心原理解析\n\n这是一个极其优秀的架构设计，支持高并发。")
                .categoryId(1L)
                .tagIds(List.of(10L))
                .isDraft(false)
                .build();

        ArticleCreateResponse response = articleService.createArticle(request, 888L);

        assertNotNull(response);
        assertEquals(1001L, response.getId());
        assertEquals(ArticleStatus.PUBLISHED, response.getStatus());

        ArgumentCaptor<Article> articleCaptor = ArgumentCaptor.forClass(Article.class);
        verify(articleMapper, times(1)).insert(articleCaptor.capture());

        Article saved = articleCaptor.getValue();
        assertEquals("Spring Boot 3 深度实战", saved.getTitle());
        assertEquals(888L, saved.getAuthorId());
        assertEquals(1L, saved.getCategoryId());
        assertTrue(saved.getSummary().contains("这是一个极其优秀的架构设计"));
        assertTrue(saved.getWordCount() > 0);
        assertTrue(saved.getReadTimeMinutes() >= 1);
        assertNotNull(saved.getToc());
        assertFalse(saved.getToc().isEmpty());
        assertEquals("一、核心原理解析", saved.getToc().getFirst().getTitle());
        assertEquals(1, saved.getToc().getFirst().getLevel());
        assertEquals(ArticleStatus.PUBLISHED, saved.getStatus());
        assertNotNull(saved.getPublishTime());

        verify(articleTagRelMapper, times(1)).insert(any(ArticleTagRel.class));
    }

    @Test
    @DisplayName("测试保存为草稿：status 为 DRAFT，publishTime 为 null")
    void testCreateArticle_SaveDraft() {
        when(categoryMapper.selectById(1L)).thenReturn(mockCategory);

        ArticleCreateRequest request = ArticleCreateRequest.builder()
                .title("草稿文章")
                .contentMd("正在写作中...")
                .categoryId(1L)
                .isDraft(true)
                .build();

        ArticleCreateResponse response = articleService.createArticle(request, 888L);

        assertNotNull(response);
        assertEquals(ArticleStatus.DRAFT, response.getStatus());

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleMapper).insert(captor.capture());
        assertEquals(ArticleStatus.DRAFT, captor.getValue().getStatus());
        assertNull(captor.getValue().getPublishTime());
    }

    @Test
    @DisplayName("测试分类不存在抛出异常")
    void testCreateArticle_CategoryNotFound() {
        when(categoryMapper.selectById(999L)).thenReturn(null);

        ArticleCreateRequest request = ArticleCreateRequest.builder()
                .title("测试")
                .contentMd("正文")
                .categoryId(999L)
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> articleService.createArticle(request, 888L));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("测试修改文章：作者本人可更新标题与发布状态")
    void testUpdateArticle_Success() {
        Article existing = Article.builder()
                .id(1001L)
                .authorId(888L)
                .title("旧标题")
                .contentMd("旧正文")
                .status(ArticleStatus.DRAFT)
                .build();
        when(articleMapper.selectById(1001L)).thenReturn(existing);

        ArticlePatchRequest patch = ArticlePatchRequest.builder()
                .title("新标题")
                .status(ArticleStatus.PUBLISHED)
                .build();

        ArticlePatchResponse response = articleService.updateArticle(1001L, patch, 888L);

        assertNotNull(response);
        assertEquals(1001L, response.getId());
        assertEquals(ArticleStatus.PUBLISHED, response.getStatus());

        verify(articleMapper, times(1)).updateById(existing);
        assertEquals("新标题", existing.getTitle());
        assertEquals(ArticleStatus.PUBLISHED, existing.getStatus());
        assertNotNull(existing.getPublishTime());
    }

    @Test
    @DisplayName("测试越权修改他人文章抛出 ARTICLE_FORBIDDEN 异常")
    void testUpdateArticle_Forbidden() {
        Article existing = Article.builder()
                .id(1001L)
                .authorId(888L)
                .build();
        when(articleMapper.selectById(1001L)).thenReturn(existing);

        ArticlePatchRequest patch = ArticlePatchRequest.builder()
                .title("黑客修改")
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> articleService.updateArticle(1001L, patch, 999L));
        assertEquals(ErrorCode.ARTICLE_FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("测试虚拟线程并发获取文章详情：聚合正文、TOC、标签、16B SDS 计数与位图状态")
    void testGetDetail_Success() {
        Article article = Article.builder()
                .id(1001L)
                .authorId(888L)
                .categoryId(1L)
                .title("架构设计实战")
                .summary("摘要内容")
                .contentMd("# 标题\n\n正文内容")
                .wordCount(500)
                .readTimeMinutes(2)
                .status(ArticleStatus.PUBLISHED)
                .build();

        when(articleMapper.selectById(1001L)).thenReturn(article);
        when(counterService.getCounts(eq(CounterSchema.EntityType.ARTICLE), eq("1001")))
                .thenReturn(Map.of(
                        CounterSchema.ArticleMetric.VIEWS, 1500L,
                        CounterSchema.ArticleMetric.LIKE, 320L,
                        CounterSchema.ArticleMetric.COMMENT, 45L,
                        CounterSchema.ArticleMetric.FAVORITE, 60L
                ));
        when(counterService.isSet(eq(CounterSchema.EntityType.ARTICLE), eq("1001"), eq(CounterSchema.ArticleMetric.LIKE), eq(100L)))
                .thenReturn(true);
        when(counterService.isSet(eq(CounterSchema.EntityType.ARTICLE), eq("1001"), eq(CounterSchema.ArticleMetric.FAVORITE), eq(100L)))
                .thenReturn(false);

        ArticleTagRel rel = ArticleTagRel.builder().articleId(1001L).tagId(10L).build();
        when(articleTagRelMapper.selectList(any())).thenReturn(List.of(rel));
        when(tagMapper.selectByIds(any())).thenReturn(List.of(mockTag));

        ArticleDetailResponse response = articleService.getDetail(1001L, 100L);

        assertNotNull(response);
        assertEquals(1001L, response.getId());
        assertEquals("架构设计实战", response.getTitle());
        assertEquals(888L, response.getAuthorId());
        assertEquals(1500L, response.getViewCount());
        assertEquals(320L, response.getLikeCount());
        assertEquals(45L, response.getCommentCount());
        assertEquals(60L, response.getFavoriteCount());
        assertTrue(response.getIsLiked());
        assertFalse(response.getIsFavorited());
        assertNotNull(response.getTags());
        assertEquals(1, response.getTags().size());
        assertEquals("Java", response.getTags().getFirst().getName());
    }

    @Test
    @DisplayName("测试获取文章详情文章不存在或已被删除抛出 404")
    void testGetDetail_NotFound() {
        when(articleMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> articleService.getDetail(999L, 100L));
        assertEquals(ErrorCode.ARTICLE_NOT_FOUND, ex.getErrorCode());
    }
}
