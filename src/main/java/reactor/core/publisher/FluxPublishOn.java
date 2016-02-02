package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.DeferredSubscription;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.fn.Consumer;
import reactor.fn.Supplier;

/**
 * Subscribes to the source Publisher asynchronously through a scheduler function or
 * ExecutorService.
 * 
 * @param <T> the value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
final class FluxPublishOn<T> extends FluxSource<T, T> {

	final Callable<? extends Consumer<Runnable>> schedulerFactory;
	
	public FluxPublishOn(
			Publisher<? extends T> source, 
			Callable<? extends Consumer<Runnable>> schedulerFactory) {
		super(source);
		this.schedulerFactory = Objects.requireNonNull(schedulerFactory, "schedulerFactory");
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		Consumer<Runnable> scheduler;
		
		try {
			scheduler = schedulerFactory.call();
		} catch (Throwable e) {
			Exceptions.throwIfFatal(e);
			EmptySubscription.error(s, e);
			return;
		}
		
		if (scheduler == null) {
			EmptySubscription.error(s, new NullPointerException("The schedulerFactory returned a null Function"));
			return;
		}
		
		if (source instanceof Supplier) {
			
			@SuppressWarnings("unchecked")
			Supplier<T> supplier = (Supplier<T>) source;
			
			T v = supplier.get();
			
			if (v == null) {
				ScheduledEmptySubscriptionEager parent = new ScheduledEmptySubscriptionEager(s, scheduler);
				s.onSubscribe(parent);
				scheduler.accept(parent);
			} else {
				s.onSubscribe(new ScheduledSubscriptionEagerCancel<>(s, v, scheduler));
			}
			return;
		}
		
		PublishOnClassic<T> parent = new PublishOnClassic<>(s, scheduler);
		s.onSubscribe(parent);
		
		scheduler.accept(new SourceSubscribeTask<>(parent, source));
	}
	
	static final class PublishOnClassic<T>
	extends DeferredSubscription implements Subscriber<T> {
		final Subscriber<? super T> actual;
		
		final Consumer<Runnable> scheduler;

		public PublishOnClassic(Subscriber<? super T> actual, Consumer<Runnable> scheduler) {
			this.actual = actual;
			this.scheduler = scheduler;
		}
		
		@Override
		public void onSubscribe(Subscription s) {
			set(s);
		}
		
		@Override
		public void onNext(T t) {
			actual.onNext(t);
		}
		
		@Override
		public void onError(Throwable t) {
			scheduler.accept(null);
			actual.onError(t);
		}
		
		@Override
		public void onComplete() {
			scheduler.accept(null);
			actual.onComplete();
		}
		
		@Override
		public void request(long n) {
			if (BackpressureUtils.validate(n)) {
				scheduler.accept(new RequestTask(n, this));
			}
		}

		@Override
		public void cancel() {
			super.cancel();
			scheduler.accept(null);
		}
		
		void requestInner(long n) {
			super.request(n);
		}

		static final class RequestTask implements Runnable {

			final long n;
			final PublishOnClassic<?> parent;

			public RequestTask(long n, PublishOnClassic<?> parent) {
				this.n = n;
				this.parent = parent;
			}

			@Override
			public void run() {
				parent.requestInner(n);
			}
		}
	}
	
	static final class ScheduledSubscriptionEagerCancel<T> implements Subscription, Runnable {

		final Subscriber<? super T> actual;
		
		final T value;
		
		final Consumer<Runnable> scheduler;

		volatile int once;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ScheduledSubscriptionEagerCancel> ONCE =
				AtomicIntegerFieldUpdater.newUpdater(ScheduledSubscriptionEagerCancel.class, "once");

		public ScheduledSubscriptionEagerCancel(Subscriber<? super T> actual, T value, Consumer<Runnable> scheduler) {
			this.actual = actual;
			this.value = value;
			this.scheduler = scheduler;
		}
		
		@Override
		public void request(long n) {
			if (BackpressureUtils.validate(n)) {
				if (ONCE.compareAndSet(this, 0, 1)) {
					scheduler.accept(this);
				}
			}
		}
		
		@Override
		public void cancel() {
			ONCE.lazySet(this, 1);
			scheduler.accept(null);
		}
		
		@Override
		public void run() {
			actual.onNext(value);
			scheduler.accept(null);
			actual.onComplete();
		}
	}

	static final class ScheduledEmptySubscriptionEager implements Subscription, Runnable {
		final Subscriber<?> actual;

		final Consumer<Runnable> scheduler;

		public ScheduledEmptySubscriptionEager(Subscriber<?> actual, Consumer<Runnable> scheduler) {
			this.actual = actual;
			this.scheduler = scheduler;
		}
		
		@Override
		public void request(long n) {
			BackpressureUtils.validate(n);
		}
		
		@Override
		public void cancel() {
			scheduler.accept(null);
		}
		
		@Override
		public void run() {
			scheduler.accept(null);
			actual.onComplete();
		}
	}

	static final class SourceSubscribeTask<T> implements Runnable {

		final Subscriber<? super T> actual;
		
		final Publisher<? extends T> source;

		public SourceSubscribeTask(Subscriber<? super T> s, Publisher<? extends T> source) {
			this.actual = s;
			this.source = source;
		}

		@Override
		public void run() {
			source.subscribe(actual);
		}
	}

}