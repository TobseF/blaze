/*
 * Copyright 2015 Fizzed, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fizzed.blaze.ssh.impl;

import com.fizzed.blaze.ssh.*;
import com.fizzed.blaze.Context;
import com.fizzed.blaze.core.UnexpectedExitValueException;
import com.fizzed.blaze.core.BlazeException;
import com.fizzed.blaze.util.ObjectHelper;
import com.fizzed.blaze.util.WrappedOutputStream;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fizzed.blaze.core.ExecMixin;
import com.fizzed.blaze.util.InterruptibleInputStream;
import java.io.InputStream;
import org.apache.commons.io.output.NullOutputStream;

public class JschExec extends SshExec implements ExecMixin<SshExec> {
    static private final Logger log = LoggerFactory.getLogger(JschExec.class);
    
    public JschExec(Context context, SshSession session) {
        super(context, session);
    }
    
    @Override
    protected Result doRun() throws BlazeException {
        Session jschSession = ((JschSession)session).getJschSession();
        ObjectHelper.requireNonNull(jschSession, "ssh session must be established first");
        ObjectHelper.requireNonNull(command, "ssh command cannot be null");
        
        ChannelExec channel = null;
        try {
            channel = (ChannelExec)jschSession.openChannel("exec");
            
            if (pty) {
                channel.setPty(true);
            }
            
            // setup environment
            if (this.environment != null) {
                for (Map.Entry<String,String> entry : this.environment.entrySet()) {
                    log.debug("Adding env {}={}", entry.getKey(), entry.getValue());
                    channel.setEnv(entry.getKey(), entry.getValue());
                }
            }
            
            // NOTE: In order for JSCH to pump the inputstream to its outputstream
            // it starts an "exec thread" for every channel-exec it creates
            // that also includes non-null inputstream. The problem is that
            // JSCH's exec thread will block forever on the inputstream.read()
            // call unless the inputstream is actually closed.  Since we don't
            // want that behavior, we'll sneakily introduce a wrapped inputstream
            // that will supply an interruptible read() call. This is done in
            // 2 steps:  1) our read() method will use a combo of Thread.sleep()
            // and available() to provide a non-blocking read() that will also
            // response to a Thread.interrupt() call.  and 2) we'll capture
            // a reference to JSCH's exec thread by saving it when it actually
            // enters the read() method.
            
            // setup in/out streams
            final InputStream is = (pipeInput != null ? new InterruptibleInputStream(pipeInput.stream()) : null);
            final OutputStream os = (pipeOutput != null ? pipeOutput.stream() : new NullOutputStream());
            final OutputStream es = (pipeErrorToOutput ? os : (pipeError != null ? pipeError.stream() : new NullOutputStream()));
            
            if (is != null) {
                channel.setInputStream(is, false);
            }
            
            // both streams closing signals exec is finished
            final CountDownLatch outputStreamClosedSignal = new CountDownLatch(1);
            final CountDownLatch errorStreamClosedSignal = new CountDownLatch(1);
            
            // determine final ouput and then wrap to monitor for close events
            if (os != null) {
                channel.setOutputStream(new WrappedOutputStream(os) {
                    @Override
                    public void close() throws IOException {
                        outputStreamClosedSignal.countDown();
                        super.close();
                    }
                }, false);
            } else {
                outputStreamClosedSignal.countDown();
            }
            
            // determine final error and then wrap to monitor for close events
            if (es != null) {
                channel.setErrStream(new WrappedOutputStream(es) {
                    @Override
                    public void close() throws IOException {
                        errorStreamClosedSignal.countDown();
                        super.close();
                    }
                }, false);
            } else {
                errorStreamClosedSignal.countDown();
            }
            
            // building the command may be a little tricky, not sure about spaces...
            final StringBuilder sb = new StringBuilder(PathHelper.toString(command));
            
            arguments.stream().forEach((arg) -> {
                sb.append(" ");
                // TODO: should we actually escape instead such as " " -> "\ "???
                if (arg.contains(" ")) {
                    sb.append("'");
                    sb.append(arg);
                    sb.append("'");
                } else {
                    sb.append(arg);
                }
            });
            
            String finalCommand = sb.toString();
            
            log.debug("ssh-exec [{}]", finalCommand);
            
            channel.setCommand(finalCommand);
            
            // this connects and sends command
            channel.connect();
            
            // wait for both streams to be closed
            outputStreamClosedSignal.await();
            errorStreamClosedSignal.await();
            
            Integer exitValue = channel.getExitStatus();
            
            if (!this.exitValues.contains(exitValue)) {
                throw new UnexpectedExitValueException("Process exited with unexpected value", this.exitValues, exitValue);
            }
            
            return new SshExec.Result(this, exitValue);
        } catch (JSchException | InterruptedException e) {
            throw new SshException(e.getMessage(), e);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            
            /**
            // cleanup JSCH exec thread if it exists
            Thread execThread = execThreadRef.get();
            if (execThread != null) {
                log.trace("Interrupting thread [{}]", execThread.getName());
                execThread.interrupt();
            }
            */
        }
    }
    
}
