/*
 * RED5 Open Source Flash Server - https://github.com/red5 Copyright 2006-2018 by respective authors (see below). All rights reserved. Licensed under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless
 * required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package org.red5.net.websocket;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import javax.websocket.Session;

import org.apache.commons.lang3.StringUtils;
import org.apache.tomcat.websocket.Constants;
import org.apache.tomcat.websocket.WsSession;
import org.red5.server.AttributeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocketConnection <br>
 * This class represents a WebSocket connection with a client (browser).
 *
 * @see https://tools.ietf.org/html/rfc6455
 *
 * @author Paul Gregoire
 */
public class WebSocketConnection extends AttributeStore {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConnection.class);

    // Sending async on windows times out
    private static boolean useAsync = !System.getProperty("os.name").contains("Windows");

    private AtomicBoolean connected = new AtomicBoolean(false);

    // associated websocket session
    private final WsSession wsSession;

    private String httpSessionId;

    private String host;

    private String path;

    private String origin;

    private String userAgent = "undefined";

    /**
     * Contains http headers and other web-socket information from the initial request.
     */
    private Map<String, List<String>> headers;

    private Map<String, Object> extensions;

    /**
     * Contains uri parameters from the initial request.
     */
    private Map<String, Object> querystringParameters;

    /**
     * Connection protocol (ex. chat, json, etc)
     */
    private String protocol;

    // stats
    private long readBytes, writtenBytes;

    // protect against mulitiple threads attempting to send at once
    private Semaphore sendLock = new Semaphore(1, true);

    // future used for sending to prevent concurrent write exceptions
    private Future<Void> sendFuture;

    // temporary storage for outgoing text messages
    private ConcurrentLinkedQueue<String> outputQueue = new ConcurrentLinkedQueue<>();

    public WebSocketConnection(WebSocketScope scope, Session session) {
        // set our path
        path = scope.getPath();
        // cast ws session
        this.wsSession = (WsSession) session;
        // set the local session id
        httpSessionId = Optional.ofNullable(wsSession.getHttpSessionId()).orElse(wsSession.getId());
        // get extensions
        wsSession.getNegotiatedExtensions().forEach(extension -> {
            if (extensions == null) {
                extensions = new HashMap<>();
            }
            extensions.put(extension.getName(), extension);
        });
        log.debug("extensions: {}", extensions);
        // get querystring
        String queryString = wsSession.getQueryString();
        log.debug("queryString: {}", queryString);
        if (StringUtils.isNotBlank(queryString)) {
            // bust it up by ampersand
            String[] qsParams = queryString.split("&");
            // loop-thru adding to the local map
            querystringParameters = new HashMap<>();
            Stream.of(qsParams).forEach(qsParam -> {
                String[] parts = qsParam.split("=");
                if (parts.length == 2) {
                    querystringParameters.put(parts[0], parts[1]);
                } else {
                    querystringParameters.put(parts[0], null);
                }
            });
        }
        // get request parameters
        Map<String, String> pathParameters = wsSession.getPathParameters();
        log.debug("pathParameters: {}", pathParameters);
        // get user props
        Map<String, Object> userProps = wsSession.getUserProperties();
        log.debug("userProps: {}", userProps);
    }

    /**
     * Sends text to the client.
     *
     * @param data
     *            string / text data
     * @throws UnsupportedEncodingException
     */
    public void send(String data) throws UnsupportedEncodingException {
        log.debug("send message: {}", data);
        // process the incoming string
        if (StringUtils.isNotBlank(data)) {
            if (wsSession != null && isConnected()) {
                // add the data to the queue first
                outputQueue.add(data);
                // check for an existing send future and if there is one, return and let it do its work
                if (sendFuture == null || sendFuture.isDone()) {
                    try {
                        // acquire lock and drain the queue
                        if (sendLock.tryAcquire(100L, TimeUnit.MILLISECONDS)) {
                            outputQueue.forEach(output -> {
                                if (isConnected()) {
                                    try {
                                        if (useAsync) {
                                            sendFuture = wsSession.getAsyncRemote().sendText(output);
                                            sendFuture.get(20000L, TimeUnit.MILLISECONDS);
                                        } else {
                                            wsSession.getBasicRemote().sendText(output);
                                        }
                                        writtenBytes += output.getBytes().length;
                                    } catch (TimeoutException e) {
                                        if (log.isDebugEnabled()) {
                                            log.warn("Send timed out");
                                        }
                                    } catch (Exception e) {
                                        if (log.isDebugEnabled()) {
                                            log.warn("Send wait interrupted", e);
                                        }
                                    } finally {
                                        outputQueue.remove(output);
                                    }
                                }
                            });
                            // release
                            sendLock.release();
                        }
                    } catch (InterruptedException e) {
                        log.warn("Send interrupted", e);
                        // release
                        sendLock.release();
                    }
                }
            }
        } else {
            throw new UnsupportedEncodingException("Cannot send a null string");
        }
    }

    /**
     * Sends binary data to the client.
     *
     * @param buf
     */
    public void send(byte[] buf) {
        if (log.isDebugEnabled()) {
            log.debug("send binary: {}", Arrays.toString(buf));
        }
        if (wsSession != null && isConnected()) {
            try {
                if (sendLock.tryAcquire(100L, TimeUnit.MILLISECONDS)) {
                    // send the bytes
                    if (useAsync) {
                        sendFuture = wsSession.getAsyncRemote().sendBinary(ByteBuffer.wrap(buf));
                        // wait up-to ws timeout
                        sendFuture.get(20000L, TimeUnit.MILLISECONDS);
                    } else {
                        wsSession.getBasicRemote().sendBinary(ByteBuffer.wrap(buf));
                    }
                    // update counter
                    writtenBytes += buf.length;
                    // release
                    sendLock.release();
                }
            } catch (Exception e) {
                log.warn("Send bytes interrupted", e);
                // release
                sendLock.release();
            }
        }
    }

    /**
     * Sends a ping to the client.
     *
     * @param buf
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public void sendPing(byte[] buf) throws IllegalArgumentException, IOException {
        if (log.isTraceEnabled()) {
            log.trace("send ping: {}", buf);
        }
        if (wsSession != null && isConnected()) {
            // send the bytes
            wsSession.getBasicRemote().sendPing(ByteBuffer.wrap(buf));
            // update counter
            writtenBytes += buf.length;
        }
    }

    /**
     * Sends a pong back to the client; normally in response to a ping.
     *
     * @param buf
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public void sendPong(byte[] buf) throws IllegalArgumentException, IOException {
        if (log.isTraceEnabled()) {
            log.trace("send pong: {}", buf);
        }
        if (wsSession != null && isConnected()) {
            // send the bytes
            wsSession.getBasicRemote().sendPong(ByteBuffer.wrap(buf));
            // update counter
            writtenBytes += buf.length;
        }
    }

    /**
     * close Connection
     */
    public void close() {
        if (connected.compareAndSet(true, false)) {
            // TODO disconnect from scope etc...
            if (sendFuture != null) {
                // be nice, dont interrupt
                sendFuture.cancel(false);
                try {
                    // give it a few ticks to complete (using write timeout 20s)
                    sendFuture.get(20000L, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.warn("Exception at close waiting for send to finish", e);
                    }
                } finally {
                    outputQueue.clear();
                }
            }
            // normal close
            if (wsSession != null) {
                try {
                    wsSession.close();
                } catch (IOException e) {
                }
            }
        }
    }

    public long getReadBytes() {
        return readBytes;
    }

    public void updateReadBytes(long read) {
        readBytes += read;
    }

    public long getWrittenBytes() {
        return writtenBytes;
    }

    /**
     * @return the connected
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * On connected, set flag.
     */
    public void setConnected() {
        boolean connectSuccess = connected.compareAndSet(false, true);
        log.debug("Connect success: {}", connectSuccess);
    }

    /**
     * @return the host
     */
    public String getHost() {
        return String.format("%s://%s%s", (isSecure() ? "wss" : "ws"), host, path);
    }

    /**
     * @param host
     *            the host to set
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * @return the origin
     */
    public String getOrigin() {
        return origin;
    }

    /**
     * @param origin
     *            the origin to set
     */
    public void setOrigin(String origin) {
        this.origin = origin;
    }

    /**
     * Return whether or not the session is secure.
     *
     * @return true if secure and false if unsecure or unconnected
     */
    public boolean isSecure() {
        return (wsSession != null) ? wsSession.isSecure() : false;
    }

    public String getPath() {
        return path;
    }

    /**
     * @param path
     *            the path to set
     */
    public void setPath(String path) {
        if (path.charAt(path.length() - 1) == '/') {
            this.path = path.substring(0, path.length() - 1);
        } else {
            this.path = path;
        }
    }

    /**
     * Returns the WsSession id associated with this connection.
     *
     * @return sessionId
     */
    public String getSessionId() {
        return wsSession.getId();
    }

    /**
     * Sets / overrides this connections HttpSession id.
     *
     * @param httpSessionId
     */
    public void setHttpSessionId(String httpSessionId) {
        this.httpSessionId = httpSessionId;
    }

    /**
     * Returns the HttpSession id associated with this connection.
     *
     * @return sessionId
     */
    public String getHttpSessionId() {
        return httpSessionId;
    }

    /**
     * Returns the user agent.
     *
     * @return userAgent
     */
    public String getUserAgent() {
        return userAgent;
    }

    /**
     * Sets the incoming headers.
     *
     * @param headers
     */
    public void setHeaders(Map<String, List<String>> headers) {
        if (headers != null && !headers.isEmpty()) {
            // look for both upper and lower case
            List<String> userAgentHeader = Optional.ofNullable(headers.get(WSConstants.HTTP_HEADER_USERAGENT)).orElse(headers.get(WSConstants.HTTP_HEADER_USERAGENT.toLowerCase()));
            if (userAgentHeader != null && !userAgentHeader.isEmpty()) {
                userAgent = userAgentHeader.get(0);
            }
            List<String> hostHeader = Optional.ofNullable(headers.get(Constants.HOST_HEADER_NAME)).orElse(headers.get(Constants.HOST_HEADER_NAME.toLowerCase()));
            if (hostHeader != null && !hostHeader.isEmpty()) {
                host = hostHeader.get(0);
            }
            List<String> originHeader = Optional.ofNullable(headers.get(Constants.ORIGIN_HEADER_NAME)).orElse(headers.get(Constants.ORIGIN_HEADER_NAME.toLowerCase()));
            if (originHeader != null && !originHeader.isEmpty()) {
                origin = originHeader.get(0);
            }
            Optional<List<String>> protocolHeader = Optional.ofNullable(headers.get(WSConstants.WS_HEADER_PROTOCOL));
            if (protocolHeader.isPresent()) {
                log.debug("Protocol header(s) exist: {}", protocolHeader.get());
                protocol = protocolHeader.get().get(0);
            }
            log.debug("Set from headers - user-agent: {} host: {} origin: {}", userAgent, host, origin);
            this.headers = headers;
        } else {
            this.headers = Collections.emptyMap();
        }
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public Map<String, Object> getQuerystringParameters() {
        return querystringParameters;
    }

    public void setQuerystringParameters(Map<String, Object> querystringParameters) {
        if (this.querystringParameters == null) {
            this.querystringParameters = new ConcurrentHashMap<>();
        }
        this.querystringParameters.putAll(querystringParameters);
    }

    /**
     * Returns whether or not extensions are enabled on this connection.
     *
     * @return true if extensions are enabled, false otherwise
     */
    public boolean hasExtensions() {
        return extensions != null && !extensions.isEmpty();
    }

    /**
     * Returns enabled extensions.
     *
     * @return extensions
     */
    public Map<String, Object> getExtensions() {
        return extensions;
    }

    /**
     * Sets the extensions.
     *
     * @param extensions
     */
    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }

    /**
     * Returns the extensions list as a comma separated string as specified by the rfc.
     *
     * @return extension list string or null if no extensions are enabled
     */
    public String getExtensionsAsString() {
        String extensionsList = null;
        if (extensions != null) {
            StringBuilder sb = new StringBuilder();
            for (String key : extensions.keySet()) {
                sb.append(key);
                sb.append("; ");
            }
            extensionsList = sb.toString().trim();
        }
        return extensionsList;
    }

    /**
     * Returns whether or not a protocol is enabled on this connection.
     *
     * @return true if protocol is enabled, false otherwise
     */
    public boolean hasProtocol() {
        return protocol != null;
    }

    /**
     * Returns the protocol enabled on this connection.
     *
     * @return protocol
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * Sets the protocol.
     *
     * @param protocol
     */
    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    @Override
    public String toString() {
        if (wsSession != null && connected.get()) {
            return "WebSocketConnection [wsId=" + wsSession.getId() + ", sessionId=" + httpSessionId + ", host=" + host + ", origin=" + origin + ", path=" + path + ", secure=" + isSecure() + ", connected=" + connected + "]";
        }
        if (wsSession == null) {
            return "WebSocketConnection [wsId=not-set, sessionId=not-set, host=" + host + ", origin=" + origin + ", path=" + path + ", secure=not-set, connected=" + connected + "]";
        }
        return "WebSocketConnection [host=" + host + ", origin=" + origin + ", path=" + path + " connected=false]";
    }

}
