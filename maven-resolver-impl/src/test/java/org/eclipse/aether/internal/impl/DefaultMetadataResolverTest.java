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
package org.eclipse.aether.internal.impl;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositoryEvent.EventType;
import org.eclipse.aether.internal.impl.filter.DefaultRemoteRepositoryFilterManager;
import org.eclipse.aether.internal.impl.filter.Filters;
import org.eclipse.aether.internal.test.util.TestFileUtils;
import org.eclipse.aether.internal.test.util.TestLocalRepositoryManager;
import org.eclipse.aether.internal.test.util.TestUtils;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.LocalMetadataRegistration;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;
import org.eclipse.aether.spi.connector.ArtifactDownload;
import org.eclipse.aether.spi.connector.MetadataDownload;
import org.eclipse.aether.spi.connector.filter.RemoteRepositoryFilterSource;
import org.eclipse.aether.transfer.MetadataNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DefaultMetadataResolverTest {

    private DefaultMetadataResolver resolver;

    private StubRepositoryConnectorProvider connectorProvider;

    private RemoteRepository repository;

    private DefaultRepositorySystemSession session;

    private Metadata metadata;

    private RecordingRepositoryConnector connector;

    private TestLocalRepositoryManager lrm;

    private HashMap<String, RemoteRepositoryFilterSource> remoteRepositoryFilterSources;

    private DefaultRemoteRepositoryFilterManager remoteRepositoryFilterManager;

    private RecordingRepositoryListener listener;

    @TempDir
    private File tempDirectory;

    @BeforeEach
    void setup() throws Exception {
        remoteRepositoryFilterSources = new HashMap<>();
        remoteRepositoryFilterManager = new DefaultRemoteRepositoryFilterManager(remoteRepositoryFilterSources);

        session = TestUtils.newSession();
        lrm = (TestLocalRepositoryManager) session.getLocalRepositoryManager();
        connectorProvider = new StubRepositoryConnectorProvider();
        resolver = new DefaultMetadataResolver(
                new StubRepositoryEventDispatcher(),
                new StaticUpdateCheckManager(true),
                connectorProvider,
                new StubRemoteRepositoryManager(),
                new StubSyncContextFactory(),
                new DefaultOfflineController(),
                remoteRepositoryFilterManager,
                new DefaultPathProcessor());
        repository = new RemoteRepository.Builder(
                        "test-DMRT", "default", tempDirectory.toURI().toURL().toString())
                .build();
        metadata = new DefaultMetadata("gid", "aid", "ver", "maven-metadata.xml", Metadata.Nature.RELEASE_OR_SNAPSHOT);
        connector = new RecordingRepositoryConnector();
        connectorProvider.setConnector(connector);

        listener = new RecordingRepositoryListener();
        session.setRepositoryListener(listener);
    }

    @AfterEach
    void teardown() throws Exception {
        TestFileUtils.deleteFile(new File(new URI(repository.getUrl())));
        TestFileUtils.deleteFile(session.getLocalRepository().getBasedir());
    }

    @Test
    void testNoRepositoryFailing() {
        MetadataRequest request = new MetadataRequest(metadata, null, "");
        List<MetadataResult> results = resolver.resolveMetadata(session, Arrays.asList(request));

        assertEquals(1, results.size());

        MetadataResult result = results.get(0);
        assertSame(request, result.getRequest());
        assertNotNull(result.getException());
        assertEquals(MetadataNotFoundException.class, result.getException().getClass());
        assertNull(result.getMetadata());
    }

    @Test
    void testResolve() throws IOException {
        connector.setExpectGet(metadata);

        // prepare "download"
        File file = new File(
                session.getLocalRepository().getBasedir(),
                session.getLocalRepositoryManager().getPathForRemoteMetadata(metadata, repository, ""));

        TestFileUtils.writeString(file, file.getAbsolutePath());

        MetadataRequest request = new MetadataRequest(metadata, repository, "");
        List<MetadataResult> results = resolver.resolveMetadata(session, Arrays.asList(request));

        assertEquals(1, results.size());

        MetadataResult result = results.get(0);
        assertSame(request, result.getRequest());
        assertNull(result.getException());
        assertNotNull(result.getMetadata());
        assertNotNull(result.getMetadata().getFile());

        assertEquals(file, result.getMetadata().getFile());
        assertEquals(metadata, result.getMetadata().setFile(null));

        connector.assertSeenExpected();
        Set<Metadata> metadataRegistration =
                ((TestLocalRepositoryManager) session.getLocalRepositoryManager()).getMetadataRegistration();
        assertTrue(metadataRegistration.contains(metadata));
        assertEquals(1, metadataRegistration.size());

        List<RepositoryEvent> events =
                listener.getEvents(EventType.METADATA_DOWNLOADING, EventType.METADATA_DOWNLOADED);
        assertEquals(2, events.size());
        assertSame(events.get(0).getTrace(), events.get(1).getTrace());

        RepositoryEvent event = events.get(0);
        assertEquals(EventType.METADATA_DOWNLOADING, event.getType());
        assertEquals(metadata, event.getMetadata());

        event = events.get(1);
        assertEquals(EventType.METADATA_DOWNLOADED, event.getType());
        assertEquals(metadata, event.getMetadata());
        assertNull(event.getException());
        assertNotNull(event.getFile());
    }

    @Test
    void testRemoveMetadataIfMissing() throws IOException {
        connector = new RecordingRepositoryConnector() {

            @Override
            public void get(
                    Collection<? extends ArtifactDownload> artifactDownloads,
                    Collection<? extends MetadataDownload> metadataDownloads) {
                super.get(artifactDownloads, metadataDownloads);
                for (MetadataDownload d : metadataDownloads) {
                    d.setException(new MetadataNotFoundException(metadata, repository));
                }
            }
        };
        connectorProvider.setConnector(connector);

        File file = new File(
                session.getLocalRepository().getBasedir(),
                session.getLocalRepositoryManager().getPathForRemoteMetadata(metadata, repository, ""));
        TestFileUtils.writeString(file, file.getAbsolutePath());
        metadata.setFile(file);

        MetadataRequest request = new MetadataRequest(metadata, repository, "");
        request.setDeleteLocalCopyIfMissing(true);

        List<MetadataResult> results = resolver.resolveMetadata(session, Arrays.asList(request));
        assertEquals(1, results.size());
        MetadataResult result = results.get(0);

        assertNotNull(result.getException());
        assertFalse(file.exists());
    }

    @Test
    void testOfflineSessionResolveMetadataMissing() {
        session.setOffline(true);
        MetadataRequest request = new MetadataRequest(metadata, repository, "");
        List<MetadataResult> results = resolver.resolveMetadata(session, Arrays.asList(request));

        assertEquals(1, results.size());

        MetadataResult result = results.get(0);
        assertSame(request, result.getRequest());
        assertNotNull(result.getException());
        assertNull(result.getMetadata());

        connector.assertSeenExpected();
    }

    @Test
    void testOfflineSessionResolveMetadata() throws IOException {
        session.setOffline(true);

        String path = session.getLocalRepositoryManager().getPathForRemoteMetadata(metadata, repository, "");
        File file = new File(session.getLocalRepository().getBasedir(), path);
        TestFileUtils.writeString(file, file.getAbsolutePath());

        // set file to use in TestLRM find()
        metadata = metadata.setFile(file);

        MetadataRequest request = new MetadataRequest(metadata, repository, "");
        List<MetadataResult> results = resolver.resolveMetadata(session, Arrays.asList(request));

        assertEquals(1, results.size());
        MetadataResult result = results.get(0);
        assertSame(request, result.getRequest());
        assertNull(result.getException(), String.valueOf(result.getException()));
        assertNotNull(result.getMetadata());
        assertNotNull(result.getMetadata().getFile());

        assertEquals(file, result.getMetadata().getFile());
        assertEquals(metadata.setFile(null), result.getMetadata().setFile(null));

        connector.assertSeenExpected();
    }

    @Test
    void testFavorLocal() throws IOException {
        lrm.add(session, new LocalMetadataRegistration(metadata));
        String path = session.getLocalRepositoryManager().getPathForLocalMetadata(metadata);
        File file = new File(session.getLocalRepository().getBasedir(), path);
        TestFileUtils.writeString(file, file.getAbsolutePath());

        MetadataRequest request = new MetadataRequest(metadata, repository, "");
        request.setFavorLocalRepository(true);

        resolver = new DefaultMetadataResolver(
                new StubRepositoryEventDispatcher(),
                new StaticUpdateCheckManager(true, true),
                connectorProvider,
                new StubRemoteRepositoryManager(),
                new StubSyncContextFactory(),
                new DefaultOfflineController(),
                remoteRepositoryFilterManager,
                new DefaultPathProcessor());

        List<MetadataResult> results = resolver.resolveMetadata(session, Arrays.asList(request));

        assertEquals(1, results.size());
        MetadataResult result = results.get(0);
        assertSame(request, result.getRequest());
        assertNull(result.getException(), String.valueOf(result.getException()));

        connector.assertSeenExpected();
    }

    @Test
    void testResolveAlwaysAcceptFilter() throws IOException {
        remoteRepositoryFilterSources.put("filter1", Filters.neverAcceptFrom("invalid repo id"));
        remoteRepositoryFilterSources.put("filter2", Filters.alwaysAccept());
        connector.setExpectGet(metadata);

        // prepare "download"
        File file = new File(
                session.getLocalRepository().getBasedir(),
                session.getLocalRepositoryManager().getPathForRemoteMetadata(metadata, repository, ""));

        TestFileUtils.writeString(file, file.getAbsolutePath());

        MetadataRequest request = new MetadataRequest(metadata, repository, "");
        List<MetadataResult> results = resolver.resolveMetadata(session, Arrays.asList(request));

        assertEquals(1, results.size());

        MetadataResult result = results.get(0);
        assertSame(request, result.getRequest());
        assertNull(result.getException());
        assertNotNull(result.getMetadata());
        assertNotNull(result.getMetadata().getFile());

        assertEquals(file, result.getMetadata().getFile());
        assertEquals(metadata, result.getMetadata().setFile(null));

        connector.assertSeenExpected();
        Set<Metadata> metadataRegistration =
                ((TestLocalRepositoryManager) session.getLocalRepositoryManager()).getMetadataRegistration();
        assertTrue(metadataRegistration.contains(metadata));
        assertEquals(1, metadataRegistration.size());
    }

    @Test
    void testResolveNeverAcceptFilter() throws IOException {
        remoteRepositoryFilterSources.put("filter1", Filters.neverAcceptFrom("invalid repo id"));
        remoteRepositoryFilterSources.put("filter2", Filters.neverAccept());
        // connector.setExpectGet( metadata ); // should not see it

        // prepare "download"
        File file = new File(
                session.getLocalRepository().getBasedir(),
                session.getLocalRepositoryManager().getPathForRemoteMetadata(metadata, repository, ""));

        TestFileUtils.writeString(file, file.getAbsolutePath());

        MetadataRequest request = new MetadataRequest(metadata, repository, "");
        List<MetadataResult> results = resolver.resolveMetadata(session, Arrays.asList(request));

        assertEquals(1, results.size());

        MetadataResult result = results.get(0);
        assertSame(request, result.getRequest());
        assertNotNull(result.getException());
        assertInstanceOf(MetadataNotFoundException.class, result.getException());
        assertEquals("never-accept", result.getException().getMessage());
        assertNull(result.getMetadata());

        connector.assertSeenExpected();
    }

    @Test
    void testResolveAlwaysAcceptFromRepoFilter() throws IOException {
        remoteRepositoryFilterSources.put("filter1", Filters.alwaysAcceptFrom(repository.getId()));
        connector.setExpectGet(metadata);

        // prepare "download"
        File file = new File(
                session.getLocalRepository().getBasedir(),
                session.getLocalRepositoryManager().getPathForRemoteMetadata(metadata, repository, ""));

        TestFileUtils.writeString(file, file.getAbsolutePath());

        MetadataRequest request = new MetadataRequest(metadata, repository, "");
        List<MetadataResult> results = resolver.resolveMetadata(session, Arrays.asList(request));

        assertEquals(1, results.size());

        MetadataResult result = results.get(0);
        assertSame(request, result.getRequest());
        assertNull(result.getException());
        assertNotNull(result.getMetadata());
        assertNotNull(result.getMetadata().getFile());

        assertEquals(file, result.getMetadata().getFile());
        assertEquals(metadata, result.getMetadata().setFile(null));

        connector.assertSeenExpected();
        Set<Metadata> metadataRegistration =
                ((TestLocalRepositoryManager) session.getLocalRepositoryManager()).getMetadataRegistration();
        assertTrue(metadataRegistration.contains(metadata));
        assertEquals(1, metadataRegistration.size());
    }

    @Test
    void testResolveNeverAcceptFromRepoFilter() throws IOException {
        remoteRepositoryFilterSources.put("filter1", Filters.neverAcceptFrom(repository.getId()));
        // connector.setExpectGet( metadata ); // should not see it

        // prepare "download"
        File file = new File(
                session.getLocalRepository().getBasedir(),
                session.getLocalRepositoryManager().getPathForRemoteMetadata(metadata, repository, ""));

        TestFileUtils.writeString(file, file.getAbsolutePath());

        MetadataRequest request = new MetadataRequest(metadata, repository, "");
        List<MetadataResult> results = resolver.resolveMetadata(session, Arrays.asList(request));

        assertEquals(1, results.size());

        MetadataResult result = results.get(0);
        assertSame(request, result.getRequest());
        assertNotNull(result.getException());
        assertInstanceOf(MetadataNotFoundException.class, result.getException());
        assertEquals("never-accept-" + repository.getId(), result.getException().getMessage());
        assertNull(result.getMetadata());

        connector.assertSeenExpected();
    }
}
