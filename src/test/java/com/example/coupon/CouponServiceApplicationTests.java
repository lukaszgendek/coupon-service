package com.example.coupon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CouponServiceApplicationTests {

	@MockitoBean
	GeoLocationPort geo;

	@Test
	void contextLoads() {
	}

}
