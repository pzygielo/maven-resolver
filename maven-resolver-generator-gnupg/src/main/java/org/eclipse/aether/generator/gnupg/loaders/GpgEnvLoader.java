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
package org.eclipse.aether.generator.gnupg.loaders;

import javax.inject.Named;
import javax.inject.Singleton;

import java.nio.charset.StandardCharsets;

import org.bouncycastle.util.encoders.Hex;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.generator.gnupg.GnupgSignatureArtifactGeneratorFactory;
import org.eclipse.aether.util.ConfigUtils;
import org.eclipse.sisu.Priority;

import static org.eclipse.aether.generator.gnupg.GnupgConfigurationKeys.RESOLVER_GPG_KEY;
import static org.eclipse.aether.generator.gnupg.GnupgConfigurationKeys.RESOLVER_GPG_KEY_FINGERPRINT;
import static org.eclipse.aether.generator.gnupg.GnupgConfigurationKeys.RESOLVER_GPG_KEY_PASS;

/**
 * Loader that looks for environment variables.
 */
@Singleton
@Named(GpgEnvLoader.NAME)
@Priority(30)
public final class GpgEnvLoader implements GnupgSignatureArtifactGeneratorFactory.Loader {
    public static final String NAME = "env";

    @Override
    public byte[] loadKeyRingMaterial(RepositorySystemSession session) {
        String keyMaterial = ConfigUtils.getString(session, null, "env." + RESOLVER_GPG_KEY);
        if (keyMaterial != null) {
            return keyMaterial.getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    @Override
    public byte[] loadKeyFingerprint(RepositorySystemSession session) {
        String keyFingerprint = ConfigUtils.getString(session, null, "env." + RESOLVER_GPG_KEY_FINGERPRINT);
        if (keyFingerprint != null) {
            if (keyFingerprint.trim().length() == 40) {
                return Hex.decode(keyFingerprint);
            } else {
                throw new IllegalArgumentException(
                        "Key fingerprint configuration is wrong (hex encoded, 40 characters)");
            }
        }
        return null;
    }

    @Override
    public char[] loadPassword(RepositorySystemSession session, byte[] fingerprint) {
        String keyPassword = ConfigUtils.getString(session, null, "env." + RESOLVER_GPG_KEY_PASS);
        if (keyPassword != null) {
            return keyPassword.toCharArray();
        }
        return null;
    }
}
