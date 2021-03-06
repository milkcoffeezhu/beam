/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.gcp.pubsub;

import static org.apache.beam.sdk.io.gcp.pubsub.PubsubMessageToRow.ATTRIBUTES_FIELD;
import static org.apache.beam.sdk.io.gcp.pubsub.PubsubMessageToRow.DLQ_TAG;
import static org.apache.beam.sdk.io.gcp.pubsub.PubsubMessageToRow.MAIN_TAG;
import static org.apache.beam.sdk.io.gcp.pubsub.PubsubMessageToRow.PAYLOAD_FIELD;
import static org.apache.beam.sdk.io.gcp.pubsub.PubsubMessageToRow.TIMESTAMP_FIELD;
import static org.apache.beam.sdk.schemas.Schema.TypeName.ROW;

import com.google.auto.service.AutoService;
import java.io.Serializable;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.io.InvalidConfigurationException;
import org.apache.beam.sdk.schemas.io.InvalidSchemaException;
import org.apache.beam.sdk.schemas.io.SchemaCapableIOProvider;
import org.apache.beam.sdk.schemas.io.SchemaIO;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.POutput;
import org.apache.beam.sdk.values.Row;

/**
 * An implementation of {@link SchemaCapableIOProvider} for reading and writing JSON payloads with
 * {@link PubsubIO}.
 *
 * <h2>Schema</h2>
 *
 * <p>The data schema passed to {@link #from(String, Row, Schema)} must either be of the nested or
 * flat style.
 *
 * <h3>Nested style</h3>
 *
 * <p>If nested structure is used, the required fields included in the Pubsub message model are
 * 'event_timestamp', 'attributes', and 'payload'.
 *
 * <h3>Flat style</h3>
 *
 * <p>If flat structure is used, the required fields include just 'event_timestamp'. Every other
 * field is assumed part of the payload. See {@link PubsubMessageToRow} for details.
 *
 * <h2>Configuration</h2>
 *
 * <p>{@link #configurationSchema()} consists of two attributes, timestampAttributeKey and
 * deadLetterQueue.
 *
 * <h3>timestampAttributeKey</h3>
 *
 * <p>An optional attribute key of the Pubsub message from which to extract the event timestamp. If
 * not specified, the message publish time will be used as event timestamp.
 *
 * <p>This attribute has to conform to the same requirements as in {@link
 * PubsubIO.Read.Builder#withTimestampAttribute(String)}
 *
 * <p>Short version: it has to be either millis since epoch or string in RFC 3339 format.
 *
 * <p>If the attribute is specified then event timestamps will be extracted from the specified
 * attribute. If it is not specified then message publish timestamp will be used.
 *
 * <h3>deadLetterQueue</h3>
 *
 * <p>deadLetterQueue is an optional topic path which will be used as a dead letter queue.
 *
 * <p>Messages that cannot be processed will be sent to this topic. If it is not specified then
 * exception will be thrown for errors during processing causing the pipeline to crash.
 */
@Internal
@AutoService(SchemaCapableIOProvider.class)
public class PubsubSchemaCapableIOProvider implements SchemaCapableIOProvider {
  public static final FieldType VARCHAR = FieldType.STRING;
  public static final FieldType TIMESTAMP = FieldType.DATETIME;

  /** Returns an id that uniquely represents this IO. */
  @Override
  public String identifier() {
    return "pubsub";
  }

  /**
   * Returns the expected schema of the configuration object. Note this is distinct from the schema
   * of the data source itself.
   */
  @Override
  public Schema configurationSchema() {
    return Schema.builder()
        .addNullableField("timestampAttributeKey", FieldType.STRING)
        .addNullableField("deadLetterQueue", FieldType.STRING)
        .build();
  }

  /**
   * Produce a SchemaIO given a String representing the data's location, the schema of the data that
   * resides there, and some IO-specific configuration object.
   */
  @Override
  public PubsubSchemaIO from(String location, Row configuration, Schema dataSchema) {
    validateConfigurationSchema(configuration);
    validateDlq(configuration.getValue("deadLetterQueue"));
    validateEventTimestamp(dataSchema);
    return new PubsubSchemaIO(location, configuration, dataSchema);
  }

  private void validateEventTimestamp(Schema schema) {
    if (!PubsubSchemaIO.fieldPresent(schema, TIMESTAMP_FIELD, TIMESTAMP)) {
      throw new InvalidSchemaException(
          "Unsupported schema specified for Pubsub source in CREATE TABLE."
              + "CREATE TABLE for Pubsub topic must include at least 'event_timestamp' field of "
              + "type 'TIMESTAMP'");
    }
  }

  private void validateDlq(String deadLetterQueue) {
    if (deadLetterQueue != null && deadLetterQueue.isEmpty()) {
      throw new InvalidConfigurationException("Dead letter queue topic name is not specified");
    }
  }

  private void validateConfigurationSchema(Row configuration) {
    if (!configuration.getSchema().equals(configurationSchema())) {
      throw new InvalidConfigurationException(
          "Configuration schema provided does not match expected");
    }
  }

  /** An abstraction to create schema aware IOs. */
  @Internal
  private static class PubsubSchemaIO implements SchemaIO, Serializable {
    protected final Row config;
    protected final Schema dataSchema;
    protected final String location;
    protected final Boolean useFlatSchema;

    private PubsubSchemaIO(String location, Row config, Schema dataSchema) {
      this.config = config;
      this.dataSchema = dataSchema;
      this.location = location;
      this.useFlatSchema = !definesAttributeAndPayload(dataSchema);
    }

    @Override
    public Schema schema() {
      return dataSchema;
    }

    @Override
    public PTransform<PBegin, PCollection<Row>> buildReader() {
      return new PTransform<PBegin, PCollection<Row>>() {
        @Override
        public PCollection<Row> expand(PBegin begin) {
          PCollectionTuple rowsWithDlq =
              begin
                  .apply("ReadFromPubsub", readMessagesWithAttributes())
                  .apply(
                      "PubsubMessageToRow",
                      PubsubMessageToRow.builder()
                          .messageSchema(dataSchema)
                          .useDlq(useDlqCheck(config))
                          .useFlatSchema(useFlatSchema)
                          .build());
          rowsWithDlq.get(MAIN_TAG).setRowSchema(dataSchema);

          if (useDlqCheck(config)) {
            rowsWithDlq.get(DLQ_TAG).apply(writeMessagesToDlq());
          }

          return rowsWithDlq.get(MAIN_TAG);
        }
      };
    }

    @Override
    public PTransform<PCollection<Row>, POutput> buildWriter() {
      if (!useFlatSchema) {
        throw new UnsupportedOperationException(
            "Writing to a Pubsub topic is only supported for flattened schemas");
      }

      return new PTransform<PCollection<Row>, POutput>() {
        @Override
        public POutput expand(PCollection<Row> input) {
          return input
              .apply(
                  RowToPubsubMessage.fromConfig(
                      config, useFlatSchema, useTimestampAttribute(config)))
              .apply(createPubsubMessageWrite());
        }
      };
    }

    private PubsubIO.Read<PubsubMessage> readMessagesWithAttributes() {
      PubsubIO.Read<PubsubMessage> read = PubsubIO.readMessagesWithAttributes().fromTopic(location);

      return useTimestampAttribute(config)
          ? read.withTimestampAttribute(config.getValue("timestampAttributeKey"))
          : read;
    }

    private PubsubIO.Write<PubsubMessage> createPubsubMessageWrite() {
      PubsubIO.Write<PubsubMessage> write = PubsubIO.writeMessages().to(location);
      if (useTimestampAttribute(config)) {
        write = write.withTimestampAttribute(config.getValue("timestampAttributeKey"));
      }
      return write;
    }

    private PubsubIO.Write<PubsubMessage> writeMessagesToDlq() {
      PubsubIO.Write<PubsubMessage> write =
          PubsubIO.writeMessages().to(config.getString("deadLetterQueue"));

      return useTimestampAttribute(config)
          ? write.withTimestampAttribute(config.getString("timestampAttributeKey"))
          : write;
    }

    private boolean useDlqCheck(Row config) {
      return config.getValue("deadLetterQueue") != null;
    }

    private boolean useTimestampAttribute(Row config) {
      return config.getValue("timestampAttributeKey") != null;
    }

    private boolean definesAttributeAndPayload(Schema schema) {
      return fieldPresent(
              schema, ATTRIBUTES_FIELD, Schema.FieldType.map(VARCHAR.withNullable(false), VARCHAR))
          && (schema.hasField(PAYLOAD_FIELD)
              && ROW.equals(schema.getField(PAYLOAD_FIELD).getType().getTypeName()));
    }

    private static boolean fieldPresent(
        Schema schema, String field, Schema.FieldType expectedType) {
      return schema.hasField(field)
          && expectedType.equivalent(
              schema.getField(field).getType(), Schema.EquivalenceNullablePolicy.IGNORE);
    }
  }
}
