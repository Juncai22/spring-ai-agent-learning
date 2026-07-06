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

package ai.spring.demo.ai.playground.services;

import java.time.LocalDate;
import java.util.function.Function;

import ai.spring.demo.ai.playground.data.BookingStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.core.NestedExceptionUtils;

/**
 * 工具注册中心 —— 把三个业务操作以"函数式工具"形态注册给 ChatClient 调用。
 * <p>
 * 注册方式：<b>@Bean + {@link Function} + {@link Description}</b>，与 @Tool 注解方式对比：
 * <ul>
 *   <li>工具只做"协议层"，把请求委托给 {@link FlightBookingService}，与业务类彻底解耦（便于单测业务逻辑）</li>
 *   <li>请求/响应用 {@code record} 定义，结构即 JSON Schema，LLM 据此生成函数签名</li>
 *   <li>{@link Description} 的中文描述会作为工具说明喂给 LLM，决定 LLM 何时调用</li>
 *   <li>Bean 名（方法名）即工具名，由 {@code CustomerSupportAssistant.defaultToolNames} 按名启用</li>
 * </ul>
 * 三个工具：getBookingDetails（查）、changeBooking（改）、cancelBooking（取消）。
 */
// Note 1: ★★ BookingTools 是「工具注册中心」——把三个业务操作以 Function Bean 形态注册给 ChatClient。
//
// 注册方式: @Bean + Function + @Description (第5站学的「方式④ Function Bean」)
// 三个工具: getBookingDetails (查) / changeBooking (改) / cancelBooking (取消)
//
// ★ 设计要点:
//   - 工具只做「协议层」, 把请求委托给 FlightBookingService, 与业务类彻底解耦 (业务可独立单测)
//   - 请求/响应用 record 定义, 结构即 JSON Schema, LLM 据此生成函数签名
//   - @Description 的中文描述会喂给 LLM, 决定 LLM 何时调用
//   - Bean 名 (方法名) 即工具名, 由 CustomerSupportAssistant.defaultToolNames 按名启用
//
// ★ 异常处理要点:
//   getBookingDetails 捕获所有异常返回稀疏对象, 绝不把 Java 异常栈抛给 LLM
//   (LLM 会把栈信息当文档吞进上下文, 浪费 token 又误导推理)
//   changeBooking/cancelBooking 让异常抛出, LLM 视为工具失败据此回复用户
@Configuration
public class BookingTools {

	private static final Logger logger = LoggerFactory.getLogger(BookingTools.class);

	/** 真实业务实现，工具仅做转发，保证业务逻辑可脱离 Spring AI 独立测试。 */
	@Autowired
	private FlightBookingService flightBookingService;

	/** 工具入参：查询预订所需的两要素——预订号 + 客户姓名（用于身份校验）。 */
	public record BookingDetailsRequest(String bookingNumber, String name) {
	}

	/** 工具入参：改签请求，含新的日期与起降地。 */
	public record ChangeBookingDatesRequest(String bookingNumber, String name, String date, String from, String to) {
	}

	/** 工具入参：取消预订请求。 */
	public record CancelBookingRequest(String bookingNumber, String name) {
	}

	/**
	 * 工具出参：预订详情。{@link JsonInclude}({@link Include#NON_NULL}) 让空字段不序列化，
	 * 便于查询失败时返回稀疏对象而不暴露内部异常。
	 */
	@JsonInclude(Include.NON_NULL)
	public record BookingDetails(String bookingNumber, String name, LocalDate date, BookingStatus bookingStatus,
			String from, String to, String bookingClass) {
	}

	/**
	 * 工具：获取机票预定详细信息。
	 * <p>
	 * 委托给 {@link FlightBookingService#getBookingDetails}，工具与业务解耦。
	 * <p>
	 * 异常处理要点：捕获所有异常并返回稀疏的 {@link BookingDetails}（仅含预订号和姓名），
	 * <b>绝不把 Java 异常栈抛给 LLM</b>——否则 LLM 会把栈信息当作文档吞进上下文，
	 * 既浪费 token 又可能误导后续推理。让 LLM 收到空字段后自然地告诉用户"未找到订单"。
	 */
	// Note 2: ★ @Bean + @Description + Function——Function Bean 模式 (第5站方式④)。
	// Bean 名 "getBookingDetails" 即工具名, CustomerSupportAssistant.defaultToolNames 按此启用。
	// @Description 告诉 LLM 这个工具干啥, LLM 据此决定何时调。
	@Bean
	@Description("获取机票预定详细信息")
	public Function<BookingDetailsRequest, BookingDetails> getBookingDetails() {
		// Note 3: 返回一个 lambda (Function 实现), LLM 调工具时框架执行它。
		return request -> {
			try {
				// Note 4: 委托给业务层查订单, 工具只做转发 (解耦)。
				return flightBookingService.getBookingDetails(request.bookingNumber(), request.name());
			}
			catch (Exception e) {
				// Note 5: ★ 异常不抛给 LLM! 记日志 + 返回稀疏对象 (只含预订号和姓名, 其他字段 null)。
				// LLM 收到空字段会自然告诉用户"未找到订单", 不会看到 Java 异常栈。
				// NestedExceptionUtils.getMostSpecificCause 取最具体的异常原因便于排查。
				logger.warn("Booking details: {}", NestedExceptionUtils.getMostSpecificCause(e).getMessage());
				return new BookingDetails(request.bookingNumber(), request.name(), null, null, null, null, null);
			}
		};
	}

	/**
	 * 工具：修改机票预定日期。委托给业务层，由业务层校验"24 小时内不可改签"规则。
	 * 返回空串表示执行结果由业务层异常信号传达（异常会被 LLM 视为工具失败并据此回复用户）。
	 */
	// Note 6: changeBooking——改签工具。和 getBookingDetails 不同, 这里异常直接抛!
	// 抛出的异常会被框架捕获, LLM 视为「工具失败」, 据此回复用户 (如"24h内不可改签")。
	@Bean
	@Description("修改机票预定日期")
	public Function<ChangeBookingDatesRequest, String> changeBooking() {
		return request -> {
			// Note 7: 委托业务层改签, 业务层校验 24h 规则 (不满足抛 IllegalArgumentException)。
			flightBookingService.changeBooking(request.bookingNumber(), request.name(), request.date(), request.from(),
					request.to());
			// Note 8: 成功返回空串。LLM 看到空串知道改签成功, 会自己组织语言告诉用户。
			return "";
		};
	}

	/**
	 * 工具：取消机票预定。委托给业务层，由业务层校验"48 小时内不可取消"规则。
	 */
	// Note 9: cancelBooking——取消工具。和 changeBooking 一样, 异常直接抛给 LLM。
	@Bean
	@Description("取消机票预定")
	public Function<CancelBookingRequest, String> cancelBooking() {
		return request -> {
			// Note 10: 委托业务层取消, 业务层校验 48h 规则 (不满足抛异常)。
			flightBookingService.cancelBooking(request.bookingNumber(), request.name());
			// Note 11: 成功返回空串, LLM 据此告知用户取消成功。
			return "";
		};
	}

}
