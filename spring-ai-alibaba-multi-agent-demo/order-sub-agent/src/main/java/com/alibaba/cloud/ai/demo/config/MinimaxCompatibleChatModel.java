package com.alibaba.cloud.ai.demo.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.regex.Pattern;

final class MinimaxCompatibleChatModel implements ChatModel {

    private static final Pattern THINKING_BLOCK = Pattern.compile("(?is)<think>.*?</think>");

    private final ChatModel delegate;

    MinimaxCompatibleChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public String call(String message) {
        return sanitize(delegate.call(message));
    }

    @Override
    public String call(Message... messages) {
        return sanitize(delegate.call(messages));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return sanitize(delegate.call(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            ThinkingState state = new ThinkingState();
            return delegate.stream(prompt).handle((response, sink) -> {
                ChatResponse sanitized = sanitizeStreaming(response, state);
                if (hasText(sanitized) || hasToolCalls(sanitized)) {
                    sink.next(sanitized);
                }
            });
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private ChatResponse sanitize(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return response;
        }
        List<Generation> generations = response.getResults().stream()
                .map(generation -> sanitize(generation, this::sanitize))
                .toList();
        return new ChatResponse(generations, response.getMetadata());
    }

    private ChatResponse sanitizeStreaming(ChatResponse response, ThinkingState state) {
        if (response == null || response.getResults() == null) {
            return response;
        }
        List<Generation> generations = response.getResults().stream()
                .map(generation -> sanitize(generation, text -> sanitizeStreaming(text, state)))
                .toList();
        return new ChatResponse(generations, response.getMetadata());
    }

    private Generation sanitize(Generation generation, TextSanitizer sanitizer) {
        if (generation == null || generation.getOutput() == null) {
            return generation;
        }
        AssistantMessage output = generation.getOutput();
        AssistantMessage sanitizedOutput = new AssistantMessage(
                sanitizer.apply(output.getText()),
                output.getMetadata(),
                output.getToolCalls(),
                output.getMedia());
        return new Generation(sanitizedOutput, generation.getMetadata());
    }

    private boolean hasText(ChatResponse response) {
        return response != null
                && response.getResult() != null
                && response.getResult().getOutput() != null
                && response.getResult().getOutput().getText() != null
                && !response.getResult().getOutput().getText().isEmpty();
    }

    private boolean hasToolCalls(ChatResponse response) {
        return response != null
                && response.getResult() != null
                && response.getResult().getOutput() != null
                && response.getResult().getOutput().hasToolCalls();
    }

    private String sanitize(String text) {
        return text == null ? "" : THINKING_BLOCK.matcher(text).replaceAll("").trim();
    }

    private String sanitizeStreaming(String text, ThinkingState state) {
        if (text == null) {
            return "";
        }
        StringBuilder visible = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (state.inThinking) {
                int end = text.indexOf("</think>", index);
                if (end < 0) {
                    return visible.toString();
                }
                state.inThinking = false;
                index = end + "</think>".length();
            }
            else {
                int start = text.indexOf("<think>", index);
                if (start < 0) {
                    visible.append(text.substring(index));
                    break;
                }
                visible.append(text, index, start);
                state.inThinking = true;
                index = start + "<think>".length();
            }
        }
        return visible.toString();
    }

    private interface TextSanitizer {
        String apply(String text);
    }

    private static final class ThinkingState {
        private boolean inThinking;
    }
}
