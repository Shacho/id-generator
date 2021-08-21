package com.github.jojotech.id.generator.test;

import com.github.jojotech.id.generator.UniqueID;
import com.github.jojotech.id.generator.UniqueIDImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import redis.embedded.RedisServer;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = {
})
public class UniqueIDTest {
    private static RedisServer redisServer;


    @BeforeAll
    public static void setUp() throws Exception {
        System.out.println("start redis");
        redisServer = RedisServer.builder().port(6379).setting("maxheap 200m").build();
        redisServer.start();
        System.out.println("redis started");
    }

    @AfterAll
    public static void tearDown() throws Exception {
        System.out.println("stop redis");
        redisServer.stop();
        System.out.println("redis stopped");
    }

    @EnableAutoConfiguration
    @Configuration
    public static class App {
    }

    @Autowired
    private UniqueID uniqueID;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final int THREAD_COUNT = 200;
    private static final int GET_COUNT = 1000;

    @Test
    public void testMultiThreadGetUniqueId() throws InterruptedException {
        //设置为最大值，这样就能测达到最大值的时候的是否会有问题
        for (int i = 0; i < UniqueIDImpl.MAX_BUCKET_SIZE; i++) {
            stringRedisTemplate.opsForValue().set(UniqueIDImpl.SEQUENCE_NUM_KEY_PREFIX + i, UniqueIDImpl.MAX_SEQUENCE_NUMBER + "");
        }
        Set<String> ids = new ConcurrentSkipListSet<>();
        Thread[] threads = new Thread[THREAD_COUNT];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < GET_COUNT; j++) {
                    String uniqueId = uniqueID.getUniqueId("biz");
                    boolean biz = ids.add(uniqueId);
                    if (!biz) {
                        System.out.println("duplicated: " + uniqueId);
                    }
                }
            });
            threads[i].start();
        }
        for (int i = 0; i < threads.length; i++) {
            threads[i].join();
        }
        Assertions.assertEquals(ids.size(), THREAD_COUNT * GET_COUNT);
    }

    static String forTest = null;
    static Long forTestSeq = null;

    @Test
    public void testSingleThreadSpeed() {
        //for JIT compile
        for (int i = 0; i < 10000; i++) {
            forTest = uniqueID.getUniqueId("biz");
        }
        long start = System.currentTimeMillis();
        //test redis baseline
        for (int i = 0; i < THREAD_COUNT * GET_COUNT; i++) {
            stringRedisTemplate.opsForValue().increment("test", 1);
        }
        System.out.println("BaseLine(only redis): " + THREAD_COUNT * GET_COUNT + " in: " + (System.currentTimeMillis() - start) + "ms");
        start = System.currentTimeMillis();
        //test sequence baseline
        for (int i = 0; i < THREAD_COUNT * GET_COUNT; i++) {
            forTestSeq = ((UniqueIDImpl) uniqueID).getSequenceNumber(new HashSet<>());
        }
        System.out.println("Sequence generate: " + THREAD_COUNT * GET_COUNT + " in: " + (System.currentTimeMillis() - start) + "ms");
        start = System.currentTimeMillis();
        for (int i = 0; i < THREAD_COUNT * GET_COUNT; i++) {
            forTest = uniqueID.getUniqueId("biz");
        }
        System.out.println("ID generate: " + THREAD_COUNT * GET_COUNT + " in: " + (System.currentTimeMillis() - start) + "ms");
    }

    @Test
    public void testMultipleThreadSpeed() throws InterruptedException {
        //for JIT compile
        for (int i = 0; i < 10000; i++) {
            forTest = uniqueID.getUniqueId("biz");
        }
        long start = System.currentTimeMillis();
        Thread[] threads = new Thread[THREAD_COUNT];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < GET_COUNT; j++) {
                    stringRedisTemplate.opsForValue().increment("test", 1);
                }
            });
            threads[i].start();
        }
        for (int i = 0; i < threads.length; i++) {
            threads[i].join();
        }
        System.out.println("BaseLine(only redis): " + THREAD_COUNT * GET_COUNT + " in: " + (System.currentTimeMillis() - start) + "ms");
        start = System.currentTimeMillis();
        threads = new Thread[THREAD_COUNT];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < GET_COUNT; j++) {
                    forTestSeq = ((UniqueIDImpl) uniqueID).getSequenceNumber(new HashSet<>());
                }
            });
            threads[i].start();
        }
        for (int i = 0; i < threads.length; i++) {
            threads[i].join();
        }
        System.out.println("Sequence generate: " + THREAD_COUNT * GET_COUNT + " in: " + (System.currentTimeMillis() - start) + "ms");
        start = System.currentTimeMillis();
        threads = new Thread[THREAD_COUNT];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < GET_COUNT; j++) {
                    forTest = uniqueID.getUniqueId("biz");
                }
            });
            threads[i].start();
        }
        for (int i = 0; i < threads.length; i++) {
            threads[i].join();
        }
        System.out.println("ID generate: " + THREAD_COUNT * GET_COUNT + " in: " + (System.currentTimeMillis() - start) + "ms");
    }

}
