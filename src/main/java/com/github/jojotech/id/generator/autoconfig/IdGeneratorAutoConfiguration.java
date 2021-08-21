package com.github.jojotech.id.generator.autoconfig;

import com.github.jojotech.id.generator.config.IdGeneratorConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({IdGeneratorConfiguration.class})
public class IdGeneratorAutoConfiguration {
}
