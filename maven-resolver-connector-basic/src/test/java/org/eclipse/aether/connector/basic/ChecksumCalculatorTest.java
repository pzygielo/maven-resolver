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
package org.eclipse.aether.connector.basic;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.aether.internal.test.util.TestFileUtils;
import org.eclipse.aether.spi.connector.checksum.ChecksumAlgorithmFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.aether.connector.basic.TestChecksumAlgorithmSelector.MD5;
import static org.eclipse.aether.connector.basic.TestChecksumAlgorithmSelector.SHA1;
import static org.eclipse.aether.connector.basic.TestChecksumAlgorithmSelector.SHA256;
import static org.eclipse.aether.connector.basic.TestChecksumAlgorithmSelector.SHA512;
import static org.junit.jupiter.api.Assertions.*;

public class ChecksumCalculatorTest {

    private File file;

    private final TestChecksumAlgorithmSelector selector = new TestChecksumAlgorithmSelector();

    private ChecksumCalculator newCalculator(String... algos) {
        List<ChecksumAlgorithmFactory> checksumAlgorithmFactories = new ArrayList<>();
        for (String algo : algos) {
            checksumAlgorithmFactories.add(selector.select(algo));
        }
        return ChecksumCalculator.newInstance(file.toPath(), checksumAlgorithmFactories);
    }

    private ByteBuffer toBuffer(String data) {
        return ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void init() throws Exception {
        file = TestFileUtils.createTempFile("Hello World!");
    }

    @Test
    void testNoOffset() {
        ChecksumCalculator calculator = newCalculator(SHA512, SHA256, SHA1, MD5);
        calculator.init(0);
        calculator.update(toBuffer("Hello World!"));
        Map<String, Object> digests = calculator.get();
        assertNotNull(digests);
        assertEquals(
                "861844d6704e8573fec34d967e20bcfef3d424cf48be04e6dc08f2bd58c729743371015ead891cc3cf1c9d34b49264b510751b1ff9e537937bc46b5d6ff4ecc8",
                digests.get(SHA512));
        assertEquals("7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069", digests.get(SHA256));
        assertEquals("2ef7bde608ce5404e97d5f042f95f89f1c232871", digests.get(SHA1));
        assertEquals("ed076287532e86365e841e92bfc50d8c", digests.get(MD5));
        assertEquals(4, digests.size());
    }

    @Test
    void testWithOffset() {
        ChecksumCalculator calculator = newCalculator(SHA512, SHA256, SHA1, MD5);
        calculator.init(6);
        calculator.update(toBuffer("World!"));
        Map<String, Object> digests = calculator.get();
        assertNotNull(digests);
        assertEquals(
                "861844d6704e8573fec34d967e20bcfef3d424cf48be04e6dc08f2bd58c729743371015ead891cc3cf1c9d34b49264b510751b1ff9e537937bc46b5d6ff4ecc8",
                digests.get(SHA512));
        assertEquals("7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069", digests.get(SHA256));
        assertEquals("2ef7bde608ce5404e97d5f042f95f89f1c232871", digests.get(SHA1));
        assertEquals("ed076287532e86365e841e92bfc50d8c", digests.get(MD5));
        assertEquals(4, digests.size());
    }

    @Test
    void testWithExcessiveOffset() {
        ChecksumCalculator calculator = newCalculator(SHA512, SHA256, SHA1, MD5);
        calculator.init(100);
        calculator.update(toBuffer("World!"));
        Map<String, Object> digests = calculator.get();
        assertNotNull(digests);
        assertInstanceOf(IOException.class, digests.get(SHA512));
        assertInstanceOf(IOException.class, digests.get(SHA256));
        assertInstanceOf(IOException.class, digests.get(SHA1));
        assertInstanceOf(IOException.class, digests.get(MD5));
        assertEquals(4, digests.size());
    }

    @Test
    void testUnknownAlgorithm() {
        // resolver now does not tolerate unknown checksums: as they may be set by user only, it is user
        // misconfiguration
        assertThrows(IllegalArgumentException.class, () -> newCalculator("unknown", SHA1));
    }

    @Test
    void testNoInitCall() {
        ChecksumCalculator calculator = newCalculator(SHA512, SHA256, SHA1, MD5);
        calculator.update(toBuffer("Hello World!"));
        Map<String, Object> digests = calculator.get();
        assertNotNull(digests);
        assertEquals(
                "861844d6704e8573fec34d967e20bcfef3d424cf48be04e6dc08f2bd58c729743371015ead891cc3cf1c9d34b49264b510751b1ff9e537937bc46b5d6ff4ecc8",
                digests.get(SHA512));
        assertEquals("7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069", digests.get(SHA256));
        assertEquals("2ef7bde608ce5404e97d5f042f95f89f1c232871", digests.get(SHA1));
        assertEquals("ed076287532e86365e841e92bfc50d8c", digests.get(MD5));
        assertEquals(4, digests.size());
    }

    @Test
    void testRestart() {
        ChecksumCalculator calculator = newCalculator(SHA512, SHA256, SHA1, MD5);
        calculator.init(0);
        calculator.update(toBuffer("Ignored"));
        calculator.init(0);
        calculator.update(toBuffer("Hello World!"));
        Map<String, Object> digests = calculator.get();
        assertNotNull(digests);
        assertEquals(
                "861844d6704e8573fec34d967e20bcfef3d424cf48be04e6dc08f2bd58c729743371015ead891cc3cf1c9d34b49264b510751b1ff9e537937bc46b5d6ff4ecc8",
                digests.get(SHA512));
        assertEquals("7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069", digests.get(SHA256));
        assertEquals("2ef7bde608ce5404e97d5f042f95f89f1c232871", digests.get(SHA1));
        assertEquals("ed076287532e86365e841e92bfc50d8c", digests.get(MD5));
        assertEquals(4, digests.size());
    }

    @Test
    void testRestartAfterError() {
        ChecksumCalculator calculator = newCalculator(SHA512, SHA256, SHA1, MD5);
        calculator.init(100);
        calculator.init(0);
        calculator.update(toBuffer("Hello World!"));
        Map<String, Object> digests = calculator.get();
        assertNotNull(digests);
        assertEquals(
                "861844d6704e8573fec34d967e20bcfef3d424cf48be04e6dc08f2bd58c729743371015ead891cc3cf1c9d34b49264b510751b1ff9e537937bc46b5d6ff4ecc8",
                digests.get(SHA512));
        assertEquals("7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069", digests.get(SHA256));
        assertEquals("2ef7bde608ce5404e97d5f042f95f89f1c232871", digests.get(SHA1));
        assertEquals("ed076287532e86365e841e92bfc50d8c", digests.get(MD5));
        assertEquals(4, digests.size());
    }
}
