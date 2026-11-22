package com.example.coupon;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A failing geo API must stop receiving traffic once the circuit opens; the context is not shared with other tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DirtiesContext
class GeoCircuitBreakerIT {

	@RegisterExtension
	static WireMockExtension geoApi = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	@DynamicPropertySource
	static void geoProperties(DynamicPropertyRegistry registry) {
		registry.add("coupon.geo.base-url", geoApi::baseUrl);
	}

	@Autowired
	MockMvc mvc;

	@Test
	void failingGeoApiStopsReceivingTrafficAfterTheCircuitOpens() throws Exception {
		geoApi.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(500)));
		mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"BREAKER\",\"country\":\"PL\",\"maxUses\":5}")).andExpect(status().isCreated());

		for (int i = 1; i <= 10; i++) {
			use("203.0.113." + i).andExpect(status().isServiceUnavailable());
		}
		int callsUntilOpen = geoApi.getAllServeEvents().size();

		for (int i = 11; i <= 20; i++) {
			use("203.0.113." + i).andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.error").value("COUNTRY_UNKNOWN"));
		}

		assertThat(callsUntilOpen).isEqualTo(10);
		assertThat(geoApi.getAllServeEvents()).hasSize(callsUntilOpen);
	}

	private ResultActions use(String ip) throws Exception {
		return mvc.perform(post("/api/coupons/BREAKER/usages")
				.with(request -> {
					request.setRemoteAddr(ip);
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"u-" + ip + "\"}"));
	}

}
