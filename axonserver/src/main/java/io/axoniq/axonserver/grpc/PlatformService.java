/*
 * Copyright (c) 2017-2019 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 * under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.grpc;

import io.axoniq.axonserver.applicationevents.EventProcessorEvents.PauseEventProcessorRequest;
import io.axoniq.axonserver.applicationevents.EventProcessorEvents.ProcessorStatusRequest;
import io.axoniq.axonserver.applicationevents.EventProcessorEvents.StartEventProcessorRequest;
import io.axoniq.axonserver.applicationevents.TopologyEvents;
import io.axoniq.axonserver.component.tags.ClientTagsUpdate;
import io.axoniq.axonserver.component.version.ClientVersionUpdate;
import io.axoniq.axonserver.exception.ErrorCode;
import io.axoniq.axonserver.exception.ExceptionUtils;
import io.axoniq.axonserver.exception.MessagingPlatformException;
import io.axoniq.axonserver.grpc.control.ClientIdentification;
import io.axoniq.axonserver.grpc.control.EventProcessorReference;
import io.axoniq.axonserver.grpc.control.NodeInfo;
import io.axoniq.axonserver.grpc.control.PlatformInboundInstruction;
import io.axoniq.axonserver.grpc.control.PlatformInboundInstruction.RequestCase;
import io.axoniq.axonserver.grpc.control.PlatformInfo;
import io.axoniq.axonserver.grpc.control.PlatformOutboundInstruction;
import io.axoniq.axonserver.grpc.control.PlatformServiceGrpc;
import io.axoniq.axonserver.grpc.control.RequestReconnect;
import io.axoniq.axonserver.topology.AxonServerNode;
import io.axoniq.axonserver.topology.Topology;
import io.axoniq.axonserver.util.StreamObserverUtils;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * gRPC service to track connected applications. Each application will first call the openStream operation with a
 * register request to retrieve information on which Axon Server node to connect to (Standard edition will always return
 * current node as node to connect to).
 *
 * @author Marc Gathier
 * @since 4.0
 */
@Service("PlatformService")
public class PlatformService extends PlatformServiceGrpc.PlatformServiceImplBase implements AxonServerClientService {

    private static final Logger logger = LoggerFactory.getLogger(PlatformService.class);

    private final Map<ClientComponent, SendingStreamObserver<PlatformOutboundInstruction>> connectionMap = new ConcurrentHashMap<>();
    private final Topology topology;
    private final ContextProvider contextProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<RequestCase, Deque<InstructionConsumer>> handlers = new EnumMap<>(RequestCase.class);
    private final InstructionAckSource<PlatformOutboundInstruction> instructionAckSource;

    /**
     * Instantiate a {@link PlatformService}, used to track all connected applications and deal with internal events.
     *
     * @param topology             the {@link Topology} of the group this Axon Server instance participates in
     * @param contextProvider      a {@link ContextProvider} used to retrieve the context this Axon Server instance is
     *                             working under
     * @param eventPublisher       the {@link ApplicationEventPublisher} to publish events through this Axon Server
     * @param instructionAckSource responsible for sending instruction acknowledgements
     */
    public PlatformService(Topology topology,
                           ContextProvider contextProvider,
                           ApplicationEventPublisher eventPublisher,
                           @Qualifier("platformInstructionAckSource")
                                   InstructionAckSource<PlatformOutboundInstruction> instructionAckSource) {
        this.topology = topology;
        this.contextProvider = contextProvider;
        this.eventPublisher = eventPublisher;
        this.instructionAckSource = instructionAckSource;
        onInboundInstruction(RequestCase.ACK, (client, context, instruction) -> {
            InstructionAck ack = instruction.getAck();
            if (isUnsupportedInstructionErrorResult(ack)) {
                logger.warn("Unsupported instruction sent to the client {} of context {}.", client, context);
            } else {
                logger.trace("Received instruction ack from the client {} of context {}. Result {}.",
                             client,
                             context,
                             ack);
            }
        });
    }

    private boolean isUnsupportedInstructionErrorResult(InstructionAck instructionAck) {
        return instructionAck.hasError()
                && instructionAck.getError().getErrorCode().equals(ErrorCode.UNSUPPORTED_INSTRUCTION.getCode());
    }

    @Override
    public void getPlatformServer(ClientIdentification request, StreamObserver<PlatformInfo> responseObserver) {
        String context = contextProvider.getContext();
        eventPublisher.publishEvent(new ClientTagsUpdate(request.getClientId(), context, request.getTagsMap()));
        try {
            AxonServerNode connectTo = topology.findNodeForClient(request.getClientId(),
                                                                  request.getComponentName(),
                                                                  context);
            responseObserver.onNext(PlatformInfo.newBuilder()
                                                .setSameConnection(connectTo.getName().equals(topology.getName()))
                                                .setPrimary(NodeInfo.newBuilder().setNodeName(connectTo.getName())
                                                                    .setHostName(connectTo.getHostName())
                                                                    .setGrpcPort(connectTo.getGrpcPort())
                                                                    .setHttpPort(connectTo.getHttpPort())
                                                ).build());
            responseObserver.onCompleted();
        } catch (MessagingPlatformException cause) {
            logger.info("Error finding target for client {}/{}: {}", request.getClientId(),
                        context,
                        cause.getMessage());
            responseObserver.onError(GrpcExceptionBuilder.build(cause));
        } catch (RuntimeException cause) {
            logger.warn("Error processing client request {}", request, cause);
            responseObserver.onError(GrpcExceptionBuilder.build(cause));
        }
    }

    @Override
    public StreamObserver<PlatformInboundInstruction> openStream(
            StreamObserver<PlatformOutboundInstruction> responseObserver) {
        String context = contextProvider.getContext();
        SendingStreamObserver<PlatformOutboundInstruction> sendingStreamObserver =
                new SendingStreamObserver<>(responseObserver);

        return new ReceivingStreamObserver<PlatformInboundInstruction>(logger) {
            private ClientComponent clientComponent;

            @Override
            protected void consume(PlatformInboundInstruction instruction) {
                RequestCase requestCase = instruction.getRequestCase();
                if (instruction.hasRegister()) { // TODO: 11/1/2019 register this as instruction handler
                    instructionAckSource.sendSuccessfulAck(instruction.getInstructionId(), sendingStreamObserver);
                    ClientIdentification client = instruction.getRegister();
                    eventPublisher.publishEvent(new ClientTagsUpdate(client.getClientId(),
                                                                     context,
                                                                     client.getTagsMap()));
                    clientComponent = new ClientComponent(client.getClientId(), client.getComponentName(), context);
                    registerClient(clientComponent, sendingStreamObserver);
                    eventPublisher.publishEvent(new ClientVersionUpdate(client.getClientId(),
                                                                        context,
                                                                        client.getVersion()));
                } else if (!handlers.containsKey(requestCase)) {
                    instructionAckSource.sendUnsupportedInstruction(instruction.getInstructionId(),
                                                                    topology.getMe().getName(),
                                                                    sendingStreamObserver);
                } else {
                    handlers.getOrDefault(requestCase, new ArrayDeque<>())
                            .forEach(consumer -> {
                                instructionAckSource.sendSuccessfulAck(instruction.getInstructionId(),
                                                                       sendingStreamObserver);
                                consumer.accept(clientComponent.client, context, instruction);
                            });
                }
            }

            @Override
            protected String sender() {
                return clientComponent == null ? null : clientComponent.client;
            }

            @Override
            public void onError(Throwable throwable) {
                if (!ExceptionUtils.isCancelled(throwable)) {
                    logger.warn("{}: error on connection - {}", sender(), throwable.getMessage());
                }
                deregisterClient(clientComponent);
            }

            @Override
            public void onCompleted() {
                deregisterClient(clientComponent);
            }
        };
    }

    public boolean requestReconnect(ClientComponent clientName) {
        logger.debug("Request reconnect: {}", clientName);

        StreamObserver<PlatformOutboundInstruction> stream = connectionMap.get(clientName);
        if (stream != null) {
            stream.onNext(PlatformOutboundInstruction.newBuilder()
                                                     .setRequestReconnect(RequestReconnect.newBuilder())
                                                     .build());
            return true;
        }
        return false;
    }

    public boolean requestReconnect(String client) {
        logger.debug("Request reconnect: {}", client);
        return connectionMap.entrySet().stream()
                            .filter(e -> e.getKey().client.equals(client))
                            .map(e -> requestReconnect(e.getKey()))
                            .findFirst().orElse(false);
    }

    /**
     * Sends the specified instruction to all the clients that are directly connected to this instance of AxonServer.
     *
     * @param instruction the {@link PlatformInboundInstruction} to be sent
     */
    public void sendToAllClients(PlatformOutboundInstruction instruction) {
        connectionMap.values()
                     .forEach(stream -> stream.onNext(instruction));
    }


    /**
     * Sends the specified instruction to all the clients that are directly connected to this instance of AxonServer.
     *
     * @param context     the context of the connected client
     * @param clientName  the name of the connected client
     * @param instruction the {@link PlatformInboundInstruction} to be sent
     */
    public void sendToClient(String context, String clientName, PlatformOutboundInstruction instruction) {
        connectionMap.entrySet().stream()
                     .filter(e -> e.getKey().client.equals(clientName))
                     .filter(e -> e.getKey().context.equals(context))
                     .map(Map.Entry::getValue)
                     .forEach(stream -> stream.onNext(instruction));
    }

    @EventListener
    public void onPauseEventProcessorRequest(PauseEventProcessorRequest evt) {
        PlatformOutboundInstruction instruction = PlatformOutboundInstruction
                .newBuilder()
                .setPauseEventProcessor(EventProcessorReference.newBuilder()
                                                               .setProcessorName(evt.processorName()))
                .build();
        this.sendToClient(evt.context(), evt.clientName(), instruction);
    }

    @EventListener
    public void onStartEventProcessorRequest(StartEventProcessorRequest evt) {
        PlatformOutboundInstruction instruction = PlatformOutboundInstruction
                .newBuilder()
                .setStartEventProcessor(EventProcessorReference.newBuilder().setProcessorName(evt.processorName()))
                .build();
        this.sendToClient(evt.context(),evt.clientName(), instruction);
    }

    @EventListener
    public void on(TopologyEvents.ApplicationDisconnected event) {
        StreamObserver<PlatformOutboundInstruction> connection = connectionMap
                .remove(new ClientComponent(event.getClient(), event.getComponentName(), event.getContext()));
        logger.debug("application disconnected: {}, connection: {}", event.getClient(), connection);
        if (connection != null) {
            try {
                connection.onCompleted();
            } catch (Exception ex) {
                logger.debug("Error while closing tracking event processor connection from {} - {}",
                             event.getClient(),
                             ex.getMessage());
            }
        }
    }

    public void onInboundInstruction(RequestCase requestCase, InstructionConsumer consumer) {
        Deque<InstructionConsumer> consumers = handlers.computeIfAbsent(requestCase, rc -> new ArrayDeque<>());
        consumers.add(consumer);
    }

    @EventListener
    public void on(ProcessorStatusRequest event) {
        EventProcessorReference eventProcessorInfoRequest =
                EventProcessorReference.newBuilder()
                                       .setProcessorName(event.processorName())
                                       .build();

        PlatformOutboundInstruction outboundInstruction =
                PlatformOutboundInstruction.newBuilder()
                                           .setRequestEventProcessorInfo(eventProcessorInfoRequest)
                                           .build();
        sendToClient(event.context(), event.clientName(), outboundInstruction);
    }

    private void registerClient(ClientComponent clientComponent,
                                SendingStreamObserver<PlatformOutboundInstruction> responseObserver) {
        logger.debug("Registered client : {}", clientComponent);

        connectionMap.put(clientComponent, responseObserver);
        eventPublisher.publishEvent(new TopologyEvents.ApplicationConnected(
                clientComponent.context, clientComponent.component, clientComponent.client
        ));
    }

    private void deregisterClient(ClientComponent clientComponent) {
        logger.debug("De-registered client : {}", clientComponent);

        if (clientComponent != null) {
            SendingStreamObserver<PlatformOutboundInstruction> stream = connectionMap.remove(clientComponent);
            if (stream != null) {
                StreamObserverUtils.complete(stream);
            }

            eventPublisher.publishEvent(new TopologyEvents.ApplicationDisconnected(
                    clientComponent.context, clientComponent.component, clientComponent.client, null
            ));
        }
    }

    /**
     * De-registers a client if it turns out to be inactive/not properly connected
     *
     * @param evt the event of inactivity timeout for a specific client component
     */
    @EventListener
    public void on(TopologyEvents.ApplicationInactivityTimeout evt) {
        ClientComponent clientComponent = new ClientComponent(evt.clientIdentification().getClient(),
                                                              evt.componentName(),
                                                              evt.clientIdentification().getContext());
        deregisterClient(clientComponent);
    }

    /**
     * Return a {@link Set} of connected clients as {@link ClientComponent} instances.
     *
     * @return a {@link Set} of {@link ClientComponent} instances representing the connected clients.
     */
    public Set<ClientComponent> getConnectedClients() {
        return connectionMap.keySet();
    }

    /**
     * Finds all clients that are currently connected to the specified context and requests these client to reconnect.
     *
     * @param context the context
     */
    public void requestReconnectForContext(String context) {
        Set<ClientComponent> clients = connectionMap.keySet().stream().filter(c -> c.context.equals(context)).collect(
                Collectors.toSet());

        clients.forEach(this::requestReconnect);
    }

    /**
     * Functional interface describing a consumer of {@link PlatformOutboundInstruction}s to be called as a form of
     * handler functions when new instructions are received.
     */
    @FunctionalInterface
    public interface InstructionConsumer {

        /**
         * Consume the given {@code client}, {@code context} and {@link PlatformInboundInstruction}.
         *
         * @param client      a {@link String} specifying the name of the client
         * @param context     a {@link String} specifying the context of the client
         * @param instruction a {@link PlatformOutboundInstruction} describing the inbound instruction to be consumed
         */
        void accept(String client, String context, PlatformInboundInstruction instruction);
    }

    /**
     * Represent a client, specifying the {@code client} id, {@code component} name and the {@code context} of the
     * given client.
     */
    public static class ClientComponent {

        private final String client;
        private final String component;
        private final String context;

        public ClientComponent(String client, String component, String context) {
            this.client = client;
            this.component = component;
            this.context = context;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ClientComponent that = (ClientComponent) o;
            return client.equals(that.client) &&
                    context.equals(that.context);
        }

        @Override
        public int hashCode() {
            return Objects.hash(client, context);
        }

        /**
         * Return the id of this client.
         *
         * @return a {@link String} representing the id of this client
         */
        public String getClient() {
            return client;
        }

        /**
         * Return the component name of this client.
         *
         * @return a {@link String} representing the component name of this client
         */
        public String getComponent() {
            return component;
        }

        /**
         * Return the context this client is working under.
         *
         * @return a {@link String} representing the context this client is working under
         */
        public String getContext() {
            return context;
        }

        @Override
        public String toString() {
            return "ClientComponent{" +
                    "client='" + client + '\'' +
                    ", component='" + component + '\'' +
                    ", context='" + context + '\'' +
                    '}';
        }
    }
}
