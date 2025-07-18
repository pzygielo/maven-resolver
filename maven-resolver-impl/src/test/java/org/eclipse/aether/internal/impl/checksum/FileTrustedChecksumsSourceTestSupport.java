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
package org.eclipse.aether.internal.impl.checksum;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.RepositorySystemLifecycle;
import org.eclipse.aether.internal.impl.DefaultRepositorySystemLifecycle;
import org.eclipse.aether.internal.test.util.TestUtils;
import org.eclipse.aether.spi.checksums.TrustedChecksumsSource;
import org.eclipse.aether.spi.connector.checksum.ChecksumAlgorithmFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

public abstract class FileTrustedChecksumsSourceTestSupport {
    private static final Artifact ARTIFACT_WITHOUT_CHECKSUM = new DefaultArtifact("test:test:1.0");

    private static final Artifact ARTIFACT_WITH_CHECKSUM = new DefaultArtifact("test:test:2.0");

    private static final String ARTIFACT_TRUSTED_CHECKSUM = "trustedChecksum";

    private DefaultRepositorySystemSession session;

    private ChecksumAlgorithmFactory checksumAlgorithmFactory;

    private RepositorySystemLifecycle repositorySystemLifecycle;

    private FileTrustedChecksumsSourceSupport subject;

    private boolean checksumWritten;

    @BeforeEach
    void before() throws Exception {
        session = TestUtils.newSession();
        // populate local repository
        checksumAlgorithmFactory = new Sha1ChecksumAlgorithmFactory();
        repositorySystemLifecycle = new DefaultRepositorySystemLifecycle();
        subject = prepareSubject(repositorySystemLifecycle);
        checksumWritten = false;

        DefaultRepositorySystemSession prepareSession = new DefaultRepositorySystemSession(session);
        enableSource(prepareSession);
        TrustedChecksumsSource.Writer writer = subject.getTrustedArtifactChecksumsWriter(prepareSession);
        if (writer != null) {
            HashMap<String, String> checksums = new HashMap<>();
            checksums.put(checksumAlgorithmFactory.getName(), ARTIFACT_TRUSTED_CHECKSUM);
            writer.addTrustedArtifactChecksums(
                    ARTIFACT_WITH_CHECKSUM,
                    prepareSession.getLocalRepository(),
                    Collections.singletonList(checksumAlgorithmFactory),
                    checksums);
            checksumWritten = true;
        }
    }

    protected abstract FileTrustedChecksumsSourceSupport prepareSubject(RepositorySystemLifecycle lifecycle);

    protected abstract void enableSource(DefaultRepositorySystemSession session);

    @Test
    void notEnabled() {
        assertNull(subject.getTrustedArtifactChecksums(
                session,
                ARTIFACT_WITH_CHECKSUM,
                session.getLocalRepository(),
                Collections.singletonList(checksumAlgorithmFactory)));
    }

    @Test
    void noProvidedArtifactChecksum() {
        enableSource(session);
        Map<String, String> providedChecksums = subject.getTrustedArtifactChecksums(
                session,
                ARTIFACT_WITHOUT_CHECKSUM,
                session.getLocalRepository(),
                Collections.singletonList(checksumAlgorithmFactory));
        assertNotNull(providedChecksums);
        assertTrue(providedChecksums.isEmpty());
    }

    @Test
    void haveProvidedArtifactChecksum() {
        assumeTrue(checksumWritten);
        enableSource(session);
        Map<String, String> providedChecksums = subject.getTrustedArtifactChecksums(
                session,
                ARTIFACT_WITH_CHECKSUM,
                session.getLocalRepository(),
                Collections.singletonList(checksumAlgorithmFactory));
        assertNotNull(providedChecksums);
        assertFalse(providedChecksums.isEmpty());
        assertEquals(1, providedChecksums.size());
        assertEquals(ARTIFACT_TRUSTED_CHECKSUM, providedChecksums.get(checksumAlgorithmFactory.getName()));
    }
}
