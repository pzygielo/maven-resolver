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
package org.eclipse.aether.named.ipc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

/**
 * Trivial ByteChannel wrapper to avoid the read/write synchronization which
 * happens when the channel implements SelectableChannel.
 *
 * @since 2.0.1
 */
public class ByteChannelWrapper implements ByteChannel {

    private final ByteChannel socket;

    public ByteChannelWrapper(ByteChannel socket) {
        this.socket = socket;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return socket.read(dst);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        return socket.write(src);
    }

    @Override
    public boolean isOpen() {
        return socket.isOpen();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
