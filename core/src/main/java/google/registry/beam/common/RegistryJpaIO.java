// Copyright 2020 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.beam.common;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.apache.beam.sdk.values.TypeDescriptors.integers;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import google.registry.backup.AppEngineEnvironment;
import google.registry.model.ofy.ObjectifyService;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import java.util.Objects;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupIntoBatches;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.util.ShardedKey;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;

/**
 * Contains IO {@link PTransform transforms} for use in a pipeline that reads and writes from a
 * single database through a {@link JpaTransactionManager}.
 *
 * <p>The {@code JpaTransactionManager} is expected to be set up once on each pipeline worker, and
 * made available through the static method {@link TransactionManagerFactory#jpaTm()}.
 */
public final class RegistryJpaIO {

  private RegistryJpaIO() {}

  public static <T> Write<T> write() {
    return Write.<T>builder().build();
  }

  @AutoValue
  public abstract static class Write<T> extends PTransform<PCollection<T>, PCollection<Void>> {

    public static final String DEFAULT_NAME = "RegistryJpaIO.Write";

    public static final int DEFAULT_BATCH_SIZE = 1;

    public static final int DEFAULT_SHARDS = 1;

    public abstract String name();

    public abstract int batchSize();

    public abstract int shards();

    public abstract SerializableFunction<T, Object> jpaConverter();

    public Write<T> withName(String name) {
      return toBuilder().name(name).build();
    }

    public Write<T> withBatchSize(int batchSize) {
      return toBuilder().batchSize(batchSize).build();
    }

    public Write<T> withShards(int shards) {
      return toBuilder().shards(shards).build();
    }

    public Write<T> withJpaConverter(SerializableFunction<T, Object> jpaConverter) {
      return toBuilder().jpaConverter(jpaConverter).build();
    }

    abstract Builder<T> toBuilder();

    @Override
    public PCollection<Void> expand(PCollection<T> input) {
      return input
          .apply(
              "Convert value to KV for batching " + name(),
              WithKeys.<Integer, T>of(e -> 1).withKeyType(integers()))
          .apply(
              "Shard and batch " + name(),
              GroupIntoBatches.<Integer, T>ofSize(batchSize()).withShardedKey())
          .apply(
              "Write in batch for " + name(),
              ParDo.of(new SqlBatchWriter<>(name(), jpaConverter())));
    }

    static <T> Builder<T> builder() {
      return new AutoValue_RegistryJpaIO_Write.Builder<T>()
          .name(DEFAULT_NAME)
          .batchSize(DEFAULT_BATCH_SIZE)
          .shards(DEFAULT_SHARDS)
          .jpaConverter(x -> x);
    }

    @AutoValue.Builder
    abstract static class Builder<T> {

      abstract Builder<T> name(String name);

      abstract Builder<T> batchSize(int batchSize);

      abstract Builder<T> shards(int jdbcNumConnsHint);

      abstract Builder<T> jpaConverter(SerializableFunction<T, Object> jpaConverter);

      abstract Write<T> build();
    }
  }

  /**
   * Writes a batch of entities to a SQL database.
   *
   * <p>Note that an arbitrary number of instances of this class may be created and freed in
   * arbitrary order in a single JVM. Due to the tech debt that forced us to use a static variable
   * to hold the {@code JpaTransactionManager} instance, we must ensure that JpaTransactionManager
   * is not changed or torn down while being used by some instance.
   */
  private static class SqlBatchWriter<T> extends DoFn<KV<ShardedKey<Integer>, Iterable<T>>, Void> {
    private final Counter counter;
    private final SerializableFunction<T, Object> jpaConverter;

    SqlBatchWriter(String type, SerializableFunction<T, Object> jpaConverter) {
      counter = Metrics.counter("SQL_WRITE", type);
      this.jpaConverter = jpaConverter;
    }

    @Setup
    public void setup() {
      try (AppEngineEnvironment env = new AppEngineEnvironment()) {
        ObjectifyService.initOfy();
      }
    }

    @ProcessElement
    public void processElement(@Element KV<ShardedKey<Integer>, Iterable<T>> kv) {
      try (AppEngineEnvironment env = new AppEngineEnvironment()) {
        ImmutableList<Object> ofyEntities =
            Streams.stream(kv.getValue())
                .map(this.jpaConverter::apply)
                // TODO(b/177340730): post migration delete the line below.
                .filter(Objects::nonNull)
                .collect(ImmutableList.toImmutableList());
        try {
          jpaTm().transact(() -> jpaTm().putAll(ofyEntities));
          counter.inc(ofyEntities.size());
        } catch (RuntimeException e) {
          processSingly(ofyEntities);
        }
      }
    }

    /**
     * Writes entities in a failed batch one by one to identify the first bad entity and throws a
     * {@link RuntimeException} on it.
     */
    private void processSingly(ImmutableList<Object> ofyEntities) {
      for (Object ofyEntity : ofyEntities) {
        try {
          jpaTm().transact(() -> jpaTm().put(ofyEntity));
          counter.inc();
        } catch (RuntimeException e) {
          throw new RuntimeException(toOfyKey(ofyEntity).toString(), e);
        }
      }
    }

    private com.googlecode.objectify.Key<?> toOfyKey(Object ofyEntity) {
      return com.googlecode.objectify.Key.create(ofyEntity);
    }
  }
}
