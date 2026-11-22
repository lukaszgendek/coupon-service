package com.example.coupon.application;

import java.time.Instant;
import java.util.Optional;

import com.example.coupon.domain.CountryCode;
import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponCode;
import com.example.coupon.domain.CouponException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

	private static final CouponCode CODE = CouponCode.of("wiosna");

	private static final String IP = "203.0.113.7";

	@Mock
	CouponRepository coupons;

	@Mock
	GeoLocationPort geo;

	@InjectMocks
	CouponService service;

	@Test
	void unknownCouponIsNotFoundAndGeoIsNotCalled() {
		given(coupons.findByCode(CODE)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.use(CODE, "u1", IP)).isInstanceOf(CouponException.NotFound.class);

		verifyNoInteractions(geo);
		verify(coupons, never()).consume(anyLong(), anyString());
	}

	@Test
	void exhaustedCouponIsRejectedBeforeGeoLookupAndWithoutWriting() {
		given(coupons.findByCode(CODE)).willReturn(Optional.of(coupon("PL", 2, 2)));

		assertThatThrownBy(() -> service.use(CODE, "u1", IP)).isInstanceOf(CouponException.Exhausted.class);

		verifyNoInteractions(geo);
		verify(coupons, never()).consume(anyLong(), anyString());
	}

	@Test
	void requestFromOtherCountryIsRejectedWithoutWriting() {
		given(coupons.findByCode(CODE)).willReturn(Optional.of(coupon("PL", 2, 0)));
		given(geo.countryOf(IP)).willReturn(Optional.of(CountryCode.of("DE")));

		assertThatThrownBy(() -> service.use(CODE, "u1", IP)).isInstanceOf(CouponException.CountryNotAllowed.class);

		verify(coupons, never()).consume(anyLong(), anyString());
	}

	@Test
	void unknownCountryFailsClosed() {
		given(coupons.findByCode(CODE)).willReturn(Optional.of(coupon("PL", 2, 0)));
		given(geo.countryOf(IP)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.use(CODE, "u1", IP)).isInstanceOf(CouponException.CountryUnknown.class);

		verify(coupons, never()).consume(anyLong(), anyString());
	}

	@Test
	void validRequestConsumesTheCoupon() {
		given(coupons.findByCode(CODE)).willReturn(Optional.of(coupon("PL", 2, 1)));
		given(geo.countryOf(IP)).willReturn(Optional.of(CountryCode.of("PL")));

		assertThatCode(() -> service.use(CODE, "u1", IP)).doesNotThrowAnyException();

		verify(coupons).consume(7L, "u1");
	}

	@Test
	void failuresFromConsumeArePropagated() {
		given(coupons.findByCode(CODE)).willReturn(Optional.of(coupon("PL", 2, 1)));
		given(geo.countryOf(IP)).willReturn(Optional.of(CountryCode.of("PL")));
		org.mockito.Mockito.doThrow(new CouponException.AlreadyUsed()).when(coupons).consume(7L, "u1");

		assertThatThrownBy(() -> service.use(CODE, "u1", IP)).isInstanceOf(CouponException.AlreadyUsed.class);
	}

	@Test
	void codeIsNormalisedToUpperCase() {
		org.assertj.core.api.Assertions.assertThat(CouponCode.of("  wiosna ")).isEqualTo(CouponCode.of("WIOSNA"));
	}

	private static Coupon coupon(String country, int maxUses, int usedCount) {
		return new Coupon(7L, CODE, CountryCode.of(country), maxUses, usedCount, Instant.now());
	}

}
