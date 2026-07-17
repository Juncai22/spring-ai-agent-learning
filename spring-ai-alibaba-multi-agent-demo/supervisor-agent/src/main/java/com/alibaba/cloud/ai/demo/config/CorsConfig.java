/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * ============================================
 * 跨域配置（CORS）
 * ============================================
 *
 * 【为什么需要 CORS 配置】
 * 前端（Vue.js，运行在 localhost:3000）和后端（Spring Boot，运行在 localhost:10008）
 * 是不同的源（协议+域名+端口），浏览器默认禁止跨域请求。
 * 需要后端配置 CORS 允许前端访问。
 *
 * 【两种配置方式】
 * 1. 实现 WebMvcConfigurer.addCorsMappings() —— 简单配置
 * 2. 注册 CorsConfigurationSource Bean —— 更细粒度的控制
 * 这里同时使用了两种方式，确保在所有场景下都能正确处理跨域请求。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 方式 1：通过 WebMvcConfigurer 配置全局 CORS
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")                    // 对所有路径生效
                .allowedOriginPatterns("*")            // 允许所有来源（使用 patterns 而非 origins，支持 credentials）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")  // 允许的 HTTP 方法
                .allowedHeaders("*")                   // 允许所有请求头
                .allowCredentials(true)                // 允许携带凭证（Cookie/Authorization）
                .maxAge(3600);                         // 预检请求缓存时间（秒）
    }

    /**
     * 方式 2：通过 CorsConfigurationSource Bean 配置
     * 配合 Spring Security 或其他需要 CorsConfigurationSource 的组件使用
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 使用 allowedOriginPatterns 而不是 allowedOrigins
        // 因为 allowedOrigins 与 allowCredentials(true) 不能同时使用 "*"
        configuration.setAllowedOriginPatterns(Arrays.asList("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}