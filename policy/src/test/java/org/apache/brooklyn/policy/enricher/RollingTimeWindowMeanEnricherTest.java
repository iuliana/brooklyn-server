/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.policy.enricher;

import static org.testng.Assert.assertEquals;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.EnricherSpec;
import org.apache.brooklyn.api.sensor.Sensor;
import org.apache.brooklyn.core.sensor.BasicAttributeSensor;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.policy.enricher.RollingTimeWindowMeanEnricher.ConfidenceQualifiedNumber;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class RollingTimeWindowMeanEnricherTest extends BrooklynAppUnitTestSupport {
    
    Entity producer;

    Sensor<Integer> intSensor;
    AttributeSensor<Integer> deltaSensor;
    AttributeSensor<Double> avgSensor;

    RollingTimeWindowMeanEnricher<Integer> averager;
    ConfidenceQualifiedNumber average;

    private final long timePeriod = 1000;
    
    @BeforeMethod(alwaysRun=true)
    @SuppressWarnings("unchecked")
    @Override
    public void setUp() throws Exception {
        super.setUp();
        producer = app.addChild(EntitySpec.create(TestEntity.class));

        intSensor = new BasicAttributeSensor<Integer>(Integer.class, "int sensor");
        deltaSensor = new BasicAttributeSensor<Integer>(Integer.class, "delta sensor");
        avgSensor = new BasicAttributeSensor<Double>(Double.class, "avg sensor");
        
        producer.enrichers().add(EnricherSpec.create(DeltaEnricher.class)
                .configure("producer", producer)
                .configure("source", intSensor)
                .configure("target", deltaSensor));
        averager = producer.enrichers().add(EnricherSpec.create(RollingTimeWindowMeanEnricher.class)
                .configure("producer", producer)
                .configure("source", deltaSensor)
                .configure("target", avgSensor)
                .configure("timePeriod", timePeriod));
    }

    @Test
    public void testDefaultAverageWhenEmpty() {
        average = averager.getAverage(0, 0);
        assertEquals(average.value, 0d);
        assertEquals(average.confidence, 0.0d);
    }
    
    @Test
    public void testNoRecentValuesAverage() {
        averager.onEvent(intSensor.newEvent(producer, 10), 0L);
        average = averager.getAverage(timePeriod+1000, 0);
        assertEquals(average.value, 10d);
        assertEquals(average.confidence, 0d);
    }
    
    @Test
    public void testNoRecentValuesUsesLastForAverage() {
        averager.onEvent(intSensor.newEvent(producer, 10), 0L);
        averager.onEvent(intSensor.newEvent(producer, 20), 10L);
        average = averager.getAverage(timePeriod+1000, 0);
        assertEquals(average.value, 20d);
        assertEquals(average.confidence, 0d);
    }
    
    @Test
    public void testSingleValueTimeAverage() {
        averager.onEvent(intSensor.newEvent(producer, 10), 1000);
        average = averager.getAverage(1000, 0);
        assertEquals(average.confidence, 0d);
    }
    
    @Test
    public void testTwoValueAverageForPeriod() {
        averager.onEvent(intSensor.newEvent(producer, 10), 1000);
        averager.onEvent(intSensor.newEvent(producer, 10), 2000);
        average = averager.getAverage(2000, 0);
        assertEquals(average.value, 10 /1d);
        assertEquals(average.confidence, 1d);
    }
    
    @Test
    public void testMonospacedAverage() {
        averager.onEvent(intSensor.newEvent(producer, 10), 1000);
        averager.onEvent(intSensor.newEvent(producer, 20), 1250);
        averager.onEvent(intSensor.newEvent(producer, 30), 1500);
        averager.onEvent(intSensor.newEvent(producer, 40), 1750);
        averager.onEvent(intSensor.newEvent(producer, 50), 2000);
        average = averager.getAverage(2000, 0);
        assertEquals(average.value, (20+30+40+50)/4d);
        assertEquals(average.confidence, 1d);
    }
    
    @Test
    public void testWeightedAverage() {
        averager.onEvent(intSensor.newEvent(producer, 10), 1000);
        averager.onEvent(intSensor.newEvent(producer, 20), 1100);
        averager.onEvent(intSensor.newEvent(producer, 30), 1300);
        averager.onEvent(intSensor.newEvent(producer, 40), 1600);
        averager.onEvent(intSensor.newEvent(producer, 50), 2000);
        average = averager.getAverage(2000, 0);
        assertEquals(average.value, (20*0.1d)+(30*0.2d)+(40*0.3d)+(50*0.4d));
        assertEquals(average.confidence, 1d);
    }
    
    @Test
    public void testConfidenceDecay() {
        averager.onEvent(intSensor.newEvent(producer, 10), 1000);
        averager.onEvent(intSensor.newEvent(producer, 20), 1250);
        averager.onEvent(intSensor.newEvent(producer, 30), 1500);
        averager.onEvent(intSensor.newEvent(producer, 40), 1750);
        averager.onEvent(intSensor.newEvent(producer, 50), 2000);
        
        average = averager.getAverage(2250, 0);
        assertEquals(average.value, (30+40+50)/3d);
        assertEquals(average.confidence, 0.75d);
        average = averager.getAverage(2500, 0);
        assertEquals(average.value, (40+50)/2d);
        assertEquals(average.confidence, 0.5d);
        average = averager.getAverage(2750, 0);
        assertEquals(average.value, 50d);
        assertEquals(average.confidence, 0.25d);
        average = averager.getAverage(3000, 0);
        assertEquals(average.value, 50d);
        assertEquals(average.confidence, 0d);
    }
}
