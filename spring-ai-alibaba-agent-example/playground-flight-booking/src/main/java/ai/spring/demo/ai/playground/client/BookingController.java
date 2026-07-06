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

package ai.spring.demo.ai.playground.client;


import ai.spring.demo.ai.playground.services.BookingTools.BookingDetails;
import ai.spring.demo.ai.playground.services.FlightBookingService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * 预订管理接口 + 首页控制器（非 AI 链路，供前端调试/展示用）。
 */
// Note 1: ★ 这个 Controller 和 AI 无关! 它是普通 REST 接口, 给前端展示订单列表用。
// 对比 AssistantController (AI 链路, 走 ChatClient):
//   AssistantController → CustomerSupportAssistant → ChatClient → LLM (AI 链路)
//   BookingController   → FlightBookingService → 直接查内存数据   (普通链路)
// 两条链路共用 FlightBookingService (业务层), 但 BookingController 不经过 LLM。
@Controller
@RequestMapping("/")
public class BookingController {

	private final FlightBookingService flightBookingService;

	public BookingController(FlightBookingService flightBookingService) {
		this.flightBookingService = flightBookingService;
	}

	@RequestMapping("/")
	/**
	 * 返回首页视图名（Thymeleaf 模板 index.html）。
	 */
	public String index() {
		return "index";
	}

	@RequestMapping("/api/bookings")
	@ResponseBody
	/**
	 * 返回全部预订（管理/调试用，前端表格展示）。非 AI 链路。
	 */
	public List<BookingDetails> getBookings() {
		return flightBookingService.getBookings();
	}

}
