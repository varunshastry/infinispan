package org.infinispan.counter.impl.listener;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.infinispan.Cache;
import org.infinispan.commons.util.ByRef;
import org.infinispan.configuration.cache.ClusteringConfiguration;
import org.infinispan.counter.api.CounterEvent;
import org.infinispan.counter.api.CounterListener;
import org.infinispan.counter.api.Handle;
import org.infinispan.counter.api.WeakCounter;
import org.infinispan.counter.impl.entries.CounterKey;
import org.infinispan.counter.impl.entries.CounterValue;
import org.infinispan.counter.logging.Log;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Stop;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryModified;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryRemoved;
import org.infinispan.notifications.cachelistener.annotation.TopologyChanged;
import org.infinispan.notifications.cachelistener.event.CacheEntryEvent;
import org.infinispan.notifications.cachelistener.event.TopologyChangedEvent;
import org.infinispan.util.ByteString;
import org.infinispan.util.concurrent.BlockingManager;
import org.infinispan.util.logging.LogFactory;

/**
 * It manages all the caches events and handles them. Also, it handles the user-specific {@link CounterListener}.
 * <p>
 * When a particular key is updated, its update is send to the counter, via {@link
 * CounterEventGenerator#generate(CounterKey, CounterValue)}, and the result {@link CounterEvent} is used to notify the
 * users {@link CounterListener}.
 * <p>
 * Also listens to topology changes in the cache to update the {@link WeakCounter} preferred keys, via {@link
 * TopologyChangeListener#topologyChanged()}.
 * <p>
 * An user's {@link CounterListener} is invoked in sequence (i.e. only the next update is invoked when the previous one
 * is handled) but it can be invoked in different thread.
 *
 * @author Pedro Ruivo
 * @since 9.2
 */
@Scope(Scopes.GLOBAL)
public class CounterManagerNotificationManager {

   private static final Log log = LogFactory.getLog(CounterManagerNotificationManager.class, Log.class);
   private final Map<ByteString, Holder> counters;
   private final CounterValueListener valueListener;
   private final TopologyListener topologyListener;
   private volatile BlockingManager.BlockingExecutor userListenerExecutor;

   public CounterManagerNotificationManager() {
      counters = new ConcurrentHashMap<>(32);
      valueListener = new CounterValueListener();
      topologyListener = new TopologyListener();
   }

   @Inject
   public void inject(BlockingManager blockingManager) {
      userListenerExecutor = blockingManager.limitedBlockingExecutor("counter-listener", 1);
   }

   @Stop
   public void stop() {
      // do not try to remove the listeners; the counter cache is already stopped at this point
      counters.clear();
   }

   /**
    * It registers a new counter created locally.
    *
    * @param counterName            The counter's name.
    * @param generator              The counter's {@link CounterEvent} generator.
    * @param topologyChangeListener The counter's listener to topology change. It can be {@code null}.
    * @throws IllegalStateException If the counter with that name is already registered.
    */
   public void registerCounter(ByteString counterName, CounterEventGenerator generator,
         TopologyChangeListener topologyChangeListener) {
      if (counters.putIfAbsent(counterName, new Holder(generator, topologyChangeListener)) != null) {
         throw new IllegalStateException();
      }
   }

   /**
    * It registers a user's {@link CounterListener} for a specific counter.
    *
    * @param counterName  The counter's name to listen.
    * @param userListener The {@link CounterListener} to be invoked.
    * @return The {@link Handle} for the {@link CounterListener}.
    */
   public <T extends CounterListener> Handle<T> registerUserListener(ByteString counterName, T userListener) {
      ByRef<Handle<T>> handleByRef = new ByRef<>(null);
      counters.computeIfPresent(counterName, (name, holder) -> holder.addListener(userListener, handleByRef));
      return handleByRef.get();
   }

   /**
    * It registers the clustered cache listener if they aren't already registered.
    * <p>
    * This listener receives notification when a counter's value is updated.
    *
    * @param cache The {@link  Cache} to listen to cluster events.
    */
   public void registerCounterValueListener(Cache<? extends CounterKey, CounterValue> cache) {
      valueListener.register(cache);
   }

   /**
    * It registers the topology cache listener if they aren't already registered.
    *
    * @param cache The {@link  Cache} to listen to cluster events.
    */
   public void registerTopologyListener(Cache<? extends CounterKey, CounterValue> cache) {
      topologyListener.register(cache);
   }

   /**
    * It removes and stops sending notification to the counter.
    *
    * @param counterName The counter's name to remove.
    */
   public void removeCounter(ByteString counterName) {
      counters.remove(counterName);
   }

   /**
    * A holder for a counter that container the {@link CounterEventGenerator}, the {@link TopologyChangeListener} and
    * the user's {@link CounterListener}.
    */
   private static class Holder {
      private final CounterEventGenerator generator;
      private final List<CounterListenerResponse<?>> userListeners;
      private final TopologyChangeListener topologyChangeListener;

      private Holder(CounterEventGenerator generator, TopologyChangeListener topologyChangeListener) {
         this.generator = generator;
         this.topologyChangeListener = topologyChangeListener;
         userListeners = new CopyOnWriteArrayList<>();
      }

      <T extends CounterListener> Holder addListener(T userListener,
            ByRef<Handle<T>> handleByRef) {
         CounterListenerResponse<T> handle = new CounterListenerResponse<>(userListener, this);
         userListeners.add(handle);
         handleByRef.set(handle);
         return this;
      }

      <T extends CounterListener> void removeListener(CounterListenerResponse<T> userListener) {
         userListeners.remove(userListener);
      }

      TopologyChangeListener getTopologyChangeListener() {
         return topologyChangeListener;
      }
   }

   /**
    * The {@link Handle} implementation for a specific {@link CounterListener}.
    */
   private static class CounterListenerResponse<T extends CounterListener> implements Handle<T>, CounterListener {
      private final T listener;
      private final Holder holder;

      private CounterListenerResponse(T listener, Holder holder) {
         this.listener = listener;
         this.holder = holder;
      }

      @Override
      public T getCounterListener() {
         return listener;
      }

      @Override
      public void remove() {
         holder.removeListener(this);
      }

      @Override
      public void onUpdate(CounterEvent event) {
         try {
            listener.onUpdate(event);
         } catch (Throwable t) {
            log.warnf(t, "Exception while invoking listener %s", listener);
         }
      }

      @Override
      public int hashCode() {
         return listener.hashCode();
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) {
            return true;
         }
         if (o == null || getClass() != o.getClass()) {
            return false;
         }

         CounterListenerResponse<?> that = (CounterListenerResponse<?>) o;
         return listener.equals(that.listener);
      }
   }

   /**
    * The listener that register counter's value change.
    */
   @Listener(clustered = true, observation = Listener.Observation.POST)
   private class CounterValueListener {

      final AtomicBoolean registered = new AtomicBoolean(false);

      @CacheEntryCreated
      @CacheEntryModified
      @CacheEntryRemoved
      public void updateState(CacheEntryEvent<? extends CounterKey, CounterValue> event) {
         CounterKey key = event.getKey();
         Holder holder = counters.get(key.getCounterName());
         if (holder == null) {
            return;
         }
         synchronized (holder.generator) {
            //weak counter events execute the updateState method in parallel.
            //if we don't synchronize, we can have events reordered.
            triggerUserListener(holder.userListeners, holder.generator.generate(key, event.getValue()));
         }
      }

      private void triggerUserListener(List<CounterListenerResponse<?>> userListeners, CounterEvent event) {
         if (userListeners.isEmpty() || event == null) {
            return;
         }
         userListenerExecutor.execute(() -> userListeners.forEach(l -> l.onUpdate(event)), event);
      }

      void register(Cache<? extends CounterKey, CounterValue> cache) {
         if (registered.compareAndSet(false, true)) {
            cache.addListener(this);
         }
      }
   }

   /**
    * The listener that registers topology changes.
    */
   @Listener(sync = false)
   private class TopologyListener {

      final AtomicBoolean registered = new AtomicBoolean(false);

      @TopologyChanged
      public void topologyChanged(TopologyChangedEvent<?, ?> event) {
         counters.values().parallelStream()
                 .map(Holder::getTopologyChangeListener)
                 .filter(Objects::nonNull)
                 .forEach(TopologyChangeListener::topologyChanged);
      }

      private void register(Cache<?, ?> cache) {
         ClusteringConfiguration config = cache.getCacheConfiguration().clustering();
         if (!config.cacheMode().isClustered()) {
            return;
         }
         if (registered.compareAndSet(false, true)) {
            cache.addListener(this);
         }
      }
   }
}
