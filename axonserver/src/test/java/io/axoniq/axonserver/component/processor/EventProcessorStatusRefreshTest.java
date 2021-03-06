package io.axoniq.axonserver.component.processor;

import io.axoniq.axonserver.applicationevents.EventProcessorEvents.EventProcessorStatusUpdated;
import io.axoniq.axonserver.component.processor.listener.ClientProcessor;
import io.axoniq.axonserver.component.processor.listener.FakeClientProcessor;
import io.axoniq.axonserver.grpc.control.EventProcessorInfo;
import org.junit.*;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

import static io.axoniq.axonserver.test.AssertUtils.assertWithin;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link EventProcessorStatusRefresh}.
 *
 * @author Sara Pellegrini
 */
public class EventProcessorStatusRefreshTest {

    private final Duration timeout = Duration.ofMillis(200);
    private EventProcessorIdentifier processorA = new EventProcessorIdentifier("processorA", "");
    private EventProcessorIdentifier processorB = new EventProcessorIdentifier("processorB", "");
    private EventProcessorIdentifier processorC = new EventProcessorIdentifier("processorC", "");
    private ClientProcessor clientProcessor1 = new FakeClientProcessor("redClient", false, "processorA");
    private ClientProcessor clientProcessor2 = new FakeClientProcessor("redClient", false, "processorB");
    private ClientProcessor clientProcessor3 = new FakeClientProcessor("greenClient", false, "processorB");
    private ClientProcessor clientProcessor4 = new FakeClientProcessor("blueClient", false, "processorB");
    private ClientProcessor clientProcessor5 = new FakeClientProcessor("blueClient", false, "processorC");
    private List<ClientProcessor> clientProcessors = asList(clientProcessor1,
                                                            clientProcessor2,
                                                            clientProcessor3,
                                                            clientProcessor4,
                                                            clientProcessor5);
    private List<Object> publishedInternalEvents = new LinkedList<>();
    private EventProcessorStatusRefresh testSubject = new EventProcessorStatusRefresh(timeout,
                                                                                      () -> clientProcessors.iterator(),
                                                                                      publishedInternalEvents::add);

    @Before
    public void setUp() throws Exception {
        publishedInternalEvents.clear();
    }

    @Test
    public void runSuccessfully() throws InterruptedException {
        CompletableFuture<Void> completableFuture = testSubject.run("context", processorB);
        assertWithin(1000, MILLISECONDS, () -> assertFalse(publishedInternalEvents.isEmpty()));
        testSubject.on(updateEvent("redClient", "processorB"));
        testSubject.on(updateEvent("greenClient", "processorB"));
        testSubject.on(updateEvent("blueClient", "processorB"));
        assertWithin((int) timeout.toMillis(), MILLISECONDS, () -> assertTrue(completableFuture.isDone()));
    }

    @Test()
    public void testFailureForMissingUpdate() throws InterruptedException {
        CompletableFuture<Void> completableFuture = testSubject.run("context", processorB);
        assertWithin(100, MILLISECONDS, () -> assertFalse(publishedInternalEvents.isEmpty()));
        testSubject.on(updateEvent("redClient", "processorB"));
        testSubject.on(updateEvent("greenClient", "processorB"));
        testSubject.on(updateEvent("greenClient", "processorB"));
        assertWithin((int) timeout.toMillis() + 100,
                     MILLISECONDS,
                     () -> assertTrue(completableFuture.isCompletedExceptionally()));
    }

    @Test()
    public void testFailureForMissingUpdate2() throws InterruptedException {
        CompletableFuture<Void> completableFuture = testSubject.run("context", processorA);
        assertWithin(100, MILLISECONDS, () -> assertFalse(publishedInternalEvents.isEmpty()));
        testSubject.on(updateEvent("redClient", "processorB"));
        testSubject.on(updateEvent("greenClient", "processorB"));
        testSubject.on(updateEvent("blueClient", "processorB"));
        testSubject.on(updateEvent("redClient", "processorC"));
        testSubject.on(updateEvent("greenClient", "processorC"));
        testSubject.on(updateEvent("blueClient", "processorC"));
        testSubject.on(updateEvent("greenClient", "processorA"));
        testSubject.on(updateEvent("blueClient", "processorA"));
        assertWithin((int) timeout.toMillis() + 100,
                     MILLISECONDS,
                     () -> assertTrue(completableFuture.isCompletedExceptionally()));
    }

    @Nonnull
    private EventProcessorStatusUpdated updateEvent(String client, String processorName) {
        return new EventProcessorStatusUpdated(
                new ClientEventProcessorInfo(
                        client,
                        "context",
                        EventProcessorInfo.newBuilder().setProcessorName(processorName).build()),
                false);
    }
}