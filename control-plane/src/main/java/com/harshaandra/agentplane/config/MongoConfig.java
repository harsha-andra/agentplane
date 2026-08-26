package com.harshaandra.agentplane.config;

import com.harshaandra.agentplane.trace.RunTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/**
 * MongoDB holds {@link RunTrace}: schemaless run logs/tool-call output. Index creation is done
 * explicitly here (driven by the {@code @Indexed}/{@code @CompoundIndex} annotations on the
 * document) rather than via {@code spring.data.mongodb.auto-index-creation}, so that it only
 * happens once, deliberately, at startup - and can be disabled entirely (see
 * {@code application-test.yml}) so the context-load test never has to talk to a real Mongo.
 */
@Configuration
public class MongoConfig {

    @Bean
    @ConditionalOnProperty(prefix = "agentplane.mongo", name = "ensure-indexes-on-startup", havingValue = "true", matchIfMissing = true)
    public MongoIndexInitializer mongoIndexInitializer(MongoTemplate mongoTemplate) {
        return new MongoIndexInitializer(mongoTemplate);
    }

    @Slf4j
    static class MongoIndexInitializer implements InitializingBean {

        private final MongoTemplate mongoTemplate;

        MongoIndexInitializer(MongoTemplate mongoTemplate) {
            this.mongoTemplate = mongoTemplate;
        }

        @Override
        public void afterPropertiesSet() {
            try {
                MongoMappingContext mappingContext = (MongoMappingContext) mongoTemplate.getConverter().getMappingContext();
                IndexResolver resolver = new MongoPersistentEntityIndexResolver(mappingContext);
                IndexOperations indexOps = mongoTemplate.indexOps(RunTrace.class);
                resolver.resolveIndexFor(RunTrace.class).forEach(indexOps::ensureIndex);
                log.info("Ensured MongoDB indexes for {}", RunTrace.class.getSimpleName());
            } catch (Exception e) {
                log.warn("Could not ensure MongoDB indexes at startup (non-fatal, will be created lazily/manually): {}",
                        e.getMessage());
            }
        }
    }
}
