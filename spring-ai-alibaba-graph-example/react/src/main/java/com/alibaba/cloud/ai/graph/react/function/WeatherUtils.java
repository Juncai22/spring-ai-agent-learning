/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.graph.react.function;

// Note 1: WeatherUtils 是天气服务的「工具小助手」——专门处理中文城市名转拼音。
//
// 为什么需要它: 天气 API (weatherapi.com) 是国外的, 不认中文 "杭州",
// 但认拼音 "hangzhou"。所以调用 API 前要先把中文转拼音。
//
// 用了 hutool 库的 PinyinUtil, 它是 Java 里常用的工具库, 处理中文很方便。
import cn.hutool.extra.pinyin.PinyinUtil;

public class WeatherUtils {

    // Note 2: 预处理地名。如果含中文, 转成拼音; 否则原样返回。
    // 这样无论用户问 "杭州" 还是 "hangzhou", 都能正确调用 API。
    public static String preprocessLocation(String location) {
        if (containsChinese(location)) {
            // PinyinUtil.getPinyin("杭州", "") → "hangzhou"
            // 第二个参数是分隔符, 传 "" 表示不加分隔符 (默认会有空格)。
            return PinyinUtil.getPinyin(location, "");
        }
        return location;
    }

    // Note 3: 判断字符串是否含中文字符。
    // 正则 [一-龥] 匹配 Unicode 中的 CJK 统一汉字范围 (一=一, 龥=龥)。
    // ".*[一-龥].*" 表示「任意字符 + 至少一个汉字 + 任意字符」。
    // 这是检测中文的常用写法。
    public static boolean containsChinese(String str) {
        return str.matches(".*[一-龥].*");
    }

}
