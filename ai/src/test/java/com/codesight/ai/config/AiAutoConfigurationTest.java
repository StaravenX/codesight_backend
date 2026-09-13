package com.codesight.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiAutoConfiguration.class);

    @Test
    @DisplayName("未提供 ChatModel 时，上下文启动失败")
    void testWithoutChatModel() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("ChatModel");
        });
    }

    @Test
    @DisplayName("存在 ChatModel 时，自动装配 ChatClient")
    void testWithChatModel_ShouldCreateChatClient() {
        ChatModel mockChatModel = Mockito.mock(ChatModel.class);

        contextRunner
                .withBean(ChatModel.class, () -> mockChatModel)
                .run(context -> assertThat(context).hasSingleBean(ChatClient.class));
    }
}
