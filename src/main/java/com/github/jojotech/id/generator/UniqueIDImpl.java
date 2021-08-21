package com.github.jojotech.id.generator;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RLockReactive;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Log4j2
public class UniqueIDImpl implements UniqueID {

    public static final long SEQUENCE_BITS = 18L;
    public static final long MAX_SEQUENCE_NUMBER = 1L << SEQUENCE_BITS;
    public static final long BUCKET_SIZE_BITS = 8L;
    public static final long BUCKET_SIZE_SHIFT = SEQUENCE_BITS;
    public static final long MAX_BUCKET_SIZE = 1 << BUCKET_SIZE_BITS;
    public static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyMMddHHmmssSSS");
    public static final String SEQUENCE_NUM_KEY_PREFIX = "sequence_num_key:";
    public static final String SEQUENCE_NUM_LOCK_PREFIX = "sequnce_num_lock:";

    private final Cache<Long, Long> circuitBreaker = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.SECONDS).build();
    private final RedissonClient redissonClient;
    private final RedissonReactiveClient redissonReactiveClient;
    private final StringRedisTemplate redisTemplate;
    private final ReactiveStringRedisTemplate reactiveRedisTemplate;
    private final ThreadLocal<Long> position = ThreadLocal.withInitial(() -> ThreadLocalRandom.current().nextLong(MAX_BUCKET_SIZE));


    public UniqueIDImpl(RedissonClient redissonClient,
                        RedissonReactiveClient redissonReactiveClient,
                        StringRedisTemplate redisTemplate,
                        ReactiveStringRedisTemplate reactiveRedisTemplate) {
        this.redissonClient = redissonClient;
        this.redissonReactiveClient = redissonReactiveClient;
        this.redisTemplate = redisTemplate;
        this.reactiveRedisTemplate = reactiveRedisTemplate;
    }

    @VisibleForTesting
    public long getSequenceNumber(Set<Long> tried) {
        long currentBucket = getNextBucket();
        if (circuitBreaker.getIfPresent(currentBucket) == null) {
            try {
                return (currentBucket << BUCKET_SIZE_SHIFT) + getSequenceNumber(currentBucket);
            } catch (DataAccessException e) {
                log.error("get sequence number error, try next bucket", e);
                circuitBreaker.put(currentBucket, currentBucket);
                tried.add(currentBucket);
                return getSequenceNumber(tried);
            }
        } else {
            tried.add(currentBucket);
            if (tried.size() == MAX_BUCKET_SIZE) {
                throw new IdGenerateException("Failed to fetch sequence for each bucket");
            }
        }
        return getSequenceNumber(tried);
    }

    /**
     * 获取下一个 Bucket 号码
     * @return
     */
    private long getNextBucket() {
        //获取
        long currentPosition = position.get();
        long currentBucket = Math.abs(currentPosition ^ (MAX_BUCKET_SIZE - 1));
        position.set(currentPosition + 1);
        return currentBucket;
    }

    private long getSequenceNumber(long currentBucket) {
        ValueOperations<String, String> valueOperations = redisTemplate.opsForValue();
        String sequenceKey = SEQUENCE_NUM_KEY_PREFIX + currentBucket;
        long sequenceNumber = valueOperations.increment(sequenceKey, 1);
        if (sequenceNumber < MAX_SEQUENCE_NUMBER) {
            return sequenceNumber;
        }
        RLock lock = redissonClient.getLock(SEQUENCE_NUM_LOCK_PREFIX + currentBucket);
        lock.lock();
        try {
            sequenceNumber = Long.parseLong(valueOperations.get(sequenceKey));
            if (sequenceNumber >= MAX_SEQUENCE_NUMBER) {
                valueOperations.set(sequenceKey, "0");
            }
            return valueOperations.increment(sequenceKey, 1);
        } finally {
            lock.unlock();
        }
    }

//    private Mono<Long> reactiveGetSequenceNumber(long currentBucket) {
//        ReactiveValueOperations<String, String> reactiveValueOperations = reactiveRedisTemplate.opsForValue();
//        String sequenceKey = SEQUENCE_NUM_KEY_PREFIX + currentBucket;
//        return reactiveValueOperations.increment(sequenceKey, 1).flatMap(sequenceNumber -> {
//            if (sequenceNumber < MAX_SEQUENCE_NUMBER) {
//                return Mono.just(sequenceNumber);
//            }
//            RLockReactive lock = redissonReactiveClient.getLock(SEQUENCE_NUM_LOCK_PREFIX + currentBucket);
//            return lock.lock().flatMap(result -> {
//                if (result) {
//                    return reactiveValueOperations.set(sequenceKey, "0").map(b -> 0L);
//                }
//                return reactiveValueOperations.get(sequenceKey).re(Retry.)
//            }).doFinally(signalType -> {
//
//            });
//        });
//    }

    @Override
    public String getUniqueId(String bizType) {
        if (StringUtils.isEmpty(bizType)) {
            throw new IdGenerateException("biz type is empty");
        }
        if (bizType.length() > 4) {
            throw new IdGenerateException("biz type lenth > 4");
        }
        String timestamp = FORMAT.format(LocalDateTime.now());
        try {
            return timestamp + bizType + String.format("%08d", getSequenceNumber(new HashSet<>()));
        } catch (Exception e) {
            throw new IdGenerateException(e);
        }
    }

    @Override
    public Mono<String> reactiveGetUniqueId(String bizType) {
        return null;
    }
}
