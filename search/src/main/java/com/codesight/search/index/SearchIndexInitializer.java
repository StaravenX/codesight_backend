package com.codesight.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DateProperty;
import co.elastic.clients.elasticsearch._types.mapping.IntegerNumberProperty;
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty;
import co.elastic.clients.elasticsearch._types.mapping.LongNumberProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import com.codesight.search.config.EsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 搜索索引初始化器
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SearchIndexInitializer {

    private final ElasticsearchClient es;
    private final EsProperties props;

    private static final String ANALYZER = "cjk";

    @PostConstruct
    public void ensureIndex() {
        String indexName = props.getIndex();
        try {
            boolean exists = es.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                log.info("Elasticsearch 索引 [{}] 已存在。", indexName);
                return;
            }

            es.indices().create(c -> c.index(indexName).mappings(m -> m
                    .properties("article_id", Property.of(p -> p.long_(LongNumberProperty.of(b -> b))))
                    .properties("title", Property.of(p -> p.text(TextProperty.of(b -> b.analyzer(ANALYZER)))))
                    .properties("body", Property.of(p -> p.text(TextProperty.of(b -> b.analyzer(ANALYZER)))))
                    .properties("summary", Property.of(p -> p.text(TextProperty.of(b -> b.analyzer(ANALYZER)))))
                    .properties("tags", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                    .properties("author_id", Property.of(p -> p.long_(LongNumberProperty.of(b -> b))))
                    .properties("author_avatar", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                    .properties("author_nickname", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                    .properties("cover_url", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
                    .properties("publish_time", Property.of(p -> p.date(DateProperty.of(b -> b))))
                    .properties("like_count", Property.of(p -> p.integer(IntegerNumberProperty.of(b -> b))))
                    .properties("favorite_count", Property.of(p -> p.integer(IntegerNumberProperty.of(b -> b))))
                    .properties("view_count", Property.of(p -> p.integer(IntegerNumberProperty.of(b -> b))))
                    .properties("status", Property.of(p -> p.keyword(KeywordProperty.of(b -> b))))
            ));
        } catch (Exception e) {
            log.warn("创建 Elasticsearch 索引 [{}] 失败：{}", indexName, e.getMessage());
        }
    }
}
