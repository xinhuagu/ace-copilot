package dev.acecopilot.daemon;

import dev.acecopilot.infra.event.SchedulerEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerEventFeedTest {

    @Test
    void poll_returnsOnlyEventsAfterSequence() {
        var feed = new SchedulerEventFeed(16);
        feed.append(new SchedulerEvent.JobTriggered("job-a", "* * * * *", Instant.now()));
        feed.append(new SchedulerEvent.JobCompleted("job-a", 1200, "done", Instant.now()));

        var first = feed.poll(0, 10);
        assertThat(first.entries()).hasSize(2);
        long seq = first.entries().getFirst().sequence();

        var second = feed.poll(seq, 10);
        assertThat(second.entries()).hasSize(1);
        assertThat(second.entries().getFirst().event())
                .isInstanceOf(SchedulerEvent.JobCompleted.class);
    }

    @Test
    void feed_keepsOnlyLatestEntries() {
        var feed = new SchedulerEventFeed(2);
        feed.append(new SchedulerEvent.JobTriggered("j1", "* * * * *", Instant.now()));
        feed.append(new SchedulerEvent.JobTriggered("j2", "* * * * *", Instant.now()));
        feed.append(new SchedulerEvent.JobTriggered("j3", "* * * * *", Instant.now()));

        var result = feed.poll(0, 10);
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(0).event().jobId()).isEqualTo("j2");
        assertThat(result.entries().get(1).event().jobId()).isEqualTo("j3");
    }
}
