package com.connectedvehicle.telemetry.network

import com.connectedvehicle.telemetry.domain.model.SignedVehicleCommandEnvelope
import com.connectedvehicle.telemetry.domain.model.VehicleTelemetryFrame
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CommandDispatchAck(
    val commandId: String,
    val status: String,
    val tcuCorrelationId: String,
)

sealed interface GatewayNetworkResult<out T> {
    data class Success<T>(val data: T) : GatewayNetworkResult<T>
    data class ApiError(val statusCode: Int, val message: String) : GatewayNetworkResult<Nothing>
    data class ConnectivityFailure(val cause: Throwable) : GatewayNetworkResult<Nothing>
}

/**
 * Connected Vehicle Telematics Control Unit (TCU) Cloud Gateway client built on Ktor.
 *
 * Injects deterministic `Idempotency-Key` and `X-TCU-Signature` headers on every remote
 * actuation command and configures exponential backoff for cellular dead-zones.
 */
class VehicleTelemetryGatewayClient(
    engine: HttpClientEngine = CIO.create(),
    private val baseUrl: String = "https://telematics.connectedvehicle.internal/api/v1",
) {
    private val httpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
            )
        }
        install(WebSockets)
        install(HttpRequestRetry) {
            maxRetries = 2
            retryIf { _, response ->
                response.status.value in 500..599 || response.status == HttpStatusCode.TooManyRequests
            }
            exponentialDelay(base = 2.0, maxDelayMs = 2_000L)
        }
    }

    suspend fun dispatchSignedCommand(
        envelope: SignedVehicleCommandEnvelope,
    ): GatewayNetworkResult<CommandDispatchAck> {
        return try {
            val response = httpClient.post("$baseUrl/vehicles/${envelope.command.vin}/commands") {
                contentType(ContentType.Application.Json)
                header("Idempotency-Key", envelope.command.commandId)
                header("X-TCU-Signature-Ed25519", envelope.signatureBase64)
                header("X-TCU-Key-Id", envelope.publicKeyId)
                setBody(envelope)
            }
            if (response.status.isSuccess()) {
                GatewayNetworkResult.Success(response.body<CommandDispatchAck>())
            } else {
                GatewayNetworkResult.ApiError(
                    statusCode = response.status.value,
                    message = "TCU Gateway rejected command (${response.status.value})",
                )
            }
        } catch (t: Throwable) {
            GatewayNetworkResult.ConnectivityFailure(t)
        }
    }

    suspend fun fetchLatestSnapshot(vin: String): GatewayNetworkResult<VehicleTelemetryFrame> {
        return try {
            val response = httpClient.get("$baseUrl/vehicles/$vin/telemetry/latest")
            if (response.status.isSuccess()) {
                GatewayNetworkResult.Success(response.body<VehicleTelemetryFrame>())
            } else {
                GatewayNetworkResult.ApiError(
                    statusCode = response.status.value,
                    message = "Failed to fetch telemetry snapshot (${response.status.value})",
                )
            }
        } catch (t: Throwable) {
            GatewayNetworkResult.ConnectivityFailure(t)
        }
    }
}
