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
package org.apache.curator.x.discovery;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Closeables;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.TestingServer;
import org.apache.curator.test.Timing;
import org.apache.curator.x.discovery.details.JsonInstanceSerializer;
import org.apache.curator.x.discovery.details.ServiceDiscoveryImpl;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.io.Closeable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;

public class TestServiceDiscovery
{
    private static final Comparator<ServiceInstance<Void>>      comparator = new Comparator<ServiceInstance<Void>>()
    {
        @Override
        public int compare(ServiceInstance<Void> o1, ServiceInstance<Void> o2)
        {
            return o1.getId().compareTo(o2.getId());
        }
    };

    @Test
    public void         testCrashedServerMultiInstances() throws Exception
    {
        List<Closeable>     closeables = Lists.newArrayList();
        TestingServer       server = new TestingServer();
        closeables.add(server);
        try
        {
            Timing              timing = new Timing();
            CuratorFramework    client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
            closeables.add(client);
            client.start();

            final Semaphore             semaphore = new Semaphore(0);
            ServiceInstance<String>     instance1 = ServiceInstance.<String>builder().payload("thing").name("test").port(10064).build();
            ServiceInstance<String>     instance2 = ServiceInstance.<String>builder().payload("thing").name("test").port(10065).build();
            ServiceDiscovery<String>    discovery = new ServiceDiscoveryImpl<String>(client, "/test", new JsonInstanceSerializer<String>(String.class), instance1)
            {
                @Override
                protected void internalRegisterService(ServiceInstance<String> service) throws Exception
                {
                    super.internalRegisterService(service);
                    semaphore.release();
                }
            };
            closeables.add(discovery);
            discovery.start();
            discovery.registerService(instance2);

            timing.acquireSemaphore(semaphore, 2);
            Assert.assertEquals(discovery.queryForInstances("test").size(), 2);

            KillSession.kill(client.getZookeeperClient().getZooKeeper(), server.getConnectString());
            server.stop();

            server = new TestingServer(server.getPort(), server.getTempDirectory());
            closeables.add(server);

            timing.acquireSemaphore(semaphore, 2);
            Assert.assertEquals(discovery.queryForInstances("test").size(), 2);
        }
        finally
        {
            for ( Closeable c : closeables )
            {
                Closeables.closeQuietly(c);
            }
        }
    }

    @Test
    public void         testCrashedServer() throws Exception
    {
        List<Closeable>     closeables = Lists.newArrayList();
        TestingServer       server = new TestingServer();
        closeables.add(server);
        try
        {
            Timing              timing = new Timing();
            CuratorFramework    client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
            closeables.add(client);
            client.start();

            final Semaphore             semaphore = new Semaphore(0);
            ServiceInstance<String>     instance = ServiceInstance.<String>builder().payload("thing").name("test").port(10064).build();
            ServiceDiscovery<String>    discovery = new ServiceDiscoveryImpl<String>(client, "/test", new JsonInstanceSerializer<String>(String.class), instance)
            {
                @Override
                protected void internalRegisterService(ServiceInstance<String> service) throws Exception
                {
                    super.internalRegisterService(service);
                    semaphore.release();
                }
            };
            closeables.add(discovery);
            discovery.start();

            timing.acquireSemaphore(semaphore);
            Assert.assertEquals(discovery.queryForInstances("test").size(), 1);

            KillSession.kill(client.getZookeeperClient().getZooKeeper(), server.getConnectString());
            server.stop();

            server = new TestingServer(server.getPort(), server.getTempDirectory());
            closeables.add(server);

            timing.acquireSemaphore(semaphore);
            Assert.assertEquals(discovery.queryForInstances("test").size(), 1);
        }
        finally
        {
            for ( Closeable c : closeables )
            {
                Closeables.closeQuietly(c);
            }
        }
    }

    @Test
    public void         testCrashedInstance() throws Exception
    {
        List<Closeable>     closeables = Lists.newArrayList();
        TestingServer       server = new TestingServer();
        closeables.add(server);
        try
        {
            Timing              timing = new Timing();

            CuratorFramework    client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
            closeables.add(client);
            client.start();

            ServiceInstance<String>     instance = ServiceInstance.<String>builder().payload("thing").name("test").port(10064).build();
            ServiceDiscovery<String>    discovery = new ServiceDiscoveryImpl<String>(client, "/test", new JsonInstanceSerializer<String>(String.class), instance);
            closeables.add(discovery);
            discovery.start();

            Assert.assertEquals(discovery.queryForInstances("test").size(), 1);
            
            KillSession.kill(client.getZookeeperClient().getZooKeeper(), server.getConnectString());
            Thread.sleep(timing.multiple(1.5).session());

            Assert.assertEquals(discovery.queryForInstances("test").size(), 1);
        }
        finally
        {
            Collections.reverse(closeables);
            for ( Closeable c : closeables )
            {
                Closeables.closeQuietly(c);
            }
        }
    }

    @Test
    public void         testMultipleInstances() throws Exception
    {
        final String        SERVICE_ONE = "one";
        final String        SERVICE_TWO = "two";

        List<Closeable>     closeables = Lists.newArrayList();
        TestingServer       server = new TestingServer();
        closeables.add(server);
        try
        {
            CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
            closeables.add(client);
            client.start();

            ServiceInstance<Void>       s1_i1 = ServiceInstance.<Void>builder().name(SERVICE_ONE).build();
            ServiceInstance<Void>       s1_i2 = ServiceInstance.<Void>builder().name(SERVICE_ONE).build();
            ServiceInstance<Void>       s2_i1 = ServiceInstance.<Void>builder().name(SERVICE_TWO).build();
            ServiceInstance<Void>       s2_i2 = ServiceInstance.<Void>builder().name(SERVICE_TWO).build();

            ServiceDiscovery<Void>      discovery = ServiceDiscoveryBuilder.builder(Void.class).client(client).basePath("/test").build();
            closeables.add(discovery);
            discovery.start();

            discovery.registerService(s1_i1);
            discovery.registerService(s1_i2);
            discovery.registerService(s2_i1);
            discovery.registerService(s2_i2);

            Assert.assertEquals(Sets.newHashSet(discovery.queryForNames()), Sets.newHashSet(SERVICE_ONE, SERVICE_TWO));

            List<ServiceInstance<Void>> list = Lists.newArrayList();
            list.add(s1_i1);
            list.add(s1_i2);
            Collections.sort(list, comparator);
            List<ServiceInstance<Void>> queriedInstances = Lists.newArrayList(discovery.queryForInstances(SERVICE_ONE));
            Collections.sort(queriedInstances, comparator);
            Assert.assertEquals(queriedInstances, list, String.format("Not equal l: %s - d: %s", list, queriedInstances));

            list.clear();

            list.add(s2_i1);
            list.add(s2_i2);
            Collections.sort(list, comparator);
            queriedInstances = Lists.newArrayList(discovery.queryForInstances(SERVICE_TWO));
            Collections.sort(queriedInstances, comparator);
            Assert.assertEquals(queriedInstances, list, String.format("Not equal 2: %s - d: %s", list, queriedInstances));
        }
        finally
        {
            Collections.reverse(closeables);
            for ( Closeable c : closeables )
            {
                Closeables.closeQuietly(c);
            }
        }
    }

    @Test
    public void         testBasic() throws Exception
    {
        List<Closeable>     closeables = Lists.newArrayList();
        TestingServer       server = new TestingServer();
        closeables.add(server);
        try
        {
            CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
            closeables.add(client);
            client.start();
            
            ServiceInstance<String>     instance = ServiceInstance.<String>builder().payload("thing").name("test").port(10064).build();
            ServiceDiscovery<String>    discovery = ServiceDiscoveryBuilder.builder(String.class).basePath("/test").client(client).thisInstance(instance).build();
            closeables.add(discovery);
            discovery.start();

            Assert.assertEquals(discovery.queryForNames(), Arrays.asList("test"));

            List<ServiceInstance<String>> list = Lists.newArrayList();
            list.add(instance);
            Assert.assertEquals(discovery.queryForInstances("test"), list);
        }
        finally
        {
            Collections.reverse(closeables);
            for ( Closeable c : closeables )
            {
                Closeables.closeQuietly(c);
            }
        }
    }
}
