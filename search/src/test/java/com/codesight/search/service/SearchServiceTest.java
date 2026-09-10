package com.codesight.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.search.api.dto.request.SearchRequest;
import com.codesight.search.config.EsProperties;
import com.codesight.search.index.ArticleSearchDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private ElasticsearchClient es;

    @Mock
    private EsProperties props;

    @Mock
    private CounterService counterService;

    @InjectMocks
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        lenient().when(props.getIndex()).thenReturn("codesight_article_index");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSearchSuccessfullyWithHighlightAndHydration() throws IOException {
        SearchRequest request = new SearchRequest("Java", 20, null);
        Long currentUserId = 123L;

        ArticleSearchDoc doc = ArticleSearchDoc.builder()
                .articleId(1001L)
                .title("Java并发编程实战")
                .summary("深入解析并发")
                .tags(List.of("Java", "JUC"))
                .authorId(888L)
                .authorNickname("架构师")
                .coverUrl("https://img.example.com/cover.png")
                .publishTime(1700000000000L)
                .likeCount(5L)
                .favoriteCount(2L)
                .viewCount(100L)
                .status("published")
                .build();

        Hit<ArticleSearchDoc> mockHit = mock(Hit.class);
        when(mockHit.source()).thenReturn(doc);
        when(mockHit.highlight()).thenReturn(Map.of("summary", List.of("深入解析<em>Java</em>并发")));
        when(mockHit.sort()).thenReturn(List.of(
                FieldValue.of(2.5),
                FieldValue.of(1700000000000L),
                FieldValue.of(5L),
                FieldValue.of(1001L)
        ));

        HitsMetadata<ArticleSearchDoc> mockHitsMetadata = mock(HitsMetadata.class);
        when(mockHitsMetadata.hits()).thenReturn(List.of(mockHit));

        SearchResponse<ArticleSearchDoc> mockEsResponse = mock(SearchResponse.class);
        when(mockEsResponse.hits()).thenReturn(mockHitsMetadata);

        doReturn(mockEsResponse).when(es).search(any(Function.class), eq(ArticleSearchDoc.class));

        // Mock 计数中台返回实时数据
        when(counterService.batchGetCounts(eq(CounterSchema.EntityType.ARTICLE), eq(List.of("1001"))))
                .thenReturn(Map.of("1001", Map.of(
                        CounterSchema.ArticleMetric.LIKE, 20L,
                        CounterSchema.ArticleMetric.VIEWS, 500L,
                        CounterSchema.ArticleMetric.FAVORITE, 10L,
                        CounterSchema.ArticleMetric.COMMENT, 8L
                )));

        // Mock 用户点赞态
        when(counterService.batchIsSet(
                eq(CounterSchema.EntityType.ARTICLE),
                eq(List.of("1001")),
                eq(CounterSchema.ArticleMetric.LIKE),
                eq(currentUserId)
        )).thenReturn(Map.of("1001", true));

        com.codesight.search.api.dto.response.SearchResponse response = searchService.search(request, currentUserId);

        assertNotNull(response);
        assertEquals(1, response.items().size());
        ArticleFeedItemResponse item = response.items().getFirst();

        assertEquals(1001L, item.getId());
        assertEquals("Java并发编程实战", item.getTitle());
        assertEquals("深入解析<em>Java</em>并发", item.getSummary()); // 验证高亮切片覆盖原摘要
        assertEquals(20L, item.getLikeCount()); // 验证实时计数覆盖 ES 快照
        assertEquals(500L, item.getViewCount());
        assertEquals(10L, item.getCollectCount());
        assertEquals(8L, item.getCommentCount());
        assertTrue(item.getIsLiked()); // 验证点赞水合

        assertNotNull(response.after());
        assertFalse(response.hasMore()); // items.size() < size(20)
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSupportPaginationWithCursor() throws IOException {
        // 构造合法的 Base64 游标：2.5,1700000000000,5,1002
        String cursorRaw = "2.5,1700000000000,5,1002";
        String after = Base64.getUrlEncoder().withoutPadding().encodeToString(cursorRaw.getBytes(StandardCharsets.UTF_8));

        SearchRequest request = new SearchRequest("Java", 1, after);

        ArticleSearchDoc doc = ArticleSearchDoc.builder()
                .articleId(1002L)
                .title("第二篇文章")
                .summary("内容")
                .authorId(888L)
                .build();

        Hit<ArticleSearchDoc> mockHit = mock(Hit.class);
        when(mockHit.source()).thenReturn(doc);
        when(mockHit.highlight()).thenReturn(null);
        when(mockHit.sort()).thenReturn(List.of(
                FieldValue.of(1.8),
                FieldValue.of(1690000000000L),
                FieldValue.of(2L),
                FieldValue.of(1002L)
        ));

        HitsMetadata<ArticleSearchDoc> mockHitsMetadata = mock(HitsMetadata.class);
        when(mockHitsMetadata.hits()).thenReturn(List.of(mockHit));

        SearchResponse<ArticleSearchDoc> mockEsResponse = mock(SearchResponse.class);
        when(mockEsResponse.hits()).thenReturn(mockHitsMetadata);

        doReturn(mockEsResponse).when(es).search(any(Function.class), eq(ArticleSearchDoc.class));

        com.codesight.search.api.dto.response.SearchResponse response = searchService.search(request, null);

        assertNotNull(response);
        assertEquals(1, response.items().size());
        assertTrue(response.hasMore()); // items.size() (1) >= size (1)
        assertNotNull(response.after());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldHandleEmptyResultsGracefully() throws IOException {
        SearchRequest request = new SearchRequest("不存在的关键词", 20, null);

        HitsMetadata<ArticleSearchDoc> mockHitsMetadata = mock(HitsMetadata.class);
        when(mockHitsMetadata.hits()).thenReturn(List.of());

        SearchResponse<ArticleSearchDoc> mockEsResponse = mock(SearchResponse.class);
        when(mockEsResponse.hits()).thenReturn(mockHitsMetadata);

        doReturn(mockEsResponse).when(es).search(any(Function.class), eq(ArticleSearchDoc.class));

        com.codesight.search.api.dto.response.SearchResponse response = searchService.search(request, null);

        assertNotNull(response);
        assertTrue(response.items().isEmpty());
        assertNull(response.after());
        assertFalse(response.hasMore());
        verifyNoInteractions(counterService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldHandleUnauthenticatedUser() throws IOException {
        SearchRequest request = new SearchRequest("Java", 20, null);

        ArticleSearchDoc doc = ArticleSearchDoc.builder()
                .articleId(1001L)
                .title("Java并发")
                .summary("内容")
                .authorId(888L)
                .build();

        Hit<ArticleSearchDoc> mockHit = mock(Hit.class);
        when(mockHit.source()).thenReturn(doc);
        when(mockHit.highlight()).thenReturn(null);
        when(mockHit.sort()).thenReturn(null);

        HitsMetadata<ArticleSearchDoc> mockHitsMetadata = mock(HitsMetadata.class);
        when(mockHitsMetadata.hits()).thenReturn(List.of(mockHit));

        SearchResponse<ArticleSearchDoc> mockEsResponse = mock(SearchResponse.class);
        when(mockEsResponse.hits()).thenReturn(mockHitsMetadata);

        doReturn(mockEsResponse).when(es).search(any(Function.class), eq(ArticleSearchDoc.class));

        com.codesight.search.api.dto.response.SearchResponse response = searchService.search(request, null);

        assertNotNull(response);
        assertEquals(1, response.items().size());
        assertFalse(response.items().getFirst().getIsLiked()); // 未登录默认 false
        verify(counterService, never()).batchIsSet(any(), any(), any(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldFallbackWhenEsThrowsException() throws IOException {
        SearchRequest request = new SearchRequest("Java", 20, null);
        doThrow(new RuntimeException("ES连接超时")).when(es).search(any(Function.class), eq(ArticleSearchDoc.class));

        com.codesight.search.api.dto.response.SearchResponse response = searchService.search(request, 123L);

        assertNotNull(response);
        assertTrue(response.items().isEmpty());
        assertNull(response.after());
        assertFalse(response.hasMore());
    }
}
