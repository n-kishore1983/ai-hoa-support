package com.learning.advisors;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

import java.util.logging.Logger;

@Component
public class TokenUsageAdvisor implements CallAdvisor {

    private final Logger LOGGER = Logger.getLogger(TokenUsageAdvisor.class.getName());
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(chatClientRequest);
        Usage usage = chatClientResponse.chatResponse().getMetadata().getUsage();
        LOGGER.info("Token usage for the call: " + usage.getPromptTokens() + " prompt tokens, " + usage.getCompletionTokens() + " completion tokens, " + usage.getTotalTokens() + " total tokens.");
        return chatClientResponse;
    }

    @Override
    public String getName() {
        return "TokenUsageAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
