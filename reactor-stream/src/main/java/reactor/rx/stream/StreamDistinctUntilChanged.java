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
package reactor.rx.stream;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.subscriber.SubscriberBarrier;
import reactor.fn.Function;

/**
 * @author Stephane Maldini
 * @since 2.0, 2.5
 */

public class StreamDistinctUntilChanged<T, V> extends StreamBarrier<T, T> {

	private final Function<? super T, ? extends V> keySelector;

	public StreamDistinctUntilChanged(Publisher<T> source, Function<? super T, ? extends V> keySelector) {
		super(source);
		this.keySelector = keySelector;
	}

	@Override
	public Subscriber<? super T> apply(Subscriber<? super T> subscriber) {
		return new DistinctUntilChangedAction<>(subscriber, keySelector);
	}

	static final class DistinctUntilChangedAction<T, V> extends SubscriberBarrier<T, T> {

		private V lastKey;

		private final Function<? super T, ? extends V> keySelector;

		public DistinctUntilChangedAction(
				Subscriber<? super T> subscriber,
				Function<? super T, ? extends V> keySelector) {
			super(subscriber);
			this.keySelector = keySelector;
		}

		@Override
		@SuppressWarnings("unchecked")
		protected void doNext(T currentData) {
			V currentKey;
			if (keySelector != null) {
				currentKey = keySelector.apply(currentData);
			} else {
				currentKey = (V) currentData;
			}

			if(currentKey == null || !currentKey.equals(lastKey)) {
				lastKey = currentKey;
				subscriber.onNext(currentData);
			}
		}
	}

}
