/*
 * Copyright 2021-2023 Aklivity Inc.
 *
 * Aklivity licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.aklivity.zilla.runtime.binding.kafka.internal.config;

import static io.aklivity.zilla.runtime.binding.kafka.internal.types.KafkaDeltaType.JSON_PATCH;
import static io.aklivity.zilla.runtime.binding.kafka.internal.types.KafkaOffsetType.LIVE;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;

import org.junit.Before;
import org.junit.Test;

import io.aklivity.zilla.runtime.binding.kafka.config.KafkaOptionsConfig;
import io.aklivity.zilla.runtime.binding.kafka.config.KafkaSaslConfig;
import io.aklivity.zilla.runtime.binding.kafka.config.KafkaServerConfig;
import io.aklivity.zilla.runtime.binding.kafka.config.KafkaTopicConfig;
import io.aklivity.zilla.runtime.engine.test.internal.validator.config.TestValidatorConfig;

public class KafkaOptionsConfigAdapterTest
{
    private Jsonb jsonb;

    @Before
    public void initJson()
    {
        JsonbConfig config = new JsonbConfig()
                .withAdapters(new KafkaOptionsConfigAdapter());
        jsonb = JsonbBuilder.create(config);
    }

    @Test
    public void shouldReadOptions()
    {
        String text =
                "{" +
                    "\"bootstrap\":" +
                    "[" +
                        "\"test\"" +
                    "]," +
                    "\"topics\":" +
                    "[" +
                        "{" +
                            "\"name\": \"test\"," +
                            "\"defaultOffset\": \"live\"," +
                            "\"deltaType\": \"json_patch\"" +
                        "}" +
                    "]," +
                    "\"sasl\":" +
                    "{" +
                        "\"mechanism\": \"plain\"," +
                        "\"username\": \"username\"," +
                        "\"password\": \"password\"" +
                    "}" +
                "}";

        KafkaOptionsConfig options = jsonb.fromJson(text, KafkaOptionsConfig.class);

        assertThat(options, not(nullValue()));
        assertThat(options.bootstrap, equalTo(singletonList("test")));
        assertThat(options.topics, equalTo(singletonList(new KafkaTopicConfig("test", LIVE, JSON_PATCH, null, null))));
        assertThat(options.sasl.mechanism, equalTo("plain"));
        assertThat(options.sasl.username, equalTo("username"));
        assertThat(options.sasl.password, equalTo("password"));
    }

    @Test
    public void shouldWriteOptions()
    {
        KafkaOptionsConfig options = new KafkaOptionsConfig(
                singletonList("test"),
                singletonList(new KafkaTopicConfig("test", LIVE, JSON_PATCH, null, TestValidatorConfig.builder().build())),
                singletonList(new KafkaServerConfig("localhost", 9092)),
                new KafkaSaslConfig("plain", "username", "password"));

        String text = jsonb.toJson(options);

        assertThat(text, not(nullValue()));
        assertThat(text, equalTo("{\"bootstrap\":[\"test\"]," +
                "\"topics\":[{\"name\":\"test\",\"defaultOffset\":\"live\",\"deltaType\":\"json_patch\"," +
                "\"value\":\"test\"}]," +
                "\"servers\":[\"localhost:9092\"]," +
                "\"sasl\":{\"mechanism\":\"plain\",\"username\":\"username\",\"password\":\"password\"}}"));
    }

    @Test
    public void shouldReadSaslScramOptions()
    {
        String text =
                "{" +
                        "\"bootstrap\":" +
                        "[" +
                        "\"test\"" +
                        "]," +
                        "\"topics\":" +
                        "[" +
                        "{" +
                        "\"name\": \"test\"," +
                        "\"defaultOffset\": \"live\"," +
                        "\"deltaType\": \"json_patch\"" +
                        "}" +
                        "]," +
                        "\"sasl\":" +
                        "{" +
                        "\"mechanism\": \"scram-sha-256\"," +
                        "\"username\": \"username\"," +
                        "\"password\": \"password\"" +
                        "}" +
                        "}";

        KafkaOptionsConfig options = jsonb.fromJson(text, KafkaOptionsConfig.class);

        assertThat(options, not(nullValue()));
        assertThat(options.bootstrap, equalTo(singletonList("test")));
        assertThat(options.topics, equalTo(singletonList(
                new KafkaTopicConfig("test", LIVE, JSON_PATCH, null, null))));
        assertThat(options.sasl.mechanism, equalTo("scram-sha-256"));
        assertThat(options.sasl.username, equalTo("username"));
        assertThat(options.sasl.password, equalTo("password"));
    }

    @Test
    public void shouldWriteSaslScramOptions()
    {
        KafkaOptionsConfig options = new KafkaOptionsConfig(
                singletonList("test"),
                singletonList(new KafkaTopicConfig("test", LIVE, JSON_PATCH, null, null)),
                singletonList(new KafkaServerConfig("localhost", 9092)),
                new KafkaSaslConfig("scram-sha-256", "username", "password"));

        String text = jsonb.toJson(options);

        assertThat(text, not(nullValue()));
        assertThat(text, equalTo("{\"bootstrap\":[\"test\"]," +
                "\"topics\":[{\"name\":\"test\",\"defaultOffset\":\"live\",\"deltaType\":\"json_patch\"}]," +
                "\"servers\":[\"localhost:9092\"]," +
                "\"sasl\":{\"mechanism\":\"scram-sha-256\",\"username\":\"username\",\"password\":\"password\"}}"));
    }

    @Test
    public void shouldWriteCatalogOptions()
    {
        KafkaOptionsConfig options = new KafkaOptionsConfig(
                singletonList("test"),
                singletonList(new KafkaTopicConfig("test", LIVE, JSON_PATCH, null, new TestValidatorConfig())),
                singletonList(new KafkaServerConfig("localhost", 9092)),
                new KafkaSaslConfig("plain", "username", "password"));

        String text = jsonb.toJson(options);

        assertThat(text, not(nullValue()));
        assertThat(text, equalTo("{\"bootstrap\":[\"test\"]," +
                "\"topics\":[{\"name\":\"test\",\"defaultOffset\":\"live\",\"deltaType\":\"json_patch\"," +
                "\"value\":\"test\"}]," +
                "\"servers\":[\"localhost:9092\"]," +
                "\"sasl\":{\"mechanism\":\"plain\",\"username\":\"username\",\"password\":\"password\"}}"));
    }
}
