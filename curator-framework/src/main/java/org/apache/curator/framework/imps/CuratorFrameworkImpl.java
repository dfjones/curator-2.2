/**
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
package org.apache.curator.framework.imps;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import org.apache.curator.CuratorConnectionLossException;
import org.apache.curator.CuratorZookeeperClient;
import org.apache.curator.RetryLoop;
import org.apache.curator.TimeTrace;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.*;
import org.apache.curator.framework.api.transaction.CuratorTransaction;
import org.apache.curator.framework.listen.Listenable;
import org.apache.curator.framework.listen.ListenerContainer;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.framework.state.ConnectionStateManager;
import org.apache.curator.utils.DebugUtils;
import org.apache.curator.utils.EnsurePath;
import org.apache.curator.utils.ThreadUtils;
import org.apache.curator.utils.ZookeeperFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

public class CuratorFrameworkImpl implements CuratorFramework
{
    private final Logger                                                log = LoggerFactory.getLogger(getClass());
    private final CuratorZookeeperClient                                client;
    private final ListenerContainer<CuratorListener>                    listeners;
    private final ListenerContainer<UnhandledErrorListener>             unhandledErrorListeners;
    private final ThreadFactory                                         threadFactory;
    private final BlockingQueue<OperationAndData<?>>                    backgroundOperations;
    private final NamespaceImpl                                         namespace;
    private final ConnectionStateManager                                connectionStateManager;
    private final AtomicReference<AuthInfo>                             authInfo = new AtomicReference<AuthInfo>();
    private final byte[]                                                defaultData;
    private final FailedDeleteManager                                   failedDeleteManager;
    private final CompressionProvider                                   compressionProvider;
    private final ACLProvider                                           aclProvider;
    private final NamespaceFacadeCache                                  namespaceFacadeCache;
    private final NamespaceWatcherMap                                   namespaceWatcherMap = new NamespaceWatcherMap(this);

    private volatile ExecutorService                                    executorService;

    interface DebugBackgroundListener
    {
        void        listen(OperationAndData<?> data);
    }
    volatile DebugBackgroundListener        debugListener = null;

    private final AtomicReference<CuratorFrameworkState>                    state;

    private static class AuthInfo
    {
        final String    scheme;
        final byte[]    auth;

        private AuthInfo(String scheme, byte[] auth)
        {
            this.scheme = scheme;
            this.auth = auth;
        }

        @Override
        public String toString()
        {
            return "AuthInfo{" +
                "scheme='" + scheme + '\'' +
                ", auth=" + Arrays.toString(auth) +
                '}';
        }
    }

    public CuratorFrameworkImpl(CuratorFrameworkFactory.Builder builder)
    {
        ZookeeperFactory localZookeeperFactory = makeZookeeperFactory(builder.getZookeeperFactory());
        this.client = new CuratorZookeeperClient
        (
            localZookeeperFactory,
            builder.getEnsembleProvider(),
            builder.getSessionTimeoutMs(),
            builder.getConnectionTimeoutMs(),
            new Watcher()
            {
                @Override
                public void process(WatchedEvent watchedEvent)
                {
                    CuratorEvent event = new CuratorEventImpl
                    (
                        CuratorFrameworkImpl.this,
                        CuratorEventType.WATCHED,
                        watchedEvent.getState().getIntValue(),
                        unfixForNamespace(watchedEvent.getPath()),
                        null,
                        null,
                        null,
                        null,
                        null,
                        watchedEvent,
                        null
                    );
                    processEvent(event);
                }
            },
            builder.getRetryPolicy(),
            builder.canBeReadOnly()
        );

        listeners = new ListenerContainer<CuratorListener>();
        unhandledErrorListeners = new ListenerContainer<UnhandledErrorListener>();
        backgroundOperations = new DelayQueue<OperationAndData<?>>();
        namespace = new NamespaceImpl(this, builder.getNamespace());
        threadFactory = getThreadFactory(builder);
        connectionStateManager = new ConnectionStateManager(this, builder.getThreadFactory());
        compressionProvider = builder.getCompressionProvider();
        aclProvider = builder.getAclProvider();
        state = new AtomicReference<CuratorFrameworkState>(CuratorFrameworkState.LATENT);

        byte[]      builderDefaultData = builder.getDefaultData();
        defaultData = (builderDefaultData != null) ? Arrays.copyOf(builderDefaultData, builderDefaultData.length) : new byte[0];

        if ( builder.getAuthScheme() != null )
        {
            authInfo.set(new AuthInfo(builder.getAuthScheme(), builder.getAuthValue()));
        }

        failedDeleteManager = new FailedDeleteManager(this);
        namespaceFacadeCache = new NamespaceFacadeCache(this);
    }

    private ZookeeperFactory makeZookeeperFactory(final ZookeeperFactory actualZookeeperFactory)
    {
        return new ZookeeperFactory()
        {
            @Override
            public ZooKeeper newZooKeeper(String connectString, int sessionTimeout, Watcher watcher, boolean canBeReadOnly) throws Exception
            {
                ZooKeeper zooKeeper = actualZookeeperFactory.newZooKeeper(connectString, sessionTimeout, watcher, canBeReadOnly);
                AuthInfo auth = authInfo.get();
                if ( auth != null )
                {
                    zooKeeper.addAuthInfo(auth.scheme, auth.auth);
                }

                return zooKeeper;
            }
        };
    }

    private ThreadFactory getThreadFactory(CuratorFrameworkFactory.Builder builder)
    {
        ThreadFactory threadFactory = builder.getThreadFactory();
        if ( threadFactory == null )
        {
            threadFactory = ThreadUtils.newThreadFactory("CuratorFramework");
        }
        return threadFactory;
    }

    protected CuratorFrameworkImpl(CuratorFrameworkImpl parent)
    {
        client = parent.client;
        listeners = parent.listeners;
        unhandledErrorListeners = parent.unhandledErrorListeners;
        threadFactory = parent.threadFactory;
        backgroundOperations = parent.backgroundOperations;
        connectionStateManager = parent.connectionStateManager;
        defaultData = parent.defaultData;
        failedDeleteManager = parent.failedDeleteManager;
        compressionProvider = parent.compressionProvider;
        aclProvider = parent.aclProvider;
        namespaceFacadeCache = parent.namespaceFacadeCache;
        namespace = new NamespaceImpl(this, null);
        state = parent.state;
    }

    @Override
    public CuratorFrameworkState getState()
    {
        return state.get();
    }

    @Override
    public boolean isStarted()
    {
        return state.get() == CuratorFrameworkState.STARTED;
    }

    @Override
    public void     start()
    {
        log.info("Starting");
        if ( !state.compareAndSet(CuratorFrameworkState.LATENT, CuratorFrameworkState.STARTED) )
        {
            IllegalStateException error = new IllegalStateException();
            log.error("Cannot be started more than once", error);
            throw error;
        }

        try
        {
            connectionStateManager.start(); // ordering dependency - must be called before client.start()
            client.start();
            executorService = Executors.newFixedThreadPool(2, threadFactory);  // 1 for listeners, 1 for background ops

            executorService.submit
            (
                new Callable<Object>()
                {
                    @Override
                    public Object call() throws Exception
                    {
                        backgroundOperationsLoop();
                        return null;
                    }
                }
            );
        }
        catch ( Exception e )
        {
            handleBackgroundOperationException(null, e);
        }
    }

    @Override
    public void     close()
    {
        log.debug("Closing");
        if ( state.compareAndSet(CuratorFrameworkState.STARTED, CuratorFrameworkState.STOPPED) )
        {
            listeners.forEach
                (
                    new Function<CuratorListener, Void>()
                    {
                        @Override
                        public Void apply(CuratorListener listener)
                        {
                            CuratorEvent event = new CuratorEventImpl(CuratorFrameworkImpl.this, CuratorEventType.CLOSING, 0, null, null, null, null, null, null, null, null);
                            try
                            {
                                listener.eventReceived(CuratorFrameworkImpl.this, event);
                            }
                            catch ( Exception e )
                            {
                                log.error("Exception while sending Closing event", e);
                            }
                            return null;
                        }
                    }
                );

            listeners.clear();
            unhandledErrorListeners.clear();
            connectionStateManager.close();
            client.close();
            namespaceWatcherMap.close();
            if ( executorService != null )
            {
                executorService.shutdownNow();
            }
        }
    }

    @Override
    public CuratorFramework nonNamespaceView()
    {
        return usingNamespace(null);
    }

    @Override
    public String getNamespace()
    {
        String str = namespace.getNamespace();
        return (str != null) ? str : "";
    }

    @Override
    public CuratorFramework usingNamespace(String newNamespace)
    {
        Preconditions.checkState(isStarted(), "instance must be started before calling this method");

        return namespaceFacadeCache.get(newNamespace);
    }

    @Override
    public CreateBuilder create()
    {
        Preconditions.checkState(isStarted(), "instance must be started before calling this method");

        return new CreateBuilderImpl(this);
    }

    @Override
    public DeleteBuilder delete()
    {
        Preconditions.checkState(isStarted(), "instance must be started before calling this method");

        return new DeleteBuilderImpl(this);
    }

    @Override
    public ExistsBuilder checkExists()
    {
        Preconditions.checkState(isStarted(), "instance must be started before calling this method");

        return new ExistsBuilderImpl(this);
    }

    @Override
    public GetDataBuilder getData()
    {
        Preconditions.checkState(isStarted(), "instance must be started before calling this method");

        return new GetDataBuilderImpl(this);
    }

    @Override
    public SetDataBuilder setData()
    {
        Preconditions.checkState(isStarted(), "instance must be started before calling this method");

        return new SetDataBuilderImpl(this);
    }

    @Override
    public GetChildrenBuilder getChildren()
    {
        Preconditions.checkState(isStarted(), "instance must be started before calling this method");

        return new GetChildrenBuilderImpl(this);
    }

    @Override
    public GetACLBuilder getACL()
    {
        Preconditions.checkState(isStarted(), "instance must be started before calling this method");

        return new GetACLBuilderImpl(this);
    }

    @Override
    public SetACLBuilder setACL()
    {
        Preconditions.checkState(isStarted(), "instance must be started before calling this method");

        return new SetACLBuilderImpl(this);
    }

    @Override
    public CuratorTransaction inTransaction()
    {
        Preconditions.checkState(isStarted(), "instance must be started before calling this method");

        return new CuratorTransactionImpl(this);
    }

    @Override
    public Listenable<ConnectionStateListener> getConnectionStateListenable()
    {
        return connectionStateManager.getListenable();
    }

    @Override
    public Listenable<CuratorListener> getCuratorListenable()
    {
        return listeners;
    }

    @Override
    public Listenable<UnhandledErrorListener> getUnhandledErrorListenable()
    {
        return unhandledErrorListeners;
    }

    @Override
    public void sync(String path, Object context)
    {
        Preconditions.checkState(isStarted(), "instance must be started before calling this method");

        path = fixForNamespace(path);

        internalSync(this, path, context);
    }

    @Override
    public SyncBuilder sync()
    {
        return new SyncBuilderImpl(this);
    }

    protected void internalSync(CuratorFrameworkImpl impl, String path, Object context)
    {
        BackgroundOperation<String> operation = new BackgroundSyncImpl(impl, context);
        performBackgroundOperation(new OperationAndData<String>(operation, path, null, null, context));
    }

    @Override
    public CuratorZookeeperClient getZookeeperClient()
    {
        return client;
    }

    @Override
    public EnsurePath newNamespaceAwareEnsurePath(String path)
    {
        return namespace.newNamespaceAwareEnsurePath(path);
    }

    ACLProvider getAclProvider()
    {
        return aclProvider;
    }

    FailedDeleteManager getFailedDeleteManager()
    {
        return failedDeleteManager;
    }

    RetryLoop newRetryLoop()
    {
        return client.newRetryLoop();
    }

    ZooKeeper getZooKeeper() throws Exception
    {
        return client.getZooKeeper();
    }

    CompressionProvider getCompressionProvider()
    {
        return compressionProvider;
    }

    <DATA_TYPE> void processBackgroundOperation(OperationAndData<DATA_TYPE> operationAndData, CuratorEvent event)
    {
        boolean     isInitialExecution = (event == null);
        if ( isInitialExecution )
        {
            performBackgroundOperation(operationAndData);
            return;
        }

        boolean     doQueueOperation = false;
        do
        {
            if ( RetryLoop.shouldRetry(event.getResultCode()) )
            {
                doQueueOperation = checkBackgroundRetry(operationAndData, event);
                break;
            }

            if ( operationAndData.getCallback() != null )
            {
                sendToBackgroundCallback(operationAndData, event);
                break;
            }

            processEvent(event);
        } while ( false );

        if ( doQueueOperation )
        {
            queueOperation(operationAndData);
        }
    }

    <DATA_TYPE> void queueOperation(OperationAndData<DATA_TYPE> operationAndData)
    {
        backgroundOperations.offer(operationAndData);
    }

    void logError(String reason, final Throwable e)
    {
        if ( (reason == null) || (reason.length() == 0) )
        {
            reason = "n/a";
        }

        if ( !Boolean.getBoolean(DebugUtils.PROPERTY_DONT_LOG_CONNECTION_ISSUES) || !(e instanceof KeeperException) )
        {
            log.error(reason, e);
        }

        if ( e instanceof KeeperException.ConnectionLossException )
        {
            connectionStateManager.addStateChange(ConnectionState.LOST);
        }

        final String        localReason = reason;
        unhandledErrorListeners.forEach
        (
            new Function<UnhandledErrorListener, Void>()
            {
                @Override
                public Void apply(UnhandledErrorListener listener)
                {
                    listener.unhandledError(localReason, e);
                    return null;
                }
            }
        );
    }

    String    unfixForNamespace(String path)
    {
        return namespace.unfixForNamespace(path);
    }

    String    fixForNamespace(String path)
    {
        return namespace.fixForNamespace(path);
    }

    byte[] getDefaultData()
    {
        return defaultData;
    }

    NamespaceFacadeCache getNamespaceFacadeCache()
    {
        return namespaceFacadeCache;
    }

    NamespaceWatcherMap getNamespaceWatcherMap()
    {
        return namespaceWatcherMap;
    }

    @SuppressWarnings({"ThrowableResultOfMethodCallIgnored"})
    private <DATA_TYPE> boolean checkBackgroundRetry(OperationAndData<DATA_TYPE> operationAndData, CuratorEvent event)
    {
        boolean doRetry = false;
        if ( client.getRetryPolicy().allowRetry(operationAndData.getThenIncrementRetryCount(), operationAndData.getElapsedTimeMs(), operationAndData) )
        {
            doRetry = true;
        }
        else
        {
            if ( operationAndData.getErrorCallback() != null )
            {
                operationAndData.getErrorCallback().retriesExhausted(operationAndData);
            }

            if ( operationAndData.getCallback() != null )
            {
                sendToBackgroundCallback(operationAndData, event);
            }

            KeeperException.Code    code = KeeperException.Code.get(event.getResultCode());
            Exception               e = null;
            try
            {
                e = (code != null) ? KeeperException.create(code) : null;
            }
            catch ( Throwable ignore )
            {
            }
            if ( e == null )
            {
                e = new Exception("Unknown result code: " + event.getResultCode());
            }
            logError("Background operation retry gave up", e);
        }
        return doRetry;
    }

    private <DATA_TYPE> void sendToBackgroundCallback(OperationAndData<DATA_TYPE> operationAndData, CuratorEvent event)
    {
        try
        {
            operationAndData.getCallback().processResult(this, event);
        }
        catch ( Exception e )
        {
            handleBackgroundOperationException(operationAndData, e);
        }
    }

    private<DATA_TYPE> void handleBackgroundOperationException(OperationAndData<DATA_TYPE> operationAndData, Throwable e)
    {
        do
        {
            if ( (operationAndData != null) && RetryLoop.isRetryException(e) )
            {
                if ( !Boolean.getBoolean(DebugUtils.PROPERTY_DONT_LOG_CONNECTION_ISSUES) )
                {
                    log.debug("Retry-able exception received", e);
                }
                if ( client.getRetryPolicy().allowRetry(operationAndData.getThenIncrementRetryCount(), operationAndData.getElapsedTimeMs(), operationAndData) )
                {
                    if ( !Boolean.getBoolean(DebugUtils.PROPERTY_DONT_LOG_CONNECTION_ISSUES) )
                    {
                        log.debug("Retrying operation");
                    }
                    backgroundOperations.offer(operationAndData);
                    break;
                }
                else
                {
                    if ( !Boolean.getBoolean(DebugUtils.PROPERTY_DONT_LOG_CONNECTION_ISSUES) )
                    {
                        log.debug("Retry policy did not allow retry");
                    }
                    if ( operationAndData.getErrorCallback() != null )
                    {
                        operationAndData.getErrorCallback().retriesExhausted(operationAndData);
                    }
                }
            }

            logError("Background exception was not retry-able or retry gave up", e);
        } while ( false );
    }

    private void backgroundOperationsLoop()
    {
        while ( !Thread.interrupted() )
        {
            OperationAndData<?>         operationAndData;
            try
            {
                operationAndData = backgroundOperations.take();
                if ( debugListener != null )
                {
                    debugListener.listen(operationAndData);
                }
            }
            catch ( InterruptedException e )
            {
                Thread.currentThread().interrupt();
                break;
            }

            performBackgroundOperation(operationAndData);
        }
    }

    private void performBackgroundOperation(OperationAndData<?> operationAndData)
    {
        try
        {
            operationAndData.callPerformBackgroundOperation();
        }
        catch ( Throwable e )
        {
            /**
             * Fix edge case reported as CURATOR-52. ConnectionState.checkTimeouts() throws KeeperException.ConnectionLossException
             * when the initial (or previously failed) connection cannot be re-established. This needs to be run through the retry policy
             * and callbacks need to get invoked, etc.
             */
            if ( e instanceof CuratorConnectionLossException )
            {
                WatchedEvent watchedEvent = new WatchedEvent(Watcher.Event.EventType.None, Watcher.Event.KeeperState.Disconnected, null);
                CuratorEvent event = new CuratorEventImpl(this, CuratorEventType.WATCHED, KeeperException.Code.CONNECTIONLOSS.intValue(), null, null, operationAndData.getContext(), null, null, null, watchedEvent, null);
                if ( checkBackgroundRetry(operationAndData, event) )
                {
                    queueOperation(operationAndData);
                }
                else
                {
                    handleBackgroundOperationException(operationAndData, e);
                }
            }
            else
            {
                handleBackgroundOperationException(operationAndData, e);
            }
        }
    }

    private void processEvent(final CuratorEvent curatorEvent)
    {
        validateConnection(curatorEvent);

        listeners.forEach
        (
            new Function<CuratorListener, Void>()
            {
                @Override
                public Void apply(CuratorListener listener)
                {
                    try
                    {
                        TimeTrace trace = client.startTracer("EventListener");
                        listener.eventReceived(CuratorFrameworkImpl.this, curatorEvent);
                        trace.commit();
                    }
                    catch ( Exception e )
                    {
                        logError("Event listener threw exception", e);
                    }
                    return null;
                }
            }
        );
    }

    private void validateConnection(CuratorEvent curatorEvent)
    {
        if ( curatorEvent.getType() == CuratorEventType.WATCHED )
        {
            if ( curatorEvent.getWatchedEvent().getState() == Watcher.Event.KeeperState.Disconnected )
            {
                connectionStateManager.addStateChange(ConnectionState.SUSPENDED);
                internalSync(this, "/", null);  // we appear to have disconnected, force a new ZK event and see if we can connect to another server
            }
            else if ( curatorEvent.getWatchedEvent().getState() == Watcher.Event.KeeperState.Expired )
            {
                connectionStateManager.addStateChange(ConnectionState.LOST);
            }
            else if ( curatorEvent.getWatchedEvent().getState() == Watcher.Event.KeeperState.SyncConnected )
            {
                connectionStateManager.addStateChange(ConnectionState.RECONNECTED);
            }
            else if ( curatorEvent.getWatchedEvent().getState() == Watcher.Event.KeeperState.ConnectedReadOnly )
            {
                connectionStateManager.addStateChange(ConnectionState.READ_ONLY);
            }
        }
    }
}
