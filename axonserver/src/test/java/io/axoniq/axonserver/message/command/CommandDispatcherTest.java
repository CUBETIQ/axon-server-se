/*
 * Copyright (c) 2017-2019 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 * under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.message.command;

import com.google.common.collect.Sets;
import io.axoniq.axonserver.ProcessingInstructionHelper;
import io.axoniq.axonserver.applicationevents.TopologyEvents;
import io.axoniq.axonserver.grpc.SerializedCommand;
import io.axoniq.axonserver.grpc.SerializedCommandProviderInbound;
import io.axoniq.axonserver.grpc.SerializedCommandResponse;
import io.axoniq.axonserver.grpc.command.Command;
import io.axoniq.axonserver.grpc.command.CommandResponse;
import io.axoniq.axonserver.message.ClientIdentification;
import io.axoniq.axonserver.metric.DefaultMetricCollector;
import io.axoniq.axonserver.metric.MeterFactory;
import io.axoniq.axonserver.topology.Topology;
import io.axoniq.axonserver.test.FakeStreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * @author Marc Gathier
 */
@RunWith(MockitoJUnitRunner.class)
public class CommandDispatcherTest {
    private CommandDispatcher commandDispatcher;
    private CommandMetricsRegistry metricsRegistry;
    @Mock
    private CommandCache commandCache;
    @Mock
    private CommandRegistrationCache registrations;

    @Before
    public void setup() {
        MeterFactory meterFactory = new MeterFactory(new SimpleMeterRegistry(), new DefaultMetricCollector());
        metricsRegistry = new CommandMetricsRegistry(meterFactory);
        commandDispatcher = new CommandDispatcher(registrations, commandCache, metricsRegistry, meterFactory, 10_000);
        ConcurrentMap<CommandHandler, Set<CommandRegistrationCache.RegistrationEntry>> dummyRegistrations = new ConcurrentHashMap<>();
        Set<CommandRegistrationCache.RegistrationEntry> commands =
                Sets.newHashSet(new CommandRegistrationCache.RegistrationEntry(Topology.DEFAULT_CONTEXT, "Command"));
        dummyRegistrations.put(new DirectCommandHandler(new FakeStreamObserver<>(),
                                                        new ClientIdentification(Topology.DEFAULT_CONTEXT, "client"),
                                                        "component"),
                               commands);
    }

    @Test
    public void unregisterCommandHandler()  {
        commandDispatcher.on(new TopologyEvents.ApplicationDisconnected(null, null, "client"));
    }

    @Test
    public void dispatch()  {
        FakeStreamObserver<SerializedCommandResponse> responseObserver = new FakeStreamObserver<>();
        Command request = Command.newBuilder()
                                 .addProcessingInstructions(ProcessingInstructionHelper.routingKey("1234"))
                                 .setName("Command")
                                 .setMessageIdentifier("12")
                                 .build();
        FakeStreamObserver<SerializedCommandProviderInbound> commandProviderInbound = new FakeStreamObserver<>();
        ClientIdentification client = new ClientIdentification(Topology.DEFAULT_CONTEXT, "client");
        DirectCommandHandler result = new DirectCommandHandler(commandProviderInbound,
                                                               client, "component");
        when(registrations.getHandlerForCommand(eq(Topology.DEFAULT_CONTEXT), anyObject(), anyObject())).thenReturn(result);

        commandDispatcher.dispatch(Topology.DEFAULT_CONTEXT, new SerializedCommand(request), response -> {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }, false);
        assertEquals(1, commandDispatcher.getCommandQueues().getSegments().get(client.toString()).size());
        assertEquals(0, responseObserver.values().size());
        Mockito.verify(commandCache, times(1)).put(eq("12"), anyObject());

    }
    @Test
    public void dispatchNotFound() {
        FakeStreamObserver<SerializedCommandResponse> responseObserver = new FakeStreamObserver<>();
        Command request = Command.newBuilder()
                                 .addProcessingInstructions(ProcessingInstructionHelper.routingKey("1234"))
                                 .setName("Command")
                                 .setMessageIdentifier("12")
                                 .build();
        when(registrations.getHandlerForCommand(any(), anyObject(), anyObject())).thenReturn(null);

        commandDispatcher.dispatch(Topology.DEFAULT_CONTEXT, new SerializedCommand(request), response -> {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }, false);
        assertEquals(1, responseObserver.values().size());
        assertNotEquals("", responseObserver.values().get(0).getErrorCode());
        Mockito.verify(commandCache, times(0)).put(eq("12"), anyObject());
    }

    @Test
    public void dispatchUnknownContext() {
        FakeStreamObserver<SerializedCommandResponse> responseObserver = new FakeStreamObserver<>();
        Command request = Command.newBuilder()
                                 .addProcessingInstructions(ProcessingInstructionHelper.routingKey("1234"))
                                 .setName("Command")
                                 .setMessageIdentifier("12")
                                 .build();
        when(registrations.getHandlerForCommand(any(), anyObject(), anyObject())).thenReturn(null);

        commandDispatcher.dispatch("UnknownContext", new SerializedCommand(request), response -> {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }, false);
        assertEquals(1, responseObserver.values().size());
        assertEquals("AXONIQ-4000", responseObserver.values().get(0).getErrorCode());
        Mockito.verify(commandCache, times(0)).put(eq("12"), anyObject());
    }

    @Test
    public void dispatchProxied() throws Exception {
        FakeStreamObserver<SerializedCommandResponse> responseObserver = new FakeStreamObserver<>();
        Command request = Command.newBuilder()
                                 .setName("Command")
                                 .setMessageIdentifier("12")
                                 .build();
        ClientIdentification clientIdentification = new ClientIdentification(Topology.DEFAULT_CONTEXT, "client");
        FakeStreamObserver<SerializedCommandProviderInbound> commandProviderInbound = new FakeStreamObserver<>();
        DirectCommandHandler result = new DirectCommandHandler(commandProviderInbound,
                                                               clientIdentification,
                                                               "component");
        when(registrations.findByClientAndCommand(eq(clientIdentification), anyObject())).thenReturn(result);

        commandDispatcher.dispatch(Topology.DEFAULT_CONTEXT, new SerializedCommand(request.toByteArray(), "client", request.getMessageIdentifier()), responseObserver::onNext, true);
        assertEquals(1, commandDispatcher.getCommandQueues().getSegments().get(clientIdentification.toString()).size());
        assertEquals("12", commandDispatcher.getCommandQueues().take(clientIdentification.toString()).command()
                                            .getMessageIdentifier());
        assertEquals(0, responseObserver.values().size());
        Mockito.verify(commandCache, times(1)).put(eq("12"), anyObject());
    }

    @Test
    public void dispatchProxiedClientNotFound()  {
        FakeStreamObserver<SerializedCommandResponse> responseObserver = new FakeStreamObserver<>();
        Command request = Command.newBuilder()
                                 .addProcessingInstructions(ProcessingInstructionHelper.routingKey("1234"))
                                 .setName("Command")
                                 .setMessageIdentifier("12")
                                 .build();

        commandDispatcher.dispatch(Topology.DEFAULT_CONTEXT,
                                   new SerializedCommand(request),
                                   responseObserver::onNext,
                                   true);
        assertEquals(1, responseObserver.values().size());
        Mockito.verify(commandCache, times(0)).put(eq("12"), anyObject());
    }

    @Test
    public void handleResponse() {
        AtomicBoolean responseHandled = new AtomicBoolean(false);
        ClientIdentification client = new ClientIdentification(Topology.DEFAULT_CONTEXT, "Client");
        CommandInformation commandInformation = new CommandInformation("TheCommand",
                                                                       "Source",
                                                                       (r) -> responseHandled.set(true),
                                                                       client, "Component");
        when(commandCache.remove(any(String.class))).thenReturn(commandInformation);

        commandDispatcher.handleResponse(new SerializedCommandResponse(CommandResponse.newBuilder().build()), false);
        assertTrue(responseHandled.get());
//        assertEquals(1, metricsRegistry.commandMetric("TheCommand", client, "Component").getCount());

    }
}
