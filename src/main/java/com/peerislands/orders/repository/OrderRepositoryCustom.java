package com.peerislands.orders.repository;

import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** MongoTemplate-based queries that derived repository methods cannot express. */
public interface OrderRepositoryCustom {

    /**
     * Keyset page of orders, newest first ({@code createdAt DESC, _id DESC}). Never uses skip/offset.
     *
     * @param status optional status filter (null = all)
     * @param after  optional cursor; only orders strictly after it in sort order are returned
     * @param limit  maximum number of orders to return
     */
    List<Order> findPage(OrderStatus status, OrderCursor after, int limit);

    /**
     * Conditional atomic status change: one findAndModify filtered on {@code {_id: id, status: expected}}
     * that sets {@code status} and {@code updatedAt} and increments {@code version}.
     *
     * @return the updated order, or empty if no order with that id is currently in {@code expected}
     */
    Optional<Order> transitionStatus(String id, OrderStatus expected, OrderStatus target, Instant now);

    /**
     * IDs of PENDING orders with {@code createdAt <= cutoff}, oldest first, at most {@code limit}.
     * Projects only {@code _id}, so it is answered from the {@code status_createdAt_id} index.
     */
    List<String> findPendingIdsCreatedAtOrBefore(Instant cutoff, int limit);

    /**
     * Batch conditional update: {@code updateMulti({_id: {$in: ids}, status: PENDING})} to PROCESSING,
     * setting {@code updatedAt} and incrementing {@code version}. Orders no longer PENDING are untouched.
     *
     * @return number of orders actually modified
     */
    long markProcessing(List<String> ids, Instant now);
}
