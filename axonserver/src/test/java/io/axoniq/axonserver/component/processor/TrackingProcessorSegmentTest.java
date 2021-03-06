/*
 * Copyright (c) 2017-2019 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 * under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.component.processor;

import io.axoniq.axonserver.grpc.control.EventProcessorInfo.SegmentStatus;
import io.axoniq.axonserver.serializer.GsonMedia;
import org.junit.*;

import static io.axoniq.axonserver.grpc.control.EventProcessorInfo.SegmentStatus.newBuilder;
import static org.junit.Assert.*;

/**
 * Created by Sara Pellegrini on 23/03/2018.
 * sara.pellegrini@gmail.com
 */
public class TrackingProcessorSegmentTest {

    @Test
    public void printOn() {
        GsonMedia gsonMedia = new GsonMedia();
        SegmentStatus eventTrackerInfo = newBuilder().setSegmentId(1)
                                                     .setOnePartOf(2)
                                                     .setReplaying(false)
                                                     .setCaughtUp(true)
                                                     .build();
        TrackingProcessorSegment tracker = new TrackingProcessorSegment("myClient", eventTrackerInfo);
        tracker.printOn(gsonMedia);
        assertEquals("{\"clientId\":\"myClient\",\"segmentId\":1,\"caughtUp\":true,\"replaying\":false,\"tokenPosition\":0,\"errorState\":\"\",\"onePartOf\":2}",
                     gsonMedia.toString());
    }
}
