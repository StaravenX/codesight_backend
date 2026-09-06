package com.codesight.article.service;

import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.mapper.ArticleTagRelMapper;
import com.codesight.article.mapper.TagMapper;
import com.codesight.article.model.dto.ArticleDetailStatic;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.entity.ArticleTagRel;
import com.codesight.article.model.entity.Tag;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.common.cache.MultiLevelCacheTemplate;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArticleCacheServiceTest {

    @Mock
    private Cache<Long, ArticleDetailStatic> localCache;

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private TagMapper tagMapper;

    @Mock
    private ArticleTagRelMapper articleTagRelMapper;

    @Mock
    private MultiLevelCacheTemplate cacheTemplate;

    @InjectMocks
    private ArticleCacheService articleCacheService;

    @Test
    @DisplayName("测试获取文章静态详情 - 正常命中并组装标签")
    void testGetStaticDetail_Success() {
        Long articleId = 1001L;
        Article article = new Article();
        article.setId(articleId);
        article.setTitle("Java 21 深度实践");
        article.setStatus(ArticleStatus.PUBLISHED);

        ArticleTagRel rel = new ArticleTagRel();
        rel.setArticleId(articleId);
        rel.setTagId(10L);

        Tag tag = new Tag();
        tag.setId(10L);
        tag.setName("Java");

        when(cacheTemplate.get(eq(localCache), eq(articleId), eq("article:detail:static:1001"), eq(ArticleDetailStatic.class), any()))
                .thenAnswer(invocation -> {
                    Supplier<ArticleDetailStatic> loader = invocation.getArgument(4);
                    when(articleMapper.selectById(articleId)).thenReturn(article);
                    when(articleTagRelMapper.selectList(any())).thenReturn(List.of(rel));
                    when(tagMapper.selectByIds(any())).thenReturn(List.of(tag));
                    return loader.get();
                });

        ArticleDetailStatic detail = articleCacheService.getStaticDetail(articleId);

        assertNotNull(detail);
        assertEquals(articleId, detail.getId());
        assertEquals("Java 21 深度实践", detail.getTitle());
        assertEquals(1, detail.getTags().size());
        assertEquals("Java", detail.getTags().getFirst().name());
    }

    @Test
    @DisplayName("测试获取文章静态详情 - 文章已删除时返回 null")
    void testGetStaticDetail_Deleted() {
        Long articleId = 1002L;
        Article article = new Article();
        article.setId(articleId);
        article.setStatus(ArticleStatus.DELETED);

        when(cacheTemplate.get(eq(localCache), eq(articleId), eq("article:detail:static:1002"), eq(ArticleDetailStatic.class), any()))
                .thenAnswer(invocation -> {
                    Supplier<ArticleDetailStatic> loader = invocation.getArgument(4);
                    when(articleMapper.selectById(articleId)).thenReturn(article);
                    return loader.get();
                });

        ArticleDetailStatic detail = articleCacheService.getStaticDetail(articleId);

        assertNull(detail);
    }

    @Test
    @DisplayName("测试获取文章静态详情 - ID为空直接返回 null")
    void testGetStaticDetail_NullId() {
        assertNull(articleCacheService.getStaticDetail(null));
        verifyNoInteractions(cacheTemplate);
    }

    @Test
    @DisplayName("测试淘汰缓存 - 正确调用多级缓存双淘汰")
    void testEvictCache() {
        Long articleId = 1001L;

        articleCacheService.evictCache(articleId);

        verify(cacheTemplate).evict(eq(localCache), eq(articleId), eq("article:detail:static:1001"));
    }

    @Test
    @DisplayName("测试淘汰缓存 - ID为空不触发淘汰")
    void testEvictCache_NullId() {
        articleCacheService.evictCache(null);

        verifyNoInteractions(cacheTemplate);
    }
}
