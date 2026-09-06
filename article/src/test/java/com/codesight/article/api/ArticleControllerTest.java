package com.codesight.article.api;

import com.codesight.article.api.dto.request.ArticleCreateRequest;
import com.codesight.article.api.dto.request.ArticleFeedRequest;
import com.codesight.article.api.dto.request.ArticlePatchRequest;
import com.codesight.article.api.dto.response.*;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.FeedSortType;
import com.codesight.article.service.ArticleFeedService;
import com.codesight.article.service.ArticleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleControllerTest {

    @Mock
    private ArticleService articleService;

    @Mock
    private ArticleFeedService articleFeedService;

    @InjectMocks
    private ArticleController articleController;

    @Test
    @DisplayName("测试创建文章接口 - 控制器参数透传与响应")
    void testCreateArticle() {
        ArticleCreateRequest request = ArticleCreateRequest.builder()
                .title("Java 21 虚拟线程深入剖析")
                .contentMd("# 标题\n这是正文内容")
                .coverUrl("https://cdn.example.com/cover.jpg")
                .categoryId(1L)
                .tagIds(List.of(10L, 11L))
                .isDraft(false) // 直接发布
                .build();

        ArticleCreateResponse mockResponse = new ArticleCreateResponse(1001L, ArticleStatus.PUBLISHED);
        when(articleService.createArticle(any(ArticleCreateRequest.class), eq(2001L))).thenReturn(mockResponse);

        ArticleCreateResponse response = articleController.createArticle(request, 2001L);

        assertNotNull(response);
        assertEquals(1001L, response.id());
        assertEquals(ArticleStatus.PUBLISHED, response.status());
        verify(articleService).createArticle(request, 2001L);
    }

    @Test
    @DisplayName("测试修改文章接口 - 局部更新参数传递")
    void testUpdateArticle() {
        ArticlePatchRequest request = ArticlePatchRequest.builder()
                .title("Java 21 虚拟线程 (修订版)")
                .status(ArticleStatus.PUBLISHED)
                .build();

        ArticlePatchResponse mockResponse = new ArticlePatchResponse(1001L, ArticleStatus.PUBLISHED);
        when(articleService.updateArticle(eq(1001L), any(ArticlePatchRequest.class), eq(2001L))).thenReturn(mockResponse);

        ArticlePatchResponse response = articleController.updateArticle(1001L, request, 2001L);

        assertNotNull(response);
        assertEquals(1001L, response.id());
        assertEquals(ArticleStatus.PUBLISHED, response.status());
        verify(articleService).updateArticle(1001L, request, 2001L);
    }

    @Test
    @DisplayName("测试文章详情接口 - 详情与身份上下文传递")
    void testGetDetail() {
        ArticleDetailResponse mockResponse = ArticleDetailResponse.builder()
                .id(1001L)
                .title("Java 21 虚拟线程深入剖析")
                .contentMd("# 标题\n这是正文内容")
                .coverUrl("https://cdn.example.com/cover.jpg")
                .categoryId(1L)
                .authorId(2001L)
                .isLiked(true)
                .isFavorited(false)
                .build();

        when(articleService.getDetail(1001L, 2001L)).thenReturn(mockResponse);

        ArticleDetailResponse response = articleController.getDetail(1001L, 2001L);

        assertNotNull(response);
        assertEquals(1001L, response.getId());
        assertEquals("Java 21 虚拟线程深入剖析", response.getTitle());
        assertTrue(response.getIsLiked());
        verify(articleService).getDetail(1001L, 2001L);
    }

    @Test
    @DisplayName("测试文章信息流接口 - 分页筛选参数与结果返回")
    void testGetFeed() {
        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .sortBy(FeedSortType.RECOMMENDED)
                .size(20)
                .build();

        ArticleFeedPageResponse mockPage = new ArticleFeedPageResponse(List.of(), "next_cursor_xxx", false);
        when(articleFeedService.getFeed(any(ArticleFeedRequest.class), eq(2001L))).thenReturn(mockPage);

        ArticleFeedPageResponse response = articleController.getFeed(request, 2001L);

        assertNotNull(response);
        assertEquals("next_cursor_xxx", response.nextCursor());
        assertFalse(response.hasMore());
        verify(articleFeedService).getFeed(request, 2001L);
    }

    @Test
    @DisplayName("测试相关推荐接口 - 获取 Top-5 相关文章")
    void testGetRelated() {
        ArticleFeedItemResponse item = ArticleFeedItemResponse.builder()
                .id(1002L)
                .title("相关推荐文章")
                .summary("摘要")
                .authorId(2002L)
                .tags(List.of())
                .viewCount(10L)
                .likeCount(2L)
                .build();

        when(articleFeedService.listRelatedArticles(1001L, 2001L)).thenReturn(List.of(item));

        List<ArticleFeedItemResponse> list = articleController.getRelated(1001L, 2001L);

        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals(1002L, list.getFirst().getId());
        verify(articleFeedService).listRelatedArticles(1001L, 2001L);
    }
}
