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

import ai.spring.demo.ai.playground.data.*;
import ai.spring.demo.ai.playground.services.BookingTools.BookingDetails;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 模拟的航班预订业务系统 —— 工具背后的真实业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>内存数据持有（{@link BookingData}），启动时随机生成 5 条模拟订单（编号 101~105）</li>
 *   <li>航班预订管理：查询、修改、取消</li>
 *   <li><b>业务规则硬约束</b>：改签需提前 24 小时、取消需提前 48 小时</li>
 * </ul>
 * 设计要点：业务规则在代码里硬编码一道，与 System Prompt 里的"软约束"形成双保险——
 * LLM 是概率系统，涉及真实状态变更的操作必须有确定性代码兜底。
 */
// Note 1: ★ 模拟航班预订业务系统——工具背后的真实业务逻辑。
// 职责: 内存数据持有 (5条mock订单) + 预订管理 (查/改/取消) + 业务规则硬约束 (24h改签/48h取消)。
// ★ 设计要点: 业务规则代码硬编码, 与 System Prompt 的"软约束"双保险——
// LLM 是概率系统, 涉及真实状态变更必须有确定性代码兜底。
@Service
public class FlightBookingService {

	// Note 2: 内存数据库——持有 customers + bookings。重启丢失 (演示用)。
	private final BookingData db;

	/**
	 * 构造时初始化内存数据库并灌入模拟数据。
	 */
	public FlightBookingService() {
		db = new BookingData();

		initDemoData();
	}

	/**
	 * 随机生成5条订单放入内存存储
	 */
	private void initDemoData() {
		List<String> names = List.of("云小宝", "李千问", "张百炼", "王通义", "刘魔搭");
		List<String> airportCodes = List.of("北京", "上海", "广州", "深圳", "杭州", "南京", "青岛", "成都", "武汉", "西安", "重庆", "大连",
				"天津");
		Random random = new Random();

		var customers = new ArrayList<Customer>();
		var bookings = new ArrayList<Booking>();

		for (int i = 0; i < 5; i++) {
			String name = names.get(i);
			String from = airportCodes.get(random.nextInt(airportCodes.size()));
			String to = airportCodes.get(random.nextInt(airportCodes.size()));
			BookingClass bookingClass = BookingClass.values()[random.nextInt(BookingClass.values().length)];
			Customer customer = new Customer();
			customer.setName(name);

			LocalDate date = LocalDate.now().plusDays(2 * (i + 1));

			Booking booking = new Booking("10" + (i + 1), date, customer, BookingStatus.CONFIRMED, from, to,
					bookingClass);
			customer.getBookings().add(booking);

			customers.add(customer);
			bookings.add(booking);
		}

		// Reset the database on each start
		db.setCustomers(customers);
		db.setBookings(bookings);
	}

	/**
	 * 查询全部预订（供管理接口 {@code /api/bookings} 调用，非 LLM 工具）。
	 * @return 所有预订的详情列表
	 */
	public List<BookingDetails> getBookings() {
		return db.getBookings().stream().map(this::toBookingDetails).toList();
	}

	/**
	 * 按预订号 + 客户姓名定位订单（忽略大小写）。
	 * <p>姓名与预订号双重匹配，等价于"验明正身"——避免用户 A 查到用户 B 的订单。
	 * @throws IllegalArgumentException 预订不存在或姓名不匹配
	 */
	private Booking findBooking(String bookingNumber, String name) {
		return db.getBookings()
			.stream()
			.filter(b -> b.getBookingNumber().equalsIgnoreCase(bookingNumber))
			.filter(b -> b.getCustomer().getName().equalsIgnoreCase(name))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Booking not found"));
	}

	/**
	 * 查询单个预订详情（被 {@code getBookingDetails} 工具调用）。
	 */
	public BookingDetails getBookingDetails(String bookingNumber, String name) {
		var booking = findBooking(bookingNumber, name);
		return toBookingDetails(booking);
	}

	/**
	 * 修改预订的日期与起降地。
	 * <p><b>业务规则硬约束</b>：起飞前 24 小时内不可改签，违者抛异常。
	 * 这与 System Prompt 里"改签前确保条款允许"的软约束形成双保险——
	 * 即使 LLM 被绕过，代码层仍会拒绝。
	 * @throws IllegalArgumentException 起飞前 24 小时内
	 */
	public void changeBooking(String bookingNumber, String name, String newDate, String from, String to) {
		var booking = findBooking(bookingNumber, name);
		if (booking.getDate().isBefore(LocalDate.now().plusDays(1))) {
			throw new IllegalArgumentException("Booking cannot be changed within 24 hours of the start date.");
		}
		booking.setDate(LocalDate.parse(newDate));
		booking.setFrom(from);
		booking.setTo(to);
	}

	/**
	 * 取消预订（置状态为 {@link BookingStatus#CANCELLED}）。
	 * <p><b>业务规则硬约束</b>：起飞前 48 小时内不可取消，违者抛异常。
	 * @throws IllegalArgumentException 起飞前 48 小时内
	 */
	public void cancelBooking(String bookingNumber, String name) {
		var booking = findBooking(bookingNumber, name);
		if (booking.getDate().isBefore(LocalDate.now().plusDays(2))) {
			throw new IllegalArgumentException("Booking cannot be cancelled within 48 hours of the start date.");
		}
		booking.setBookingStatus(BookingStatus.CANCELLED);
	}

	/**
	 * 内部模型 {@link Booking} → 工具出参 {@link BookingDetails} DTO 的转换。
	 */
	private BookingDetails toBookingDetails(Booking booking) {
		return new BookingDetails(booking.getBookingNumber(), booking.getCustomer().getName(), booking.getDate(),
				booking.getBookingStatus(), booking.getFrom(), booking.getTo(), booking.getBookingClass().toString());
	}

}
