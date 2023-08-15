/*
 * Copyright (C) 2016 Julien Viet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.julienviet.childprocess.impl;

import com.julienviet.childprocess.StreamInput;
import com.julienviet.childprocess.StreamOutput;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;
import com.zaxxer.nuprocess.NuProcessHandler;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import com.julienviet.childprocess.Process;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class ProcessImpl implements NuProcessHandler, Process, StreamOutput {

  private static final int OPEN = 0, CLOSING = 1, CLOSED = 2;

  private NuProcessBuilder builder;
  private int stdinStatus = OPEN;
  private final ArrayDeque<Buffer> stdinPending = new ArrayDeque<>();
  private int stdinSize;
  private int stdinMaxSize = 1024;
  private Handler<Void> drainHandler;
  private final Context context;
  private final ProcessStreamInput stdout;
  private final ProcessStreamInput stderr;
  private Handler<Process> processHandler;
  private Handler<Integer> exitHandler;
  private NuProcess process;
  private boolean wantWrite;

  public ProcessImpl(Context context, NuProcessBuilder builder) {
    this.context = context;
    this.stdout = new ProcessStreamInput(context);
    this.stderr = new ProcessStreamInput(context);
    this.builder = builder;
  }

  //


  @Override
  public synchronized void start() {
    start(p -> {});
  }

  @Override
  public synchronized void start(Handler<Process> handler) {
    if (processHandler != null) {
      throw new IllegalStateException();
    }
    processHandler = handler;
    builder.setProcessListener(this);
    context.runOnContext(v -> {
      builder.start();
    });
  }

  @Override
  public synchronized Process exitHandler(Handler<Integer> handler) {
    exitHandler = handler;
    return this;
  }

  @Override
  public synchronized Integer pid() {
    return process != null ? process.getPID() : null;
  }

  @Override
  public StreamOutput stdin() {
    return this;
  }

  @Override
  public StreamInput stdout() {
    return stdout;
  }

  @Override
  public StreamInput stderr() {
    return stderr;
  }

  //

  @Override
  public StreamOutput exceptionHandler(Handler<Throwable> handler) {
    return this;
  }

  @Override
  public void write(Buffer data, Handler<AsyncResult<Void>> handler) {

  }

  @Override
  public void end(Handler<AsyncResult<Void>> handler) {

  }

  @Override
  public Future<Void> write(Buffer buffer) {
    boolean hasPending;
    synchronized (this) {
      if (stdinStatus == CLOSING || stdinStatus == CLOSED) {
        throw new IllegalStateException();
      }
      stdinPending.add(buffer);
      stdinSize += buffer.length();
      hasPending = stdinSize > 0;
    }
    if (process != null && hasPending && !wantWrite) {
      wantWrite = true;
      process.wantWrite();
    }
    return null;
  }

  @Override
  public synchronized StreamOutput setWriteQueueMaxSize(int i) {
    stdinMaxSize = i;
    return this;
  }

  @Override
  public StreamOutput drainHandler(Handler<Void> handler) {
    drainHandler = handler;
    checkDrained();
    return this;
  }

  @Override
  public synchronized boolean writeQueueFull() {
    return stdinSize > stdinMaxSize;
  }

  @Override
  public Future<Void> close() {
    synchronized (this) {
      switch (stdinStatus) {
        case OPEN:
          if (process != null) {
            if (stdinSize == 0) {
              stdinStatus = CLOSED;
            } else {
              stdinStatus = CLOSING;
              return null;
            }
          } else {
            // We close the stream before the process started
            stdinStatus = CLOSING;
            return null;
          }
          break;
        default:
          return null;
      }
    }
    process.closeStdin(false);
    return null;
  }

  //

  @Override
  public void onPreStart(NuProcess nuProcess) {
  }

  @Override
  public synchronized void onStart(NuProcess nuProcess) {
    process = nuProcess;
    stdinStatus = OPEN;
    if (stdinPending.size() > 0) {
      wantWrite = true;
      process.wantWrite();
    }
    context.runOnContext(v -> {
      processHandler.handle(this);
    });
  }

  @Override
  public synchronized void onExit(int exitCode) {
    if (process == null) {
      // Early failure
      context.runOnContext(v -> {
        if (processHandler != null) {
          processHandler.handle(this);
        }
        handleExit(exitCode);
      });
    } else {
      process = null;
      synchronized (this) {
        stdinStatus = CLOSED;
      }
      handleExit(exitCode);
    }
  }

  private void handleExit(int exitCode) {
    Handler<Integer> handler = exitHandler;
    if (handler != null) {
      context.runOnContext(v -> {
        handler.handle(exitCode);
      });
    }
  }

  @Override
  public void onStdout(ByteBuffer byteBuffer, boolean closed) {
    if (byteBuffer != null && byteBuffer.remaining() > 0) {
      stdout.write(byteBuffer);
    }
    if (closed) {
      stdout.close();
    }
  }

  @Override
  public void onStderr(ByteBuffer byteBuffer, boolean closed) {
    if (byteBuffer != null && byteBuffer.remaining() > 0) {
      stderr.write(byteBuffer);
    }
    if (closed) {
      stderr.close();
    }
  }

  @Override
  public synchronized boolean onStdinReady(ByteBuffer byteBuffer) {
    Buffer buffer;
    while (byteBuffer.remaining() > 0 && (buffer = stdinPending.poll()) != null) {
      byte[] bytes;
      if (buffer.length() <= byteBuffer.remaining()) {
        bytes = buffer.getBytes();
      } else {
        bytes = buffer.getBytes(0, byteBuffer.remaining());
        stdinPending.addFirst(buffer.slice(byteBuffer.remaining(), buffer.length()));
      }
      byteBuffer.put(bytes); // See to do directly with Netty ByteBuf
      stdinSize -= bytes.length;
    }
    byteBuffer.flip();
    context.runOnContext(v -> checkDrained());
    if (stdinSize > 0) {
      return true;
    } else {
      wantWrite = false;
      if (stdinStatus == CLOSING) {
        stdinStatus = CLOSED;
        process.closeStdin(false);
      }
      return false;
    }
  }

  private void checkDrained() {
    synchronized (this) {
      if (stdinSize >= stdinMaxSize / 2) {
        return;
      }
    }
    if (drainHandler != null) {
      Handler<Void> handler = drainHandler;
      drainHandler = null;
      handler.handle(null);
    }
  }

  @Override
  public void kill(boolean force) {
    if (process != null) {
      process.destroy(force);
    }
  }

  @Override
  public boolean isRunning() {
    return process != null && process.isRunning();
  }
}
