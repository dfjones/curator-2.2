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

import com.google.common.collect.Queues;
import com.google.common.io.Closeables;
import org.apache.curator.RetryPolicy;
import org.apache.curator.RetrySleeper;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.BackgroundCallback;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorEventType;
import org.apache.curator.framework.api.CuratorListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.TestingServer;
import org.apache.curator.test.Timing;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TestFrameworkEdges extends BaseClassForTests
{
    @Test
    public void connectionLossWithBackgroundTest() throws Exception
    {
        Timing timing = new Timing();
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), 1, new RetryOneTime(1));
        try
        {
            final CountDownLatch latch = new CountDownLatch(1);
            client.start();
            client.getZookeeperClient().blockUntilConnectedOrTimedOut();
            server.close();
            client.getChildren().inBackground
            (
                new BackgroundCallback()
                {
                    public void processResult(CuratorFramework client, CuratorEvent event) throws Exception
                    {
                        latch.countDown();
                    }
                }
            ).forPath("/");
            Assert.assertTrue(timing.awaitLatch(latch));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testReconnectAfterLoss() throws Exception
    {
        Timing timing = new Timing();
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        try
        {
            client.start();

            final CountDownLatch lostLatch = new CountDownLatch(1);
            ConnectionStateListener listener = new ConnectionStateListener()
            {
                @Override
                public void stateChanged(CuratorFramework client, ConnectionState newState)
                {
                    if ( newState == ConnectionState.LOST )
                    {
                        lostLatch.countDown();
                    }
                }
            };
            client.getConnectionStateListenable().addListener(listener);

            client.checkExists().forPath("/");

            server.close();

            Assert.assertTrue(timing.awaitLatch(lostLatch));

            try
            {
                client.checkExists().forPath("/");
                Assert.fail();
            }
            catch ( KeeperException.ConnectionLossException e )
            {
                // correct
            }

            server = new TestingServer(server.getPort());
            client.checkExists().forPath("/");
        }
        finally
        {
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testGetAclNoStat() throws Exception
    {

        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            try
            {
                client.getACL().forPath("/");
            }
            catch ( NullPointerException e )
            {
                Assert.fail();
            }
        }
        finally
        {
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testMissedResponseOnBackgroundESCreate() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            CreateBuilderImpl createBuilder = (CreateBuilderImpl)client.create();
            createBuilder.failNextCreateForTesting = true;

            final BlockingQueue<String> queue = Queues.newArrayBlockingQueue(1);
            BackgroundCallback callback = new BackgroundCallback()
            {
                @Override
                public void processResult(CuratorFramework client, CuratorEvent event) throws Exception
                {
                    queue.put(event.getPath());
                }
            };
            createBuilder.withProtection().withMode(CreateMode.EPHEMERAL_SEQUENTIAL).inBackground(callback).forPath("/");
            String ourPath = queue.poll(10, TimeUnit.SECONDS);
            Assert.assertTrue(ourPath.startsWith(ZKPaths.makePath("/", CreateBuilderImpl.PROTECTED_PREFIX)));
            Assert.assertFalse(createBuilder.failNextCreateForTesting);
        }
        finally
        {
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testMissedResponseOnESCreate() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            CreateBuilderImpl createBuilder = (CreateBuilderImpl)client.create();
            createBuilder.failNextCreateForTesting = true;
            String ourPath = createBuilder.withProtection().withMode(CreateMode.EPHEMERAL_SEQUENTIAL).forPath("/");
            Assert.assertTrue(ourPath.startsWith(ZKPaths.makePath("/", CreateBuilderImpl.PROTECTED_PREFIX)));
            Assert.assertFalse(createBuilder.failNextCreateForTesting);
        }
        finally
        {
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testSessionKilled() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            client.create().forPath("/sessionTest");

            final AtomicBoolean sessionDied = new AtomicBoolean(false);
            Watcher watcher = new Watcher()
            {
                @Override
                public void process(WatchedEvent event)
                {
                    if ( event.getState() == Event.KeeperState.Expired )
                    {
                        sessionDied.set(true);
                    }
                }
            };
            client.checkExists().usingWatcher(watcher).forPath("/sessionTest");
            KillSession.kill(client.getZookeeperClient().getZooKeeper(), server.getConnectString());
            Assert.assertNotNull(client.checkExists().forPath("/sessionTest"));
            Assert.assertTrue(sessionDied.get());
        }
        finally
        {
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testNestedCalls() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            client.getCuratorListenable().addListener
                (
                    new CuratorListener()
                    {
                        @Override
                        public void eventReceived(CuratorFramework client, CuratorEvent event) throws Exception
                        {
                            if ( event.getType() == CuratorEventType.EXISTS )
                            {
                                Stat stat = client.checkExists().forPath("/yo/yo/yo");
                                Assert.assertNull(stat);

                                client.create().inBackground(event.getContext()).forPath("/what");
                            }
                            else if ( event.getType() == CuratorEventType.CREATE )
                            {
                                ((CountDownLatch)event.getContext()).countDown();
                            }
                        }
                    }
                );

            CountDownLatch latch = new CountDownLatch(1);
            client.checkExists().inBackground(latch).forPath("/hey");
            Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));
        }
        finally
        {
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testBackgroundFailure() throws Exception
    {
        Timing timing = new Timing();
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        client.start();
        try
        {
            final CountDownLatch latch = new CountDownLatch(1);
            client.getConnectionStateListenable().addListener
                (
                    new ConnectionStateListener()
                    {
                        @Override
                        public void stateChanged(CuratorFramework client, ConnectionState newState)
                        {
                            if ( newState == ConnectionState.LOST )
                            {
                                latch.countDown();
                            }
                        }
                    }
                );

            client.checkExists().forPath("/hey");
            client.checkExists().inBackground().forPath("/hey");

            server.stop();

            client.checkExists().inBackground().forPath("/hey");
            Assert.assertTrue(timing.awaitLatch(latch));
        }
        finally
        {
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testFailure() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), 100, 100, new RetryOneTime(1));
        client.start();
        try
        {
            client.checkExists().forPath("/hey");
            client.checkExists().inBackground().forPath("/hey");

            server.stop();

            client.checkExists().forPath("/hey");
            Assert.fail();
        }
        catch ( KeeperException.ConnectionLossException e )
        {
            // correct
        }
        finally
        {
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testRetry() throws Exception
    {
        final int MAX_RETRIES = 3;
        final int serverPort = server.getPort();

        final CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), 1000, 1000, new RetryOneTime(10));
        client.start();
        try
        {
            final AtomicInteger retries = new AtomicInteger(0);
            final Semaphore semaphore = new Semaphore(0);
            client.getZookeeperClient().setRetryPolicy
                (
                    new RetryPolicy()
                    {
                        @Override
                        public boolean allowRetry(int retryCount, long elapsedTimeMs, RetrySleeper sleeper)
                        {
                            semaphore.release();
                            if ( retries.incrementAndGet() == MAX_RETRIES )
                            {
                                try
                                {
                                    server = new TestingServer(serverPort);
                                }
                                catch ( Exception e )
                                {
                                    throw new Error(e);
                                }
                            }
                            return true;
                        }
                    }
                );

            server.stop();

            // test foreground retry
            client.checkExists().forPath("/hey");
            Assert.assertTrue(semaphore.tryAcquire(MAX_RETRIES, 10, TimeUnit.SECONDS));

            semaphore.drainPermits();
            retries.set(0);

            server.stop();

            // test background retry
            client.checkExists().inBackground().forPath("/hey");
            Assert.assertTrue(semaphore.tryAcquire(MAX_RETRIES, 10, TimeUnit.SECONDS));
        }
        catch ( Throwable e )
        {
            Assert.fail("Error", e);
        }
        finally
        {
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testNotStarted() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
        try
        {
            client.getData();
            Assert.fail();
        }
        catch ( Exception e )
        {
            // correct
        }
        catch ( Throwable e )
        {
            Assert.fail("", e);
        }
    }

    @Test
    public void testStopped() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
        try
        {
            client.start();
            client.getData();
        }
        finally
        {
            Closeables.closeQuietly(client);
        }

        try
        {
            client.getData();
            Assert.fail();
        }
        catch ( Exception e )
        {
            // correct
        }
    }
}
