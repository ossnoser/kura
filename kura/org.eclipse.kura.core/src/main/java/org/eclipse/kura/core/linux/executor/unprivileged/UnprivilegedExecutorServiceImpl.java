/*******************************************************************************
 * Copyright (c) 2019, 2020 Eurotech and/or its affiliates
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Eurotech
 *******************************************************************************/
package org.eclipse.kura.core.linux.executor.unprivileged;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.commons.io.Charsets;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.core.internal.linux.executor.ExecutorUtil;
import org.eclipse.kura.core.linux.executor.LinuxSignal;
import org.eclipse.kura.executor.Command;
import org.eclipse.kura.executor.CommandStatus;
import org.eclipse.kura.executor.ExecutorFactory;
import org.eclipse.kura.executor.Pid;
import org.eclipse.kura.executor.Signal;
import org.eclipse.kura.executor.UnprivilegedExecutorService;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UnprivilegedExecutorServiceImpl implements UnprivilegedExecutorService, ConfigurableComponent {

    private static final Logger logger = LoggerFactory.getLogger(UnprivilegedExecutorServiceImpl.class);
    private static final LinuxSignal DEFAULT_SIGNAL = LinuxSignal.SIGTERM;

    @SuppressWarnings("unused")
    private ComponentContext ctx;
    private UnprivilegedExecutorServiceOptions options;
    private ExecutorUtil executorUtil;

    public UnprivilegedExecutorServiceImpl() {
        this.executorUtil = new ExecutorUtil();
    }

    protected void activate(ComponentContext componentContext, Map<String, Object> properties) {
        logger.info("activate...");

        this.ctx = componentContext;
        this.options = new UnprivilegedExecutorServiceOptions(properties);
        this.executorUtil.setCommandUsername(this.options.getCommandUsername());
        this.executorUtil = new ExecutorUtil();
    }

    public void update(Map<String, Object> properties) {
        logger.info("updated...");

        this.options = new UnprivilegedExecutorServiceOptions(properties);
        this.executorUtil.setCommandUsername(this.options.getCommandUsername());
    }

    @Override
    public void setExecutorFactory(ExecutorFactory executorFactory) {
        if (this.executorUtil != null) {
            this.executorUtil.setExecutorFactory(executorFactory);
        }
    }

    protected void deactivate(ComponentContext componentContext) {
        logger.info("deactivate...");
        this.ctx = null;
    }

    @Override
    public CommandStatus execute(Command command) {
        if (command.getCommandLine() == null || command.getCommandLine().length == 0) {
            return buildErrorStatus();
        }
        if (command.getSignal() == null) {
            command.setSignal(DEFAULT_SIGNAL);
        }
        return this.executorUtil.executeUnprivileged(command);
    }

    @Override
    public void execute(Command command, Consumer<CommandStatus> callback) {
        if (command.getCommandLine() == null || command.getCommandLine().length == 0) {
            callback.accept(buildErrorStatus());
        }
        if (command.getSignal() == null) {
            command.setSignal(DEFAULT_SIGNAL);
        }
        this.executorUtil.executeUnprivileged(command, callback);
    }

    @Override
    public boolean stop(Pid pid, Signal signal) {
        boolean isStopped = false;
        if (signal == null) {
            isStopped = this.executorUtil.stopUnprivileged(pid, DEFAULT_SIGNAL);
        } else {
            isStopped = this.executorUtil.stopUnprivileged(pid, signal);
        }
        return isStopped;
    }

    @Override
    public boolean kill(String[] commandLine, Signal signal) {
        boolean isKilled = false;
        if (signal == null) {
            isKilled = this.executorUtil.killUnprivileged(commandLine, DEFAULT_SIGNAL);
        } else {
            isKilled = this.executorUtil.killUnprivileged(commandLine, signal);
        }
        return isKilled;
    }

    @Override
    public boolean isRunning(Pid pid) {
        return this.executorUtil.isRunning(pid);
    }

    @Override
    public boolean isRunning(String[] commandLine) {
        return this.executorUtil.isRunning(commandLine);
    }

    @Override
    public Map<String, Pid> getPids(String[] commandLine) {
        return this.executorUtil.getPids(commandLine);
    }

    private CommandStatus buildErrorStatus() {
        CommandStatus status = new CommandStatus(1);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            err.write("The commandLine cannot be empty or not defined".getBytes(Charsets.UTF_8));
        } catch (IOException e) {
            logger.error("Cannot write to error stream", e);
        }
        status.setErrorStream(err);
        return status;
    }

}