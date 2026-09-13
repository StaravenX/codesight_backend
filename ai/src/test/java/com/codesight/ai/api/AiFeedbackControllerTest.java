package com.codesight.ai.api;

import com.codesight.ai.service.ArticleVectorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiFeedbackControllerTest {

    @Mock
    private ArticleVectorService articleVectorService;

    @InjectMocks
    private AiFeedbackController aiFeedbackController;

    @Test
    @DisplayName("测试用户负反馈：正确调用 recordFeedback")
    void testDislikeArticle() {
        Long userId = 999L;
        Long articleId = 12345L;

        aiFeedbackController.dislikeArticle(userId, articleId);

        verify(articleVectorService).recordFeedback(ArticleVectorService.FeedbackType.NEGATIVE, userId, articleId);
    }
}
