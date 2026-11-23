package com.example.coupon;

import java.sql.Connection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import com.example.coupon.application.port.out.GeoLocationPort;
import com.example.coupon.domain.CountryCode;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL and a pool of one connection, to reach the pool timeout on the transactional path. */
@SpringBootTest(properties = { "spring.datasource.hikari.maximum-pool-size=1",
		"spring.datasource.hikari.connection-timeout=250" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PoolExhaustionIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	DataSource dataSource;

	@MockitoBean
	GeoLocationPort geo;

	@Test
	void usageIsAnsweredWith503WhenTheTransactionCannotGetAConnection() throws Exception {
		mvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"BUSY\",\"country\":\"PL\",\"maxUses\":5}")).andExpect(status().isCreated());

		// The coupon is read before the geo lookup, so taking the only connection here starves the transaction in consume.
		AtomicReference<Connection> held = new AtomicReference<>();
		given(geo.countryOf(anyString())).willAnswer(invocation -> {
			held.set(dataSource.getConnection());
			return Optional.of(CountryCode.of("PL"));
		});

		try {
			mvc.perform(post("/api/coupons/BUSY/usages").contentType(MediaType.APPLICATION_JSON)
					.content("{\"userId\":\"u1\"}"))
					.andExpect(status().isServiceUnavailable())
					.andExpect(header().string("Retry-After", "1"))
					.andExpect(jsonPath("$.error").value("SERVICE_OVERLOADED"));
		}
		finally {
			held.get().close();
		}
	}

}
