package com.codesight.counter;

import com.codesight.app.CodeSightApplication;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = CodeSightApplication.class)
public class CounterIT {

    @Autowired
    private CounterService counterService;

    @Test
    void testLikeIntegrationFlow() throws InterruptedException {
        CounterSchema.EntityType entityType = CounterSchema.EntityType.ARTICLE;
        String entityId = String.valueOf(System.currentTimeMillis());
        long userId = 999L;

        boolean liked = counterService.toggle(entityType, entityId, CounterSchema.ArticleMetric.LIKE, userId, true);
        assertTrue(liked);

        boolean stateInBitmap = counterService.isSet(entityType, entityId, CounterSchema.ArticleMetric.LIKE, userId);
        assertTrue(stateInBitmap);

        Thread.sleep(2000);

        Map<CounterSchema.MetricItem, Long> counts = counterService.getCounts(entityType, entityId);
        long likeCount = counts.getOrDefault(CounterSchema.ArticleMetric.LIKE, 0L);
        assertEquals(1L, likeCount);
    }
}
