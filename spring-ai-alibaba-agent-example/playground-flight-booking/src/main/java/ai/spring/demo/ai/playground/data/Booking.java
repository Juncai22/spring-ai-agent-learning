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

import java.time.LocalDate;

/**
 * 预订实体（内部业务模型，与工具出参 {@code BookingDetails} 区分）。
 * 由 {@link ai.spring.demo.ai.playground.services.FlightBookingService} 在内存中持有，
 * 工具调用时转换成 DTO 返回给 LLM。
 */
public class Booking {

	/** 预订号，唯一标识一笔订单（模拟数据为 101~105）。 */
	private String bookingNumber;

	/** 起飞日期。业务规则的校验基准（24h 改签 / 48h 取消都基于它）。 */
	private LocalDate date;

	/** （保留字段）返程/截止日期，当前流程未使用。 */
	private LocalDate bookingTo;

	/** 所属客户。 */
	private Customer customer;

	/** 出发地。 */
	private String from;

	/** 目的地。 */
	private String to;

	/** 预订状态。 */
	private BookingStatus bookingStatus;

	/** 舱位等级（影响改签/取消费用，见条款文档）。 */
	private BookingClass bookingClass;

	public Booking(String bookingNumber, LocalDate date, Customer customer, BookingStatus bookingStatus, String from,
			String to, BookingClass bookingClass) {
		this.bookingNumber = bookingNumber;
		this.date = date;
		this.customer = customer;
		this.bookingStatus = bookingStatus;
		this.from = from;
		this.to = to;
		this.bookingClass = bookingClass;
	}

	public String getBookingNumber() {
		return bookingNumber;
	}

	public void setBookingNumber(String bookingNumber) {
		this.bookingNumber = bookingNumber;
	}

	public LocalDate getDate() {
		return date;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}

	public LocalDate getBookingTo() {
		return bookingTo;
	}

	public void setBookingTo(LocalDate bookingTo) {
		this.bookingTo = bookingTo;
	}

	public Customer getCustomer() {
		return customer;
	}

	public void setCustomer(Customer customer) {
		this.customer = customer;
	}

	public BookingStatus getBookingStatus() {
		return bookingStatus;
	}

	public void setBookingStatus(BookingStatus bookingStatus) {
		this.bookingStatus = bookingStatus;
	}

	public String getFrom() {
		return from;
	}

	public void setFrom(String from) {
		this.from = from;
	}

	public String getTo() {
		return to;
	}

	public void setTo(String to) {
		this.to = to;
	}

	public BookingClass getBookingClass() {
		return bookingClass;
	}

	public void setBookingClass(BookingClass bookingClass) {
		this.bookingClass = bookingClass;
	}

}
