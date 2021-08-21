package com.github.jojotech.id.generator;

import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

public interface UniqueID {
    /**
     * @param bizType 业务类型编号
     * @return id     全局唯一id
     */
    String getUniqueId(String bizType);

    Mono<String> reactiveGetUniqueId(String bizType);
}
