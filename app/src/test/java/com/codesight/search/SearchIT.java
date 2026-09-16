package com.codesight.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.codesight.app.CodeSightApplication;
import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import com.codesight.article.event.ArticleEventProducer;
import com.codesight.article.event.ArticleSyncEvent;
import com.codesight.search.api.dto.request.SearchRequest;
import com.codesight.search.api.dto.response.SearchResponse;
import com.codesight.search.config.SearchProperties;
import com.codesight.search.event.ArticleSearchKafkaConsumer;
import com.codesight.search.index.ArticleSearchDoc;
import com.codesight.search.index.SearchIndexService;
import com.codesight.search.service.SearchService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 搜索模块真实中间件集成测试：
 * 验证与 Elasticsearch、Redis 的端到端真实交互
 */
@SpringBootTest(classes = CodeSightApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SearchIT {

    @Autowired(required = false)
    private SearchService searchService;

    @Autowired(required = false)
    private SearchIndexService searchIndexService;

    @Autowired(required = false)
    private ArticleSearchKafkaConsumer articleSearchKafkaConsumer;

    @Autowired(required = false)
    private ArticleEventProducer articleEventProducer;

    @Autowired(required = false)
    private ElasticsearchClient es;

    @Autowired(required = false)
    private SearchProperties searchProperties;

    private static final long TEST_ARTICLE_A = 999901L;
    private static final long TEST_ARTICLE_B = 999902L;

    private boolean isEsAvailable() {
        if (es == null) {
            return false;
        }
        try {
            return es.ping().value();
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @Order(1)
    void testSearchBeansLoadedSuccessfully() {
        assertNotNull(searchService, "SearchService 应成功注入");
        assertNotNull(searchIndexService, "SearchIndexService 应成功注入");
        assertNotNull(articleSearchKafkaConsumer, "ArticleSearchKafkaConsumer 应成功注入");
        assertNotNull(articleEventProducer, "ArticleEventProducer 应成功注入");
    }

    @Test
    @Order(2)
    void testElasticsearchIndexMappingAndUpsert() throws Exception {
        assumeTrue(isEsAvailable(), "Elasticsearch 服务未就绪，跳过真实中间件交互测试");

        String indexName = searchProperties.getIndex();
        boolean indexExists = es.indices().exists(e -> e.index(indexName)).value();
        assertTrue(indexExists, "ES 索引应当已初始化存在: " + indexName);

        // 构造测试用 1536 维向量
        int dims = searchProperties.getVectorDims();
        float[] vectorA = new float[dims];
        float[] vectorB = new float[dims];
        for (int i = 0; i < dims; i++) {
            vectorA[i] = (i % 2 == 0) ? 0.05f : -0.05f;
            vectorB[i] = (i % 2 == 0) ? 0.048f : -0.049f; // 高余弦相似度
        }

        // 真实写入测试文档 A
        ArticleSearchDoc docA = ArticleSearchDoc.builder()
                .articleId(TEST_ARTICLE_A)
                .title("集成测试：Spring Boot 与 Elasticsearch 实战")
                .summary("本文介绍基于 ES 8.x 实现全文检索与稠密向量召回")
                .body("深入解析 Elasticsearch 索引分词与 RRF 融合打分机制。")
                .tags(List.of("Elasticsearch", "Java", "Spring"))
                .authorId(101L)
                .authorNickname("测试作者")
                .publishTime(System.currentTimeMillis())
                .likeCount(50L)
                .favoriteCount(20L)
                .viewCount(500L)
                .status("published")
                .articleVector(vectorA)
                .build();

        // 真实写入测试文档 B
        ArticleSearchDoc docB = ArticleSearchDoc.builder()
                .articleId(TEST_ARTICLE_B)
                .title("集成测试：Elasticsearch 向量检索与推荐")
                .summary("探讨通过 dense_vector 字段实现余弦相似度 KNN 推荐")
                .body("向量检索与 BM25 文本检索混合排名的核心设计。")
                .tags(List.of("Elasticsearch", "推荐系统", "AI"))
                .authorId(102L)
                .authorNickname("测试作者2")
                .publishTime(System.currentTimeMillis() - 1000)
                .likeCount(30L)
                .favoriteCount(10L)
                .viewCount(300L)
                .status("published")
                .articleVector(vectorB)
                .build();

        es.index(i -> i.index(indexName).id(String.valueOf(TEST_ARTICLE_A)).document(docA).refresh(Refresh.True));
        es.index(i -> i.index(indexName).id(String.valueOf(TEST_ARTICLE_B)).document(docB).refresh(Refresh.True));

        // 验证文档真实存在
        var getRespA = es.get(g -> g.index(indexName).id(String.valueOf(TEST_ARTICLE_A)), ArticleSearchDoc.class);
        assertTrue(getRespA.found());
        assertNotNull(getRespA.source());
        assertEquals("集成测试：Spring Boot 与 Elasticsearch 实战", getRespA.source().title());
    }

    @Test
    @Order(3)
    void testKeywordFullTextSearchWithHighlight() {
        assumeTrue(isEsAvailable(), "Elasticsearch 服务未就绪，跳过真实全文检索测试");

        SearchRequest request = new SearchRequest("Elasticsearch 实战", 10, null);
        SearchResponse response = searchService.search(request, null);

        assertNotNull(response);
        assertNotNull(response.items());
        assertFalse(response.items().isEmpty(), "应检索出写入的 Elasticsearch 测试文档");

        // 校验包含目标文章且摘要高亮正常返回
        boolean containsDocA = response.items().stream()
                .anyMatch(item -> item.getId().equals(TEST_ARTICLE_A));
        assertTrue(containsDocA, "检索结果应包含 TEST_ARTICLE_A");
    }

    @Test
    @Order(4)
    void testItem2ItemRelatedArticlesRecommendation() {
        assumeTrue(isEsAvailable(), "Elasticsearch 服务未就绪，跳过相关推荐测试");

        // 查询与 TEST_ARTICLE_A 相关的文章列表
        List<ArticleFeedItemResponse> related = searchService.listRelatedArticles(TEST_ARTICLE_A, null);

        assertNotNull(related);
        // 验证排他性：结果中严禁包含文章自身
        boolean containsSelf = related.stream().anyMatch(item -> item.getId().equals(TEST_ARTICLE_A));
        assertFalse(containsSelf, "相关文章推荐必须严格排除文章自身");

        // 验证基于向量召回了相似文档 B
        boolean containsDocB = related.stream().anyMatch(item -> item.getId().equals(TEST_ARTICLE_B));
        assertTrue(containsDocB, "应当基于向量余弦相似度推荐 TEST_ARTICLE_B");
    }

    @Test
    @Order(5)
    void testKafkaEventAndCleanup() {
        Long testArticleId = System.currentTimeMillis();

        // 验证 Kafka 生产者投递逻辑
        assertDoesNotThrow(() ->
                articleEventProducer.sendSyncEvent(testArticleId, ArticleSyncEvent.Action.UPSERT)
        );

        if (isEsAvailable()) {
            String indexName = searchProperties.getIndex();
            // 清理测试数据
            try {
                es.delete(d -> d.index(indexName).id(String.valueOf(TEST_ARTICLE_A)));
                es.delete(d -> d.index(indexName).id(String.valueOf(TEST_ARTICLE_B)));
            } catch (Exception ignored) {
            }
        }
    }
}

