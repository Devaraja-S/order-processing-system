package com.peerislands.orders.repository;

import com.peerislands.orders.domain.Order;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface OrderRepository extends MongoRepository<Order, String>, OrderRepositoryCustom {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);
}
