/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.discovery;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.zen.UnicastHostsProvider;
import org.elasticsearch.discovery.zen.ZenDiscovery;
import org.elasticsearch.discovery.zen.ZenPing;
import org.elasticsearch.plugins.DiscoveryPlugin;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.NoopDiscovery;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.junit.After;
import org.junit.Before;

public class DiscoveryModuleTests extends ESTestCase {

    private TransportService transportService;
    private ClusterService clusterService;

    public interface DummyHostsProviderPlugin extends DiscoveryPlugin {
        Map<String, Supplier<UnicastHostsProvider>> impl();
        @Override
        default Map<String, Supplier<UnicastHostsProvider>> getZenHostsProviders(TransportService transportService,
                                                                                 NetworkService networkService) {
            return impl();
        }
    }

    public interface DummyDiscoveryPlugin extends DiscoveryPlugin {
        Map<String, Supplier<Discovery>> impl();
        @Override
        default Map<String, Supplier<Discovery>> getDiscoveryTypes(ThreadPool threadPool, TransportService transportService,
                                                                   ClusterService clusterService, ZenPing zenPing) {
            return impl();
        }
    }

    @Before
    public void setupDummyServices() {
        ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        transportService = MockTransportService.createNewService(Settings.EMPTY, Version.CURRENT, null, null);
        clusterService = new ClusterService(Settings.EMPTY, clusterSettings, null);
    }

    @After
    public void clearDummyServices() throws IOException {
        IOUtils.close(transportService, clusterService);
        transportService = null;
        clusterService = null;
    }

    private DiscoveryModule newModule(Settings settings, Function<UnicastHostsProvider, ZenPing> createZenPing,
                                      List<DiscoveryPlugin> plugins) {
        return new DiscoveryModule(settings, null, transportService, null, clusterService, createZenPing, plugins);
    }

    public void testDefaults() {
        DiscoveryModule module = newModule(Settings.EMPTY, hostsProvider -> null, Collections.emptyList());
        assertTrue(module.getDiscovery() instanceof ZenDiscovery);
    }

    public void testLazyConstructionDiscovery() {
        DummyDiscoveryPlugin plugin = () -> Collections.singletonMap("custom",
            () -> { throw new AssertionError("created discovery type which was not selected"); });
        newModule(Settings.EMPTY, hostsProvider -> null, Collections.singletonList(plugin));
    }

    public void testRegisterDiscovery() {
        Settings settings = Settings.builder().put(DiscoveryModule.DISCOVERY_TYPE_SETTING.getKey(), "custom").build();
        DummyDiscoveryPlugin plugin = () -> Collections.singletonMap("custom", NoopDiscovery::new);
        DiscoveryModule module = newModule(settings, hostsProvider -> null, Collections.singletonList(plugin));
        assertTrue(module.getDiscovery() instanceof NoopDiscovery);
    }

    public void testUnknownDiscovery() {
        Settings settings = Settings.builder().put(DiscoveryModule.DISCOVERY_TYPE_SETTING.getKey(), "dne").build();
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            newModule(settings, hostsProvider -> null, Collections.emptyList()));
        assertEquals("Unknown discovery type [dne]", e.getMessage());
    }

    public void testDuplicateDiscovery() {
        DummyDiscoveryPlugin plugin1 = () -> Collections.singletonMap("dup", () -> null);
        DummyDiscoveryPlugin plugin2 = () -> Collections.singletonMap("dup", () -> null);
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            newModule(Settings.EMPTY, hostsProvider -> null, Arrays.asList(plugin1, plugin2)));
        assertEquals("Cannot register discovery type [dup] twice", e.getMessage());
    }

    public void testHostsProvider() {
        Settings settings = Settings.builder().put(DiscoveryModule.DISCOVERY_HOSTS_PROVIDER_SETTING.getKey(), "custom").build();
        final UnicastHostsProvider provider = Collections::emptyList;
        DummyHostsProviderPlugin plugin = () -> Collections.singletonMap("custom", () -> provider);
        newModule(settings, hostsProvider -> {
            assertEquals(provider, hostsProvider);
            return null;
        }, Collections.singletonList(plugin));
    }

    public void testUnknownHostsProvider() {
        Settings settings = Settings.builder().put(DiscoveryModule.DISCOVERY_HOSTS_PROVIDER_SETTING.getKey(), "dne").build();
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            newModule(settings, hostsProvider -> null, Collections.emptyList()));
        assertEquals("Unknown zen hosts provider [dne]", e.getMessage());
    }

    public void testDuplicateHostsProvider() {
        DummyHostsProviderPlugin plugin1 = () -> Collections.singletonMap("dup", () -> null);
        DummyHostsProviderPlugin plugin2 = () -> Collections.singletonMap("dup", () -> null);
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            newModule(Settings.EMPTY, hostsProvider -> null, Arrays.asList(plugin1, plugin2)));
        assertEquals("Cannot register zen hosts provider [dup] twice", e.getMessage());
    }

    public void testLazyConstructionHostsProvider() {
        DummyHostsProviderPlugin plugin = () -> Collections.singletonMap("custom",
            () -> { throw new AssertionError("created hosts provider which was not selected"); });
        newModule(Settings.EMPTY, hostsProvider -> null, Collections.singletonList(plugin));
    }
}
