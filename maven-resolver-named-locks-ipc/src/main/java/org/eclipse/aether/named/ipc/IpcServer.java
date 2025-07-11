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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of the server side.
 * The server instance is bound to a given maven repository.
 *
 * @since 2.0.1
 */
public class IpcServer {
    /**
     * Should the IPC server not fork? (i.e. for testing purposes)
     *
     * @configurationSource {@link System#getProperty(String, String)}
     * @configurationType {@link java.lang.Boolean}
     * @configurationDefaultValue {@link #DEFAULT_NO_FORK}
     */
    public static final String SYSTEM_PROP_NO_FORK = "aether.named.ipc.nofork";

    public static final boolean DEFAULT_NO_FORK = false;

    /**
     * IPC idle timeout in seconds. If there is no IPC request during idle time, it will stop.
     *
     * @configurationSource {@link System#getProperty(String, String)}
     * @configurationType {@link java.lang.Integer}
     * @configurationDefaultValue {@link #DEFAULT_IDLE_TIMEOUT}
     */
    public static final String SYSTEM_PROP_IDLE_TIMEOUT = "aether.named.ipc.idleTimeout";

    public static final int DEFAULT_IDLE_TIMEOUT = 300;

    /**
     * IPC socket family to use.
     *
     * @configurationSource {@link System#getProperty(String, String)}
     * @configurationType {@link java.lang.String}
     * @configurationDefaultValue {@link #DEFAULT_FAMILY}
     */
    public static final String SYSTEM_PROP_FAMILY = "aether.named.ipc.family";

    public static final String DEFAULT_FAMILY = "unix";

    /**
     * Should the IPC server not use native executable?
     *
     * @configurationSource {@link System#getProperty(String, String)}
     * @configurationType {@link java.lang.Boolean}
     * @configurationDefaultValue {@link #DEFAULT_NO_NATIVE}
     */
    public static final String SYSTEM_PROP_NO_NATIVE = "aether.named.ipc.nonative";

    public static final boolean DEFAULT_NO_NATIVE = true;

    /**
     * The name if the IPC server native executable (without file extension like ".exe")
     *
     * @configurationSource {@link System#getProperty(String, String)}
     * @configurationType {@link java.lang.String}
     * @configurationDefaultValue {@link #DEFAULT_NATIVE_NAME}
     */
    public static final String SYSTEM_PROP_NATIVE_NAME = "aether.named.ipc.nativeName";

    public static final String DEFAULT_NATIVE_NAME = "ipc-sync";

    /**
     * Should the IPC server log debug messages? (i.e. for testing purposes)
     *
     * @configurationSource {@link System#getProperty(String, String)}
     * @configurationType {@link java.lang.Boolean}
     * @configurationDefaultValue {@link #DEFAULT_DEBUG}
     */
    public static final String SYSTEM_PROP_DEBUG = "aether.named.ipc.debug";

    public static final boolean DEFAULT_DEBUG = false;

    private final ServerSocketChannel serverSocket;
    private final Map<SocketChannel, Thread> clients = new HashMap<>();
    private final AtomicInteger counter = new AtomicInteger();
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();
    private final Map<String, Context> contexts = new ConcurrentHashMap<>();
    private static final boolean DEBUG =
            Boolean.parseBoolean(System.getProperty(SYSTEM_PROP_DEBUG, Boolean.toString(DEFAULT_DEBUG)));
    private final long idleTimeout;
    private volatile long lastUsed;
    private volatile boolean closing;

    public IpcServer(SocketFamily family) throws IOException {
        serverSocket = family.openServerSocket();
        long timeout = TimeUnit.SECONDS.toNanos(DEFAULT_IDLE_TIMEOUT);
        String str = System.getProperty(SYSTEM_PROP_IDLE_TIMEOUT);
        if (str != null) {
            try {
                TimeUnit unit = TimeUnit.SECONDS;
                if (str.endsWith("ms")) {
                    unit = TimeUnit.MILLISECONDS;
                    str = str.substring(0, str.length() - 2);
                }
                long dur = Long.parseLong(str);
                timeout = unit.toNanos(dur);
            } catch (NumberFormatException e) {
                error("Property " + SYSTEM_PROP_IDLE_TIMEOUT + " specified with invalid value: " + str, e);
            }
        }
        idleTimeout = timeout;
    }

    public static void main(String[] args) throws Exception {
        // When spawning a new process, the child process is create within
        // the same process group.  This means that a few signals are sent
        // to the whole group.  This is the case for SIGINT (Ctrl-C) and
        // SIGTSTP (Ctrl-Z) which are both sent to all the processed in the
        // group when initiated from the controlling terminal.
        // This is only a problem when the client creates the daemon, but
        // without ignoring those signals, a client being interrupted will
        // also interrupt and kill the daemon.
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("INT"), sun.misc.SignalHandler.SIG_IGN);
            if (IpcClient.IS_WINDOWS) {
                sun.misc.Signal.handle(new sun.misc.Signal("TSTP"), sun.misc.SignalHandler.SIG_IGN);
            }
        } catch (Throwable t) {
            error("Unable to ignore INT and TSTP signals", t);
        }

        String family = args[0];
        String tmpAddress = args[1];
        String rand = args[2];

        runServer(SocketFamily.valueOf(family), tmpAddress, rand);
    }

    static IpcServer runServer(SocketFamily family, String tmpAddress, String rand) throws IOException {
        IpcServer server = new IpcServer(family);
        run(server::run, false); // this is one-off
        String address = SocketFamily.toString(server.getLocalAddress());
        SocketAddress socketAddress = SocketFamily.fromString(tmpAddress);
        try (SocketChannel socket = SocketChannel.open(socketAddress)) {
            try (DataOutputStream dos = new DataOutputStream(Channels.newOutputStream(socket))) {
                dos.writeUTF(rand);
                dos.writeUTF(address);
                dos.flush();
            }
        }

        return server;
    }

    private static void debug(String msg, Object... args) {
        if (DEBUG) {
            System.out.printf("[ipc] [debug] " + msg + "\n", args);
        }
    }

    private static void info(String msg, Object... args) {
        System.out.printf("[ipc] [info] " + msg + "\n", args);
    }

    private static void error(String msg, Throwable t) {
        System.out.println("[ipc] [error] " + msg);
        t.printStackTrace(System.out);
    }

    private static void run(Runnable runnable, boolean daemon) {
        Thread thread = new Thread(runnable);
        if (daemon) {
            thread.setDaemon(true);
        }
        thread.start();
    }

    public SocketAddress getLocalAddress() throws IOException {
        return serverSocket.getLocalAddress();
    }

    public void run() {
        try {
            info("IpcServer started at %s", getLocalAddress().toString());
            use();
            run(this::expirationCheck, true);
            while (!closing) {
                SocketChannel socket = this.serverSocket.accept();
                run(() -> client(socket), false);
            }
        } catch (Throwable t) {
            if (!closing) {
                error("Error running sync server loop", t);
            }
        }
    }

    private void client(SocketChannel socket) {
        int c;
        synchronized (clients) {
            clients.put(socket, Thread.currentThread());
            c = clients.size();
        }
        info("New client connected (%d connected)", c);
        use();
        Map<String, Context> clientContexts = new ConcurrentHashMap<>();
        try {
            ByteChannel wrapper = new ByteChannelWrapper(socket);
            DataInputStream input = new DataInputStream(Channels.newInputStream(wrapper));
            DataOutputStream output = new DataOutputStream(Channels.newOutputStream(wrapper));
            while (!closing) {
                int requestId = input.readInt();
                int sz = input.readInt();
                List<String> request = new ArrayList<>(sz);
                for (int i = 0; i < sz; i++) {
                    request.add(input.readUTF());
                }
                if (request.isEmpty()) {
                    throw new IOException("Received invalid request");
                }
                use();
                String contextId;
                Context context;
                String command = request.remove(0);
                switch (command) {
                    case IpcMessages.REQUEST_CONTEXT:
                        if (request.size() != 1) {
                            throw new IOException("Expected one argument for " + command + " but got " + request);
                        }
                        boolean shared = Boolean.parseBoolean(request.remove(0));
                        context = new Context(shared);
                        contexts.put(context.id, context);
                        clientContexts.put(context.id, context);
                        synchronized (output) {
                            debug("Created context %s", context.id);
                            output.writeInt(requestId);
                            output.writeInt(2);
                            output.writeUTF(IpcMessages.RESPONSE_CONTEXT);
                            output.writeUTF(context.id);
                            output.flush();
                        }
                        break;
                    case IpcMessages.REQUEST_ACQUIRE:
                        if (request.size() < 1) {
                            throw new IOException(
                                    "Expected at least one argument for " + command + " but got " + request);
                        }
                        contextId = request.remove(0);
                        context = contexts.get(contextId);
                        if (context == null) {
                            throw new IOException(
                                    "Unknown context: " + contextId + ". Known contexts = " + contexts.keySet());
                        }
                        context.lock(request).thenRun(() -> {
                            try {
                                synchronized (output) {
                                    debug("Locking in context %s", context.id);
                                    output.writeInt(requestId);
                                    output.writeInt(1);
                                    output.writeUTF(IpcMessages.RESPONSE_ACQUIRE);
                                    output.flush();
                                }
                            } catch (IOException e) {
                                try {
                                    socket.close();
                                } catch (IOException ioException) {
                                    e.addSuppressed(ioException);
                                }
                                error("Error writing lock response", e);
                            }
                        });
                        break;
                    case IpcMessages.REQUEST_CLOSE:
                        if (request.size() != 1) {
                            throw new IOException("Expected one argument for " + command + " but got " + request);
                        }
                        contextId = request.remove(0);
                        context = contexts.remove(contextId);
                        clientContexts.remove(contextId);
                        if (context == null) {
                            throw new IOException(
                                    "Unknown context: " + contextId + ". Known contexts = " + contexts.keySet());
                        }
                        context.unlock();
                        synchronized (output) {
                            debug("Closing context %s", context.id);
                            output.writeInt(requestId);
                            output.writeInt(1);
                            output.writeUTF(IpcMessages.RESPONSE_CLOSE);
                            output.flush();
                        }
                        break;
                    case IpcMessages.REQUEST_STOP:
                        if (request.size() != 0) {
                            throw new IOException("Expected zero argument for " + command + " but got " + request);
                        }
                        synchronized (output) {
                            debug("Stopping server");
                            output.writeInt(requestId);
                            output.writeInt(1);
                            output.writeUTF(IpcMessages.RESPONSE_STOP);
                            output.flush();
                        }
                        close();
                        break;
                    default:
                        throw new IOException("Unknown request: " + request.get(0));
                }
            }
        } catch (Throwable t) {
            if (!closing) {
                error("Error processing request", t);
            }
        } finally {
            if (!closing) {
                info("Client disconnecting...");
            }
            clientContexts.values().forEach(context -> {
                contexts.remove(context.id);
                context.unlock();
            });
            try {
                socket.close();
            } catch (IOException ioException) {
                // ignore
            }
            synchronized (clients) {
                clients.remove(socket);
                c = clients.size();
            }
            if (!closing) {
                info("%d clients remained", c);
            }
        }
    }

    private void use() {
        lastUsed = System.nanoTime();
    }

    private void expirationCheck() {
        while (true) {
            long current = System.nanoTime();
            long left = (lastUsed + idleTimeout) - current;
            if (clients.isEmpty() && left < 0) {
                info("IpcServer expired, closing");
                close();
                break;
            } else {
                try {
                    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(left));
                } catch (InterruptedException e) {
                    info("IpcServer expiration check interrupted, closing");
                    close();
                    break;
                }
            }
        }
    }

    void close() {
        closing = true;
        try {
            serverSocket.close();
        } catch (IOException e) {
            error("Error closing server socket", e);
        }
        clients.forEach((s, t) -> {
            try {
                s.close();
            } catch (IOException e) {
                // ignore
            }
            t.interrupt();
        });
    }

    static class Waiter {
        final Context context;
        final CompletableFuture<Void> future;

        Waiter(Context context, CompletableFuture<Void> future) {
            this.context = context;
            this.future = future;
        }
    }

    static class Lock {

        final String key;

        List<Context> holders;
        List<Waiter> waiters;

        Lock(String key) {
            this.key = key;
        }

        public synchronized CompletableFuture<Void> lock(Context context) {
            if (holders == null) {
                holders = new ArrayList<>();
            }
            if (holders.isEmpty() || holders.get(0).shared && context.shared) {
                holders.add(context);
                return CompletableFuture.completedFuture(null);
            }
            if (waiters == null) {
                waiters = new ArrayList<>();
            }

            CompletableFuture<Void> future = new CompletableFuture<>();
            waiters.add(new Waiter(context, future));
            return future;
        }

        public synchronized void unlock(Context context) {
            if (holders.remove(context)) {
                while (waiters != null
                        && !waiters.isEmpty()
                        && (holders.isEmpty() || holders.get(0).shared && waiters.get(0).context.shared)) {
                    Waiter waiter = waiters.remove(0);
                    holders.add(waiter.context);
                    waiter.future.complete(null);
                }
            } else if (waiters != null) {
                for (Iterator<Waiter> it = waiters.iterator(); it.hasNext(); ) {
                    Waiter waiter = it.next();
                    if (waiter.context == context) {
                        it.remove();
                        waiter.future.cancel(false);
                    }
                }
            }
        }
    }

    class Context {

        final String id;
        final boolean shared;
        final List<String> locks = new CopyOnWriteArrayList<>();

        Context(boolean shared) {
            this.id = String.format("%08x", counter.incrementAndGet());
            this.shared = shared;
        }

        public CompletableFuture<?> lock(List<String> keys) {
            locks.addAll(keys);
            CompletableFuture<?>[] futures = keys.stream()
                    .map(k -> IpcServer.this.locks.computeIfAbsent(k, Lock::new))
                    .map(l -> l.lock(this))
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(futures);
        }

        public void unlock() {
            locks.stream()
                    .map(k -> IpcServer.this.locks.computeIfAbsent(k, Lock::new))
                    .forEach(l -> l.unlock(this));
        }
    }
}
