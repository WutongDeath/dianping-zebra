package com.dianping.zebra.shard.jdbc.parallel;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ExtendedThreadPoolExecutor extends ThreadPoolExecutor {

	public ExtendedThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
			BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
		super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory);
	}

	public <T> List<Future<T>> invokeSQLs(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
			throws SQLException {
		if (tasks == null)
			throw new NullPointerException();
		long nanos = unit.toNanos(timeout);
		ArrayList<Future<T>> futures = new ArrayList<Future<T>>(tasks.size());
		boolean done = false;
		try {
			for (Callable<T> t : tasks)
				futures.add(newTaskFor(t));

			final long deadline = System.nanoTime() + nanos;
			final int size = futures.size();

			// Interleave time checks and calls to execute in case
			// executor doesn't have any/much parallelism.
			for (int i = 0; i < size; i++) {
				execute((Runnable) futures.get(i));
				nanos = deadline - System.nanoTime();
				if (nanos <= 0L) {
					throw new SQLException(
							"Error! Do not have enough thread to excute sql, you have to increase your thread pool maxsize");
				}
			}

			for (int i = 0; i < size; i++) {
				Future<T> f = futures.get(i);
				if (!f.isDone()) {
					if (nanos <= 0L)
						return futures;
					try {
						f.get(nanos, TimeUnit.NANOSECONDS);
					} catch (CancellationException ce) {
						cancelAll(futures);
						throw new SQLException(ce);
					} catch (ExecutionException ee) {
						cancelAll(futures);
						throw new SQLException(ee.getCause());
					} catch (TimeoutException toe) {
						cancelAll(futures);
						throw new SQLException("One of your sql's execution time is beyond 1 seconds", toe);
					} catch (InterruptedException e) {
						cancelAll(futures);
						throw new SQLException(e);
					}
					nanos = deadline - System.nanoTime();
				}
			}
			done = true;
			return futures;
		} finally {
			if (!done)
				for (int i = 0, size = futures.size(); i < size; i++)
					futures.get(i).cancel(true);
		}
	}

	private <T> void cancelAll(ArrayList<Future<T>> futures) {
		for (Future<T> f : futures) {
			if (!f.isCancelled()) {
				f.cancel(true);
			}
		}
	}
}
