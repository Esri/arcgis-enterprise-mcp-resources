/*
 * Copyright 2026 Esri
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.mcp.healthcheck;

import java.io.IOException;
import java.util.Map;

/**
 * Minimal HTTP abstraction used by {@link ServiceHealthCheck}.
 * <p>
 * The production implementation is {@link ApacheHttpFetcher} (Apache HttpClient 5).
 * Unit tests supply a fake so they run without a network or a server.
 */
interface HttpFetcher extends AutoCloseable {

    /** Result of an HTTP request: status code and (possibly truncated) body. */
    record HttpResult(int statusCode, String body) {
    }

    /**
     * Sends an HTTP POST with an {@code application/x-www-form-urlencoded} body.
     *
     * @param url            target URL, without a query string
     * @param form           form fields to send in the body (for example {@code f=json}, {@code token=...})
     * @param timeoutSeconds response timeout for this request
     * @return the status code and body
     * @throws IOException if the request can't be completed (DNS, connect, timeout, TLS, ...)
     */
    HttpResult postForm(String url, Map<String, String> form, int timeoutSeconds) throws IOException;

    /** Short description of the HTTP library in use, for diagnostic logging. */
    String describe();

    @Override
    void close();
}
