package dev.myriadclient.impl.event;

import dev.myriadclient.api.event.Cancellable;
import dev.myriadclient.api.event.HandlerThread;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.Subscription;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MyriadEventBusTest {
	static class Base {
	}

	static class Child extends Base {
	}

	static class Stoppable extends Cancellable {
	}

	static class Listener {
		final List<String> calls = new ArrayList<>();

		@Subscribe(priority = Priority.LOW)
		private void low(Child e) {
			calls.add("low");
		}

		@Subscribe(priority = Priority.HIGH)
		private void high(Child e) {
			calls.add("high");
		}

		@Subscribe
		private void base(Base e) {
			calls.add("base");
		}
	}

	static class SubListener extends Listener {
		@Subscribe
		private void own(Child e) {
			calls.add("sub");
		}
	}

	@Test
	void dispatchesByPriorityAndToSupertypes() {
		MyriadEventBus bus = new MyriadEventBus();
		Listener l = new Listener();
		bus.subscribe(l);
		bus.post(new Child());
		assertEquals(List.of("high", "base", "low"), l.calls);
	}

	@Test
	void inheritedHandlersAreFound() {
		MyriadEventBus bus = new MyriadEventBus();
		SubListener l = new SubListener();
		bus.subscribe(l);
		bus.post(new Child());
		assertTrue(l.calls.containsAll(List.of("high", "base", "low", "sub")));
		assertEquals(4, l.calls.size());
	}

	@Test
	void instancesOfOneClassEachGetTheirOwnHandlersAcrossResubscribes() {
		// Handler discovery is cached per class; the invokers must still be bound to each instance.
		MyriadEventBus bus = new MyriadEventBus();
		Listener a = new Listener(), b = new Listener();
		bus.subscribe(a);
		bus.subscribe(b);
		bus.unsubscribe(a);
		bus.subscribe(a);
		bus.post(new Child());
		assertEquals(List.of("high", "base", "low"), a.calls);
		assertEquals(List.of("high", "base", "low"), b.calls);
	}

	@Test
	void unsubscribeStopsDelivery() {
		MyriadEventBus bus = new MyriadEventBus();
		Listener l = new Listener();
		bus.subscribe(l);
		bus.unsubscribe(l);
		bus.post(new Child());
		assertTrue(l.calls.isEmpty());
		assertFalse(bus.isSubscribed(l));
		assertFalse(bus.hasListeners(Child.class));
	}

	@Test
	void cancelledEventsSkipHandlersUnlessTheyOptIn() {
		MyriadEventBus bus = new MyriadEventBus();
		List<String> calls = new ArrayList<>();
		bus.listen(Stoppable.class, Priority.HIGH, e -> {
			calls.add("canceller");
			e.cancel();
		});
		bus.listen(Stoppable.class, Priority.NORMAL, e -> calls.add("skipped"));
		Object watcher = new Object() {
			@Subscribe(priority = Priority.LOW, receiveCancelled = true)
			private void see(Stoppable e) {
				calls.add("watcher");
			}
		};
		bus.subscribe(watcher);
		assertTrue(bus.post(new Stoppable()).isCancelled());
		assertEquals(List.of("canceller", "watcher"), calls);
	}

	@Test
	void lambdaSubscriptionCanBeRemoved() {
		MyriadEventBus bus = new MyriadEventBus();
		int[] n = {0};
		Subscription s = bus.listen(Base.class, e -> n[0]++);
		bus.post(new Child());
		s.unsubscribe();
		bus.post(new Child());
		assertEquals(1, n[0]);
	}

	@Test
	void throwingHandlerDoesNotBreakOthersAndIsEventuallyDisabled() {
		MyriadEventBus bus = new MyriadEventBus();
		int[] good = {0}, bad = {0};
		bus.listen(Base.class, Priority.HIGH, e -> {
			bad[0]++;
			throw new IllegalStateException("boom");
		});
		bus.listen(Base.class, e -> good[0]++);
		for (int i = 0; i < 100; i++) bus.post(new Base());
		assertEquals(100, good[0]);
		assertEquals(50, bad[0], "handler should be disabled after 50 failures");
	}

	static class Turner {
		final MyriadEventBus bus;
		final List<String> calls;

		Turner(MyriadEventBus bus, List<String> calls) {
			this.bus = bus;
			this.calls = calls;
		}
	}

	static class First extends Turner {
		Object other;

		First(MyriadEventBus bus, List<String> calls) {
			super(bus, calls);
		}

		@Subscribe(priority = Priority.HIGH)
		private void on(Base e) {
			calls.add("first");
			bus.unsubscribe(other);
		}
	}

	static class Second extends Turner {
		Second(MyriadEventBus bus, List<String> calls) {
			super(bus, calls);
		}

		@Subscribe(priority = Priority.LOW)
		private void on(Base e) {
			calls.add("second");
		}
	}

	@Test
	void aListenerUnsubscribedDuringDispatchMissesTheRestOfIt() {
		MyriadEventBus bus = new MyriadEventBus();
		List<String> calls = new ArrayList<>();
		First first = new First(bus, calls);
		Second second = new Second(bus, calls);
		first.other = second;
		bus.subscribe(first);
		bus.subscribe(second);
		bus.post(new Base());
		assertEquals(List.of("first"), calls);
	}

	static class OnRender {
		final List<String> calls = new ArrayList<>();

		@Subscribe(thread = HandlerThread.RENDER)
		private void render(Base e) {
			calls.add("render " + Thread.currentThread().getName());
		}

		@Subscribe
		private void poster(Base e) {
			calls.add("poster " + Thread.currentThread().getName());
		}
	}

	@Test
	void aRenderHandlerGetsAnEventPostedElsewhereAtTheNextDelivery() throws Exception {
		MyriadEventBus bus = new MyriadEventBus();
		Thread render = Thread.currentThread();
		bus.setRenderThreadCheck(() -> Thread.currentThread() == render);
		OnRender l = new OnRender();
		bus.subscribe(l);
		Thread network = new Thread(() -> bus.post(new Base()), "network");
		network.start();
		network.join();
		assertEquals(List.of("poster network"), l.calls);
		bus.deliverDeferred();
		assertEquals(List.of("poster network", "render " + render.getName()), l.calls);
		// Delivered once.
		bus.deliverDeferred();
		assertEquals(2, l.calls.size());
		// Posted on the render thread: at once, in priority order as usual.
		l.calls.clear();
		bus.post(new Base());
		assertEquals(2, l.calls.size());
	}

	static class LateReader {
		int seen, seenCancelled;

		@Subscribe(thread = HandlerThread.RENDER)
		private void read(Stoppable e) {
			seen++;
		}

		@Subscribe(thread = HandlerThread.RENDER, receiveCancelled = true)
		private void readAll(Stoppable e) {
			seenCancelled++;
		}
	}

	@Test
	void aLateHandlerSkipsWhatWasCancelledMeanwhile() throws Exception {
		MyriadEventBus bus = new MyriadEventBus();
		Thread render = Thread.currentThread();
		bus.setRenderThreadCheck(() -> Thread.currentThread() == render);
		LateReader l = new LateReader();
		bus.subscribe(l);
		Thread network = new Thread(() -> {
			bus.post(new Stoppable());
			bus.post(new Stoppable()).cancel();
		}, "network");
		network.start();
		network.join();
		bus.deliverDeferred();
		assertEquals(1, l.seen);
		assertEquals(2, l.seenCancelled);
	}
}
