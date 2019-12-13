/*
 * The MIT License
 * 
 * Copyright (c) 2016 IKEDA Yasuyuki
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.plugins.throttleconcurrents.testutils;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.OneOffExecutor;
import hudson.model.Queue;
import hudson.slaves.RetentionStrategy;

/**
 * Wrapper for {@link RetentionStrategy} to mark
 * how many executors are used at the same time.
 */
public class ExecutorWaterMarkRetentionStrategy<T extends Computer> extends RetentionStrategy<T> implements ExecutorListener {
    private final RetentionStrategy<T> baseStrategy;
    private transient int executorWaterMark = 0;
    private transient int executorNum = 0;
    private transient int oneOffExecutorWaterMark = 0;
    private transient int oneOffExecutorNum = 0;
    
    public ExecutorWaterMarkRetentionStrategy(RetentionStrategy<T> baseStrategy) {
        this.baseStrategy = baseStrategy;
    }
    
    public int getExecutorWaterMark() {
        return executorWaterMark;
    }
    
    public int getOneOffExecutorWaterMark() {
        return oneOffExecutorWaterMark;
    }
    
    private void Increment(Executor executor) {
        synchronized(this) {
            if (executor instanceof OneOffExecutor) {
                if (++oneOffExecutorNum > oneOffExecutorWaterMark) {
                    oneOffExecutorWaterMark = oneOffExecutorNum;
                }
            } else {
                if (++executorNum > executorWaterMark) {
                    executorWaterMark = executorNum;
                }
            }
        }
    }
    
    private void Decrement(Executor executor) {
        synchronized(this) {
            if (executor instanceof OneOffExecutor) {
                --oneOffExecutorNum;
            } else {
                --executorNum;
            }
        }
    }
    
    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        Increment(executor);
        if (baseStrategy instanceof ExecutorListener) {
            ((ExecutorListener)baseStrategy).taskAccepted(executor, task);
        }
    }
    
    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        if (baseStrategy instanceof ExecutorListener) {
            ((ExecutorListener)baseStrategy).taskCompleted(executor, task, durationMS);
        }
        Decrement(executor);
    }
    
    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        if (baseStrategy instanceof ExecutorListener) {
            ((ExecutorListener)baseStrategy).taskCompletedWithProblems(executor, task, durationMS, problems);
        }
        Decrement(executor);
    }
    
    @Override
    public long check(T c) {
        return baseStrategy.check(c);
    }
    
}
