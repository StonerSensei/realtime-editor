package com.collabeditor.realtime_editor.config;

import com.collabeditor.realtime_editor.messaging.RedisRoomBroker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.UUID;

/**
 * Redis-related configuration.
 * <p>
 * Spring Boot's Redis auto-configuration already provides the connection factory and
 * {@code RedisTemplate}/{@code StringRedisTemplate} beans from the {@code spring.data.redis.*}
 * properties, so this class only adds app-level pieces: the instance id and the Pub/Sub
 * listener container used for cross-instance fanout.
 */
@Slf4j
@Configuration
public class RedisConfig implements DisposableBean {

    /** Time between attempts to re-subscribe after the Redis connection is lost. */
    static final long SUBSCRIPTION_RECOVERY_INTERVAL_MS = 2_000L;

    /** Upper bound on received-but-undelivered pub/sub messages before new ones are dropped. */
    static final int DISPATCH_QUEUE_CAPACITY = 50_000;

    /**
     * Runs every received pub/sub message on one thread, in arrival order. The container's
     * default executor starts a new thread per message, which would reorder a sender's frames
     * and waste threads. Deliberately not a bean: exposing an Executor bean would replace
     * Spring Boot's auto-configured {@code applicationTaskExecutor}.
     */
    private final ThreadPoolTaskExecutor dispatchExecutor = createDispatchExecutor();

    /**
     * A unique identifier for this running application instance.
     * <p>
     * Regenerated on every boot. Every pub/sub message is tagged with it so an instance can
     * recognise, and skip, its own publishes; it is also this instance's member id in the
     * room presence sets.
     *
     * @return a random UUID string (36 chars), stable for the lifetime of this JVM
     */
    @Bean
    public String instanceId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Subscribes the {@link RedisRoomBroker} to every room's Yjs and chat channel.
     * <p>
     * If Redis is down at startup or later, the container keeps retrying in the background
     * every {@link #SUBSCRIPTION_RECOVERY_INTERVAL_MS} ms; the app boots and serves local
     * rooms normally meanwhile. (The stock container would fail the whole application
     * context when Redis is down at boot; see {@link ResilientRedisMessageListenerContainer}.)
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory,
                                                                        RedisRoomBroker broker) {
        RedisMessageListenerContainer container =
                new ResilientRedisMessageListenerContainer(SUBSCRIPTION_RECOVERY_INTERVAL_MS);
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(dispatchExecutor);
        container.setRecoveryInterval(SUBSCRIPTION_RECOVERY_INTERVAL_MS);
        container.setErrorHandler(e -> log.warn("Redis pub/sub listener error: {}", e.getMessage()));
        container.addMessageListener(broker, List.of(
                new PatternTopic(RedisRoomBroker.YJS_PATTERN),
                new PatternTopic(RedisRoomBroker.CHAT_PATTERN)));
        return container;
    }

    /** Runs after the container bean (which depends on this class) has been stopped. */
    @Override
    public void destroy() {
        dispatchExecutor.shutdown();
    }

    private static ThreadPoolTaskExecutor createDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(DISPATCH_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("redis-pubsub-");
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Redis pub/sub dispatch queue full ({}); dropping a message", DISPATCH_QUEUE_CAPACITY));
        executor.initialize();
        return executor;
    }
}