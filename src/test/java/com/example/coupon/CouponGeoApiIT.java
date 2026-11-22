package com.example.coupon;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole application on a real PostgreSQL, calling a real HTTP geo API stub (WireMock) through the production
 * HTTP client: parsing, timeouts and single-flight are exercised end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CouponGeoApiIT {

	@RegisterExtension
	static WireMockExtension geoApi = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	@DynamicPropertySource
	static void geoProperties(DynamicPropertyRegistry registry) {
		registry.add("coupon.geo.base-url", geoApi::baseUrl);
		registry.add("coupon.geo.timeout", () -> "2s");
	}

	@Autowired
	MockMvc mvc;

	@Test
	void countryFromGeoApiDecidesAccess() throws Exception {
		stubCountry("203.0.113.10", "PL");
		stubCountry("198.51.100.10", "DE");
		createCoupon("GEO-ACCESS", "PL", 5);

		use("GEO-ACCESS", "u1", "203.0.113.10").andExpect(status().isNoContent());
		use("GEO-ACCESS", "u2", "198.51.100.10").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("COUNTRY_NOT_ALLOWED"));

		geoApi.verify(1, getRequestedFor(urlPathEqualTo("/203.0.113.10")));
	}

	@Test
	void geoApiSayingItDoesNotKnowTheAddressIsRejected() throws Exception {
		geoApi.stubFor(get(urlPathEqualTo("/10.0.0.20")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json").withBody("{\"success\":false}")));
		createCoupon("GEO-UNKNOWN", "PL", 5);

		use("GEO-UNKNOWN", "u1", "10.0.0.20").andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error").value("COUNTRY_UNKNOWN"));
	}

	@Test
	void slowGeoApiTimesOutAndIsRejected() throws Exception {
		geoApi.stubFor(get(urlPathEqualTo("/203.0.113.30")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json")
				.withBody("{\"success\":true,\"country_code\":\"PL\"}").withFixedDelay(4000)));
		createCoupon("GEO-SLOW", "PL", 5);

		use("GEO-SLOW", "u1", "203.0.113.30").andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error").value("COUNTRY_UNKNOWN"));
	}

	@Test
	void parallelRequestsFromOneAddressCauseASingleGeoCall() throws Exception {
		geoApi.stubFor(get(urlPathEqualTo("/203.0.113.40")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json")
				.withBody("{\"success\":true,\"country_code\":\"PL\"}").withFixedDelay(300)));
		createCoupon("GEO-PARALLEL", "PL", 100);

		try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<Integer>> statuses = IntStream.range(0, 50)
					.mapToObj(i -> callers.submit(() -> use("GEO-PARALLEL", "user-" + i, "203.0.113.40")
							.andReturn().getResponse().getStatus()))
					.toList();
			for (Future<Integer> status : statuses) {
				assertThat(status.get()).isEqualTo(204);
			}
		}

		geoApi.verify(1, getRequestedFor(urlPathEqualTo("/203.0.113.40")));
	}

	private void stubCountry(String ip, String country) {
		geoApi.stubFor(get(urlPathEqualTo("/" + ip)).willReturn(aResponse()
				.withHeader("Content-Type", "application/json")
				.withBody("{\"success\":true,\"country_code\":\"" + country + "\"}")));
	}

	private void createCoupon(String code, String country, int maxUses) throws Exception {
		mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"%s\",\"country\":\"%s\",\"maxUses\":%d}".formatted(code, country, maxUses)))
				.andExpect(status().isCreated());
	}

	private ResultActions use(String code, String userId, String ip) throws Exception {
		return mvc.perform(post("/api/coupons/" + code + "/usages")
				.with(request -> {
					request.setRemoteAddr(ip);
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + userId + "\"}"));
	}

}
