package com.peerislands.orders.repository;

import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@RequiredArgsConstructor
class OrderRepositoryCustomImpl implements OrderRepositoryCustom {

    static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "_id");
    static final String COLLECTION = "orders";

    private final MongoTemplate mongoTemplate;

    @Override
    public List<Order> findPage(OrderStatus status, OrderCursor after, int limit) {
        return mongoTemplate.find(pageQuery(status, after, limit), Order.class);
    }

    @Override
    public Optional<Order> transitionStatus(String id, OrderStatus expected, OrderStatus target, Instant now) {
        Query query = new Query(where("_id").is(id).and("status").is(expected));
        Update update = new Update()
                .set("status", target)
                .set("updatedAt", now)
                .inc("version", 1);
        return Optional.ofNullable(mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), Order.class));
    }

    @Override
    public List<String> findPendingIdsCreatedAtOrBefore(Instant cutoff, int limit) {
        return mongoTemplate.find(pendingIdsQuery(cutoff, limit), Document.class, COLLECTION).stream()
                .map(doc -> doc.getObjectId("_id").toHexString())
                .toList();
    }

    static Query pendingIdsQuery(Instant cutoff, int limit) {
        Query query = new Query(where("status").is(OrderStatus.PENDING.name()).and("createdAt").lte(cutoff))
                .with(Sort.by(Sort.Direction.ASC, "createdAt", "_id"))
                .limit(limit);
        query.fields().include("_id");
        return query;
    }

    @Override
    public long markProcessing(List<String> ids, Instant now) {
        if (ids.isEmpty()) {
            return 0;
        }
        List<ObjectId> objectIds = ids.stream().map(ObjectId::new).toList();
        Query query = new Query(where("_id").in(objectIds).and("status").is(OrderStatus.PENDING));
        Update update = new Update()
                .set("status", OrderStatus.PROCESSING)
                .set("updatedAt", now)
                .inc("version", 1);
        return mongoTemplate.updateMulti(query, update, Order.class).getModifiedCount();
    }

    /**
     * Builds the keyset query. After cursor (t, id) the condition is
     * {@code createdAt < t OR (createdAt == t AND _id < id)}, written as
     * {@code createdAt <= t AND (createdAt < t OR _id < id)} so the top-level range bounds the index scan.
     * Served by {@code status_createdAt_id} (filtered) or {@code createdAt_id} (unfiltered), scanned
     * backwards, with no in-memory sort.
     */
    static Query pageQuery(OrderStatus status, OrderCursor after, int limit) {
        List<Criteria> conditions = new ArrayList<>();
        if (status != null) {
            conditions.add(where("status").is(status));
        }
        if (after != null) {
            conditions.add(where("createdAt").lte(after.createdAt()));
            conditions.add(new Criteria().orOperator(
                    where("createdAt").lt(after.createdAt()),
                    where("_id").lt(new ObjectId(after.id()))));
        }
        Criteria criteria = conditions.isEmpty() ? new Criteria() : new Criteria().andOperator(conditions);
        return new Query(criteria).with(NEWEST_FIRST).limit(limit);
    }
}
