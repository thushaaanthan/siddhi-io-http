/*
 *  Copyright (c) 2017 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.extension.siddhi.io.http.source;

import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.extension.siddhi.io.http.source.util.HttpTestUtil;
import org.wso2.extension.siddhi.map.text.sourcemapper.TextSourceMapper;
import org.wso2.extension.siddhi.map.xml.sourcemapper.XmlSourceMapper;
import org.wso2.siddhi.core.SiddhiAppRuntime;
import org.wso2.siddhi.core.SiddhiManager;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.core.query.output.callback.QueryCallback;
import org.wso2.siddhi.core.util.EventPrinter;
import org.wso2.siddhi.core.util.SiddhiTestHelper;
import org.wso2.siddhi.core.util.persistence.InMemoryPersistenceStore;
import org.wso2.siddhi.core.util.persistence.PersistenceStore;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test cases for mapping types.
 */
public class HttpSourceMappingTestCases {
    private static final org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger
            (HttpSourceMappingTestCases.class);
    private AtomicInteger eventCount = new AtomicInteger(0);
    private int waitTime = 50;
    private int timeout = 30000;

    @BeforeMethod
    public void init() {
        eventCount.set(0);
    }

    /**
     * Creating test for publishing events with XML mapping.
     * @throws Exception Interrupted exception
     */
    @Test
    public void testXmlMapping() throws Exception {
        logger.info("Creating test for publishing events with XML mapping.");
        URI baseURI = URI.create(String.format("http://%s:%d", "localhost", 8005));
        List<String> receivedEventNameList = new ArrayList<>(2);
        PersistenceStore persistenceStore = new InMemoryPersistenceStore();
        SiddhiManager siddhiManager = new SiddhiManager();
        siddhiManager.setPersistenceStore(persistenceStore);
        siddhiManager.setExtension("xml-input-mapper", XmlSourceMapper.class);
        String inStreamDefinition = "" + "@source(type='http', @map(type='xml'), "
                + "receiver.url='http://localhost:8005/endpoints/RecPro', " + "basic.auth.enabled='false'" + ")"
                + "define stream inputStream (name string, age int, country string);";
        String query = (
                "@info(name = 'query') "
                        + "from inputStream "
                        + "select *  "
                        + "insert into outputStream;"
                        );
        SiddhiAppRuntime siddhiAppRuntime = siddhiManager
                .createSiddhiAppRuntime(inStreamDefinition + query);

        siddhiAppRuntime.addCallback("query", new QueryCallback() {
            @Override
            public void receive(long timeStamp, Event[] inEvents, Event[] removeEvents) {
                EventPrinter.print(timeStamp, inEvents, removeEvents);
                for (Event event : inEvents) {
                    eventCount.incrementAndGet();
                    receivedEventNameList.add(event.getData(0).toString());
                }
            }
        });
        siddhiAppRuntime.start();
        // publishing events
        List<String> expected = new ArrayList<>(2);
        expected.add("John");
        expected.add("Mike");
        String event1 = "<events>"
                            + "<event>"
                                + "<name>John</name>"
                                + "<age>100</age>"
                                + "<country>AUS</country>"
                            + "</event>"
                        + "</events>";
        String event2 = "<events>"
                            + "<event>"
                                + "<name>Mike</name>"
                                + "<age>20</age>"
                                + "<country>USA</country>"
                            + "</event>"
                        + "</events>";
        new HttpTestUtil().httpPublishEvent(event1, baseURI, "/endpoints/RecPro", false, "text/xml",
                "POST");
        new HttpTestUtil().httpPublishEvent(event2, baseURI, "/endpoints/RecPro", false, "text/xml",
                "POST");
        SiddhiTestHelper.waitForEvents(waitTime, 2, eventCount, timeout);
        Assert.assertEquals(receivedEventNameList.toString(), expected.toString());
        siddhiAppRuntime.shutdown();
    }

    /**
     * Creating test for publishing events with Text mapping.
     * @throws Exception Interrupted exception
     */
    @Test
    public void testTextMapping() throws Exception {
        logger.info("Creating test for publishing events with Text mapping.");
        URI baseURI = URI.create(String.format("http://%s:%d", "localhost", 8005));
        List<String> receivedEventNameList = new ArrayList<>(2);
        PersistenceStore persistenceStore = new InMemoryPersistenceStore();
        SiddhiManager siddhiManager = new SiddhiManager();
        siddhiManager.setPersistenceStore(persistenceStore);
        siddhiManager.setExtension("text", TextSourceMapper.class);
        String inStreamDefinition = "" + "@source(type='http',  @map(type='text'), "
                + "receiver.url='http://localhost:8005/endpoints/RecPro', " + "basic.auth.enabled='false'" + ")"
                + "define stream inputStream (name string, age int, country string);";
        String query = (
                "@info(name = 'query') "
                        + "from inputStream "
                        + "select *  "
                        + "insert into outputStream;"
                    );
        SiddhiAppRuntime siddhiAppRuntime = siddhiManager
                .createSiddhiAppRuntime(inStreamDefinition + query);

        siddhiAppRuntime.addCallback("query", new QueryCallback() {
            @Override
            public void receive(long timeStamp, Event[] inEvents, Event[] removeEvents) {
                EventPrinter.print(timeStamp, inEvents, removeEvents);
                for (Event event : inEvents) {
                    eventCount.incrementAndGet();
                    receivedEventNameList.add(event.getData(0).toString());
                }
            }
        });
        siddhiAppRuntime.start();

        // publishing events
        List<String> expected = new ArrayList<>(2);
        expected.add("John");
        expected.add("Mike");
        String event1 = "name:\"John\",\n" +
                "age:100,\n" +
                "country:\"USA\"";
        String event2 = "name:\"Mike\",\n" +
                "age:100,\n" +
                "country:\"USA\"";
        new HttpTestUtil().httpPublishEvent(event1, baseURI, "/endpoints/RecPro", false, "text",
                "POST");
        new HttpTestUtil().httpPublishEvent(event2, baseURI, "/endpoints/RecPro", false, "text",
                "POST");
        SiddhiTestHelper.waitForEvents(waitTime, 2, eventCount, timeout);
        Assert.assertEquals(receivedEventNameList.toString(), expected.toString());
        siddhiAppRuntime.shutdown();
    }
// TODO: 7/20/17 wait till release
//    /**
//     * Creating test for publishing events with Json mapping.
//     * @throws Exception Interrupted exception
//     */
//    @Test
//    public void testJsonMapping() throws Exception {
//        logger.info("Creating test for publishing events with Json mapping.");
//        new HttpTestUtil().setCarbonHome();
//        URI baseURI = URI.create(String.format("http://%s:%d", "localhost", 8005));
//        List<String> receivedEventNameList = new ArrayList<>(2);
//        PersistenceStore persistenceStore = new InMemoryPersistenceStore();
//        SiddhiManager siddhiManager = new SiddhiManager();
//        siddhiManager.setPersistenceStore(persistenceStore);
//        siddhiManager.setExtension("xml-input-mapper", JsonSourceMapper.class);
//        String inStreamDefinition = "" + "@source(type='http', @map(type='json'), "
//                + "receiver.url='http://localhost:8005/endpoints/RecPro', " + "basic.auth.enabled='false'" + ")"
//                + "define stream inputStream (name string, age int, country string);";
//        String query = ("@info(name = 'query') "
//                + "from inputStream "
//                + "select *  "
//                + "insert into outputStream;"
//                );
//        SiddhiAppRuntime siddhiAppRuntime = siddhiManager
//                .createSiddhiAppRuntime(inStreamDefinition + query);
//
//        siddhiAppRuntime.addCallback("query", new QueryCallback() {
//            @Override
//            public void receive(long timeStamp, Event[] inEvents, Event[] removeEvents) {
//                EventPrinter.print(timeStamp, inEvents, removeEvents);
//                for (Event event : inEvents) {
//                    eventCount.incrementAndGet();
//                    receivedEventNameList.add(event.getData(0).toString());
//                }
//            }
//        });
//        siddhiAppRuntime.start();
//        // publishing events
//        List<String> expected = new ArrayList<>(2);
//        expected.add("John");
//        expected.add("Mike");
//        String event1 = " {\n" +
//                "      \"event\":{\n" +
//                "         \"name\":\"John\",\n" +
//                "         \"age\":55.6,\n" +
//                "         \"country\":\"US\"\n" +
//                "      }\n" +
//                " }";
//        String event2 = " {\n" +
//                "      \"event\":{\n" +
//                "         \"name\":\"Mike\",\n" +
//                "         \"age\":55.6,\n" +
//                "         \"country\":\"US\"\n" +
//                "      }\n" +
//                " }";
//        new HttpTestUtil().httpPublishEvent(event1, baseURI, "/endpoints/RecPro", false,
//                "application/json", "POST");
//        new HttpTestUtil().httpPublishEvent(event2, baseURI, "/endpoints/RecPro", false,
//                "application/json", "POST");
//        SiddhiTestHelper.waitForEvents(waitTime, 2, eventCount, timeout);
//        Assert.assertEquals(receivedEventNameList.toString(), expected.toString());
//        siddhiAppRuntime.shutdown();
//    }
}
