package com.example.coupon.adapter.persistence;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.List;
import java.util.stream.IntStream;

import com.example.coupon.TestcontainersConfiguration;
import com.example.coupon.application.port.out.CouponRepository;
import com.example.coupon.application.port.out.GeoLocationPort;
import com.example.coupon.domain.CountryCode;
import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponCode;
import com.example.coupon.domain.CouponException;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcCouponRepositoryIT {

	@Autowired
	CouponRepository repository;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	GeoLocationPort geo;

	@Test
	void duplicateCodeIgnoringCaseIsRejected() {
		repository.create(CouponCode.of("dup-code"), CountryCode.of("PL"), 1);

		assertThatThrownBy(() -> repository.create(CouponCode.of("DUP-CODE"), CountryCode.of("PL"), 1))
				.isInstanceOf(CouponException.DuplicateCode.class);
	}

	@Test
	void findsCouponByNormalisedCode() {
		Coupon created = repository.create(CouponCode.of("find-me"), CountryCode.of("pl"), 3);

		assertThat(repository.findByCode(CouponCode.of("FIND-ME"))).contains(created);
		assertThat(created.usedCount()).isZero();
		assertThat(created.country()).isEqualTo(CountryCode.of("PL"));
	}

	@Test
	void sameUserCannotConsumeTwiceAndSecondAttemptConsumesNoSlot() {
		Coupon coupon = repository.create(CouponCode.of("once"), CountryCode.of("PL"), 5);

		repository.consume(coupon.id(), "u1");

		assertThatThrownBy(() -> repository.consume(coupon.id(), "u1")).isInstanceOf(CouponException.AlreadyUsed.class);
		assertThat(usedCount(coupon.id())).isEqualTo(1);
	}

	@Test
	void exhaustedCouponRollsBackTheUsageRow() {
		Coupon coupon = repository.create(CouponCode.of("last-slot"), CountryCode.of("PL"), 1);
		repository.consume(coupon.id(), "u1");

		assertThatThrownBy(() -> repository.consume(coupon.id(), "u2")).isInstanceOf(CouponException.Exhausted.class);

		assertThat(usageRows(coupon.id())).isEqualTo(1);
		assertThat(usedCount(coupon.id())).isEqualTo(1);
	}

	@Test
	void concurrentUsersNeverExceedTheLimit() throws Exception {
		int maxUses = 5;
		int users = 50;
		Coupon coupon = repository.create(CouponCode.of("race"), CountryCode.of("PL"), maxUses);

		List<Outcome> outcomes = runConcurrently(IntStream.range(0, users)
				.<Callable<Outcome>>mapToObj(i -> () -> attempt(coupon.id(), "user-" + i)).toList());

		assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(maxUses);
		assertThat(outcomes.stream().filter(o -> o == Outcome.EXHAUSTED).count()).isEqualTo(users - maxUses);
		assertThat(usedCount(coupon.id())).isEqualTo(maxUses);
		assertThat(usageRows(coupon.id())).isEqualTo(maxUses);
	}

	@Test
	void concurrentRequestsOfOneUserConsumeExactlyOnce() throws Exception {
		Coupon coupon = repository.create(CouponCode.of("same-user"), CountryCode.of("PL"), 100);

		List<Outcome> outcomes = runConcurrently(IntStream.range(0, 20)
				.<Callable<Outcome>>mapToObj(i -> () -> attempt(coupon.id(), "only-user")).toList());

		assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
		assertThat(outcomes.stream().filter(o -> o == Outcome.ALREADY_USED).count()).isEqualTo(19);
		assertThat(usedCount(coupon.id())).isEqualTo(1);
	}

	private enum Outcome { OK, EXHAUSTED, ALREADY_USED }

	private Outcome attempt(long couponId, String userId) {
		try {
			repository.consume(couponId, userId);
			return Outcome.OK;
		}
		catch (CouponException.Exhausted e) {
			return Outcome.EXHAUSTED;
		}
		catch (CouponException.AlreadyUsed e) {
			return Outcome.ALREADY_USED;
		}
	}

	private static List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Outcome>> futures = tasks.stream().map(task -> pool.submit(() -> {
				start.await();
				return task.call();
			})).toList();
			start.countDown();
			return futures.stream().map(f -> {
				try {
					return f.get();
				}
				catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}).toList();
		}
		finally {
			pool.shutdownNow();
		}
	}

	private int usedCount(long couponId) {
		return jdbc.sql("SELECT used_count FROM coupon WHERE id = :id").param("id", couponId).query(Integer.class).single();
	}

	private int usageRows(long couponId) {
		return jdbc.sql("SELECT count(*) FROM coupon_usage WHERE coupon_id = :id").param("id", couponId)
				.query(Integer.class).single();
	}

}
