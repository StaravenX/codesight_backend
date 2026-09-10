package com.codesight.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.mapper.ArticleTagRelMapper;
import com.codesight.article.mapper.TagMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.entity.ArticleTagRel;
import com.codesight.article.model.entity.Tag;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.search.config.EsProperties;
import com.codesight.user.UserBaseInfo;
import com.codesight.user.UserCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchIndexServiceTest {

    @Mock
    private ElasticsearchClient es;

    @Mock
    private EsProperties props;

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ArticleTagRelMapper articleTagRelMapper;

    @Mock
    private TagMapper tagMapper;

    @Mock
    private UserCacheService userCacheService;

    @Mock
    private CounterService counterService;

    @InjectMocks
    private SearchIndexService searchIndexService;

    @BeforeEach
    void setUp() {
        lenient().when(props.getIndex()).thenReturn("codesight_article_index");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldUpsertPublishedPublicArticleSuccessfully() throws IOException {
        Long articleId = 1001L;
        Article article = Article.builder()
                .id(articleId)
                .title("Elasticsearch架构解析")
                .contentMd("正文内容")
                .summary("摘要内容")
                .authorId(888L)
                .status(ArticleStatus.PUBLISHED)
                .visible(ArticleVisible.PUBLIC)
                .publishTime(Instant.now())
                .viewCount(100L)
                .build();

        when(articleMapper.selectById(articleId)).thenReturn(article);

        // Mock 标签关联
        ArticleTagRel rel = ArticleTagRel.builder().articleId(articleId).tagId(201L).build();
        when(articleTagRelMapper.selectList(any())).thenReturn(List.of(rel));

        Tag tag = Tag.builder().id(201L).name("ES").build();
        when(tagMapper.selectByIds(Set.of(201L))).thenReturn(List.of(tag));

        // Mock 作者画像
        UserBaseInfo author = new UserBaseInfo(888L, "搜索架构师", "https://img.example.com/avatar.png", "简介", "架构师", "Codesight");
        when(userCacheService.getUserBaseInfo(888L)).thenReturn(author);

        // Mock 计数
        when(counterService.getCounts(eq(CounterSchema.EntityType.ARTICLE), eq(String.valueOf(articleId))))
                .thenReturn(Map.of(
                        CounterSchema.ArticleMetric.LIKE, 30L,
                        CounterSchema.ArticleMetric.FAVORITE, 10L,
                        CounterSchema.ArticleMetric.VIEWS, 600L
                ));

        IndexResponse mockIndexResponse = mock(IndexResponse.class);
        doReturn(mockIndexResponse).when(es).index(any(Function.class));

        searchIndexService.upsertArticle(articleId);

        verify(es, times(1)).index(any(Function.class));
        verify(es, never()).delete(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDeleteWhenArticleIsDraftOrOffline() throws IOException {
        Long articleId = 1001L;
        Article draftArticle = Article.builder()
                .id(articleId)
                .status(ArticleStatus.DRAFT)
                .visible(ArticleVisible.PUBLIC)
                .build();

        when(articleMapper.selectById(articleId)).thenReturn(draftArticle);

        DeleteResponse mockDeleteResponse = mock(DeleteResponse.class);
        doReturn(mockDeleteResponse).when(es).delete(any(Function.class));

        searchIndexService.upsertArticle(articleId);

        verify(es, times(1)).delete(any(Function.class));
        verify(es, never()).index(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDeleteWhenArticleIsNotPublic() throws IOException {
        Long articleId = 1001L;
        Article privateArticle = Article.builder()
                .id(articleId)
                .status(ArticleStatus.PUBLISHED)
                .visible(ArticleVisible.PRIVATE)
                .build();

        when(articleMapper.selectById(articleId)).thenReturn(privateArticle);

        DeleteResponse mockDeleteResponse = mock(DeleteResponse.class);
        doReturn(mockDeleteResponse).when(es).delete(any(Function.class));

        searchIndexService.upsertArticle(articleId);

        verify(es, times(1)).delete(any(Function.class));
        verify(es, never()).index(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDeleteWhenArticleNotFound() throws IOException {
        Long articleId = 9999L;
        when(articleMapper.selectById(articleId)).thenReturn(null);

        DeleteResponse mockDeleteResponse = mock(DeleteResponse.class);
        doReturn(mockDeleteResponse).when(es).delete(any(Function.class));

        searchIndexService.upsertArticle(articleId);

        verify(es, times(1)).delete(any(Function.class));
    }

    @Test
    void shouldIgnoreNullArticleId() {
        searchIndexService.upsertArticle(null);
        searchIndexService.deleteArticle(null);

        verifyNoInteractions(articleMapper);
        verifyNoInteractions(es);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipBackfillWhenIndexHasDocuments() throws IOException {
        CountResponse mockCountResponse = mock(CountResponse.class);
        when(mockCountResponse.count()).thenReturn(100L);
        doReturn(mockCountResponse).when(es).count(any(Function.class));

        searchIndexService.ensureBackfill();

        verify(es, times(1)).count(any(Function.class));
        verify(articleMapper, never()).selectList(any());
        verify(es, never()).bulk(any(BulkRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBackfillWhenIndexIsEmpty() throws IOException {
        CountResponse mockCountResponse = mock(CountResponse.class);
        when(mockCountResponse.count()).thenReturn(0L);
        doReturn(mockCountResponse).when(es).count(any(Function.class));

        Article a1 = Article.builder()
                .id(1L)
                .title("文章1")
                .authorId(888L)
                .status(ArticleStatus.PUBLISHED)
                .visible(ArticleVisible.PUBLIC)
                .build();

        // 第一次拉取返回 1 篇，第二次拉取返回空列表以终止循环
        when(articleMapper.selectList(any()))
                .thenReturn(List.of(a1))
                .thenReturn(List.of());

        when(userCacheService.batchGetUserBaseInfo(any())).thenReturn(Map.of());
        when(articleTagRelMapper.selectList(any())).thenReturn(List.of());
        when(counterService.batchGetCounts(any(), any())).thenReturn(Map.of());

        BulkResponse mockBulkResponse = mock(BulkResponse.class);
        doReturn(mockBulkResponse).when(es).bulk(any(BulkRequest.class));

        searchIndexService.ensureBackfill();

        verify(es, times(1)).count(any(Function.class));
        verify(articleMapper, times(2)).selectList(any());
        verify(es, times(1)).bulk(any(BulkRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCatchExceptionGracefullyDuringBackfill() throws IOException {
        doThrow(new RuntimeException("ES连接异常")).when(es).count(any(Function.class));

        assertDoesNotThrow(() -> searchIndexService.ensureBackfill());
    }
}
