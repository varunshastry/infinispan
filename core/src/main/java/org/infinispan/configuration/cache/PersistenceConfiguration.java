package org.infinispan.configuration.cache;

import java.util.List;

import org.infinispan.commons.configuration.attributes.Attribute;
import org.infinispan.commons.configuration.attributes.AttributeDefinition;
import org.infinispan.commons.configuration.attributes.AttributeSet;
import org.infinispan.commons.configuration.attributes.Matchable;

/**
 * Configuration for stores.
 *
 */
public class PersistenceConfiguration implements Matchable<PersistenceConfiguration> {
   public static final AttributeDefinition<Boolean> PASSIVATION = AttributeDefinition.builder(org.infinispan.configuration.parsing.Attribute.PASSIVATION, false).immutable().build();
   public static final AttributeDefinition<Integer> AVAILABILITY_INTERVAL = AttributeDefinition.builder(org.infinispan.configuration.parsing.Attribute.AVAILABILITY_INTERVAL, 1000).immutable().build();
   public static final AttributeDefinition<Integer> CONNECTION_ATTEMPTS = AttributeDefinition.builder(org.infinispan.configuration.parsing.Attribute.CONNECTION_ATTEMPTS, 10).immutable().build();
   public static final AttributeDefinition<Integer> CONNECTION_INTERVAL = AttributeDefinition.builder(org.infinispan.configuration.parsing.Attribute.CONNECTION_INTERVAL, 50).immutable().build();
   static AttributeSet attributeDefinitionSet() {
      return new AttributeSet(PersistenceConfiguration.class, PASSIVATION, AVAILABILITY_INTERVAL, CONNECTION_ATTEMPTS, CONNECTION_INTERVAL);
   }

   private final Attribute<Boolean> passivation;
   private final Attribute<Integer> availabilityInterval;
   private final Attribute<Integer> connectionAttempts;
   private final Attribute<Integer> connectionInterval;
   private final AttributeSet attributes;
   private final List<StoreConfiguration> stores;


   PersistenceConfiguration(AttributeSet attributes, List<StoreConfiguration> stores) {
      this.attributes = attributes.checkProtection();
      this.passivation = attributes.attribute(PASSIVATION);
      this.availabilityInterval = attributes.attribute(AVAILABILITY_INTERVAL);
      this.connectionAttempts = attributes.attribute(CONNECTION_ATTEMPTS);
      this.connectionInterval = attributes.attribute(CONNECTION_INTERVAL);
      this.stores = stores;
   }

   /**
    * If true, data is only written to the cache store when it is evicted from memory, a phenomenon
    * known as 'passivation'. Next time the data is requested, it will be 'activated' which means
    * that data will be brought back to memory and removed from the persistent store. This gives you
    * the ability to 'overflow' to disk, similar to swapping in an operating system. <br />
    * <br />
    * If false, the cache store contains a copy of the contents in memory, so writes to cache result
    * in cache store writes. This essentially gives you a 'write-through' configuration.
    */
   public boolean passivation() {
      return passivation.get();
   }

   public int availabilityInterval() {
      return availabilityInterval.get();
   }

   public int connectionAttempts() {
      return connectionAttempts.get();
   }

   public int connectionInterval() {
      return connectionInterval.get();
   }

   public List<StoreConfiguration> stores() {
      return stores;
   }

   /**
    * Loops through all individual cache loader configs and checks if fetchPersistentState is set on
    * any of them
    */
   public Boolean fetchPersistentState() {
      for (StoreConfiguration c : stores) {
         if (c.fetchPersistentState())
            return true;
      }
      return false;
   }

   /**
    * Loops through all individual cache loader configs and checks if preload is set on
    * any of them
    */
   public Boolean preload() {
      for (StoreConfiguration c : stores) {
         if (c.preload())
            return true;
      }
      return false;
   }

   public boolean usingStores() {
      return !stores.isEmpty();
   }

   public boolean usingAsyncStore() {
      for (StoreConfiguration c : stores) {
         if (c.async().enabled())
            return true;
      }
      return false;
   }

   /**
    * Returns if any store is {@link StoreConfiguration#segmented()}
    * @return true if any configured store is segmented, otherwise false
    */
   public boolean usingSegmentedStore() {
      for (StoreConfiguration c : stores) {
         if (c.segmented())
            return true;
      }
      return false;
   }

   public AttributeSet attributes() {
      return attributes;
   }

   @Override
   public String toString() {
      return "PersistenceConfiguration [attributes=" + attributes + ", stores=" + stores + "]";
   }

   @Override
   public boolean equals(Object obj) {
      if (this == obj)
         return true;
      if (obj == null)
         return false;
      if (getClass() != obj.getClass())
         return false;
      PersistenceConfiguration other = (PersistenceConfiguration) obj;
      if (attributes == null) {
         if (other.attributes != null)
            return false;
      } else if (!attributes.equals(other.attributes))
         return false;
      if (stores == null) {
         return other.stores == null;
      } else if (!stores.equals(other.stores)) {
         return false;
      }
      return true;
   }

   @Override
   public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((attributes == null) ? 0 : attributes.hashCode());
      result = prime * result + ((stores == null) ? 0 : stores.hashCode());
      return result;
   }

}
