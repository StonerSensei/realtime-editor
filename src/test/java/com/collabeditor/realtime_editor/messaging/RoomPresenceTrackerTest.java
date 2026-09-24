package com.collabeditor.realtime_editor.messaging;

import com.collabeditor.realtime_editor.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomPresenceTrackerTest {

    private static final String MY_ID = "11111111-1111-4111-8111-111111111111";
    private static final Duration TTL = Duration.ofSeconds(15);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    private MutableClock clock;
    private RoomPresenceTracker tracker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        // Heartbeat interval of 1h so the background thread never fires during a test;
        // tests call heartbeat() directly.
        tracker = new RoomPresenceTracker(redisTemplate, MY_ID, TTL, Duration.ofHours(1), clock);
    }

    @AfterEach
    void tearDown() {
        tracker.trackActiveRooms(List::of); // nothing to withdraw
        tracker.shutdown();
    }

    /** doReturn() form, so stubbing several rooms doesn't invoke the already-stubbed method. */
    private void stubTouch(String roomId, Long othersResult) {
        doReturn(othersResult).when(redisTemplate).execute(eq(RoomPresenceTracker.TOUCH_SCRIPT),
                eq(List.of("collab:presence:" + roomId)), eq(MY_ID), eq(String.valueOf(TTL.toMillis())));
    }

    @Test
    @DisplayName("markActive - false when no other instance has peers (script returns 0)")
    void markActive_aloneShouldReturnFalse() {
        stubTouch("room-1", 0L);

        assertThat(tracker.markActive("room-1")).isFalse();
    }

    @Test
    @DisplayName("markActive - true when other instances have peers")
    void markActive_othersShouldReturnTrue() {
        stubTouch("room-1", 2L);

        assertThat(tracker.markActive("room-1")).isTrue();
    }

    @Test
    @DisplayName("markActive - a null script result is treated as alone")
    void markActive_nullResultShouldReturnFalse() {
        stubTouch("room-1", null);

        assertThat(tracker.markActive("room-1")).isFalse();
    }

    @Test
    @DisplayName("markActive - Redis errors report 'alone' and pause Redis for the cooldown")
    void markActive_redisErrorShouldFailToAloneWithCooldown() {
        when(redisTemplate.execute(eq(RoomPresenceTracker.TOUCH_SCRIPT), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(1L);

        assertThat(tracker.markActive("room-1")).isFalse();   // error
        assertThat(tracker.markActive("room-1")).isFalse();   // cooldown: Redis skipped
        verify(redisTemplate, times(1)).execute(eq(RoomPresenceTracker.TOUCH_SCRIPT), anyList(), any(), any());

        clock.advance(RoomPresenceTracker.REDIS_COOLDOWN.plusMillis(1));
        assertThat(tracker.markActive("room-1")).isTrue();    // Redis consulted again
    }

    @Test
    @DisplayName("markInactive - removes this instance from the room's presence set")
    void markInactive_shouldRemoveEntry() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        tracker.markInactive("room-1");

        verify(zSetOps).remove("collab:presence:room-1", MY_ID);
    }

    @Test
    @DisplayName("markInactive - Redis errors are swallowed")
    void markInactive_errorShouldBeSwallowed() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.remove(anyString(), any())).thenThrow(new RedisConnectionFailureException("down"));

        tracker.markInactive("room-1"); // must not throw
    }

    @Test
    @DisplayName("heartbeat - refreshes this instance's entry for every room with local peers")
    void heartbeat_shouldTouchEveryActiveRoom() {
        stubTouch("a", 0L);
        stubTouch("b", 1L);
        tracker.trackActiveRooms(() -> Set.of("a", "b"));

        tracker.heartbeat();

        verify(redisTemplate).execute(eq(RoomPresenceTracker.TOUCH_SCRIPT), eq(List.of("collab:presence:a")),
                eq(MY_ID), eq("15000"));
        verify(redisTemplate).execute(eq(RoomPresenceTracker.TOUCH_SCRIPT), eq(List.of("collab:presence:b")),
                eq(MY_ID), eq("15000"));
    }

    @Test
    @DisplayName("heartbeat - stops at the first Redis failure instead of timing out once per room")
    void heartbeat_shouldStopOnFailure() {
        when(redisTemplate.execute(eq(RoomPresenceTracker.TOUCH_SCRIPT), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));
        tracker.trackActiveRooms(() -> List.of("a", "b", "c"));

        tracker.heartbeat();

        verify(redisTemplate, times(1)).execute(eq(RoomPresenceTracker.TOUCH_SCRIPT), anyList(), any(), any());
    }

    @Test
    @DisplayName("heartbeat - a failing room supplier never escapes (would cancel the schedule)")
    void heartbeat_supplierFailureShouldBeContained() {
        tracker.trackActiveRooms(() -> { throw new IllegalStateException("boom"); });

        tracker.heartbeat(); // must not throw

        tracker.trackActiveRooms(List::of); // let tearDown's shutdown() run cleanly
    }

    @Test
    @DisplayName("refreshNow - ignores the error cooldown and re-registers every active room immediately")
    void refreshNow_shouldBypassCooldown() {
        when(redisTemplate.execute(eq(RoomPresenceTracker.TOUCH_SCRIPT), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(0L);
        tracker.trackActiveRooms(() -> List.of("a"));
        tracker.heartbeat();                        // fails -> cooldown active

        tracker.refreshNow();                       // Redis is back: don't wait out the cooldown

        verify(redisTemplate, times(2)).execute(eq(RoomPresenceTracker.TOUCH_SCRIPT), anyList(), any(), any());
    }

    @Test
    @DisplayName("After shutdown the tracker never touches Redis again (connection is going away)")
    void afterShutdown_shouldBeInert() {
        tracker.shutdown();

        assertThat(tracker.markActive("room-1")).isFalse();
        tracker.markInactive("room-1");
        tracker.shutdown(); // idempotent

        verify(redisTemplate, never()).execute(eq(RoomPresenceTracker.TOUCH_SCRIPT), anyList(), any(), any());
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    @DisplayName("Lifecycle - stops before the Redis connection factory (phase 0) so withdrawal can still reach Redis")
    void lifecycle_shouldStopBeforeConnectionFactory() {
        tracker.start();
        assertThat(tracker.isRunning()).isTrue();
        assertThat(tracker.getPhase()).isGreaterThan(0);
        assertThat(tracker.isAutoStartup()).isTrue();

        tracker.stop();

        assertThat(tracker.isRunning()).isFalse();
    }

    @Test
    @DisplayName("shutdown - withdraws this instance from every room it is active in")
    void shutdown_shouldWithdrawFromActiveRooms() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        tracker.trackActiveRooms(() -> List.of("a", "b"));

        tracker.shutdown();

        verify(zSetOps).remove("collab:presence:a", MY_ID);
        verify(zSetOps).remove("collab:presence:b", MY_ID);
        verify(redisTemplate, never()).execute(eq(RoomPresenceTracker.TOUCH_SCRIPT), anyList(), any(), any());
    }
}