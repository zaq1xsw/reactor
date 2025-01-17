/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.error.Exceptions;
import reactor.core.error.ReactorFatalException;
import reactor.core.error.SpecificationExceptions;
import reactor.core.subscriber.BaseSubscriber;
import reactor.core.support.BackpressureUtils;
import reactor.core.support.ReactiveState;
import reactor.core.support.SignalType;
import reactor.core.support.internal.PlatformDependent;
import reactor.core.support.rb.disruptor.RingBuffer;
import reactor.fn.BiFunction;
import reactor.fn.Function;
import reactor.fn.Supplier;
import reactor.fn.tuple.Tuple;

/**
 * A zip operator to combine 1 by 1 each upstream in parallel given the combinator function.
 * @author Stephane Maldini
 * @since 2.5
 */
public final class PublisherZip<TUPLE extends Tuple, V>
		implements Publisher<V>, ReactiveState.Factory, ReactiveState.LinkedUpstreams {

	/**
	 *
	 */
	public static final Function JOIN_FUNCTION = new Function<Tuple, List>() {
		@Override
		public List<?> apply(Tuple ts) {
			return Arrays.asList(ts.toArray());
		}
	};

	/**
	 *
	 */
	public static final BiFunction JOIN_BIFUNCTION = new BiFunction<Object, Object, List>() {
		@Override
		public List<?> apply(Object t1, Object t2) {
			return Arrays.asList(t1, t2);
		}
	};

	final Function<? super TUPLE, ? extends V> combinator;
	final int                                  bufferSize;
	final Publisher[]                          sources;

	public PublisherZip(final Publisher[] sources,
			final Function<? super TUPLE, ? extends V> combinator,
			int bufferSize) {
		this.combinator = combinator;
		this.bufferSize = bufferSize;
		this.sources = sources;
	}

	@Override
	public void subscribe(Subscriber<? super V> s) {
		if (s == null) {
			throw SpecificationExceptions.spec_2_13_exception();
		}
		try {
			if (sources == null || sources.length == 0) {
				s.onSubscribe(SignalType.NOOP_SUBSCRIPTION);
				s.onComplete();
				return;
			}

			ZipBarrier<TUPLE, V> barrier = new ZipBarrier<>(s, this);
			barrier.start();
			s.onSubscribe(barrier);
		}
		catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			s.onError(t);
		}
	}

	@Override
	public Iterator<?> upstreams() {
		return Arrays.asList(sources)
		             .iterator();
	}

	@Override
	public long upstreamsCount() {
		return sources != null ? sources.length : 0;
	}

	static final class ZipBarrier<TUPLE extends Tuple, V>
			implements Subscription,
			           ReactiveState.LinkedUpstreams,
			           ReactiveState.ActiveDownstream,
			           ReactiveState.Buffering,
			           ReactiveState.ActiveUpstream,
			           ReactiveState.DownstreamDemand,
			           ReactiveState.FailState {

		final PublisherZip<TUPLE, V> parent;
		final int                    limit;
		final ZipState<?>[]          subscribers;
		final Subscriber<? super V>  actual;

		@SuppressWarnings("unused")
		private volatile Throwable error;

		private volatile boolean cancelled;

		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<ZipBarrier, Throwable> ERROR =
				PlatformDependent.newAtomicReferenceFieldUpdater(ZipBarrier.class, "error");

		@SuppressWarnings("unused")
		private volatile       long                               requested = 0L;
		@SuppressWarnings("rawtypes")
		protected static final AtomicLongFieldUpdater<ZipBarrier> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(ZipBarrier.class, "requested");

		@SuppressWarnings("unused")
		private volatile int running;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ZipBarrier> RUNNING =
				AtomicIntegerFieldUpdater.newUpdater(ZipBarrier.class, "running");

		Object[] valueCache;

		final static Object[] TERMINATED_CACHE = new Object[0];

		public ZipBarrier(Subscriber<? super V> actual, PublisherZip<TUPLE, V> parent) {
			this.actual = actual;
			this.parent = parent;
			this.subscribers = new ZipState[parent.sources.length];
			this.limit = Math.max(1, parent.bufferSize / 2);
		}

		@SuppressWarnings("unchecked")
		void start() {
			if (cancelled) {
				return;
			}

			ZipState[] subscribers = this.subscribers;
			valueCache = new Object[subscribers.length];

			int i;
			ZipState<?> inner;
			Publisher pub;
			for (i = 0; i < subscribers.length; i++) {
				pub = parent.sources[i];
				if (pub instanceof Supplier) {
					inner = new ScalarState(((Supplier<?>) pub).get());
					subscribers[i] = inner;
				}
				else {
					inner = new BufferSubscriber(this);
					subscribers[i] = inner;
				}
			}

			for (i = 0; i < subscribers.length; i++) {
				subscribers[i].subscribeTo(parent.sources[i]);
			}

			drain();
		}

		@Override
		public void request(long n) {
			BackpressureUtils.getAndAdd(REQUESTED, this, n);
			drain();
		}

		@Override
		public void cancel() {
			if (!cancelled) {
				cancelled = true;
				if (RUNNING.getAndIncrement(this) == 0) {
					cancelStates();
				}
			}
		}

		void reportError(Throwable throwable) {
			if (!ERROR.compareAndSet(this, null, throwable)) {
				throw ReactorFatalException.create(throwable);
			}
			actual.onError(throwable);
		}

		@Override
		public Iterator<?> upstreams() {
			return Arrays.asList(subscribers)
			             .iterator();
		}

		@Override
		public long upstreamsCount() {
			return subscribers.length;
		}

		@Override
		public Throwable getError() {
			return error;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public boolean isStarted() {
			return TERMINATED_CACHE != valueCache;
		}

		@Override
		public boolean isTerminated() {
			return TERMINATED_CACHE == valueCache;
		}

		@Override
		public long pending() {
			Object[] values = valueCache;
			int count = 0;
			for(int i = 0; i < values.length; i++){
				if(values[i] != null){
					count++;
				}
			}
			return count;
		}

		@Override
		public long requestedFromDownstream() {
			return requested;
		}

		void drain() {
			ZipState[] subscribers = this.subscribers;
			if (subscribers == null) {
				return;
			}
			if (RUNNING.getAndIncrement(this) == 0) {
				drainLoop(subscribers);
			}
		}

		@Override
		public long getCapacity() {
			return subscribers != null ? subscribers.length : 1;
		}

		@SuppressWarnings("unchecked")
		void drainLoop(ZipState[] inner) {

			final Subscriber<? super V> actual = this.actual;
			int missed = 1;
			for (; ; ) {
				if (checkImmediateTerminate()) {
					return;
				}

				int n = inner.length;
				int replenishMain = 0;
				long r = requested;

				ZipState<?> state;

				for (; ; ) {

					final Object[] tuple = valueCache;
					if(TERMINATED_CACHE == tuple) return;
					boolean completeTuple = true;
					int i;
					for (i = 0; i < n; i++) {
						state = inner[i];

						Object next = state.readNext();

						if (next == null) {
							if (state.isTerminated()) {
								actual.onComplete();
								cancelStates();
								return;
							}

							completeTuple = false;
							continue;
						}

						if (r != 0) {
							tuple[i] = next;
						}
					}

					if (r != 0 && completeTuple) {
						try {
							actual.onNext(parent.combinator.apply((TUPLE) Tuple.of(tuple)));
							if (r != Long.MAX_VALUE) {
								r--;
							}
							replenishMain++;
						}
						catch (Throwable e) {
							Exceptions.throwIfFatal(e);
							actual.onError(Exceptions.addValueAsLastCause(e, tuple));
							return;
						}

						// consume 1 from each and check if complete

						for (i = 0; i < n; i++) {

							if (checkImmediateTerminate()) {
								return;
							}

							state = inner[i];
							state.requestMore();

							if (state.readNext() == null && state.isTerminated()) {
								actual.onComplete();
								cancelStates();
								return;
							}
						}

						valueCache = new Object[n];
					}
					else {
						break;
					}
				}

				if (replenishMain > 0) {
					BackpressureUtils.getAndSub(REQUESTED, this, replenishMain);
				}

				missed = RUNNING.addAndGet(this, -missed);
				if (missed == 0) {
					break;
				}
			}
		}

		void cancelStates() {
			valueCache = TERMINATED_CACHE;
			for (int i = 0; i < subscribers.length; i++) {
				subscribers[i].cancel();
			}
		}

		boolean checkImmediateTerminate() {
			if (cancelled) {
				cancelStates();
				return true;
			}
			Throwable e = error;
			if (e != null) {
				try {
					actual.onError(error);
				}
				finally {
					cancelStates();
				}
				return true;
			}
			return false;
		}
	}

	interface ZipState<V> extends ReactiveState.ActiveDownstream, ReactiveState.Buffering,
	                              ReactiveState.ActiveUpstream, ReactiveState.Inner {

		V readNext();

		boolean isTerminated();

		void requestMore();

		void cancel();

		void subscribeTo(Publisher<?> o);
	}

	static final class ScalarState implements ZipState<Object> {

		final Object val;

		boolean read = false;

		ScalarState(Object val) {
			this.val = val;
		}

		@Override
		public Object readNext() {
			return read ? null : val;
		}

		@Override
		public boolean isTerminated() {
			return read;
		}

		@Override
		public boolean isCancelled() {
			return read;
		}

		@Override
		public boolean isStarted() {
			return !read;
		}

		@Override
		public void subscribeTo(Publisher<?> o) {
			//IGNORE
		}

		@Override
		public void requestMore() {
			read = true;
		}

		@Override
		public void cancel() {
			read = true;
		}

		@Override
		public long pending() {
			return read ? 0L : 1L;
		}

		@Override
		public long getCapacity() {
			return 1L;
		}

		@Override
		public String toString() {
			return "ScalarState{" +
					"read=" + read +
					", val=" + val +
					'}';
		}
	}

	static final class BufferSubscriber<V> extends BaseSubscriber<Object>
			implements Subscriber<Object>, ReactiveState.Bounded, ZipState<Object>, ReactiveState.Upstream,
			           ReactiveState.ActiveUpstream, ReactiveState.UpstreamPrefetch, ReactiveState.UpstreamDemand,
			           ReactiveState.Downstream {

		final ZipBarrier<?, V> parent;
		final Queue<Object>    queue;
		final int              limit;
		final int              bufferSize;

		@SuppressWarnings("unused")
		volatile Subscription subscription;
		final static AtomicReferenceFieldUpdater<BufferSubscriber, Subscription> SUBSCRIPTION =
				PlatformDependent.newAtomicReferenceFieldUpdater(BufferSubscriber.class, "subscription");

		volatile boolean done;
		int outstanding;

		public BufferSubscriber(ZipBarrier<?, V> parent) {
			this.parent = parent;
			this.bufferSize = parent.parent.bufferSize;
			this.limit = bufferSize >> 2;
			this.queue = RingBuffer.newSequencedQueue(RingBuffer.createSingleProducer(bufferSize));
		}

		@Override
		public Object upstream() {
			return subscription;
		}

		@Override
		public boolean isCancelled() {
			return parent.isCancelled();
		}

		@Override
		public long pending() {
			return queue.size();
		}

		@Override
		public Object downstream() {
			return parent;
		}

		@Override
		public void subscribeTo(Publisher<?> o) {
			o.subscribe(this);
		}

		@Override
		public void onSubscribe(Subscription s) {
			super.onSubscribe(s);

			if(parent.cancelled){
				s.cancel();
				return;
			}

			if (!SUBSCRIPTION.compareAndSet(this, null, s)) {
				s.cancel();
				return;
			}
			outstanding = bufferSize;
			s.request(outstanding);
		}

		@Override
		public void onNext(Object x) {
			super.onNext(x);
			queue.add(x);
			try {
				parent.drain();
			}
			catch (Throwable t) {
				Exceptions.throwIfFatal(t);
				parent.reportError(t);
			}
		}

		@Override
		public void onError(Throwable t) {
			super.onError(t);
			parent.reportError(t);
		}

		@Override
		public void onComplete() {
			done = true;
			parent.drain();
		}

		@Override
		public Object readNext() {
			return queue.peek();
		}

		@Override
		public boolean isTerminated() {
			return done && queue.isEmpty();
		}

		@Override
		public boolean isStarted() {
			return subscription != null;
		}

		@Override
		public void requestMore() {
			queue.poll();
			int r = outstanding - 1;
			if (r > limit) {
				outstanding = r;
				return;
			}
			outstanding = bufferSize;
			int k = bufferSize - r;
			if (k > 0) {
				SUBSCRIPTION.get(this)
				            .request(k);
			}
		}

		@Override
		public void cancel() {
			Subscription s = SUBSCRIPTION.get(this);
			if (s != SignalType.NOOP_SUBSCRIPTION) {
				s = SUBSCRIPTION.getAndSet(this, SignalType.NOOP_SUBSCRIPTION);
				if (s != SignalType.NOOP_SUBSCRIPTION && s != null) {
					s.cancel();
				}
			}
		}

		@Override
		public long limit() {
			return limit;
		}

		@Override
		public long expectedFromUpstream() {
			return outstanding;
		}

		@Override
		public long getCapacity() {
			return bufferSize;
		}

		@Override
		public String toString() {
			return "BufferSubscriber{" +
					"queue=" + queue +
					", bufferSize=" + bufferSize +
					", done=" + done +
					", outstanding=" + outstanding +
					", subscription=" + subscription +
					'}';
		}
	}

}