package com.tigabersama.gpssurveilance.data.remote

import android.content.Context
import com.tigabersama.gpssurveilance.ApiService
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class AuthInterceptor(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val prefs = context.getSharedPreferences("prefs_location_sender", Context.MODE_PRIVATE)
        val key = prefs.getString("auth_key", null)
        val requestBuilder = chain.request().newBuilder()
        if (key != null) {
            requestBuilder.addHeader("Authorization", "Bearer $key")
        }
        return chain.proceed(requestBuilder.build())
    }
}

object NetworkModule {
    fun provideApiService(context: Context, baseUrl: String): ApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(AuthInterceptor(context))
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
