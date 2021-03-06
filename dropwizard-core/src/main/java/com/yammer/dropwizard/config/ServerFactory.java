package com.yammer.dropwizard.config;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.sun.jersey.api.container.filter.PostReplaceFilter.ConfigFlag;
import com.sun.jersey.spi.container.servlet.ServletContainer;
import com.yammer.dropwizard.config.HttpConfiguration.ConnectorType;
import com.yammer.dropwizard.jersey.JacksonMessageBodyProvider;
import com.yammer.dropwizard.jetty.BiDiGzipHandler;
import com.yammer.dropwizard.jetty.NonblockingServletHolder;
import com.yammer.dropwizard.jetty.UnbrandedErrorHandler;
import com.yammer.dropwizard.servlets.ThreadNameFilter;
import com.yammer.dropwizard.util.Duration;
import com.yammer.dropwizard.util.Size;
import com.yammer.metrics.HealthChecks;
import com.yammer.metrics.core.HealthCheck;
import com.yammer.metrics.jetty.*;
import com.yammer.metrics.reporting.AdminServlet;
import com.yammer.metrics.util.DeadlockHealthCheck;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.AbstractNIOConnector;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.DispatcherType;
import java.io.File;
import java.net.URI;
import java.security.KeyStore;
import java.util.EnumSet;

/*
 * A factory for creating instances of {@link org.eclipse.jetty.server.Server} and configuring Servlets
 * 
 * Registers {@link com.yammer.metrics.core.HealthCheck}s, both default and user defined
 * 
 * Creates instances of {@link org.eclipse.jetty.server.Connector},
 * configured by {@link com.yammer.dropwizard.config.HttpConfiguration} for external and admin port
 * 
 * Registers {@link org.eclipse.jetty.server.Handler}s for admin and service Servlets.
 * {@link TaskServlet} 
 * {@link AdminServlet}
 * {@link com.sun.jersey.spi.container.servlet.ServletContainer} with all resources in {@link DropwizardResourceConfig} 
 * 
 * */
public class ServerFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerFactory.class);

    private final HttpConfiguration config;
    private final RequestLogHandlerFactory requestLogHandlerFactory;

    public ServerFactory(HttpConfiguration config, String name) {
        this.config = config;
        this.requestLogHandlerFactory = new RequestLogHandlerFactory(config.getRequestLogConfiguration(),
                                                                     name);
    }

    public Server buildServer(Environment env) throws ConfigurationException {
        HealthChecks.defaultRegistry().register(new DeadlockHealthCheck());
        for (HealthCheck healthCheck : env.getHealthChecks()) {
            HealthChecks.defaultRegistry().register(healthCheck);
        }
        final Server server = createServer(env);
        server.setHandler(createHandler(env));
        return server;
    }

    private Server createServer(Environment env) {
        final Server server = env.getServer();
        LOGGER.info("createServer method called");
        if ((config.getSslPort() != 9999) && (config.getSslConfiguration() != null) && (config.getSslPort() != config.getPort())) {
        	LOGGER.info("About to create custom RTR server");
        	setCustomRtrServer(server);
        } else {
        	LOGGER.info("About to follow coda's pattern in creating servers");
            server.addConnector(createExternalConnector());
            
            // if we're dynamically allocating ports, no worries if they are the same (i.e. 0)
            if (config.getAdminPort() == 0 || (config.getAdminPort() != config.getPort()) ) {
                server.addConnector(createInternalConnector());
            }
        }
        
        server.addBean(new UnbrandedErrorHandler());

        server.setSendDateHeader(config.isDateHeaderEnabled());
        server.setSendServerVersion(config.isServerHeaderEnabled());

        server.setThreadPool(createThreadPool());

        server.setStopAtShutdown(true);

        server.setGracefulShutdown((int) config.getShutdownGracePeriod().toMilliseconds());

        return server;
    }
    
    //RTR patch
    private void setCustomRtrServer(Server server) {
    	LOGGER.info("Custom RTR Server");
    	 // if we're dynamically allocating ports, no worries if they are the same (i.e. 0)
        if (config.getAdminPort() == 0 || (config.getAdminPort() != config.getPort()) && (config.getAdminPort() != config.getSslPort())) {
            server.addConnector(createInternalConnector());
        }
        LOGGER.info("SSL port: " + config.getSslPort());
        LOGGER.info("Non-SSL port: " + config.getPort());
        server.addConnector(createExternalConnector(config.getSslPort(), config.getSslConnectorType(), "SSL"));
        server.addConnector(createExternalConnector(config.getPort(), config.getNonSslConnectorType(), "NonSSL"));
        
    }

    //RTR Patch
    private Connector createExternalConnector(int port, ConnectorType connectorType, String connectorName) {
    	final AbstractConnector connector;
        switch (connectorType) {
            case BLOCKING:
                connector = new InstrumentedBlockingChannelConnector(port);
                break;
            case LEGACY:
                connector = new InstrumentedSocketConnector(port);
                break;
            case LEGACY_SSL:
                connector = new InstrumentedSslSocketConnector(port);
                break;
            case NONBLOCKING:
                connector = new InstrumentedSelectChannelConnector(port);
                break;
            case NONBLOCKING_SSL:
                connector = new InstrumentedSslSelectChannelConnector(port);
                break;
            default:
                throw new IllegalStateException("Invalid connector type: " + config.getConnectorType());
        }

        if (connector instanceof SslConnector) {
            configureSslContext(((SslConnector) connector).getSslContextFactory());
        }

        if (connector instanceof SelectChannelConnector) {
            ((SelectChannelConnector) connector).setLowResourcesConnections(config.getLowResourcesConnectionThreshold());
        }

        if (connector instanceof AbstractNIOConnector) {
            ((AbstractNIOConnector) connector).setUseDirectBuffers(config.useDirectBuffers());
        }


        connector.setHost(config.getBindHost().orNull());

        connector.setAcceptors(config.getAcceptorThreads());

        connector.setForwarded(config.useForwardedHeaders());

        connector.setMaxIdleTime((int) config.getMaxIdleTime().toMilliseconds());

        connector.setLowResourcesMaxIdleTime((int) config.getLowResourcesMaxIdleTime()
                                                         .toMilliseconds());

        connector.setAcceptorPriorityOffset(config.getAcceptorThreadPriorityOffset());

        connector.setAcceptQueueSize(config.getAcceptQueueSize());

        connector.setMaxBuffers(config.getMaxBufferCount());

        connector.setRequestBufferSize((int) config.getRequestBufferSize().toBytes());

        connector.setRequestHeaderSize((int) config.getRequestHeaderBufferSize().toBytes());

        connector.setResponseBufferSize((int) config.getResponseBufferSize().toBytes());

        connector.setResponseHeaderSize((int) config.getResponseHeaderBufferSize().toBytes());

        connector.setReuseAddress(config.isReuseAddressEnabled());
        
        final Optional<Duration> lingerTime = config.getSoLingerTime();

        if (lingerTime.isPresent()) {
            connector.setSoLingerTime((int) lingerTime.get().toMilliseconds());
        }

        connector.setPort(port);
        connector.setName(connectorName);
    	
    	return connector;
    }
    
    
    private Connector createExternalConnector() {
        final AbstractConnector connector = createConnector(config.getPort());

        connector.setHost(config.getBindHost().orNull());

        connector.setAcceptors(config.getAcceptorThreads());

        connector.setForwarded(config.useForwardedHeaders());

        connector.setMaxIdleTime((int) config.getMaxIdleTime().toMilliseconds());

        connector.setLowResourcesMaxIdleTime((int) config.getLowResourcesMaxIdleTime()
                                                         .toMilliseconds());

        connector.setAcceptorPriorityOffset(config.getAcceptorThreadPriorityOffset());

        connector.setAcceptQueueSize(config.getAcceptQueueSize());

        connector.setMaxBuffers(config.getMaxBufferCount());

        connector.setRequestBufferSize((int) config.getRequestBufferSize().toBytes());

        connector.setRequestHeaderSize((int) config.getRequestHeaderBufferSize().toBytes());

        connector.setResponseBufferSize((int) config.getResponseBufferSize().toBytes());

        connector.setResponseHeaderSize((int) config.getResponseHeaderBufferSize().toBytes());

        connector.setReuseAddress(config.isReuseAddressEnabled());
        
        final Optional<Duration> lingerTime = config.getSoLingerTime();

        if (lingerTime.isPresent()) {
            connector.setSoLingerTime((int) lingerTime.get().toMilliseconds());
        }

        connector.setPort(config.getPort());
        connector.setName("main");

        return connector;
    }

    private AbstractConnector createConnector(int port) {
        final AbstractConnector connector;
        switch (config.getConnectorType()) {
            case BLOCKING:
                connector = new InstrumentedBlockingChannelConnector(port);
                break;
            case LEGACY:
                connector = new InstrumentedSocketConnector(port);
                break;
            case LEGACY_SSL:
                connector = new InstrumentedSslSocketConnector(port);
                break;
            case NONBLOCKING:
                connector = new InstrumentedSelectChannelConnector(port);
                break;
            case NONBLOCKING_SSL:
                connector = new InstrumentedSslSelectChannelConnector(port);
                break;
            default:
                throw new IllegalStateException("Invalid connector type: " + config.getConnectorType());
        }

        if (connector instanceof SslConnector) {
            configureSslContext(((SslConnector) connector).getSslContextFactory());
        }

        if (connector instanceof SelectChannelConnector) {
            ((SelectChannelConnector) connector).setLowResourcesConnections(config.getLowResourcesConnectionThreshold());
        }

        if (connector instanceof AbstractNIOConnector) {
            ((AbstractNIOConnector) connector).setUseDirectBuffers(config.useDirectBuffers());
        }

        return connector;
    }

    private void configureSslContext(SslContextFactory factory) {
        final SslConfiguration sslConfig = config.getSslConfiguration();

        for (File keyStore : sslConfig.getKeyStore().asSet()) {
            factory.setKeyStorePath(keyStore.getAbsolutePath());
        }

        for (String password : sslConfig.getKeyStorePassword().asSet()) {
            factory.setKeyStorePassword(password);
        }

        for (String password : sslConfig.getKeyManagerPassword().asSet()) {
            factory.setKeyManagerPassword(password);
        }

        for (String certAlias : sslConfig.getCertAlias().asSet()) {
            factory.setCertAlias(certAlias);
        }

        final String keyStoreType = sslConfig.getKeyStoreType();
        if (keyStoreType.startsWith("Windows-")) {
            try {
                final KeyStore keyStore = KeyStore.getInstance(keyStoreType);

                keyStore.load(null, null);
                factory.setKeyStore(keyStore);

            } catch (Exception e) {
                throw new IllegalStateException("Windows key store not supported", e);
            }
        } else {
            factory.setKeyStoreType(keyStoreType);
        }

        for (File trustStore : sslConfig.getTrustStore().asSet()) {
            factory.setTrustStore(trustStore.getAbsolutePath());
        }

        for (String password : sslConfig.getTrustStorePassword().asSet()) {
            factory.setTrustStorePassword(password);
        }

        final String trustStoreType = sslConfig.getTrustStoreType();
        if (trustStoreType.startsWith("Windows-")) {
            try {
                final KeyStore keyStore = KeyStore.getInstance(trustStoreType);

                keyStore.load(null, null);
                factory.setTrustStore(keyStore);

            } catch (Exception e) {
                throw new IllegalStateException("Windows key store not supported", e);
            }
        } else {
            factory.setTrustStoreType(trustStoreType);
        }

        for (Boolean needClientAuth : sslConfig.getNeedClientAuth().asSet()) {
            factory.setNeedClientAuth(needClientAuth);
        }

        for (Boolean wantClientAuth : sslConfig.getWantClientAuth().asSet()) {
            factory.setWantClientAuth(wantClientAuth);
        }

        for (Boolean allowRenegotiate : sslConfig.getAllowRenegotiate().asSet()) {
            factory.setAllowRenegotiate(allowRenegotiate);
        }

        for (File crlPath : sslConfig.getCrlPath().asSet()) {
            factory.setCrlPath(crlPath.getAbsolutePath());
        }

        for (Boolean enable : sslConfig.getCrldpEnabled().asSet()) {
            factory.setEnableCRLDP(enable);
        }

        for (Boolean enable : sslConfig.getOcspEnabled().asSet()) {
            factory.setEnableOCSP(enable);
        }

        for (Integer length : sslConfig.getMaxCertPathLength().asSet()) {
            factory.setMaxCertPathLength(length);
        }

        for (URI uri : sslConfig.getOcspResponderUrl().asSet()) {
            factory.setOcspResponderURL(uri.toASCIIString());
        }

        for (String provider : sslConfig.getJceProvider().asSet()) {
            factory.setProvider(provider);
        }

        for (Boolean validate : sslConfig.getValidatePeers().asSet()) {
            factory.setValidatePeerCerts(validate);
        }

        factory.setIncludeProtocols(Iterables.toArray(sslConfig.getSupportedProtocols(),
                                                      String.class));
    }


    private Handler createHandler(Environment env) {
        final HandlerCollection collection = new HandlerCollection();

        collection.addHandler(createExternalServlet(env));
        collection.addHandler(createInternalServlet(env));

        if (requestLogHandlerFactory.isEnabled()) {
            collection.addHandler(requestLogHandlerFactory.build());
        }

        return collection;
    }

    private Handler createInternalServlet(Environment env) {
        final ServletContextHandler handler = env.getAdminContext();
        handler.addServlet(new NonblockingServletHolder(new AdminServlet()), "/*");

        if (config.getAdminPort() != 0 && config.getAdminPort() == config.getPort()) {
            handler.setContextPath("/admin");
            handler.setConnectorNames(new String[]{"main"});
        } else {
            handler.setConnectorNames(new String[]{"internal"});
        }

        if (config.getAdminUsername().isPresent() || config.getAdminPassword().isPresent()) {
            handler.setSecurityHandler(basicAuthHandler(config.getAdminUsername().or(""), config.getAdminPassword().or("")));
        }

        return handler;
    }

    private SecurityHandler basicAuthHandler(String username, String password) {

        final HashLoginService loginService = new HashLoginService();
        loginService.putUser(username, Credential.getCredential(password), new String[] {"user"});
        loginService.setName("admin");

        final Constraint constraint = new Constraint();
        constraint.setName(Constraint.__BASIC_AUTH);
        constraint.setRoles(new String[]{"user"});
        constraint.setAuthenticate(true);

        final ConstraintMapping constraintMapping = new ConstraintMapping();
        constraintMapping.setConstraint(constraint);
        constraintMapping.setPathSpec("/*");

        final ConstraintSecurityHandler csh = new ConstraintSecurityHandler();
        csh.setAuthenticator(new BasicAuthenticator());
        csh.setRealmName("admin");
        csh.addConstraintMapping(constraintMapping);
        csh.setLoginService(loginService);

        return csh;

    }

    private Handler createExternalServlet(Environment env) {
        final ServletContextHandler handler = env.getServletContext();
        handler.addFilter(ThreadNameFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        final ServletContainer jerseyContainer = env.getJerseyServletContainer();
        if (jerseyContainer != null) {
            env.getJerseyEnvironment().addProvider(
                    new JacksonMessageBodyProvider(env.getJsonEnvironment().build(),
                                                   env.getValidator())
            );
            final ServletHolder jerseyHolder = new NonblockingServletHolder(jerseyContainer);
            jerseyHolder.setInitOrder(Integer.MAX_VALUE);
            handler.addServlet(jerseyHolder, env.getJerseyEnvironment().getUrlPattern());
        }

        handler.setConnectorNames(new String[]{"main"});

        return wrapHandler(handler);
    }

    private Handler wrapHandler(ServletContextHandler handler) {
        final InstrumentedHandler instrumented = new InstrumentedHandler(handler);
        final GzipConfiguration gzip = config.getGzipConfiguration();
        if (gzip.isEnabled()) {
            final BiDiGzipHandler gzipHandler = new BiDiGzipHandler(instrumented);

            final Size minEntitySize = gzip.getMinimumEntitySize();
            gzipHandler.setMinGzipSize((int) minEntitySize.toBytes());

            final Size bufferSize = gzip.getBufferSize();
            gzipHandler.setBufferSize((int) bufferSize.toBytes());

            final ImmutableSet<String> userAgents = gzip.getExcludedUserAgents();
            if (!userAgents.isEmpty()) {
                gzipHandler.setExcluded(userAgents);
            }

            final ImmutableSet<String> mimeTypes = gzip.getCompressedMimeTypes();
            if (!mimeTypes.isEmpty()) {
                gzipHandler.setMimeTypes(mimeTypes);
            }

            return gzipHandler;
        }
        return instrumented;
    }

    private ThreadPool createThreadPool() {
        final InstrumentedQueuedThreadPool pool = new InstrumentedQueuedThreadPool();
        pool.setMinThreads(config.getMinThreads());
        pool.setMaxThreads(config.getMaxThreads());
        return pool;
    }

    private Connector createInternalConnector() {
        final SocketConnector connector = new SocketConnector();
        connector.setHost(config.getBindHost().orNull());
        connector.setPort(config.getAdminPort());
        connector.setName("internal");
        connector.setThreadPool(new QueuedThreadPool(8));
        return connector;
    }
}
