/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.task.eventsourcing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.james.eventsourcing.Event;
import org.apache.james.eventsourcing.EventId;
import org.apache.james.eventsourcing.eventstore.History;
import org.apache.james.task.Hostname;
import org.apache.james.task.MemoryReferenceWithCounterTask;
import org.apache.james.task.Task;
import org.apache.james.task.TaskId;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Streams;
import scala.Option;

class TaskAggregateTest {

    static final Hostname HOSTNAME = Hostname.apply("foo");
    static final TaskAggregateId ID = TaskAggregateId.apply(TaskId.generateTaskId());
    static final Instant timestamp = Instant.parse("2018-11-13T12:00:55Z");

    History buildHistory(Function<EventId, Event>... events) {
        return History.of(
            Streams.zip(
                    Stream.iterate(EventId.first(), EventId::next),
                    Arrays.stream(events),
                    (id, event) -> event.apply(id))
                .collect(Collectors.toList()));
    }

    @Test
    void TaskAggregateShouldThrowWhenHistoryDoesntStartWithCreatedEvent() {
        assertThatThrownBy(() -> TaskAggregate.fromHistory(ID, buildHistory(eventId -> Started.apply(ID, eventId, HOSTNAME))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void TaskAggregateShouldThrowWhenEmptyHistory() {
        assertThatThrownBy(() -> TaskAggregate.fromHistory(ID, History.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void givenNoStartedTaskEmitNoEventWhenUpdateAdditionalInformationCommand() {
        History history = buildHistory(
            eventId -> Created.apply(ID, eventId, new MemoryReferenceWithCounterTask((counter) -> Task.Result.COMPLETED), HOSTNAME)
        );
        TaskAggregate aggregate = TaskAggregate.fromHistory(ID, history);
        assertThat(aggregate.update(new MemoryReferenceWithCounterTask.AdditionalInformation(3, timestamp))).isEmpty();
    }

    @Test
    void givenInProgressTaskEmitEventWhenUpdateAdditionalInformationCommand() {
        History history = buildHistory(
            eventId -> Created.apply(ID, eventId, new MemoryReferenceWithCounterTask((counter) -> Task.Result.COMPLETED), HOSTNAME),
            eventId -> Started.apply(ID, eventId, HOSTNAME)
        );
        TaskAggregate aggregate = TaskAggregate.fromHistory(ID, history);
        assertThat(aggregate.update(new MemoryReferenceWithCounterTask.AdditionalInformation(3, timestamp)))
            .containsExactly(AdditionalInformationUpdated.apply(ID, history.getNextEventId(), new MemoryReferenceWithCounterTask.AdditionalInformation(3, timestamp)));
    }

    @Test
    void givenCancelRequestedTaskEmitEventWhenUpdateAdditionalInformationCommand() {
        History history = buildHistory(
            eventId -> Created.apply(ID, eventId, new MemoryReferenceWithCounterTask((counter) -> Task.Result.COMPLETED), HOSTNAME),
            eventId -> Started.apply(ID, eventId, HOSTNAME),
            eventId -> CancelRequested.apply(ID, eventId, HOSTNAME)
        );
        TaskAggregate aggregate = TaskAggregate.fromHistory(ID, history);
        assertThat(aggregate.update(new MemoryReferenceWithCounterTask.AdditionalInformation(3, timestamp)))
            .containsExactly(AdditionalInformationUpdated.apply(ID, history.getNextEventId(), new MemoryReferenceWithCounterTask.AdditionalInformation(3, timestamp)));
    }

    @Test
    void givenCompletedTaskEmitNoEventWhenUpdateAdditionalInformationCommand() {
        MemoryReferenceWithCounterTask task = new MemoryReferenceWithCounterTask((counter) -> Task.Result.COMPLETED);
        History history = buildHistory(
            eventId -> Created.apply(ID, eventId, task, HOSTNAME),
            eventId -> Started.apply(ID, eventId, HOSTNAME),
            eventId -> Completed.apply(ID, eventId, Task.Result.COMPLETED, Option.empty())
        );
        TaskAggregate aggregate = TaskAggregate.fromHistory(ID, history);
        assertThat(aggregate.update(new MemoryReferenceWithCounterTask.AdditionalInformation(3, timestamp))).isEmpty();
    }

    @Test
    void givenFailedTaskEmitNoEventWhenUpdateAdditionalInformationCommand() {
        MemoryReferenceWithCounterTask task = new MemoryReferenceWithCounterTask((counter) -> Task.Result.COMPLETED);
        History history = buildHistory(
            eventId -> Created.apply(ID, eventId, task, HOSTNAME),
            eventId -> Started.apply(ID, eventId, HOSTNAME),
            eventId -> Failed.apply(ID, eventId, Option.empty(), Option.empty(), Option.empty())
        );
        TaskAggregate aggregate = TaskAggregate.fromHistory(ID, history);
        assertThat(aggregate.update(new MemoryReferenceWithCounterTask.AdditionalInformation(3, timestamp))).isEmpty();
    }

    @Test
    void givenCancelTaskEmitNoEventWhenUpdateAdditionalInformationCommand() {
        MemoryReferenceWithCounterTask task = new MemoryReferenceWithCounterTask((counter) -> Task.Result.COMPLETED);
        History history = buildHistory(
            eventId -> Created.apply(ID, eventId, task, HOSTNAME),
            eventId -> Started.apply(ID, eventId, HOSTNAME),
            eventId -> Cancelled.apply(ID, eventId, Option.empty())
        );
        TaskAggregate aggregate = TaskAggregate.fromHistory(ID, history);
        assertThat(aggregate.update(new MemoryReferenceWithCounterTask.AdditionalInformation(3, timestamp))).isEmpty();
    }
}