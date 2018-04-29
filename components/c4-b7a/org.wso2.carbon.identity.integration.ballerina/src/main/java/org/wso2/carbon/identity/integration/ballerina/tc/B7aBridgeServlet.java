/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.integration.ballerina.tc;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.messaging.Constants;
import org.wso2.msf4j.Microservice;
import org.wso2.msf4j.internal.DataHolder;
import org.wso2.msf4j.internal.MSF4JConstants;
import org.wso2.msf4j.internal.MSF4JHttpConnectorListener;
import org.wso2.msf4j.internal.MicroservicesRegistryImpl;
import org.wso2.transport.http.netty.contract.HttpConnectorListener;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * This is the Servlet used for bridging.
 * Ideally we need to use Async Servlet. However we use locking of the tomcat thread as supporting
 * async on servlet engine needs to be done all the places, including valves and filters.
 */
public class B7aBridgeServlet extends HttpServlet {

    private static final String TOMCAT_MSF4J_CHANNEL = "Tomcat";
    private static final String HTTP_ASYNC_CONTEXT = "HttpAsyncContextProperty";
    private Log log = LogFactory.getLog(B7aBridgeServlet.class);
    private MicroservicesRegistryImpl msRegistry = new MicroservicesRegistryImpl();
    private MSF4JHttpConnectorListener msf4JHttpConnectorListener;
    private TomcatBridgeListener tomcatBridgeListener;

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {

        this.init();
    }

    public void init() throws ServletException {

        msRegistry.getSessionManager().init();
        msRegistry.initServices();
        DataHolder.getInstance().getMicroservicesRegistries().put(TOMCAT_MSF4J_CHANNEL, msRegistry);

        //TODO: Need to get this listener from the container. We should not create thread pools.
        msf4JHttpConnectorListener = new MSF4JHttpConnectorListener();
        tomcatBridgeListener = new TomcatBridgeListener();
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        TomcatCarbonMessage httpCarbonMessage = translateMessage(request, response);

        B7aAsyncContext asyncContext = new B7aAsyncContext(response);
        httpCarbonMessage.setProperty(HTTP_ASYNC_CONTEXT, asyncContext);
        httpCarbonMessage.setProperty(MSF4JConstants.CHANNEL_ID, TOMCAT_MSF4J_CHANNEL);
        msf4JHttpConnectorListener.onMessage(httpCarbonMessage);
        try {
            asyncContext.awaitForMsf4j(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new ServletException("MSF4J service interrupted.", e);
        }
    }

    private TomcatCarbonMessage translateMessage(HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {

        HttpMessage httpMessage = new DefaultHttpRequest(HttpVersion.HTTP_1_1,
                new HttpMethod(servletRequest.getMethod()), servletRequest.getRequestURI());
        TomcatCarbonMessage carbonMessage = new TomcatCarbonMessage(httpMessage, servletRequest);
        carbonMessage.setProperty(Constants.PROTOCOL, servletRequest.getProtocol());
        carbonMessage.setProperty(Constants.TO, servletRequest.getRequestURI()
                .substring(servletRequest.getRequestURI().indexOf("/", 1), servletRequest.getRequestURI().length()));
        carbonMessage
                .setProperty(org.wso2.transport.http.netty.common.Constants.HTTP_METHOD, servletRequest.getMethod());
        copyHeaders(carbonMessage, servletRequest);
        carbonMessage.getHttpResponseFuture().setHttpConnectorListener(tomcatBridgeListener);
        return carbonMessage;
    }

    private void copyHeaders(HTTPCarbonMessage carbonMessage, HttpServletRequest servletRequest) {

        Enumeration<String> headerNames = servletRequest.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            carbonMessage.setHeader(name, servletRequest.getHeader(name));
        }
    }

    @Override
    public String getServletInfo() {

        return null;
    }

    @Override
    public void destroy() {

        if (msf4JHttpConnectorListener != null) {
            //TODO: Make API changes in MSF4J so that msf4JHttpConnectorListener can be shut down.
        }
        if (msRegistry != null) {
            msRegistry.preDestroyServices();
        }
    }

    /**
     * Adds the micro-service to the Servlet.
     *
     * @param service
     */
    public void addMicroServiceToRegistry(Microservice service) {

        msRegistry.addService(service);
    }

    /**
     * Removes the micro-service from the Servlet.
     *
     * @param service
     */
    public void removeMicroServiceFromRegistry(Microservice service) {

        msRegistry.removeService(service);
    }

    private class TomcatBridgeListener implements HttpConnectorListener {

        private Log log = LogFactory.getLog(TomcatBridgeListener.class);

        @Override
        public void onMessage(HTTPCarbonMessage httpCarbonMessage) {

            B7aAsyncContext asyncContex = (B7aAsyncContext) httpCarbonMessage.getProperty(HTTP_ASYNC_CONTEXT);
            if (asyncContex != null) {
                try {
                    WritableByteChannel channel = Channels.newChannel(asyncContex.getResponse().getOutputStream());
                    //                    httpCarbonMessage..getFullMessageBody().stream().forEach(bb -> {
                    //                        try {
                    //                            channel.write(bb);
                    //                        } catch (IOException e) {
                    //                            log.error("An I/O error occurred while trying to write to Channel.");
                    //                        }
                    //                    });
                    asyncContex.complete();

                } catch (IOException e) {
                    log.error("An I/O error occurred.");
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {

            log.error("Error in Tomcat MSF4J connector listener", throwable);
        }
    }

}
