package com.collabeditor.realtime_editor.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;

import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Runs against a port where nothing listens, i.e. "Redis is down", which is exactly the case
 * the stock container gets wrong. Needs no Redis.
 */
class ResilientRedisMessageListenerContainerTest {

    private LettuceConnectionFactory factory;
    private ResilientRedisMessageListenerContainer container;

    @BeforeEach
    void setUp() throws Exception {
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort(); // free now, closed below: nothing will be listening
        }
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", deadPort),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        factory.afterPropertiesSet();
        factory.start();

        container = new ResilientRedisMessageListenerContainer(200);
        container.setConnectionFactory(factory);
        container.addMessageListener((MessageListener) (message, pattern) -> { }, new PatternTopic("collab:yjs:*"));
        container.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() throws Exception {
        container.destroy();
        factory.destroy();
    }

    @Test
    @DisplayName("start() with Redis down does not throw, so the application context still starts")
    void start_withRedisDown_shouldNotThrow() {
        assertThatCode(container::start).doesNotThrowAnyException();
        assertThat(container.isListening()).isFalse();
    }

    @Test
    @DisplayName("Background retries keep failing quietly while Redis stays down")
    void retries_withRedisDown_shouldStayQuiet() throws Exception {
        container.start();

        Thread.sleep(800); // several 200 ms retry cycles

        assertThat(container.isListening()).isFalse();
    }

    @Test
    @DisplayName("stop() cancels pending retries; the container stays stopped")
    void stop_shouldCancelRetries() throws Exception {
        container.start();
        container.stop();

        Thread.sleep(600);

        assertThat(container.isRunning()).isFalse();
        assertThat(container.isListening()).isFalse();
    }
}