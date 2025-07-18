/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.eclipse.aether.transport.apache;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.auth.AuthScope;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.ServiceUnavailableRetryStrategy;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.DateUtils;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.auth.BasicSchemeFactory;
import org.apache.http.impl.auth.DigestSchemeFactory;
import org.apache.http.impl.auth.KerberosSchemeFactory;
import org.apache.http.impl.auth.NTLMSchemeFactory;
import org.apache.http.impl.auth.SPNegoSchemeFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.impl.client.StandardHttpRequestRetryHandler;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.AbstractTransporter;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.PutTask;
import org.eclipse.aether.spi.connector.transport.TransportTask;
import org.eclipse.aether.spi.connector.transport.http.ChecksumExtractor;
import org.eclipse.aether.spi.connector.transport.http.HttpTransporter;
import org.eclipse.aether.spi.connector.transport.http.HttpTransporterException;
import org.eclipse.aether.spi.io.PathProcessor;
import org.eclipse.aether.transfer.NoTransporterException;
import org.eclipse.aether.transfer.TransferCancelledException;
import org.eclipse.aether.util.ConfigUtils;
import org.eclipse.aether.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.requireNonNull;
import static org.eclipse.aether.spi.connector.transport.http.HttpConstants.CONTENT_RANGE_PATTERN;
import static org.eclipse.aether.transport.apache.ApacheTransporterConfigurationKeys.CONFIG_PROP_FOLLOW_REDIRECTS;
import static org.eclipse.aether.transport.apache.ApacheTransporterConfigurationKeys.CONFIG_PROP_HTTP_RETRY_HANDLER_NAME;
import static org.eclipse.aether.transport.apache.ApacheTransporterConfigurationKeys.CONFIG_PROP_HTTP_RETRY_HANDLER_REQUEST_SENT_ENABLED;
import static org.eclipse.aether.transport.apache.ApacheTransporterConfigurationKeys.CONFIG_PROP_MAX_REDIRECTS;
import static org.eclipse.aether.transport.apache.ApacheTransporterConfigurationKeys.CONFIG_PROP_USE_SYSTEM_PROPERTIES;
import static org.eclipse.aether.transport.apache.ApacheTransporterConfigurationKeys.DEFAULT_FOLLOW_REDIRECTS;
import static org.eclipse.aether.transport.apache.ApacheTransporterConfigurationKeys.DEFAULT_HTTP_RETRY_HANDLER_REQUEST_SENT_ENABLED;
import static org.eclipse.aether.transport.apache.ApacheTransporterConfigurationKeys.DEFAULT_MAX_REDIRECTS;
import static org.eclipse.aether.transport.apache.ApacheTransporterConfigurationKeys.DEFAULT_USE_SYSTEM_PROPERTIES;
import static org.eclipse.aether.transport.apache.ApacheTransporterConfigurationKeys.HTTP_RETRY_HANDLER_NAME_DEFAULT;
import static org.eclipse.aether.transport.apache.ApacheTransporterConfigurationKeys.HTTP_RETRY_HANDLER_NAME_STANDARD;

/**
 * A transporter for HTTP/HTTPS.
 */
final class ApacheTransporter extends AbstractTransporter implements HttpTransporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApacheTransporter.class);

    private final ChecksumExtractor checksumExtractor;

    private final PathProcessor pathProcessor;

    private final AuthenticationContext repoAuthContext;

    private final AuthenticationContext proxyAuthContext;

    private final URI baseUri;

    private final HttpHost server;

    private final HttpHost proxy;

    private final CloseableHttpClient client;

    private final Map<?, ?> headers;

    private final LocalState state;

    private final boolean preemptiveAuth;

    private final boolean preemptivePutAuth;

    private final boolean supportWebDav;

    @SuppressWarnings("checkstyle:methodlength")
    ApacheTransporter(
            RemoteRepository repository,
            RepositorySystemSession session,
            ChecksumExtractor checksumExtractor,
            PathProcessor pathProcessor)
            throws NoTransporterException {
        this.checksumExtractor = checksumExtractor;
        this.pathProcessor = pathProcessor;
        try {
            this.baseUri = new URI(repository.getUrl()).parseServerAuthority();
            if (baseUri.isOpaque()) {
                throw new URISyntaxException(repository.getUrl(), "URL must not be opaque");
            }
            this.server = URIUtils.extractHost(baseUri);
            if (server == null) {
                throw new URISyntaxException(repository.getUrl(), "URL lacks host name");
            }
        } catch (URISyntaxException e) {
            throw new NoTransporterException(repository, e.getMessage(), e);
        }
        this.proxy = toHost(repository.getProxy());

        this.repoAuthContext = AuthenticationContext.forRepository(session, repository);
        this.proxyAuthContext = AuthenticationContext.forProxy(session, repository);

        String httpsSecurityMode = ConfigUtils.getString(
                session,
                ConfigurationProperties.HTTPS_SECURITY_MODE_DEFAULT,
                ConfigurationProperties.HTTPS_SECURITY_MODE + "." + repository.getId(),
                ConfigurationProperties.HTTPS_SECURITY_MODE);
        final int connectionMaxTtlSeconds = ConfigUtils.getInteger(
                session,
                ConfigurationProperties.DEFAULT_HTTP_CONNECTION_MAX_TTL,
                ConfigurationProperties.HTTP_CONNECTION_MAX_TTL + "." + repository.getId(),
                ConfigurationProperties.HTTP_CONNECTION_MAX_TTL);
        final int maxConnectionsPerRoute = ConfigUtils.getInteger(
                session,
                ConfigurationProperties.DEFAULT_HTTP_MAX_CONNECTIONS_PER_ROUTE,
                ConfigurationProperties.HTTP_MAX_CONNECTIONS_PER_ROUTE + "." + repository.getId(),
                ConfigurationProperties.HTTP_MAX_CONNECTIONS_PER_ROUTE);
        this.state = new LocalState(
                session,
                repository,
                new ConnMgrConfig(
                        session, repoAuthContext, httpsSecurityMode, connectionMaxTtlSeconds, maxConnectionsPerRoute));

        this.headers = ConfigUtils.getMap(
                session,
                Collections.emptyMap(),
                ConfigurationProperties.HTTP_HEADERS + "." + repository.getId(),
                ConfigurationProperties.HTTP_HEADERS);

        this.preemptiveAuth = ConfigUtils.getBoolean(
                session,
                ConfigurationProperties.DEFAULT_HTTP_PREEMPTIVE_AUTH,
                ConfigurationProperties.HTTP_PREEMPTIVE_AUTH + "." + repository.getId(),
                ConfigurationProperties.HTTP_PREEMPTIVE_AUTH);
        this.preemptivePutAuth = ConfigUtils.getBoolean(
                session,
                ConfigurationProperties.DEFAULT_HTTP_PREEMPTIVE_PUT_AUTH,
                ConfigurationProperties.HTTP_PREEMPTIVE_PUT_AUTH + "." + repository.getId(),
                ConfigurationProperties.HTTP_PREEMPTIVE_PUT_AUTH);
        this.supportWebDav = ConfigUtils.getBoolean(
                session,
                ConfigurationProperties.DEFAULT_HTTP_SUPPORT_WEBDAV,
                ConfigurationProperties.HTTP_SUPPORT_WEBDAV + "." + repository.getId(),
                ConfigurationProperties.HTTP_SUPPORT_WEBDAV);
        String credentialEncoding = ConfigUtils.getString(
                session,
                ConfigurationProperties.DEFAULT_HTTP_CREDENTIAL_ENCODING,
                ConfigurationProperties.HTTP_CREDENTIAL_ENCODING + "." + repository.getId(),
                ConfigurationProperties.HTTP_CREDENTIAL_ENCODING);
        int connectTimeout = ConfigUtils.getInteger(
                session,
                ConfigurationProperties.DEFAULT_CONNECT_TIMEOUT,
                ConfigurationProperties.CONNECT_TIMEOUT + "." + repository.getId(),
                ConfigurationProperties.CONNECT_TIMEOUT);
        int requestTimeout = ConfigUtils.getInteger(
                session,
                ConfigurationProperties.DEFAULT_REQUEST_TIMEOUT,
                ConfigurationProperties.REQUEST_TIMEOUT + "." + repository.getId(),
                ConfigurationProperties.REQUEST_TIMEOUT);
        int retryCount = ConfigUtils.getInteger(
                session,
                ConfigurationProperties.DEFAULT_HTTP_RETRY_HANDLER_COUNT,
                ConfigurationProperties.HTTP_RETRY_HANDLER_COUNT + "." + repository.getId(),
                ConfigurationProperties.HTTP_RETRY_HANDLER_COUNT);
        long retryInterval = ConfigUtils.getLong(
                session,
                ConfigurationProperties.DEFAULT_HTTP_RETRY_HANDLER_INTERVAL,
                ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL + "." + repository.getId(),
                ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL);
        long retryIntervalMax = ConfigUtils.getLong(
                session,
                ConfigurationProperties.DEFAULT_HTTP_RETRY_HANDLER_INTERVAL_MAX,
                ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL_MAX + "." + repository.getId(),
                ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL_MAX);
        String serviceUnavailableCodesString = ConfigUtils.getString(
                session,
                ConfigurationProperties.DEFAULT_HTTP_RETRY_HANDLER_SERVICE_UNAVAILABLE,
                ConfigurationProperties.HTTP_RETRY_HANDLER_SERVICE_UNAVAILABLE + "." + repository.getId(),
                ConfigurationProperties.HTTP_RETRY_HANDLER_SERVICE_UNAVAILABLE);
        String retryHandlerName = ConfigUtils.getString(
                session,
                HTTP_RETRY_HANDLER_NAME_STANDARD,
                CONFIG_PROP_HTTP_RETRY_HANDLER_NAME + "." + repository.getId(),
                CONFIG_PROP_HTTP_RETRY_HANDLER_NAME);
        boolean retryHandlerRequestSentEnabled = ConfigUtils.getBoolean(
                session,
                DEFAULT_HTTP_RETRY_HANDLER_REQUEST_SENT_ENABLED,
                CONFIG_PROP_HTTP_RETRY_HANDLER_REQUEST_SENT_ENABLED + "." + repository.getId(),
                CONFIG_PROP_HTTP_RETRY_HANDLER_REQUEST_SENT_ENABLED);
        int maxRedirects = ConfigUtils.getInteger(
                session,
                DEFAULT_MAX_REDIRECTS,
                CONFIG_PROP_MAX_REDIRECTS + "." + repository.getId(),
                CONFIG_PROP_MAX_REDIRECTS);
        boolean followRedirects = ConfigUtils.getBoolean(
                session,
                DEFAULT_FOLLOW_REDIRECTS,
                CONFIG_PROP_FOLLOW_REDIRECTS + "." + repository.getId(),
                CONFIG_PROP_FOLLOW_REDIRECTS);
        String userAgent = ConfigUtils.getString(
                session, ConfigurationProperties.DEFAULT_USER_AGENT, ConfigurationProperties.USER_AGENT);

        Charset credentialsCharset = Charset.forName(credentialEncoding);
        Registry<AuthSchemeProvider> authSchemeRegistry = RegistryBuilder.<AuthSchemeProvider>create()
                .register(AuthSchemes.BASIC, new BasicSchemeFactory(credentialsCharset))
                .register(AuthSchemes.DIGEST, new DigestSchemeFactory(credentialsCharset))
                .register(AuthSchemes.NTLM, new NTLMSchemeFactory())
                .register(AuthSchemes.SPNEGO, new SPNegoSchemeFactory())
                .register(AuthSchemes.KERBEROS, new KerberosSchemeFactory())
                .build();
        SocketConfig socketConfig =
                // the time to establish connection (low level)
                SocketConfig.custom().setSoTimeout(requestTimeout).build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setMaxRedirects(maxRedirects)
                .setRedirectsEnabled(followRedirects)
                .setRelativeRedirectsAllowed(followRedirects)
                // the time waiting for data; max time between two data packets
                .setSocketTimeout(requestTimeout)
                // the time to establish the connection (high level)
                .setConnectTimeout(connectTimeout)
                // the time to wait for a connection from the connection manager/pool
                .setConnectionRequestTimeout(connectTimeout)
                .setLocalAddress(getHttpLocalAddress(session, repository))
                .setCookieSpec(CookieSpecs.STANDARD)
                .build();

        HttpRequestRetryHandler retryHandler;
        if (HTTP_RETRY_HANDLER_NAME_STANDARD.equals(retryHandlerName)) {
            retryHandler = new StandardHttpRequestRetryHandler(retryCount, retryHandlerRequestSentEnabled);
        } else if (HTTP_RETRY_HANDLER_NAME_DEFAULT.equals(retryHandlerName)) {
            retryHandler = new DefaultHttpRequestRetryHandler(retryCount, retryHandlerRequestSentEnabled);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported parameter " + CONFIG_PROP_HTTP_RETRY_HANDLER_NAME + " value: " + retryHandlerName);
        }
        Set<Integer> serviceUnavailableCodes = new HashSet<>();
        try {
            for (String code : ConfigUtils.parseCommaSeparatedUniqueNames(serviceUnavailableCodesString)) {
                serviceUnavailableCodes.add(Integer.parseInt(code));
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Illegal HTTP codes for " + ConfigurationProperties.HTTP_RETRY_HANDLER_SERVICE_UNAVAILABLE
                            + " (list of integers): " + serviceUnavailableCodesString);
        }
        ServiceUnavailableRetryStrategy serviceUnavailableRetryStrategy = new ResolverServiceUnavailableRetryStrategy(
                retryCount, retryInterval, retryIntervalMax, serviceUnavailableCodes);

        HttpClientBuilder builder = HttpClientBuilder.create()
                .setUserAgent(userAgent)
                .setRedirectStrategy(LaxRedirectStrategy.INSTANCE)
                .setDefaultSocketConfig(socketConfig)
                .setDefaultRequestConfig(requestConfig)
                .setServiceUnavailableRetryStrategy(serviceUnavailableRetryStrategy)
                .setRetryHandler(retryHandler)
                .setDefaultAuthSchemeRegistry(authSchemeRegistry)
                .setConnectionManager(state.getConnectionManager())
                .setConnectionManagerShared(true)
                .setDefaultCredentialsProvider(toCredentialsProvider(server, repoAuthContext, proxy, proxyAuthContext))
                .setProxy(proxy);
        final boolean useSystemProperties = ConfigUtils.getBoolean(
                session,
                DEFAULT_USE_SYSTEM_PROPERTIES,
                CONFIG_PROP_USE_SYSTEM_PROPERTIES + "." + repository.getId(),
                CONFIG_PROP_USE_SYSTEM_PROPERTIES);
        if (useSystemProperties) {
            LOGGER.warn(
                    "Transport used Apache HttpClient is instructed to use system properties: this may yield in unwanted side-effects!");
            LOGGER.warn("Please use documented means to configure resolver transport.");
            builder.useSystemProperties();
        }

        final String expectContinue = ConfigUtils.getString(
                session,
                null,
                ConfigurationProperties.HTTP_EXPECT_CONTINUE + "." + repository.getId(),
                ConfigurationProperties.HTTP_EXPECT_CONTINUE);
        if (expectContinue != null) {
            state.setExpectContinue(Boolean.parseBoolean(expectContinue));
        }

        final boolean reuseConnections = ConfigUtils.getBoolean(
                session,
                ConfigurationProperties.DEFAULT_HTTP_REUSE_CONNECTIONS,
                ConfigurationProperties.HTTP_REUSE_CONNECTIONS + "." + repository.getId(),
                ConfigurationProperties.HTTP_REUSE_CONNECTIONS);
        if (!reuseConnections) {
            builder.setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE);
        }

        this.client = builder.build();
    }

    /**
     * Returns non-null {@link InetAddress} if set in configuration, {@code null} otherwise.
     */
    private InetAddress getHttpLocalAddress(RepositorySystemSession session, RemoteRepository repository) {
        String bindAddress = ConfigUtils.getString(
                session,
                null,
                ConfigurationProperties.HTTP_LOCAL_ADDRESS + "." + repository.getId(),
                ConfigurationProperties.HTTP_LOCAL_ADDRESS);
        if (bindAddress == null) {
            return null;
        }
        try {
            return InetAddress.getByName(bindAddress);
        } catch (UnknownHostException uhe) {
            throw new IllegalArgumentException(
                    "Given bind address (" + bindAddress + ") cannot be resolved for remote repository " + repository,
                    uhe);
        }
    }

    private static HttpHost toHost(Proxy proxy) {
        HttpHost host = null;
        if (proxy != null) {
            // in Maven, the proxy.protocol is used for proxy matching against remote repository protocol; no TLS proxy
            // support
            // https://github.com/apache/maven/issues/2519
            // https://github.com/apache/maven-resolver/issues/745
            host = new HttpHost(proxy.getHost(), proxy.getPort());
        }
        return host;
    }

    private static CredentialsProvider toCredentialsProvider(
            HttpHost server, AuthenticationContext serverAuthCtx, HttpHost proxy, AuthenticationContext proxyAuthCtx) {
        CredentialsProvider provider = toCredentialsProvider(server.getHostName(), AuthScope.ANY_PORT, serverAuthCtx);
        if (proxy != null) {
            CredentialsProvider p = toCredentialsProvider(proxy.getHostName(), proxy.getPort(), proxyAuthCtx);
            provider = new DemuxCredentialsProvider(provider, p, proxy);
        }
        return provider;
    }

    private static CredentialsProvider toCredentialsProvider(String host, int port, AuthenticationContext ctx) {
        DeferredCredentialsProvider provider = new DeferredCredentialsProvider();
        if (ctx != null) {
            AuthScope basicScope = new AuthScope(host, port);
            provider.setCredentials(basicScope, new DeferredCredentialsProvider.BasicFactory(ctx));

            AuthScope ntlmScope = new AuthScope(host, port, AuthScope.ANY_REALM, "ntlm");
            provider.setCredentials(ntlmScope, new DeferredCredentialsProvider.NtlmFactory(ctx));
        }
        return provider;
    }

    LocalState getState() {
        return state;
    }

    private URI resolve(TransportTask task) {
        return UriUtils.resolve(baseUri, task.getLocation());
    }

    @Override
    public int classify(Throwable error) {
        if (error instanceof HttpTransporterException
                && ((HttpTransporterException) error).getStatusCode() == HttpStatus.SC_NOT_FOUND) {
            return ERROR_NOT_FOUND;
        }
        return ERROR_OTHER;
    }

    @Override
    protected void implPeek(PeekTask task) throws Exception {
        HttpHead request = commonHeaders(new HttpHead(resolve(task)));
        try {
            execute(request, null);
        } catch (HttpResponseException e) {
            throw new HttpTransporterException(e.getStatusCode());
        }
    }

    @Override
    protected void implGet(GetTask task) throws Exception {
        boolean resume = true;

        EntityGetter getter = new EntityGetter(task);
        HttpGet request = commonHeaders(new HttpGet(resolve(task)));
        while (true) {
            try {
                if (resume) {
                    resume(request, task);
                }
                execute(request, getter);
                break;
            } catch (HttpResponseException e) {
                if (resume
                        && e.getStatusCode() == HttpStatus.SC_PRECONDITION_FAILED
                        && request.containsHeader(HttpHeaders.RANGE)) {
                    request = commonHeaders(new HttpGet(resolve(task)));
                    resume = false;
                    continue;
                }
                throw new HttpTransporterException(e.getStatusCode());
            }
        }
    }

    @Override
    protected void implPut(PutTask task) throws Exception {
        PutTaskEntity entity = new PutTaskEntity(task);
        HttpPut request = commonHeaders(entity(new HttpPut(resolve(task)), entity));
        try {
            execute(request, null);
        } catch (HttpResponseException e) {
            if (e.getStatusCode() == HttpStatus.SC_EXPECTATION_FAILED && request.containsHeader(HttpHeaders.EXPECT)) {
                state.setExpectContinue(false);
                request = commonHeaders(entity(new HttpPut(request.getURI()), entity));
                execute(request, null);
                return;
            }
            throw new HttpTransporterException(e.getStatusCode());
        }
    }

    private void execute(HttpUriRequest request, EntityGetter getter) throws Exception {
        try {
            SharingHttpContext context = new SharingHttpContext(state);
            prepare(request, context);
            try (CloseableHttpResponse response = client.execute(server, request, context)) {
                try {
                    context.close();
                    handleStatus(response);
                    if (getter != null) {
                        getter.handle(response);
                    }
                } finally {
                    EntityUtils.consumeQuietly(response.getEntity());
                }
            }
        } catch (IOException e) {
            if (e.getCause() instanceof TransferCancelledException) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    private void prepare(HttpUriRequest request, SharingHttpContext context) throws Exception {
        final boolean put = HttpPut.METHOD_NAME.equalsIgnoreCase(request.getMethod());
        if (preemptiveAuth || (preemptivePutAuth && put)) {
            context.getAuthCache().put(server, new BasicScheme());
        }
        if (supportWebDav) {
            if (state.getWebDav() == null && (put || isPayloadPresent(request))) {
                HttpOptions req = commonHeaders(new HttpOptions(request.getURI()));
                try (CloseableHttpResponse response = client.execute(server, req, context)) {
                    state.setWebDav(response.containsHeader(HttpHeaders.DAV));
                    EntityUtils.consumeQuietly(response.getEntity());
                } catch (IOException e) {
                    LOGGER.debug("Failed to prepare HTTP context", e);
                }
            }
            if (put && Boolean.TRUE.equals(state.getWebDav())) {
                mkdirs(request.getURI(), context);
            }
        }
    }

    private void mkdirs(URI uri, SharingHttpContext context) throws Exception {
        List<URI> dirs = UriUtils.getDirectories(baseUri, uri);
        int index = 0;
        for (; index < dirs.size(); index++) {
            try (CloseableHttpResponse response =
                    client.execute(server, commonHeaders(new HttpMkCol(dirs.get(index))), context)) {
                try {
                    int status = response.getStatusLine().getStatusCode();
                    if (status < 300 || status == HttpStatus.SC_METHOD_NOT_ALLOWED) {
                        break;
                    } else if (status == HttpStatus.SC_CONFLICT) {
                        continue;
                    }
                    handleStatus(response);
                } finally {
                    EntityUtils.consumeQuietly(response.getEntity());
                }
            } catch (IOException e) {
                LOGGER.debug("Failed to create parent directory {}", dirs.get(index), e);
                return;
            }
        }
        for (index--; index >= 0; index--) {
            try (CloseableHttpResponse response =
                    client.execute(server, commonHeaders(new HttpMkCol(dirs.get(index))), context)) {
                try {
                    handleStatus(response);
                } finally {
                    EntityUtils.consumeQuietly(response.getEntity());
                }
            } catch (IOException e) {
                LOGGER.debug("Failed to create parent directory {}", dirs.get(index), e);
                return;
            }
        }
    }

    private <T extends HttpEntityEnclosingRequest> T entity(T request, HttpEntity entity) {
        request.setEntity(entity);
        return request;
    }

    private boolean isPayloadPresent(HttpUriRequest request) {
        if (request instanceof HttpEntityEnclosingRequest) {
            HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
            return entity != null && entity.getContentLength() != 0;
        }
        return false;
    }

    private <T extends HttpUriRequest> T commonHeaders(T request) {
        request.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store");
        request.setHeader(HttpHeaders.PRAGMA, "no-cache");

        if (state.isExpectContinue() && isPayloadPresent(request)) {
            request.setHeader(HttpHeaders.EXPECT, "100-continue");
        }

        for (Map.Entry<?, ?> entry : headers.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                continue;
            }
            if (entry.getValue() instanceof String) {
                request.setHeader(entry.getKey().toString(), entry.getValue().toString());
            } else {
                request.removeHeaders(entry.getKey().toString());
            }
        }

        if (!state.isExpectContinue()) {
            request.removeHeaders(HttpHeaders.EXPECT);
        }

        return request;
    }

    private <T extends HttpUriRequest> void resume(T request, GetTask task) throws IOException {
        long resumeOffset = task.getResumeOffset();
        if (resumeOffset > 0L && task.getDataPath() != null) {
            long lastModified = Files.getLastModifiedTime(task.getDataPath()).toMillis();
            request.setHeader(HttpHeaders.RANGE, "bytes=" + resumeOffset + '-');
            request.setHeader(
                    HttpHeaders.IF_UNMODIFIED_SINCE, DateUtils.formatDate(new Date(lastModified - 60L * 1000L)));
            request.setHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
        }
    }

    private void handleStatus(CloseableHttpResponse response) throws Exception {
        int status = response.getStatusLine().getStatusCode();
        if (status >= 300) {
            ApacheRFC9457Reporter.INSTANCE.generateException(response, (statusCode, reasonPhrase) -> {
                throw new HttpResponseException(statusCode, reasonPhrase + " (" + statusCode + ")");
            });
        }
    }

    @Override
    protected void implClose() {
        try {
            client.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        AuthenticationContext.close(repoAuthContext);
        AuthenticationContext.close(proxyAuthContext);
        state.close();
    }

    private class EntityGetter {

        private final GetTask task;

        EntityGetter(GetTask task) {
            this.task = task;
        }

        public void handle(CloseableHttpResponse response) throws IOException, TransferCancelledException {
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                entity = new ByteArrayEntity(new byte[0]);
            }

            long offset = 0L, length = entity.getContentLength();
            Header rangeHeader = response.getFirstHeader(HttpHeaders.CONTENT_RANGE);
            String range = rangeHeader != null ? rangeHeader.getValue() : null;
            if (range != null) {
                Matcher m = CONTENT_RANGE_PATTERN.matcher(range);
                if (!m.matches()) {
                    throw new IOException("Invalid Content-Range header for partial download: " + range);
                }
                offset = Long.parseLong(m.group(1));
                length = Long.parseLong(m.group(2)) + 1L;
                if (offset < 0L || offset >= length || (offset > 0L && offset != task.getResumeOffset())) {
                    throw new IOException("Invalid Content-Range header for partial download from offset "
                            + task.getResumeOffset() + ": " + range);
                }
            }

            final boolean resume = offset > 0L;
            final Path dataFile = task.getDataPath();
            if (dataFile == null) {
                try (InputStream is = entity.getContent()) {
                    utilGet(task, is, true, length, resume);
                    extractChecksums(response);
                }
            } else {
                try (FileUtils.CollocatedTempFile tempFile = FileUtils.newTempFile(dataFile)) {
                    task.setDataPath(tempFile.getPath(), resume);
                    if (resume && Files.isRegularFile(dataFile)) {
                        try (InputStream inputStream = Files.newInputStream(dataFile)) {
                            Files.copy(inputStream, tempFile.getPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    try (InputStream is = entity.getContent()) {
                        utilGet(task, is, true, length, resume);
                    }
                    tempFile.move();
                } finally {
                    task.setDataPath(dataFile);
                }
            }
            if (task.getDataPath() != null) {
                Header lastModifiedHeader =
                        response.getFirstHeader(HttpHeaders.LAST_MODIFIED); // note: Wagon also does first not last
                if (lastModifiedHeader != null) {
                    Date lastModified = DateUtils.parseDate(lastModifiedHeader.getValue());
                    if (lastModified != null) {
                        pathProcessor.setLastModified(task.getDataPath(), lastModified.getTime());
                    }
                }
            }
            extractChecksums(response);
        }

        private void extractChecksums(CloseableHttpResponse response) {
            Map<String, String> checksums = checksumExtractor.extractChecksums(headerGetter(response));
            if (checksums != null && !checksums.isEmpty()) {
                checksums.forEach(task::setChecksum);
            }
        }
    }

    private static Function<String, String> headerGetter(CloseableHttpResponse closeableHttpResponse) {
        return s -> {
            Header header = closeableHttpResponse.getFirstHeader(s);
            return header != null ? header.getValue() : null;
        };
    }

    private class PutTaskEntity extends AbstractHttpEntity {

        private final PutTask task;

        PutTaskEntity(PutTask task) {
            this.task = task;
        }

        @Override
        public boolean isRepeatable() {
            return true;
        }

        @Override
        public boolean isStreaming() {
            return false;
        }

        @Override
        public long getContentLength() {
            return task.getDataLength();
        }

        @Override
        public InputStream getContent() throws IOException {
            return task.newInputStream();
        }

        @Override
        public void writeTo(OutputStream os) throws IOException {
            try {
                utilPut(task, os, false);
            } catch (TransferCancelledException e) {
                throw (IOException) new InterruptedIOException().initCause(e);
            }
        }
    }

    private static class ResolverServiceUnavailableRetryStrategy implements ServiceUnavailableRetryStrategy {
        private final int retryCount;

        private final long retryInterval;

        private final long retryIntervalMax;

        private final Set<Integer> serviceUnavailableHttpCodes;

        /**
         * Ugly, but forced by HttpClient API {@link ServiceUnavailableRetryStrategy}: the calls for
         * {@link #retryRequest(HttpResponse, int, HttpContext)} and {@link #getRetryInterval()} are done by same
         * thread and are actually done from spot that are very close to each other (almost subsequent calls).
         */
        private static final ThreadLocal<Long> RETRY_INTERVAL_HOLDER = new ThreadLocal<>();

        private ResolverServiceUnavailableRetryStrategy(
                int retryCount, long retryInterval, long retryIntervalMax, Set<Integer> serviceUnavailableHttpCodes) {
            if (retryCount < 0) {
                throw new IllegalArgumentException("retryCount must be >= 0");
            }
            if (retryInterval < 0L) {
                throw new IllegalArgumentException("retryInterval must be >= 0");
            }
            if (retryIntervalMax < 0L) {
                throw new IllegalArgumentException("retryIntervalMax must be >= 0");
            }
            this.retryCount = retryCount;
            this.retryInterval = retryInterval;
            this.retryIntervalMax = retryIntervalMax;
            this.serviceUnavailableHttpCodes = requireNonNull(serviceUnavailableHttpCodes);
        }

        @Override
        public boolean retryRequest(HttpResponse response, int executionCount, HttpContext context) {
            final boolean retry = executionCount <= retryCount
                    && (serviceUnavailableHttpCodes.contains(
                            response.getStatusLine().getStatusCode()));
            if (retry) {
                Long retryInterval = retryInterval(response, executionCount, context);
                if (retryInterval != null) {
                    RETRY_INTERVAL_HOLDER.set(retryInterval);
                    return true;
                }
            }
            RETRY_INTERVAL_HOLDER.remove();
            return false;
        }

        /**
         * Calculates retry interval in milliseconds. If {@link HttpHeaders#RETRY_AFTER} header present, it obeys it.
         * Otherwise, it returns {@link this#retryInterval} long value multiplied with {@code executionCount} (starts
         * from 1 and goes 2, 3,...).
         *
         * @return Long representing the retry interval as millis, or {@code null} if the request should be failed.
         */
        private Long retryInterval(HttpResponse httpResponse, int executionCount, HttpContext httpContext) {
            Long result = null;
            Header header = httpResponse.getFirstHeader(HttpHeaders.RETRY_AFTER);
            if (header != null && header.getValue() != null) {
                String headerValue = header.getValue();
                if (headerValue.contains(":")) { // is date when to retry
                    Date when = DateUtils.parseDate(headerValue); // presumably future
                    if (when != null) {
                        result = Math.max(when.getTime() - System.currentTimeMillis(), 0L);
                    }
                } else {
                    try {
                        result = Long.parseLong(headerValue) * 1000L; // is in seconds
                    } catch (NumberFormatException e) {
                        // fall through
                    }
                }
            }
            if (result == null) {
                result = executionCount * this.retryInterval;
            }
            if (result > retryIntervalMax) {
                return null;
            }
            return result;
        }

        @Override
        public long getRetryInterval() {
            Long ri = RETRY_INTERVAL_HOLDER.get();
            if (ri == null) {
                return 0L;
            }
            RETRY_INTERVAL_HOLDER.remove();
            return ri;
        }
    }
}
