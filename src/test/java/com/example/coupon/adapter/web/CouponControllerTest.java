package com.example.coupon.adapter.web;

import java.util.stream.Stream;

import com.example.coupon.application.CouponService;
import com.example.coupon.domain.CountryCode;
import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponCode;
import com.example.coupon.domain.CouponException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CouponController.class)
class CouponControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	CouponService service;

	@Test
	void createReturns201WithLocationAndNormalisedCode() throws Exception {
		given(service.create(any(), any(), eq(3))).willReturn(new Coupon(1, CouponCode.of("WIOSNA"),
				CountryCode.of("PL"), 3, 0, java.time.Instant.parse("2026-11-22T10:00:00Z")));

		mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"wiosna\",\"country\":\"pl\",\"maxUses\":3}"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/coupons/WIOSNA"))
				.andExpect(jsonPath("$.code").value("WIOSNA"))
				.andExpect(jsonPath("$.usedCount").value(0));

		verify(service).create(CouponCode.of("WIOSNA"), CountryCode.of("PL"), 3);
	}

	@ParameterizedTest
	@MethodSource("invalidCreateBodies")
	void invalidCreateRequestIs400(String body) throws Exception {
		mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
	}

	static Stream<String> invalidCreateBodies() {
		return Stream.of(
				"{\"code\":\"\",\"country\":\"PL\",\"maxUses\":1}",
				"{\"code\":\"A\",\"country\":\"PL\",\"maxUses\":0}",
				"{\"code\":\"A\",\"maxUses\":1}",
				"{}");
	}

	@Test
	void malformedCodeOrCountryIs400WithErrorCode() throws Exception {
		mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"BAD CODE!\",\"country\":\"PL\",\"maxUses\":1}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("INVALID_INPUT"));
	}

	@Test
	void successfulUsageIs204AndPassesClientAddress() throws Exception {
		mvc.perform(post("/api/coupons/wiosna/usages").with(request -> {
			request.setRemoteAddr("203.0.113.7");
			return request;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"u1\"}"))
				.andExpect(status().isNoContent());

		verify(service).use(CouponCode.of("WIOSNA"), "u1", "203.0.113.7");
	}

	@ParameterizedTest
	@MethodSource("failures")
	void usageFailuresAreMappedToProblemDetails(RuntimeException failure, int httpStatus, String error)
			throws Exception {
		doThrow(failure).when(service).use(any(), any(), any());

		mvc.perform(post("/api/coupons/wiosna/usages").contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":\"u1\"}"))
				.andExpect(status().is(httpStatus))
				.andExpect(jsonPath("$.error").value(error))
				.andExpect(jsonPath("$.detail").isNotEmpty());
	}

	static Stream<Arguments> failures() {
		return Stream.of(
				Arguments.of(new CouponException.NotFound(), 404, "COUPON_NOT_FOUND"),
				Arguments.of(new CouponException.CountryNotAllowed(), 403, "COUNTRY_NOT_ALLOWED"),
				Arguments.of(new CouponException.Exhausted(), 409, "COUPON_EXHAUSTED"),
				Arguments.of(new CouponException.AlreadyUsed(), 409, "COUPON_ALREADY_USED"),
				Arguments.of(new CouponException.CountryUnknown(), 503, "COUNTRY_UNKNOWN"),
				Arguments.of(new DataAccessResourceFailureException("pool timeout"), 503, "SERVICE_OVERLOADED"));
	}

	@Test
	void duplicateCodeOnCreateIs409() throws Exception {
		given(service.create(any(), any(), eq(1))).willThrow(new CouponException.DuplicateCode());

		mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"A\",\"country\":\"PL\",\"maxUses\":1}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("COUPON_CODE_TAKEN"));
	}

	@Test
	void unknownCouponOnGetIs404() throws Exception {
		given(service.get(any())).willThrow(new CouponException.NotFound());

		mvc.perform(get("/api/coupons/nope")).andExpect(status().isNotFound());
	}

}
