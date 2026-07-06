/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.spring.demo.ai.playground.data;

/**
 * 舱位等级枚举（影响改签/取消费用，对应条款文档）。
 */
public enum BookingClass {

	/** 经济舱：改签 $50、取消 $75。 */
	ECONOMY,
	/** 超级经济舱：改签 $30、取消 $50。 */
	PREMIUM_ECONOMY,
	/** 商务舱：改签免费、取消 $25。 */
	BUSINESS

}
