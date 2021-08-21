package com.github.jojotech.id.generator.config;

import com.github.jojotech.id.generator.UniqueID;
import com.github.jojotech.id.generator.UniqueIDImpl;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
public class IdGeneratorConfiguration {
    @Bean
    public UniqueID getUniqueID(RedissonClient redissonClient,
                                RedissonReactiveClient redissonReactiveClient,
                                StringRedisTemplate redisTemplate,
                                ReactiveStringRedisTemplate reactiveRedisTemplate) {
        return new UniqueIDImpl(redissonClient, redissonReactiveClient, redisTemplate, reactiveRedisTemplate);
    }
}
