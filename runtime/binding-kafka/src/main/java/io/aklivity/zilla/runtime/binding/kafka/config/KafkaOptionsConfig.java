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
package io.aklivity.zilla.runtime.binding.kafka.config;

import java.util.List;

import io.aklivity.zilla.runtime.engine.config.OptionsConfig;

public final class KafkaOptionsConfig extends OptionsConfig
{
    public final List<String> bootstrap;
    public final List<KafkaTopicConfig> topics;
    public final List<KafkaServerConfig> servers;
    public final KafkaSaslConfig sasl;

    public KafkaOptionsConfig(
        List<String> bootstrap,
        List<KafkaTopicConfig> topics,
        List<KafkaServerConfig> servers,
        KafkaSaslConfig sasl)
    {
        this.bootstrap = bootstrap;
        this.topics = topics;
        this.servers = servers;
        this.sasl = sasl;
    }
}
