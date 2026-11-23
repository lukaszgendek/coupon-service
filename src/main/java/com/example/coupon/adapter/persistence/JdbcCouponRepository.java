package com.example.coupon.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

import com.example.coupon.application.port.out.CouponRepository;
import com.example.coupon.domain.CountryCode;
import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponCode;
import com.example.coupon.domain.CouponException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcCouponRepository implements CouponRepository {

	private final JdbcClient jdbc;

	JdbcCouponRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Coupon create(CouponCode code, CountryCode country, int maxUses) {
		try {
			return jdbc.sql("""
					INSERT INTO coupon (code, country, max_uses)
					VALUES (:code, :country, :maxUses)
					RETURNING id, code, country, max_uses, used_count, created_at
					""")
					.param("code", code.value())
					.param("country", country.value())
					.param("maxUses", maxUses)
					.query(JdbcCouponRepository::mapCoupon)
					.single();
		}
		catch (DuplicateKeyException e) {
			throw new CouponException.DuplicateCode();
		}
	}

	@Override
	public Optional<Coupon> findByCode(CouponCode code) {
		return jdbc.sql("""
				SELECT id, code, country, max_uses, used_count, created_at
				FROM coupon WHERE code = :code
				""")
				.param("code", code.value())
				.query(JdbcCouponRepository::mapCoupon)
				.optional();
	}

	@Override
	@Transactional
	public void consume(long couponId, String userId) {
		// A duplicate user writes nothing and raises no database error: 0 rows means "already used".
		int inserted = jdbc.sql("""
				INSERT INTO coupon_usage (coupon_id, user_id) VALUES (:couponId, :userId)
				ON CONFLICT DO NOTHING
				""")
				.param("couponId", couponId)
				.param("userId", userId)
				.update();
		if (inserted == 0) {
			throw new CouponException.AlreadyUsed();
		}
		// The row lock taken by this UPDATE serialises concurrent requests; the WHERE clause enforces the limit.
		int slotTaken = jdbc.sql("""
				UPDATE coupon SET used_count = used_count + 1
				WHERE id = :couponId AND used_count < max_uses
				""")
				.param("couponId", couponId)
				.update();
		if (slotTaken == 0) {
			throw new CouponException.Exhausted(); // rolls back the usage row inserted above
		}
	}

	private static Coupon mapCoupon(ResultSet rs, int rowNum) throws SQLException {
		return new Coupon(
				rs.getLong("id"),
				new CouponCode(rs.getString("code")),
				new CountryCode(rs.getString("country")),
				rs.getInt("max_uses"),
				rs.getInt("used_count"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

}
