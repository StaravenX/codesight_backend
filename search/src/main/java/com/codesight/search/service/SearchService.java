package com.codesight.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.NamedValue;
import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import com.codesight.article.api.dto.response.TagResponse;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.search.api.dto.request.SearchRequest;
import com.codesight.search.api.dto.response.SearchResponse;
import com.codesight.search.config.EsProperties;
import com.codesight.search.index.ArticleSearchDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 全文检索服务：
 * - 基于 Elasticsearch 的多字段召回与权重打分
 * - 关键词高亮与 search_after 游标分页
 * - 用户点赞状态装配
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final ElasticsearchClient es;
    private final EsProperties props;
    private final CounterService counterService;

    /**
     * 关键词全文检索
     *
     * @param request               搜索请求参数
     * @param currentUserIdNullable 当前登录用户 ID，未登录时为 null
     * @return 搜索结果分页数据
     */
    public SearchResponse search(SearchRequest request, Long currentUserIdNullable) {
        String q = request.q();
        int size = request.size();
        String after = request.after();
        String indexName = props.getIndex();
        List<FieldValue> afterValues = parseAfter(after);

        // 复合稳定排序：相关性评分优先，其次发布时间、点赞数，最后按文章 ID 兜底
        List<SortOptions> sorts = new ArrayList<>();
        sorts.add(SortOptions.of(s -> s.score(o -> o.order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("publish_time").order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("like_count").order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("article_id").order(SortOrder.Desc))));

        // 1. 基础条件：多字段宽召回（标题权重3、标签权重2）+ 仅检索公开发布状态
        Query boolQuery = Query.of(q1 -> q1.bool(b -> b
                .must(m -> m.multiMatch(mm -> mm.query(q).fields("title^3", "tags^2", "summary", "body")))
                .filter(f -> f.term(t -> t.field("status").value(v -> v.stringValue("published"))))
        ));

        // 2. 互动加权：在基础条件上对点赞数和浏览量进行 log1p 平滑提权
        Query scoreQuery = Query.of(q2 -> q2.functionScore(fs -> fs
                .query(boolQuery)
                .functions(fn -> fn.fieldValueFactor(fvf -> fvf.field("like_count").modifier(FieldValueFactorModifier.Log1p)).weight(2.0))
                .functions(fn -> fn.fieldValueFactor(fvf -> fvf.field("view_count").modifier(FieldValueFactorModifier.Log1p)).weight(1.0))
                .boostMode(FunctionBoostMode.Sum)
        ));

        try {
            var resp = es.search(s -> {
                s.index(indexName)
                        .size(size)
                        .query(scoreQuery)
                        .highlight(h -> h.fields(
                                NamedValue.of("title", HighlightField.of(hf -> hf)),
                                NamedValue.of("summary", HighlightField.of(hf -> hf)),
                                NamedValue.of("body", HighlightField.of(hf -> hf))
                        ))
                        .sort(sorts);

                if (afterValues != null && !afterValues.isEmpty()) {
                    s.searchAfter(afterValues);
                }
                return s;
            }, ArticleSearchDoc.class);

            List<Hit<ArticleSearchDoc>> hits = (resp.hits() == null || resp.hits().hits() == null)
                    ? Collections.emptyList()
                    : resp.hits().hits();

            List<String> articleIdStrs = hits.stream()
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .map(ArticleSearchDoc::articleId)
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toList();

            // 1. 批量从 SDS 获取实时计数
            Map<String, Map<CounterSchema.MetricItem, Long>> countsMap = Collections.emptyMap();
            if (!articleIdStrs.isEmpty()) {
                try {
                    countsMap = counterService.batchGetCounts(CounterSchema.EntityType.ARTICLE, articleIdStrs);
                } catch (Exception e) {
                    log.warn("获取实时计数失败: {}", e.getMessage());
                }
            }

            // 2. 批量查询当前登录用户的点赞状态
            Map<String, Boolean> isLikedMap = Collections.emptyMap();
            if (currentUserIdNullable != null && currentUserIdNullable > 0 && !articleIdStrs.isEmpty()) {
                try {
                    isLikedMap = counterService.batchIsSet(
                            CounterSchema.EntityType.ARTICLE,
                            articleIdStrs,
                            CounterSchema.ArticleMetric.LIKE,
                            currentUserIdNullable
                    );
                } catch (Exception e) {
                    log.warn("获取用户点赞状态失败: {}", e.getMessage());
                }
            }

            // 组装响应数据
            List<ArticleFeedItemResponse> items = new ArrayList<>(hits.size());
            for (Hit<ArticleSearchDoc> hit : hits) {
                ArticleSearchDoc doc = hit.source();
                if (doc == null || doc.articleId() == null) {
                    continue;
                }
                String idStr = String.valueOf(doc.articleId());
                String snippet = buildSnippet(hit);
                String displaySummary = (snippet != null && !snippet.isBlank()) ? snippet : doc.summary();

                List<TagResponse> tagResponses = doc.tags() != null
                        ? doc.tags().stream().map(t -> new TagResponse(0L, t)).toList()
                        : List.of();

                Instant publishInstant = doc.publishTime() != null
                        ? Instant.ofEpochMilli(doc.publishTime())
                        : Instant.now();

                Map<CounterSchema.MetricItem, Long> counts = countsMap.getOrDefault(idStr, Collections.emptyMap());
                long likeCount = counts.getOrDefault(CounterSchema.ArticleMetric.LIKE, doc.likeCount() != null ? doc.likeCount() : 0L);
                long favoriteCount = counts.getOrDefault(CounterSchema.ArticleMetric.FAVORITE, doc.favoriteCount() != null ? doc.favoriteCount() : 0L);
                long viewCount = counts.getOrDefault(CounterSchema.ArticleMetric.VIEWS, doc.viewCount() != null ? doc.viewCount() : 0L);
                long commentCount = counts.getOrDefault(CounterSchema.ArticleMetric.COMMENT, 0L);
                boolean isLiked = Boolean.TRUE.equals(isLikedMap.get(idStr));

                ArticleFeedItemResponse item = ArticleFeedItemResponse.builder()
                        .id(doc.articleId())
                        .title(doc.title())
                        .summary(displaySummary)
                        .coverUrl(doc.coverUrl())
                        .authorId(doc.authorId())
                        .authorName(doc.authorNickname() != null ? doc.authorNickname() : "知识作者")
                        .tags(tagResponses)
                        .publishTime(publishInstant)
                        .likeCount(likeCount)
                        .collectCount(favoriteCount)
                        .viewCount(viewCount)
                        .commentCount(commentCount)
                        .isLiked(isLiked)
                        .build();

                items.add(item);
            }

            String nextAfter = buildAfter(hits);
            boolean hasMore = items.size() >= size;
            return new SearchResponse(items, nextAfter, hasMore);
        } catch (Exception e) {
            log.error("搜索失败: {}", e.getMessage(), e);
            return new SearchResponse(Collections.emptyList(), null, false);
        }
    }

    /**
     * 解析游标值为 FieldValue 列表
     */
    private List<FieldValue> parseAfter(String after) {
        if (after == null || after.isBlank()) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(after), StandardCharsets.UTF_8).split(",");
            List<FieldValue> out = new ArrayList<>(parts.length);
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                out.add(i == 0 ? FieldValue.of(Double.parseDouble(p)) : FieldValue.of(Long.parseLong(p)));
            }
            return out;
        } catch (Exception e) {
            log.warn("解析游标失败: {}", after);
            return null;
        }
    }

    /**
     * 生成游标
     */
    private String buildAfter(List<Hit<ArticleSearchDoc>> hits) {
        // 生成下一页游标
        String nextAfter = null;
        if (!hits.isEmpty()) {
            List<FieldValue> sv = hits.getLast().sort();
            if (sv != null && !sv.isEmpty()) {
                String raw = sv.stream().map(v -> String.valueOf(v._get())).collect(Collectors.joining(","));
                nextAfter = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            }
        }
        return nextAfter;
    }

    /**
     * 合并高亮命中片段为展示摘要
     */
    private String buildSnippet(Hit<ArticleSearchDoc> hit) {
        if (hit.highlight() == null) return null;
        String snippet = Stream.of("title", "summary", "body")
                .map(k -> hit.highlight().get(k))
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.joining(" "));
        return snippet.isBlank() ? null : snippet;
    }
}
