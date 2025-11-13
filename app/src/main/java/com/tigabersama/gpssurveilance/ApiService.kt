package com.tigabersama.gpssurveilance

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

// --- Request ---
data class LoginRequest(
    val Code: String,
    val Password: String
)

// --- Response ---
data class LoginResponse(
    val data: AuthData
)

data class AuthData(
    val accessToken: String,
    val refreshToken: String,
    val name: String
)

// --- Payload lokasi ---
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val timestamp: String
)

// --- Retrofit API Service ---
interface ApiService {
    @POST("gps-embed/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("gps-embed/send")
    suspend fun sendLocation(
        @Header("Authorization") token: String,
        @Body payload: LocationPayload
    ): Response<Unit>
}

