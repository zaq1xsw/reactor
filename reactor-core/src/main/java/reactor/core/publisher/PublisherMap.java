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

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.subscriber.SubscriberBarrier;
import reactor.core.support.Assert;
import reactor.core.support.ReactiveState;
import reactor.core.support.ReactiveStateUtils;
import reactor.fn.Function;
import reactor.fn.tuple.Tuple;
import reactor.fn.tuple.Tuple2;

/**
 * @author Stephane Maldini
 * @since 1.1, 2.0, 2.5
 */
public final class PublisherMap<T, V>  extends PublisherFactory.PublisherBarrier<T, V> {

	/**
	 * A predefined map operator producing timestamp tuples
	 */
	private static final Function TIMESTAMP_OPERATOR = new Function<Object, Tuple2<Long, ?>>() {
		@Override
		public Tuple2<Long, ?> apply(Object o) {
			return Tuple.of(System.currentTimeMillis(), o);
		}
	};

	@SuppressWarnings("unchecked")
	public static <T> PublisherMap<T, Tuple2<Long, T>> timestamp(Publisher<T> source){
		return new PublisherMap<>(source, TIMESTAMP_OPERATOR);
	}

	private final Function<? super T, ? extends V> fn;

	public PublisherMap(Publisher<T> source, Function<? super T, ? extends V> fn) {
		super(source);
		this.fn = fn;
	}

	@Override
	public Subscriber<? super T> apply(Subscriber<? super V> subscriber) {
		return new MapBarrier<>(subscriber, fn);
	}

	static final class MapBarrier<T, V> extends SubscriberBarrier<T, V> implements ReactiveState.Named {

		private final Function<? super T, ? extends V> fn;

		public MapBarrier(Subscriber<? super V> actual, Function<? super T, ? extends V> fn) {
			super(actual);
			Assert.notNull(fn, "Map function cannot be null.");
			this.fn = fn;
		}

		@Override
		public String getName() {
			return ReactiveStateUtils.getName(fn);
		}

		@Override
		protected void doNext(T value) {
			V res = fn.apply(value);
			if (res != null) {
				subscriber.onNext(res);
			}
		}

	}

}
