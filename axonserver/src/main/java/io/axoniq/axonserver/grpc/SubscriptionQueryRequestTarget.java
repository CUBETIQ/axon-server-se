/*
 * Copyright (c) 2017-2019 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 * under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.grpc;

import io.axoniq.axonserver.applicationevents.SubscriptionQueryEvents.SubscriptionQueryCanceled;
import io.axoniq.axonserver.applicationevents.SubscriptionQueryEvents.SubscriptionQueryInitialResultRequested;
import io.axoniq.axonserver.applicationevents.SubscriptionQueryEvents.SubscriptionQueryRequested;
import io.axoniq.axonserver.grpc.query.SubscriptionQuery;
import io.axoniq.axonserver.grpc.query.SubscriptionQueryRequest;
import io.axoniq.axonserver.grpc.query.SubscriptionQueryResponse;
import io.axoniq.axonserver.message.query.subscription.UpdateHandler;
import io.axoniq.axonserver.message.query.subscription.handler.DirectUpdateHandler;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * @author Sara Pellegrini
 */
public class SubscriptionQueryRequestTarget extends ReceivingStreamObserver<SubscriptionQueryRequest> {

    private final String context;

    private final FlowControlledStreamObserver<SubscriptionQueryResponse> responseObserver;

    private final ApplicationEventPublisher eventPublisher;

    private final List<SubscriptionQuery> subscriptionQuery;

    private final UpdateHandler updateHandler;

    private final Consumer<Throwable> errorHandler;

    private volatile String client;

    SubscriptionQueryRequestTarget(
            String context, StreamObserver<SubscriptionQueryResponse> responseObserver,
            ApplicationEventPublisher eventPublisher) {
        super(LoggerFactory.getLogger(SubscriptionQueryRequestTarget.class));
        this.context = context;
        this.errorHandler = e -> responseObserver.onError(Status.INTERNAL
                                                                  .withDescription(e.getMessage())
                                                                  .withCause(e)
                                                                  .asRuntimeException());
        this.responseObserver = new FlowControlledStreamObserver<>(responseObserver, errorHandler);
        this.updateHandler = new DirectUpdateHandler(this.responseObserver::onNext);
        this.eventPublisher = eventPublisher;
        this.subscriptionQuery = new ArrayList<>();
    }

    @Override
    protected void consume(SubscriptionQueryRequest message) {
        switch (message.getRequestCase()) {
            case SUBSCRIBE:
                if (client == null) {
                    client = message.getSubscribe().getQueryRequest().getClientId();
                }
                subscriptionQuery.add(message.getSubscribe());
                eventPublisher.publishEvent(new SubscriptionQueryRequested(context,
                                                                           subscriptionQuery.get(0),
                                                                           updateHandler,
                                                                           errorHandler));

                break;
            case GET_INITIAL_RESULT:
                if (subscriptionQuery.isEmpty()){
                    errorHandler.accept(new IllegalStateException("Initial result asked before subscription"));
                    break;
                }
                eventPublisher.publishEvent(new SubscriptionQueryInitialResultRequested(context,
                                                                                        subscriptionQuery.get(0),
                                                                                        updateHandler,
                                                                                        errorHandler));
                break;
            case FLOW_CONTROL:
                responseObserver.addPermits(message.getFlowControl().getNumberOfPermits());
                break;
            case UNSUBSCRIBE:
                if (!subscriptionQuery.isEmpty()) {
                    unsubscribe(subscriptionQuery.get(0));
                }
                break;
        }

    }

    @Override
    protected String sender() {
        return client;
    }

    @Override
    public void onError(Throwable t) {
        if (!subscriptionQuery.isEmpty()) {
            unsubscribe(subscriptionQuery.get(0));
        }
        responseObserver.onError(t);
    }

    @Override
    public void onCompleted() {
        if (!subscriptionQuery.isEmpty()) {
            unsubscribe(subscriptionQuery.get(0));
        }
        responseObserver.onCompleted();
    }

    private void unsubscribe(SubscriptionQuery cancel) {
        subscriptionQuery.remove(cancel);
        eventPublisher.publishEvent(new SubscriptionQueryCanceled(context, cancel));
    }
}
