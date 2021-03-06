/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.internal.net.http;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.concurrent.CompletableFuture;
import jdk.internal.net.http.common.FlowTube;
import jdk.internal.net.http.common.Log;
import jdk.internal.net.http.common.MinimalFuture;
import jdk.internal.net.http.common.Utils;

/**
 * Plain raw TCP connection direct to destination.
 * The connection operates in asynchronous non-blocking mode.
 * All reads and writes are done non-blocking.
 */
class PlainHttpConnection extends HttpConnection {

    private final Object reading = new Object();
    protected final SocketChannel chan;
    private final SocketTube tube; // need SocketTube to call signalClosed().
    private final PlainHttpPublisher writePublisher = new PlainHttpPublisher(reading);
    private volatile boolean connected;
    private boolean closed;

    // should be volatile to provide proper synchronization(visibility) action

    final class ConnectEvent extends AsyncEvent {
        private final CompletableFuture<Void> cf;

        ConnectEvent(CompletableFuture<Void> cf) {
            this.cf = cf;
        }

        @Override
        public SelectableChannel channel() {
            return chan;
        }

        @Override
        public int interestOps() {
            return SelectionKey.OP_CONNECT;
        }

        @Override
        public void handle() {
            try {
                assert !connected : "Already connected";
                assert !chan.isBlocking() : "Unexpected blocking channel";
                if (debug.on())
                    debug.log("ConnectEvent: finishing connect");
                boolean finished = chan.finishConnect();
                assert finished : "Expected channel to be connected";
                if (debug.on())
                    debug.log("ConnectEvent: connect finished: %s Local addr: %s",
                              finished, chan.getLocalAddress());
                connected = true;
                // complete async since the event runs on the SelectorManager thread
                cf.completeAsync(() -> null, client().theExecutor());
            } catch (Throwable e) {
                client().theExecutor().execute( () -> cf.completeExceptionally(e));
            }
        }

        @Override
        public void abort(IOException ioe) {
            close();
            client().theExecutor().execute( () -> cf.completeExceptionally(ioe));
        }
    }

    @Override
    public CompletableFuture<Void> connectAsync() {
        CompletableFuture<Void> cf = new MinimalFuture<>();
        try {
            assert !connected : "Already connected";
            assert !chan.isBlocking() : "Unexpected blocking channel";
            boolean finished = false;
            PrivilegedExceptionAction<Boolean> pa =
                    () -> chan.connect(Utils.resolveAddress(address));
            try {
                 finished = AccessController.doPrivileged(pa);
            } catch (PrivilegedActionException e) {
                cf.completeExceptionally(e.getCause());
            }
            if (finished) {
                if (debug.on()) debug.log("connect finished without blocking");
                connected = true;
                cf.complete(null);
            } else {
                if (debug.on()) debug.log("registering connect event");
                client().registerEvent(new ConnectEvent(cf));
            }
        } catch (Throwable throwable) {
            cf.completeExceptionally(throwable);
        }
        return cf;
    }

    @Override
    SocketChannel channel() {
        return chan;
    }

    @Override
    final FlowTube getConnectionFlow() {
        return tube;
    }

    PlainHttpConnection(InetSocketAddress addr, HttpClientImpl client) {
        super(addr, client);
        try {
            this.chan = SocketChannel.open();
            chan.configureBlocking(false);
            int bufsize = client.getReceiveBufferSize();
            if (!trySetReceiveBufferSize(bufsize)) {
                trySetReceiveBufferSize(256*1024);
            }
            chan.setOption(StandardSocketOptions.TCP_NODELAY, true);
            // wrap the connected channel in a Tube for async reading and writing
            tube = new SocketTube(client(), chan, Utils::getBuffer);
        } catch (IOException e) {
            throw new InternalError(e);
        }
    }

    private boolean trySetReceiveBufferSize(int bufsize) {
        try {
            chan.setOption(StandardSocketOptions.SO_RCVBUF, bufsize);
            if (debug.on())
                debug.log("Receive buffer size is %s",
                          chan.getOption(StandardSocketOptions.SO_RCVBUF));
            return true;
        } catch(IOException x) {
            if (debug.on())
                debug.log("Failed to set receive buffer size to %d on %s",
                          bufsize, chan);
        }
        return false;
    }

    @Override
    HttpPublisher publisher() { return writePublisher; }


    @Override
    public String toString() {
        return "PlainHttpConnection: " + super.toString();
    }

    /**
     * Closes this connection
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            Log.logTrace("Closing: " + toString());
            if (debug.on())
                debug.log("Closing channel: " + client().debugInterestOps(chan));
            chan.close();
            tube.signalClosed();
        } catch (IOException e) {
            Log.logTrace("Closing resulted in " + e);
        }
    }


    @Override
    ConnectionPool.CacheKey cacheKey() {
        return new ConnectionPool.CacheKey(address, null);
    }

    @Override
    synchronized boolean connected() {
        return connected;
    }


    @Override
    boolean isSecure() {
        return false;
    }

    @Override
    boolean isProxied() {
        return false;
    }

}
