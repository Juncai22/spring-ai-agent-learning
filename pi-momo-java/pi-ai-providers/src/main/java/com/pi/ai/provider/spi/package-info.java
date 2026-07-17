/**
 * ApiProvider SPI（服务提供者接口）及流式函数定义。
 *
 * <p>该包定义了 AI 模型提供者的 SPI 接口，是 pi-momo 框架中 Provider 插件的核心契约。
 * 所有 AI 模型提供者（如 Anthropic、OpenAI、Google Gemini、Mistral 等）都必须实现
 * {@link com.pi.ai.core.registry.ApiProvider} 接口，通过 SPI 机制注册到框架中。
 *
 * <p>主要职责：
 * <ul>
 *   <li>定义 {@link com.pi.ai.core.registry.ApiProvider} 接口——所有 Provider 的公共契约</li>
 *   <li>提供流式（Streaming）函数定义，支持 Server-Sent Events (SSE) 协议</li>
 *   <li>支持 Provider 的运行时发现和按需加载</li>
 *   <li>通过 {@link com.pi.ai.core.registry.ApiProviderRegistry} 实现 Provider 的统一注册与管理</li>
 * </ul>
 *
 * <p>设计模式：
 * <ul>
 *   <li>SPI（Service Provider Interface）：允许第三方实现自定义 Provider</li>
 *   <li>策略模式：每个 Provider 封装了与特定 AI API 的通信逻辑</li>
 *   <li>工厂模式：通过 {@code ApiProviderRegistry} 根据 API 标识获取对应的 Provider 实例</li>
 * </ul>
 */
package com.pi.ai.provider.spi;
