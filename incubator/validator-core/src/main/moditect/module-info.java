/*
 * Copyright 2021-2023 Aklivity Inc
 *
 * Licensed under the Aklivity Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 *   https://www.aklivity.io/aklivity-community-license/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */
module io.aklivity.zilla.runtime.validator.core
{
    requires io.aklivity.zilla.runtime.engine;

    exports io.aklivity.zilla.runtime.validator.core.config;

    provides io.aklivity.zilla.runtime.engine.config.ValidatorConfigAdapterSpi
        with io.aklivity.zilla.runtime.validator.core.config.StringValidatorConfigAdapter,
            io.aklivity.zilla.runtime.validator.core.config.IntegerValidatorConfigAdapter,
            io.aklivity.zilla.runtime.validator.core.config.LongValidatorConfigAdapter;

    provides io.aklivity.zilla.runtime.engine.validator.ValidatorFactorySpi
        with io.aklivity.zilla.runtime.validator.core.StringValidatorFactory,
            io.aklivity.zilla.runtime.validator.core.IntegerValidatorFactory,
            io.aklivity.zilla.runtime.validator.core.LongValidatorFactory;
}
