package com.example.coupon;

import java.util.Optional;

import com.example.coupon.application.GeoLocationPort;
import com.example.coupon.domain.CountryCode;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Whole stack on a real PostgreSQL; only the external geo service is replaced. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CouponApiIT {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	GeoLocationPort geo;

	@Test
	void fullCouponLifecycle() throws Exception {
		given(geo.countryOf("203.0.113.7")).willReturn(Optional.of(CountryCode.of("PL")));
		given(geo.countryOf("198.51.100.9")).willReturn(Optional.of(CountryCode.of("DE")));

		mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"Wiosna\",\"country\":\"PL\",\"maxUses\":2}"))
				.andExpect(status().isCreated());

		// same code in another case is the same coupon
		mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"WIOSNA\",\"country\":\"PL\",\"maxUses\":5}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("COUPON_CODE_TAKEN"));

		use("wiosna", "u1", "203.0.113.7").andExpect(status().isNoContent());
		use("WIOSNA", "u1", "203.0.113.7").andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("COUPON_ALREADY_USED"));
		use("wiosna", "u2", "198.51.100.9").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("COUNTRY_NOT_ALLOWED"));
		use("wiosna", "u2", "203.0.113.7").andExpect(status().isNoContent());
		use("wiosna", "u3", "203.0.113.7").andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("COUPON_EXHAUSTED"));
		use("nope", "u1", "203.0.113.7").andExpect(status().isNotFound());

		mvc.perform(get("/api/coupons/wiosna")).andExpect(status().isOk())
				.andExpect(jsonPath("$.usedCount").value(2)).andExpect(jsonPath("$.maxUses").value(2));
	}

	private org.springframework.test.web.servlet.ResultActions use(String code, String userId, String ip)
			throws Exception {
		RequestPostProcessor from = request -> {
			request.setRemoteAddr(ip);
			return request;
		};
		return mvc.perform(post("/api/coupons/" + code + "/usages").with(from)
				.contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + userId + "\"}"));
	}

}
