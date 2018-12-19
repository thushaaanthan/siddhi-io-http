/*
 *  Copyright (c) 2018 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.extension.siddhi.io.http.sink;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;

import org.apache.log4j.Logger;
import org.wso2.carbon.messaging.Header;
import org.wso2.extension.siddhi.io.http.sink.updatetoken.HttpsClient;
import org.wso2.extension.siddhi.io.http.sink.util.HttpSinkUtil;
import org.wso2.extension.siddhi.io.http.source.HttpResponseMessageListener;
import org.wso2.extension.siddhi.io.http.util.HttpConstants;
import org.wso2.siddhi.annotation.Example;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.annotation.Parameter;
import org.wso2.siddhi.annotation.util.DataType;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.core.util.transport.DynamicOptions;
import org.wso2.siddhi.core.util.transport.Option;
import org.wso2.siddhi.core.util.transport.OptionHolder;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.StreamDefinition;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.wso2.extension.siddhi.io.http.util.HttpConstants.EMPTY_STRING;

/**
 * {@code HttpRequestSink} Handle the HTTP publishing tasks.
 */
@Extension(name = "http-request", namespace = "sink",
        description = "" +
                "This extension publish the HTTP events in any HTTP method  POST, GET, PUT, DELETE  via HTTP " +
                "or https protocols. As the additional features this component can provide basic authentication " +
                "as well as user can publish events using custom client truststore files when publishing events " +
                "via https protocol. And also user can add any number of headers including HTTP_METHOD header for " +
                "each event dynamically.\n" +
                "Following content types will be set by default according to the type of sink mapper used.\n" +
                "You can override them by setting the new content types in headers.\n" +
                "     - TEXT : text/plain\n" +
                "     - XML : application/xml\n" +
                "     - JSON : application/json\n" +
                "     - KEYVALUE : application/x-www-form-urlencoded\n\n" +
                "HTTP request sink is correlated with the " +
                "The HTTP reponse source, through a unique `sink.id`." +
                "It sends the request to the defined url and the response is received by the response source " +
                "which has the same 'sink.id'.",
        parameters = {
                @Parameter(
                        name = "publisher.url",
                        description = "The URL to which the outgoing events should be published via HTTP. " +
                                "This is a mandatory parameter and if this is not specified, an error is logged in " +
                                "the CLI. If user wants to enable SSL for the events, use `https` instead of `http` " +
                                "in the publisher.url.\n" +
                                "e.g., " +
                                "`http://localhost:8080/endpoint`, " +
                                "`https://localhost:8080/endpoint`\n" +
                                "This can be used as a dynamic parameter as well.",
                        type = {DataType.STRING},
                        dynamic = true),
                @Parameter(
                        name = "basic.auth.username",
                        description = "The username to be included in the authentication header of the basic " +
                                "authentication enabled events. It is required to specify both username and " +
                                "password to enable basic authentication. If one of the parameter is not given " +
                                "by user then an error is logged in the CLI.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = " "),
                @Parameter(
                        name = "basic.auth.password",
                        description = "The password to include in the authentication header of the basic " +
                                "authentication enabled events. It is required to specify both username and " +
                                "password to enable basic authentication. If one of the parameter is not given " +
                                "by user then an error is logged in the CLI.",
                        type = {DataType.STRING},
                        optional = true, defaultValue = " "),
                @Parameter(
                        name = "https.truststore.file",
                        description = "The file path to the location of the truststore of the client that sends " +
                                "the HTTP events through 'https' protocol. A custom client-truststore can be " +
                                "specified if required.",
                        type = {DataType.STRING},
                        optional = true, defaultValue = "${carbon.home}/resources/security/client-truststore.jks"),
                @Parameter(
                        name = "https.truststore.password",
                        description = "The password for the client-truststore. A custom password can be specified " +
                                "if required. If no custom password is specified and the protocol of URL is 'https' " +
                                "then, the system uses default password.",
                        type = {DataType.STRING},
                        optional = true, defaultValue = "wso2carbon"),
                @Parameter(
                        name = "headers",
                        description = "The headers that should be included as HTTP request headers. \n" +
                                "There can be any number of headers concatenated in following format. " +
                                "\"'header1:value1','header2:value2'\". User can include Content-Type header if he " +
                                "needs to use a specific content-type for the payload. Or else, system decides the " +
                                "Content-Type by considering the type of sink mapper, in following way.\n" +
                                " - @map(xml):application/xml\n" +
                                " - @map(json):application/json\n" +
                                " - @map(text):plain/text )\n" +
                                " - if user does not include any mapping type then the system gets 'plain/text' " +
                                "as default Content-Type header.\n" +
                                "Note that providing content-length as a header is not supported. The size of the " +
                                "payload will be automatically calculated and included in the content-length header.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = " "),
                @Parameter(
                        name = "method",
                        description = "For HTTP events, HTTP_METHOD header should be included as a request header." +
                                " If the parameter is null then system uses 'POST' as a default header.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "POST"),

                @Parameter(
                        name = "socket.idle.timeout",
                        description = "Socket timeout value in millisecond",
                        type = {DataType.INT},
                        optional = true,
                        defaultValue = "6000"),
                @Parameter(
                        name = "chunk.disabled",
                        description = "port: Port number of the remote service",
                        type = {DataType.BOOL},
                        optional = true,
                        defaultValue = "false"),
                @Parameter(
                        name = "ssl.protocol",
                        description = "The SSL protocol version",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "TLS"),
                @Parameter(
                        name = "parameters",
                        description = "Parameters other than basics such as ciphers,sslEnabledProtocols,client.enable" +
                                ".session.creation. Expected format of these parameters is as follows: " +
                                "\"'ciphers:xxx','sslEnabledProtocols,client.enable:xxx'\"",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "null"),
                @Parameter(
                        name = "ciphers",
                        description = "List of ciphers to be used. This parameter should include under parameters Ex:" +
                                " 'ciphers:TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256'",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "null"),
                @Parameter(
                        name = "ssl.enabled.protocols",
                        description = "SSL/TLS protocols to be enabled. This parameter should be in camel case format" +
                                "(sslEnabledProtocols) under parameters. Ex 'sslEnabledProtocols:true'",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "null"),
                @Parameter(
                        name = "client.enable.session.creation",
                        description = "Enable HTTP session creation.This parameter should include under parameters " +
                                "Ex:" +
                                " 'client.enable.session.creation:true'",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "null"),
                @Parameter(
                        name = "follow.redirect",
                        description = "Redirect related enabled.",
                        type = {DataType.BOOL},
                        optional = true,
                        defaultValue = "true"),
                @Parameter(
                        name = "max.redirect.count",
                        description = "Maximum redirect count.",
                        type = {DataType.INT},
                        optional = true,
                        defaultValue = "5"),
                @Parameter(
                        name = "tls.store.type",
                        description = "TLS store type to be used.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "JKS"),
                @Parameter(
                        name = "proxy.host",
                        description = "Proxy server host",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "null"),
                @Parameter(
                        name = "proxy.port",
                        description = "Proxy server port",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "null"),
                @Parameter(
                        name = "proxy.username",
                        description = "Proxy server username",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "null"),
                @Parameter(
                        name = "proxy.password",
                        description = "Proxy server password",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "null"),
                //bootstrap configurations
                @Parameter(
                        name = "client.bootstrap.configuration",
                        description = "Client bootsrap configurations. Expected format of these parameters is as " +
                                "follows:" +
                                " \"'client.bootstrap.nodelay:xxx','client.bootstrap.keepalive:xxx'\"",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "TODO"),
                @Parameter(
                        name = "client.bootstrap.nodelay",
                        description = "Http client no delay.",
                        type = {DataType.BOOL},
                        optional = true,
                        defaultValue = "true"),
                @Parameter(
                        name = "client.bootstrap.keepalive",
                        description = "Http client keep alive.",
                        type = {DataType.BOOL},
                        optional = true,
                        defaultValue = "true"),
                @Parameter(
                        name = "client.bootstrap.sendbuffersize",
                        description = "Http client send buffer size.",
                        type = {DataType.INT},
                        optional = true,
                        defaultValue = "1048576"),
                @Parameter(
                        name = "client.bootstrap.recievebuffersize",
                        description = "Http client receive buffer size.",
                        type = {DataType.INT},
                        optional = true,
                        defaultValue = "1048576"),
                @Parameter(
                        name = "client.bootstrap.connect.timeout",
                        description = "Http client connection timeout.",
                        type = {DataType.INT},
                        optional = true,
                        defaultValue = "15000"),
                @Parameter(
                        name = "client.bootstrap.socket.reuse",
                        description = "To enable http socket reuse.",
                        type = {DataType.BOOL},
                        optional = true,
                        defaultValue = "false"),
                @Parameter(
                        name = "client.bootstrap.socket.timeout",
                        description = "Http client socket timeout.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "15"),

                @Parameter(
                        name = "client.threadpool.configurations",
                        description = "Thread pool configuration. Expected format of these parameters is as follows:" +
                                " \"'client.connection.pool.count:xxx','client.max.active.connections.per.pool:xxx'\"",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "TODO"),
                @Parameter(
                        name = "client.connection.pool.count",
                        description = "Connection pool count.",
                        type = {DataType.INT},
                        optional = true,
                        defaultValue = "0"),
                @Parameter(
                        name = "client.max.active.connections.per.pool",
                        description = "Active connections per pool.",
                        type = {DataType.INT},
                        optional = true,
                        defaultValue = "-1"),
                @Parameter(
                        name = "client.min.idle.connections.per.pool",
                        description = "Minimum ideal connection per pool.",
                        type = {DataType.INT},
                        optional = true,
                        defaultValue = "0"),
                @Parameter(
                        name = "client.max.idle.connections.per.pool",
                        description = "Maximum ideal connection per pool.",
                        type = {DataType.INT},
                        optional = true,
                        defaultValue = "100"),
                @Parameter(
                        name = "client.min.eviction.idle.time",
                        description = "Minimum eviction idle time.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "5 * 60 * 1000"),
                @Parameter(
                        name = "sender.thread.count",
                        description = "Http sender thread count.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "20"),
                @Parameter(
                        name = "event.group.executor.thread.size",
                        description = "Event group executor thread size.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "15"),
                @Parameter(
                        name = "max.wait.for.client.connection.pool",
                        description = "Maximum wait for client connection pool.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "60000"),
                @Parameter(
                        name = "sink.id",
                        description = "Identifier of the sink. This is used to co-relate with the corresponding " +
                                "http-response source which needs to process the repose for the request sent by this" +
                                " sink.",
                        type = {DataType.STRING}),
                @Parameter(
                        name = "downloading.enabled",
                        description = "If this is set to 'true' then the response received by the response source " +
                                "will be written to a file. If downloading is enabled, the download.path parameter is" +
                                " mandatory.",
                        type = {DataType.BOOL},
                        optional = true,
                        defaultValue = "false"),
                @Parameter(
                        name = "download.path",
                        description = "If downloading is enabled, the path of the file which is going to be " +
                                "downloaded should be specified using 'download.path' parameter. This should be an " +
                                "absolute path including the file name.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "null",
                        dynamic = true),
                //added
                @Parameter(
                        name = "client.consumerkey",
                        description = "The consumer key for getting refresh token.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "null"),
                @Parameter(
                        name = "client.consumer.secret",
                        description = "The consumer secret key for getting refresh token.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "null"),
        },
        examples = {
                @Example(syntax =
                        "@sink(type='http-request', sink.id='foo', " +
                                "publisher.url='http://localhost:8009/foo', " +
                                "@map(type='xml', @payload('{{payloadBody}}')))\n" +
                                "define stream FooStream (payloadBody String, method string, headers string);\n" +
                                "" +
                                "@source(type='http-response', sink.id='foo', http.status.code='2\\\\d+', \n" +
                                "@map(type='text', regex.A='((.|\\n)*)', " +
                                "@attributes(headers='trp:headers', fileName='A[1]')))\n" +
                                "define stream responseStream2xx(fileName string, headers string);\n\n" +
                                "" +
                                "@source(type='http-response', sink.id='foo', http.status.code='4\\\\d+', \n" +
                                "@map(type='text', regex.A='((.|\\n)*)', @attributes(errorMsg='A[1]'))" +
                                ")\n" +
                                "define stream responseStream4xx(errorMsg string);",
                        description =
                                "In above example, the payload body for 'FooStream' will be in following " +
                                        "format.\n" +
                                        "{\n" +
                                        "<events>\n" +
                                        "    <event>\n" +
                                        "        <symbol>WSO2</symbol>\n" +
                                        "        <price>55.6</price>\n" +
                                        "        <volume>100</volume>\n" +
                                        "    </event>\n" +
                                        "</events>,\n" +
                                        "This message will sent as the body of a POST request with the content-type " +
                                        "'application/xml' to the endpoint defined as the 'publisher.url' and in " +
                                        "order to process the responses for these requests, there should be a source " +
                                        "of type 'http-response' defined with the same sink id 'foo' in the siddhi " +
                                        "app.\n The responses with 2xx status codes will be received by the " +
                                        "http-response source which has the http.status.code defined by the regex " +
                                        "'2\\\\d+'.\n" +
                                        "If the response has a 4xx status code, it will be received by the " +
                                        "http-response source which has the http.status.code defined by the regex " +
                                        "'4\\\\d+'.\n"),

                @Example(syntax = "" +
                        "define stream FooStream (name String, id int, headers String, downloadPath string);" +
                        "\n" +
                        "@sink(type='http-request', \n" +
                        "downloading.enabled='true',\n" +
                        "download.path='{{downloadPath}}'," +
                        "publisher.url='http://localhost:8005/files',\n" +
                        "method='GET', " +
                        "headers='{{headers}}',sink.id='download-sink',\n" +
                        "@map(type='json')) \n" +
                        "define stream BarStream (name String, id int, headers String, downloadPath string);" +
                        "\n\n" +
                        "@source(type='http-response', sink.id='download-sink', " +
                        "http.status.code='2\\\\d+', \n" +
                        "@map(type='text', regex.A='((.|\\n)*)', " +
                        "@attributes(headers='trp:headers', fileName='A[1]')))\n" +
                        "define stream responseStream2xx(fileName string, headers string);\n\n" +
                        "" +
                        "@source(type='http-response', sink.id='download-sink', " +
                        "http.status.code='4\\\\d+', \n" +
                        "@map(type='text', regex.A='((.|\\n)*)', @attributes(errorMsg='A[1]')))\n" +
                        "define stream responseStream4xx(errorMsg string);",
                        description =
                                "In above example, http-request sink will send a GET request to the publisher url and" +
                                        " the requested file will be received as the response by a corresponding " +
                                        "http-response source.\n" +
                                        "If the http status code of the response is a successful one (2xx), it will " +
                                        "be received by the http-response source which has the http.status.code " +
                                        "'2\\\\d+' and downloaded as a local file. Then the event received to the " +
                                        "responseStream2xx will have the headers included in the request and " +
                                        "the downloaded file name.\n" +
                                        "If the http status code of the response is a 4xx code, it will be received " +
                                        "by the http-response source which has the http.status.code '4\\\\d+'. Then " +
                                        "the event received to the responseStream4xx will have the response message " +
                                        "body in text format."
                )}
)
public class HttpRequestSink extends HttpSink {

    private static final Logger log = Logger.getLogger(HttpRequestSink.class);
    private String sinkId;
    private boolean isDownloadEnabled;
    private StreamDefinition outputStreamDefinition;
    private Option downloadPath;
    private String clientStoreFile;
    private String clientStorePass;
    private Option publisherURLOption;
    private String publisherURL;
    private String oauthUsername;
    private String oauthUserPassword;
    private String consumerKey;
    private String consumerSecret;
    private HashMap<String, String> authanticationHash = new HashMap<>();
    private String refreshToken;
    private String accessToken;

    @Override
    protected void init(StreamDefinition outputStreamDefinition, OptionHolder optionHolder,
                        ConfigReader configReader, SiddhiAppContext siddhiAppContext) {
        super.init(outputStreamDefinition, optionHolder, configReader, siddhiAppContext);

        this.sinkId = optionHolder.validateAndGetStaticValue(HttpConstants.SINK_ID);
        this.isDownloadEnabled = Boolean.parseBoolean(optionHolder.validateAndGetStaticValue(HttpConstants
                .DOWNLOAD_ENABLED, HttpConstants.DEFAULT_DOWNLOAD_ENABLED_VALUE));
        if (isDownloadEnabled) {
            this.downloadPath = optionHolder.validateAndGetOption(HttpConstants.DOWNLOAD_PATH);
        }
        this.consumerKey = optionHolder.validateAndGetStaticValue(HttpConstants.CONSUMER_KEY, EMPTY_STRING);
        this.consumerSecret = optionHolder.validateAndGetStaticValue(HttpConstants.CONSUMER_SECRET, EMPTY_STRING);
        this.outputStreamDefinition = outputStreamDefinition;
        this.oauthUsername = optionHolder.validateAndGetStaticValue(HttpConstants.RECEIVER_OAUTH_USERNAME,
                EMPTY_STRING);
        this.publisherURLOption = optionHolder.validateAndGetOption(HttpConstants.PUBLISHER_URL);
        this.publisherURL = optionHolder.validateAndGetStaticValue(HttpConstants.PUBLISHER_URL);
        this.oauthUserPassword = optionHolder.validateAndGetStaticValue(HttpConstants.RECEIVER_OAUTH_PASSWORD,
                EMPTY_STRING);
        this.refreshToken = optionHolder.validateAndGetStaticValue(HttpConstants.RECEIVER_REFRESH_TOKEN,
                EMPTY_STRING);
        clientStoreFile = optionHolder.validateAndGetStaticValue(HttpConstants.CLIENT_TRUSTSTORE_PATH_PARAM,
                HttpSinkUtil.trustStorePath(configReader));
        clientStorePass = optionHolder.validateAndGetStaticValue(HttpConstants.CLIENT_TRUSTSTORE_PASSWORD_PARAM,
                HttpSinkUtil.trustStorePassword(configReader));

        HttpsClient httpsClient = new HttpsClient();
        //generate encoded base64 auth for getting refresh token
        String consumerKeyValue = consumerKey + ":" + consumerSecret;
        String encodedRefreshAuth = "Basic " + encodeBase64(consumerKeyValue);
        ArrayList<String> accessTokenArray = httpsClient.clientGrandAccessToken(publisherURL, clientStoreFile,
                clientStorePass, encodedRefreshAuth);
        int responseCode = Integer.parseInt(accessTokenArray.get(1));
        if (responseCode == HttpConstants.SUCCESS_CODE) {
            accessToken = "Bearer " + accessTokenArray.get(0);
        } else if (responseCode == HttpConstants.AUTHENTICATION_FAIL_CODE) {
            accessToken = HttpConstants.EMPTY_STRING;
            log.error("Authentication Failure. Please apply valid Consumer key, " +
                    "Consumer secret for generate new access token. ");
        } else if (responseCode == HttpConstants.INTERNAL_SERVER_FAIL_CODE) {
            accessToken = HttpConstants.EMPTY_STRING;
            log.error("Internal server connection failure. Please apply the valid parameters. ");
        }


    }


    /**
     * This method will be called when events need to be published via this sink
     *
     * @param payload        payload of the event based on the supported event class exported by the extensions
     * @param dynamicOptions holds the dynamic options of this sink and Use this object to obtain dynamic options.
     */
    @Override
    public void publish(Object payload, DynamicOptions dynamicOptions) {
//get the dynamic parameter
        String headers = httpHeaderOption.getValue(dynamicOptions);
        List<Header> headersList = HttpSinkUtil.getHeaders(headers);

        //generate encoded base64 auth for getting refresh token
        String consumerKeyValue = consumerKey + ":" + consumerSecret;
        String encodedRefreshAuth = "Basic " + encodeBase64(consumerKeyValue);
        //check the availability of the authorization
        int authAvailability = 0;
        HttpsClient httpsClient = new HttpsClient();
        for (Header header : headersList) {
            if (header.getName().equals(HttpConstants.AUTHORIZATION_HEADER)) {
                authAvailability = authAvailability + 1;
            }
        }
        if (authAvailability == 0) {
            headersList.add(new Header(HttpConstants.AUTHORIZATION_HEADER, accessToken));
        }
        //check with a hash
        if (authanticationHash.containsKey(encodedRefreshAuth)) {
            String newAuthValue = authanticationHash.get(encodedRefreshAuth);
            for (Header header : headersList) {
                if (header.getName().equals(HttpConstants.AUTHORIZATION_HEADER)) {
                    header.setValue(newAuthValue);
                }
            }
        }
        //send a request to API and get the response
        int tryCount = 1;
        int response = sendRequest(payload, dynamicOptions, headersList, tryCount);
        //if authentication fails then get the new access token
        if (response == HttpConstants.AUTHENTICATION_FAIL_CODE) {
            ArrayList<String> newAccessTokenArray;
            if (!HttpConstants.EMPTY_STRING.equals(oauthUsername) ||
                    !HttpConstants.EMPTY_STRING.equals(oauthUserPassword)) {
                newAccessTokenArray = httpsClient.passwordGrandAccessToken(publisherURL, clientStoreFile,
                        clientStorePass, oauthUsername, oauthUserPassword, encodedRefreshAuth);
            } else if (!HttpConstants.EMPTY_STRING.equals(refreshToken)) {
                newAccessTokenArray = httpsClient.refreshGrandAccessToken(publisherURL, clientStoreFile,
                        clientStorePass, encodedRefreshAuth, refreshToken);
            } else {
                newAccessTokenArray = httpsClient.clientGrandAccessToken(publisherURL, clientStoreFile,
                        clientStorePass, encodedRefreshAuth);
            }
            int newAccessResponseCode = Integer.parseInt(newAccessTokenArray.get(1));
            if (newAccessResponseCode == HttpConstants.SUCCESS_CODE) {
                String newAccessToken = "Bearer " + newAccessTokenArray.get(0);
                authanticationHash.put(encodedRefreshAuth, newAccessToken);
                for (Header header : headersList) {
                    if (header.getName().equals(HttpConstants.AUTHORIZATION_HEADER)) {
                        header.setValue(newAccessToken);
                    }
                }
                //send a request to API with a new access token
                response = sendRequest(payload, dynamicOptions, headersList, tryCount);
                if (response == HttpConstants.SUCCESS_CODE) {
                    log.info("Request send successfully.");
                } else if (response == HttpConstants.AUTHENTICATION_FAIL_CODE) {
                    log.error("Authentication Failure. Please apply valid authorization. ");
                } else if (response == HttpConstants.INTERNAL_SERVER_FAIL_CODE) {
                    log.error("Internal server connection failure. Please apply the valid parameters. ");
                }

            } else if (newAccessResponseCode == HttpConstants.AUTHENTICATION_FAIL_CODE) {
                log.error("Authentication Failure. Please apply valid Consumer key, " +
                        "Consumer secret for generate new access token. ");
            } else {
                log.error("Internal server connection failure. Please apply the valid parameters. ");
            }
        } else if (response == HttpConstants.SUCCESS_CODE) {
            log.info("Request send successfully.");
        } else if (response == HttpConstants.INTERNAL_SERVER_FAIL_CODE) {
            log.error("Internal server connection failure. Please pass the valid parameters. ");
        }

    }

    private int sendRequest(Object payload, DynamicOptions dynamicOptions, List<Header> headersList, int tryCount) {
        if (!publisherURLOption.isStatic()) {
            super.initClientConnector(dynamicOptions);
        }
        String httpMethod = EMPTY_STRING.equals(httpMethodOption.getValue(dynamicOptions)) ?
                HttpConstants.METHOD_DEFAULT : httpMethodOption.getValue(dynamicOptions);
        String contentType = HttpSinkUtil.getContentType(mapType, headersList);
        String messageBody = getMessageBody(payload);
        HttpMethod httpReqMethod = new HttpMethod(httpMethod);
        HTTPCarbonMessage cMessage = new HTTPCarbonMessage(
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, httpReqMethod, EMPTY_STRING));
        cMessage = generateCarbonMessage(headersList, contentType, httpMethod, cMessage);
        if (!Constants.HTTP_GET_METHOD.equals(httpMethod)) {
            cMessage.addHttpContent(new DefaultLastHttpContent(Unpooled.wrappedBuffer(messageBody
                    .getBytes(Charset.defaultCharset()))));
        }
        cMessage.completeMessage();
        HttpResponseFuture httpResponseFuture = clientConnector.send(cMessage);
        CountDownLatch latch = new CountDownLatch(1);
        HttpResponseMessageListener httpListener =
                new HttpResponseMessageListener(getTrpProperties(dynamicOptions), sinkId, isDownloadEnabled, latch,
                        tryCount);
        httpResponseFuture.setHttpConnectorListener(httpListener);
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.debug(e);
        }
        HTTPCarbonMessage response = httpListener.getHttpResponseMessage();
        return response.getNettyHttpResponse().status().code();
    }

    @Override
    public String[] getSupportedDynamicOptions() {
        return new String[]{HttpConstants.HEADERS, HttpConstants.METHOD, HttpConstants.PUBLISHER_URL,
                 HttpConstants.DOWNLOAD_PATH, HttpConstants.PUBLISHER_URL};
    }

    private Map<String, Object> getTrpProperties(DynamicOptions dynamicOptions) {
        Event event = dynamicOptions.getEvent();
        Object[] data = event.getData();
        List<Attribute> attributes = outputStreamDefinition.getAttributeList();
        Map<String, Object> trpProperties = new HashMap<>();
        for (int i = 0; i < attributes.size(); i++) {
            trpProperties.put(attributes.get(i).getName(), data[i]);
        }
        if (isDownloadEnabled) {
            trpProperties.put(HttpConstants.DOWNLOAD_PATH, downloadPath.getValue(dynamicOptions));
        }
        return trpProperties;
    }

    private String encodeBase64(String consumerKeyValue) {

        ByteBuf byteBuf = Unpooled.wrappedBuffer(consumerKeyValue.getBytes(StandardCharsets.UTF_8));
        ByteBuf encodedByteBuf = Base64.encode(byteBuf);
        return encodedByteBuf.toString(StandardCharsets.UTF_8);
    }
}
