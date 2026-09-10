package com.codesight.search;

import com.codesight.app.CodeSightApplication;
import com.codesight.article.event.ArticleEventProducer;
import com.codesight.article.event.ArticleSyncEvent;
import com.codesight.search.api.dto.request.SearchRequest;
import com.codesight.search.api.dto.response.SearchResponse;
import com.codesight.search.event.ArticleSearchKafkaConsumer;
import com.codesight.search.index.SearchIndexService;
import com.codesight.search.service.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 搜索模块集成测试
 * 验证 Spring 完整上下文装配与端到端核心业务链路
 */
@SpringBootTest(classes = CodeSightApplication.class)
public class SearchIT {

    @Autowired(required = false)
    private SearchService searchService;

    @Autowired(required = false)
    private SearchIndexService searchIndexService;

    @Autowired(required = false)
    private ArticleSearchKafkaConsumer articleSearchKafkaConsumer;

    @Autowired(required = false)
    private ArticleEventProducer articleEventProducer;

    @Test
    void testSearchBeansLoadedSuccessfully() {
        assertNotNull(searchService, "SearchService 应成功注入");
        assertNotNull(searchIndexService, "SearchIndexService 应成功注入");
        assertNotNull(articleSearchKafkaConsumer, "ArticleSearchKafkaConsumer 应成功注入");
        assertNotNull(articleEventProducer, "ArticleEventProducer 应成功注入");
    }

    @Test
    void testSearchServiceEndToEndExecution() {
        SearchRequest request = new SearchRequest("Java", 10, null);
        SearchResponse response = searchService.search(request, null);

        assertNotNull(response, "搜索服务应返回非空响应体");
        assertNotNull(response.items(), "搜索结果列表不应为 null");
    }

    @Test
    void testKafkaEventProducerAndIndexServiceExecution() {
        Long testArticleId = System.currentTimeMillis();

        // 验证 Kafka 生产者投递逻辑
        assertDoesNotThrow(() ->
                articleEventProducer.sendSyncEvent(testArticleId, ArticleSyncEvent.Action.UPSERT)
        );

        // 验证索引服务单篇调用与兜底逻辑
        assertDoesNotThrow(() ->
                searchIndexService.upsertArticle(testArticleId)
        );

        // 验证下架删除调用
        assertDoesNotThrow(() ->
                searchIndexService.deleteArticle(testArticleId)
        );
    }
}
