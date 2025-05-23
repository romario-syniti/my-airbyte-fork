/* Copyright (c) 2024 Airbyte, Inc., all rights reserved. */
package io.airbyte.cdk.command

import io.airbyte.cdk.AirbyteConnectorRunnable
import io.airbyte.cdk.AirbyteConnectorRunner
import io.airbyte.cdk.AirbyteDestinationRunner
import io.airbyte.cdk.AirbyteSourceRunner
import io.airbyte.cdk.output.BufferingOutputConsumer
import io.airbyte.cdk.util.Jsons
import io.airbyte.protocol.models.v0.AirbyteMessage
import io.airbyte.protocol.models.v0.AirbyteStateMessage
import io.airbyte.protocol.models.v0.ConfiguredAirbyteCatalog
import io.micronaut.context.RuntimeBeanDefinition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

data object CliRunner {
    /**
     * Builds a [CliRunnable] which runs a source connector with the given arguments.
     *
     * This is useful for writing connector integration tests:
     * - the [config], [catalog] and [state] get written to temporary files;
     * - the file names get passed with the `--config`, `--catalog` and `--state` CLI arguments;
     * - an extra temporary file is created to store the output;
     * - that file name gets passed with the test-only `--output` CLI argument;
     * - [AirbyteSourceRunner] takes the CLI arguments and runs them in a new Micronaut context;
     * - after it's done, the output file contents are read and parsed into [AirbyteMessage]s.
     * - those are stored in the [BufferingOutputConsumer] which is returned in the [CliRunnable].
     */
    fun source(
        op: String,
        config: ConfigurationSpecification? = null,
        catalog: ConfiguredAirbyteCatalog? = null,
        state: List<AirbyteStateMessage>? = null,
        vararg featureFlags: FeatureFlag,
    ): CliRunnable {
        val out = CliRunnerOutputStream()
        val runnable: Runnable =
            makeRunnable(op, config, catalog, state) { args: Array<String> ->
                AirbyteSourceRunner(
                    args,
                    additionalMicronautEnvs = emptyList(),
                    featureFlags.systemEnv,
                    out.beanDefinition
                )
            }
        return CliRunnable(runnable, out.results)
    }

    /** Same as [source] but for destinations. */
    fun destination(
        op: String,
        configContents: String? = null,
        catalog: ConfiguredAirbyteCatalog? = null,
        state: List<AirbyteStateMessage>? = null,
        inputStream: InputStream?,
        additionalMicronautEnvs: List<String> = emptyList(),
        micronautProperties: Map<String, String> = emptyMap(),
        vararg featureFlags: FeatureFlag,
    ): CliRunnable {
        val inputBeanDefinition: RuntimeBeanDefinition<InputStream>? =
            inputStream?.let {
                RuntimeBeanDefinition.builder(InputStream::class.java) { inputStream }
                    .singleton(true)
                    .named("inputStream")
                    .replaces(InputStream::class.java)
                    .build()
            }
        val out = CliRunnerOutputStream()
        val configPath: Path? = inputFileFromString(configContents)
        val runnable: Runnable =
            makeRunnable(op, configPath, catalog, state) { args: Array<String> ->
                AirbyteDestinationRunner(
                    args,
                    additionalMicronautEnvs = additionalMicronautEnvs,
                    featureFlags.systemEnv,
                    micronautProperties,
                    *arrayOf(
                            inputBeanDefinition,
                            out.beanDefinition,
                        )
                        .filterNotNull()
                        .toTypedArray()
                )
            }
        return CliRunnable(runnable, out.results)
    }

    /** Same as the other [destination] but with [AirbyteMessage] input. */
    fun destination(
        op: String,
        configContents: String? = null,
        catalog: ConfiguredAirbyteCatalog? = null,
        state: List<AirbyteStateMessage>? = null,
        featureFlags: Set<FeatureFlag> = setOf(),
        additionalMicronautEnvs: List<String> = emptyList(),
        micronautProperties: Map<String, String> = emptyMap(),
        vararg input: AirbyteMessage,
    ): CliRunnable {
        val inputJsonBytes: ByteArray =
            ByteArrayOutputStream().use { baos ->
                for (msg in input) {
                    Jsons.writeValue(baos, msg)
                    baos.write('\n'.code)
                }
                baos.toByteArray()
            }
        val inputStream: InputStream = ByteArrayInputStream(inputJsonBytes)
        return destination(
            op,
            configContents,
            catalog,
            state,
            inputStream,
            additionalMicronautEnvs,
            micronautProperties,
            *featureFlags.toTypedArray()
        )
    }

    private fun makeRunnable(
        op: String,
        config: ConfigurationSpecification?,
        catalog: ConfiguredAirbyteCatalog?,
        state: List<AirbyteStateMessage>?,
        connectorRunnerConstructor: (Array<String>) -> AirbyteConnectorRunner,
    ): Runnable {
        val configFile: Path? = inputFile(config)
        return makeRunnable(op, configFile, catalog, state, connectorRunnerConstructor)
    }

    private fun makeRunnable(
        op: String,
        configFile: Path?,
        catalog: ConfiguredAirbyteCatalog?,
        state: List<AirbyteStateMessage>?,
        connectorRunnerConstructor: (Array<String>) -> AirbyteConnectorRunner,
    ): Runnable {
        val catalogFile: Path? = inputFile(catalog)
        val stateFile: Path? = inputFile(state)
        val args: List<String> =
            listOfNotNull(
                "--$op",
                configFile?.let { "--config=$it" },
                catalogFile?.let { "--catalog=$it" },
                stateFile?.let { "--state=$it" },
            )
        val runner: AirbyteConnectorRunner = connectorRunnerConstructor(args.toTypedArray())
        return Runnable {
            try {
                runner.run<AirbyteConnectorRunnable>()
            } finally {
                configFile?.deleteIfExists()
                catalogFile?.deleteIfExists()
                stateFile?.deleteIfExists()
            }
        }
    }

    private val Array<out FeatureFlag>.systemEnv: Map<String, String>
        get() = toSet().map { it.envVar.name to it.requiredEnvVarValue }.toMap()

    private fun inputFile(contents: Any?): Path? =
        contents?.let { inputFileFromString(Jsons.writeValueAsString(contents)) }

    private fun inputFileFromString(contents: String?): Path? =
        contents?.let {
            Files.createTempFile(null, null).also { file -> Files.writeString(file, contents) }
        }
}
