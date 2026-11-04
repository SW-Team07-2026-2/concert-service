package com.indie.concert.domain.booking.repository;

import com.indie.concert.domain.booking.entity.BookingSeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingSeatJpaRepository extends JpaRepository<BookingSeatEntity, BookingSeatEntity.Key>, BookingSeatRepository {
}
